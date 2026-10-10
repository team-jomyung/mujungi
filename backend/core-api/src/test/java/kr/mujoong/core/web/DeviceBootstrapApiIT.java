package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.device.CodeGenerator;
import kr.mujoong.core.device.DeviceSecrets;
import kr.mujoong.core.device.RegistrationCode;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.CookieJar;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.CoreWebApp.SetCookie;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 기기 웹 REST: 코드 받기 · 등록 확인 · claim · me (U1 3.5 Step 34 · 계약 4.1절 · BR1.1 ~ BR1.7 · BR3.1 · BR3.2 · BR3.5 · AC6.1.1 ~ 6.1.5 ·
 * 편차 (a)(b)(c)(k)).
 *
 * <p>앱은 루프백(127.0.0.1)을 신뢰 프록시로 두고 띄운다. 시험마다 다른 {@code X-Forwarded-For} 주소(문서용 TEST-NET 대역)를 써서 IP 한도
 * (1시간 20회)를 서로 나눠 쓰지 않게 한다. 신뢰 프록시가 아닌 출발지의 {@code X-Forwarded-For}를 무시하는지는 기본 설정(아무 프록시도 믿지 않음)의
 * 두 번째 앱으로 본다. 코드 추첨 충돌 (c)는 시험 전용 @Primary 추첨기에 정한 코드를 강제해 만든다.
 */
@Tag("u1")
@Tag("db")
class DeviceBootstrapApiIT {

    private static final String CODE_PATTERN = "^[" + RegistrationCode.ALPHABET + "]{8}$";
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    private static DbTestSupport db;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static LogCapture logs;

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app = CoreWebApp.builder(db.postgres())
                .internalToken(token)
                .realtime(realtime.baseUrl())
                .trustedProxy("127\\.0\\.0\\.1")
                .source(ForcedCodes.class)
                .start();
        logs = LogCapture.attach();
    }

    @AfterAll
    static void stop() {
        ForcedCodes.release();
        if (logs != null) {
            logs.close();
        }
        if (app != null) {
            app.close();
        }
        if (realtime != null) {
            realtime.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void freshLogWindow() {
        app.clock().advance(Duration.ofMinutes(2));
        app.bean(EventLogger.class).flushMerged();
        logs.clear();
    }

    @AfterEach
    void releaseForcedCode() {
        ForcedCodes.release();
    }

    @Test
    void bootstrapIssuesACodeWithASecretCookieAndRepeatsTheSameCodeForTheSameCookie() {
        String address = nextAddress();
        CookieJar device = new CookieJar();

        Result first = bootstrap(app, device, address);

        assertThat(first.status()).isEqualTo(201);
        String code = String.valueOf(first.json().get("code"));
        assertThat(code).matches(CODE_PATTERN);
        assertThat(first.json()).containsOnlyKeys("code", "expiresAt");
        assertThat(first.json().get("expiresAt")).isEqualTo(ApiResponses.time(app.clock().now().plus(Duration.ofHours(24))));
        assertThat(first.header("Cache-Control")).isEqualTo("no-store");
        SetCookie cookie = first.setCookie("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow();
        assertThat(cookie.attribute("Path")).isEqualTo("/api/device");
        assertThat(cookie.attribute("Max-Age")).isEqualTo("86400");
        assertThat(first.body()).as("비밀은 쿠키로만").doesNotContain(cookie.value());
        assertThat(storedBootstrapHash(code)).isEqualTo(DeviceSecrets.hash(cookie.value())).isNotEqualTo(cookie.value());

        for (int i = 0; i < 20; i++) {
            Result again = bootstrap(app, device, address);
            assertThat(again.status()).isEqualTo(201);
            assertThat(again.json()).isEqualTo(first.json());
            assertThat(again.headerValues("Set-Cookie")).as("같은 코드 재전송은 새 쿠키 없음(BR1.3)").isEmpty();
        }
        Result fresh = bootstrap(app, new CookieJar(), address);
        assertThat(fresh.status()).as("같은 쿠키 반복은 한도에 세지 않는다(k)").isEqualTo(201);
        assertThat(fresh.json().get("code")).isNotEqualTo(code);
    }

    @Test
    void statusAnswersPendingWithoutResendingTheCodeAndUnknownCookiesAre401() {
        CookieJar device = new CookieJar();
        Result issued = bootstrap(app, device, nextAddress());
        String code = String.valueOf(issued.json().get("code"));

        Result pending = app.get("/api/device/bootstrap/status").cookies(device).send();
        assertThat(pending.status()).isEqualTo(200);
        assertThat(pending.json()).containsOnlyKeys("status", "expiresAt");
        assertThat(pending.json().get("status")).isEqualTo("PENDING");
        assertThat(pending.json().get("expiresAt")).isEqualTo(issued.json().get("expiresAt"));
        assertThat(pending.body()).as("코드 재송신 없음(BR1.7)").doesNotContain(code);
        assertThat(WebFixtures.schemaErrors("device-bootstrap-status-response.schema.json", pending.body())).isEmpty();

        assertBootstrapNotFound(app.get("/api/device/bootstrap/status").send());
        assertBootstrapNotFound(app.get("/api/device/bootstrap/status")
                .cookie("MUJOONG_DEVICE_BOOTSTRAP", TestSecrets.bootstrapSecret()).send());
        assertBootstrapNotFound(app.get("/api/device/bootstrap/status")
                .header("Cookie", "MUJOONG_DEVICE_BOOTSTRAP=" + device.get("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow()
                        + "; MUJOONG_DEVICE_BOOTSTRAP=" + TestSecrets.bootstrapSecret())
                .send());
    }

    @Test
    void twentyFirstNewIssueFromOneForwardedAddressIs429AndOtherAddressesAreNotAffected() {
        String limited = nextAddress();
        CookieJar firstDevice = new CookieJar();
        for (int i = 0; i < 20; i++) {
            Result issued = bootstrap(app, i == 0 ? firstDevice : new CookieJar(), limited);
            assertThat(issued.status()).as("새 발급 %d번째", i + 1).isEqualTo(201);
        }

        Result twentyFirst = bootstrap(app, new CookieJar(), limited);
        assertThat(twentyFirst.status()).isEqualTo(429);
        assertThat(twentyFirst.code()).isEqualTo("RATE_LIMITED");
        assertThat(twentyFirst.headerValues("Set-Cookie")).isEmpty();
        assertThat(logs.events(EventCode.SECURITY_RATE_LIMITED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("limiter", "BOOTSTRAP_IP").containsEntry("statusCode", 429));

        Result sameCookie = bootstrap(app, firstDevice, limited);
        assertThat(sameCookie.status()).as("유효한 쿠키의 재요청은 한도와 무관").isEqualTo(201);
        Result otherAddress = bootstrap(app, new CookieJar(), nextAddress());
        assertThat(otherAddress.status()).as("다른 X-Forwarded-For 주소는 한도를 나누지 않는다").isEqualTo(201);

        app.clock().advance(Duration.ofMinutes(61));
        assertThat(bootstrap(app, new CookieJar(), limited).status()).as("창이 지나면 다시 발급").isEqualTo(201);
    }

    @Test
    void forwardedForFromAnUntrustedSourceIsIgnored() {
        try (CoreWebApp untrusted = CoreWebApp.builder(db.postgres())
                .internalToken(app.internalToken())
                .realtime(realtime.baseUrl())
                .start()) {
            for (int i = 0; i < 20; i++) {
                Result issued = bootstrap(untrusted, new CookieJar(), nextAddress());
                assertThat(issued.status()).as("새 발급 %d번째", i + 1).isEqualTo(201);
            }
            Result spoofed = bootstrap(untrusted, new CookieJar(), nextAddress());
            assertThat(spoofed.status()).as("믿지 않는 출발지의 X-Forwarded-For로 한도를 피할 수 없다").isEqualTo(429);
            assertThat(spoofed.code()).isEqualTo("RATE_LIMITED");
        }
    }

    @Test
    void fiveCollidingDrawsAnswer503CodeIssueFailed() {
        String forced = DbTestSupport.randomCode();
        ForcedCodes.force(forced);

        Result first = bootstrap(app, new CookieJar(), nextAddress());
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.json().get("code")).isEqualTo(forced);

        Result collided = bootstrap(app, new CookieJar(), nextAddress());
        assertThat(collided.status()).isEqualTo(503);
        assertThat(collided.code()).isEqualTo("CODE_ISSUE_FAILED");
        assertThat(collided.headerValues("Set-Cookie")).as("실패하면 쿠키 없음").isEmpty();
        assertThat(ForcedCodes.draws()).as("첫 추첨 포함 5회").isEqualTo(1 + 5);

        ForcedCodes.release();
        assertThat(bootstrap(app, new CookieJar(), nextAddress()).status()).isEqualTo(201);
    }

    @Test
    void registeredCodeClosesBootstrapAndClaimRotatesTheTokenAndDeletesTheBootstrapCookie() {
        CookieJar device = new CookieJar();
        Result issued = bootstrap(app, device, nextAddress());
        String code = String.valueOf(issued.json().get("code"));
        Result early = app.post("/api/device/bootstrap/claim").cookies(device).send();
        assertThat(early.status()).as("등록 전 claim").isEqualTo(409);
        assertThat(early.code()).isEqualTo("NOT_REGISTERED_YET");

        Guardian guardian = WebFixtures.guardian(app, db);
        Result registered = register(guardian, code);
        assertThat(registered.status()).isEqualTo(201);

        Result status = app.get("/api/device/bootstrap/status").cookies(device).send();
        assertThat(status.json().get("status")).as("AC6.1.2").isEqualTo("REGISTERED");
        Result rebootstrap = bootstrap(app, device, nextAddress());
        assertThat(rebootstrap.status()).as("claim 전 등록된 쿠키로 코드 받기 (a)").isEqualTo(409);
        assertThat(rebootstrap.code()).isEqualTo("ALREADY_REGISTERED");

        CookieJar firstClaimJar = new CookieJar();
        firstClaimJar.put("MUJOONG_DEVICE_BOOTSTRAP", device.get("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow());
        Result firstClaim = firstClaimJar.accept(app.post("/api/device/bootstrap/claim").cookies(firstClaimJar).send());
        assertThat(firstClaim.status()).isEqualTo(200);
        assertThat(firstClaim.json()).containsOnlyKeys("deviceId");
        assertThat(firstClaim.json().get("deviceId")).isEqualTo(registered.json().get("deviceId"));
        SetCookie token = firstClaim.setCookie("MUJOONG_DEVICE").orElseThrow();
        assertThat(token.attribute("Path")).isEqualTo("/");
        assertThat(token.attribute("Max-Age")).isEqualTo("34560000");
        assertThat(token.attribute("SameSite")).isEqualTo("Strict");
        SetCookie cleared = firstClaim.setCookie("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow();
        assertThat(cleared.value()).isEmpty();
        assertThat(cleared.attribute("Max-Age")).isEqualTo("0");
        assertThat(cleared.attribute("Path")).as("발급 때와 같은 Path").isEqualTo("/api/device");
        assertThat(firstClaim.body()).doesNotContain(token.value());

        Result secondClaim = device.accept(app.post("/api/device/bootstrap/claim").cookies(device).send());
        assertThat(secondClaim.status()).as("첫 연결 전 claim 재호출").isEqualTo(200);
        String rotated = secondClaim.setCookie("MUJOONG_DEVICE").orElseThrow().value();
        assertThat(rotated).as("부를 때마다 새 토큰").isNotEqualTo(token.value());
        assertThat(storedTokenHash(code)).isEqualTo(DeviceSecrets.hash(rotated));

        Result oldToken = app.get("/api/device/me").cookie("MUJOONG_DEVICE", token.value()).send();
        assertThat(oldToken.status()).as("회전된 앞 토큰은 무효").isEqualTo(401);
        assertThat(oldToken.code()).isEqualTo("DEVICE_NOT_REGISTERED");
        Result me = app.get("/api/device/me").cookie("MUJOONG_DEVICE", rotated).send();
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json()).containsOnlyKeys("deviceId", "registeredAt");
        assertThat(me.json().get("deviceId")).isEqualTo(registered.json().get("deviceId"));
        assertThat(me.json().get("registeredAt")).isEqualTo(registered.json().get("registeredAt"));
        SetCookie renewed = me.setCookie("MUJOONG_DEVICE").orElseThrow();
        assertThat(renewed.value()).as("같은 토큰을 400일로 다시 설정(BR3.5)").isEqualTo(rotated);
        assertThat(renewed.attribute("Max-Age")).isEqualTo("34560000");
        assertThat(me.header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void claimWithoutAKnownBootstrapCookieIs401AndMeWithoutAValidTokenIs401() {
        assertBootstrapNotFound(app.post("/api/device/bootstrap/claim").send());
        assertBootstrapNotFound(app.post("/api/device/bootstrap/claim")
                .cookie("MUJOONG_DEVICE_BOOTSTRAP", TestSecrets.bootstrapSecret()).send());
        assertBootstrapNotFound(app.post("/api/device/bootstrap/claim").cookie("MUJOONG_DEVICE_BOOTSTRAP", "short").send());

        Result noCookie = app.get("/api/device/me").send();
        assertThat(noCookie.status()).isEqualTo(401);
        assertThat(noCookie.code()).isEqualTo("DEVICE_NOT_REGISTERED");
        assertThat(noCookie.headerValues("Set-Cookie")).isEmpty();
        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).anySatisfy(event -> {
            assertThat(event.get("level")).as("첫 로드의 정상 경로라 INFO").isEqualTo("INFO");
            assertThat(LogCapture.attrs(event)).containsEntry("kind", "DEVICE_TOKEN").containsEntry("statusCode", 401);
        });

        Result unknownToken = app.get("/api/device/me").cookie("MUJOONG_DEVICE", TestSecrets.deviceToken()).send();
        assertThat(unknownToken.status()).isEqualTo(401);
        assertThat(unknownToken.code()).isEqualTo("DEVICE_NOT_REGISTERED");
        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).anySatisfy(event ->
                assertThat(event.get("level")).as("모르는 토큰은 WARN").isEqualTo("WARN"));
    }

    @Test
    void anExpiredCodeIsClosedAndTheNextBootstrapIssuesANewCode() {
        CookieJar device = new CookieJar();
        Result issued = bootstrap(app, device, nextAddress());
        String oldSecret = device.get("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow();

        app.clock().advance(Duration.ofHours(24).plusSeconds(1));

        Result status = app.get("/api/device/bootstrap/status").cookies(device).send();
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.json().get("status")).isEqualTo("EXPIRED");
        Result claim = app.post("/api/device/bootstrap/claim").cookies(device).send();
        assertThat(claim.status()).isEqualTo(410);
        assertThat(claim.code()).isEqualTo("BOOTSTRAP_CLOSED");

        Result renewed = bootstrap(app, device, nextAddress());
        assertThat(renewed.status()).as("AC6.1.5 만료 뒤 새 코드").isEqualTo(201);
        assertThat(renewed.json().get("code")).isNotEqualTo(issued.json().get("code"));
        assertThat(device.get("MUJOONG_DEVICE_BOOTSTRAP")).as("새 비밀 쿠키").isPresent().get().isNotEqualTo(oldSecret);
    }

    @Test
    void reRegistrationReleasesTheOldDeviceWhoseTokenAndBootstrapStopWorking() {
        Guardian guardian = WebFixtures.guardian(app, db);
        CookieJar oldDevice = new CookieJar();
        String oldCode = String.valueOf(bootstrap(app, oldDevice, nextAddress()).json().get("code"));
        assertThat(register(guardian, oldCode).status()).isEqualTo(201);
        String oldSecret = oldDevice.get("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow();
        oldDevice.accept(app.post("/api/device/bootstrap/claim").cookies(oldDevice).send());
        String oldToken = oldDevice.get("MUJOONG_DEVICE").orElseThrow();
        assertThat(app.get("/api/device/me").cookies(oldDevice).send().status()).isEqualTo(200);

        CookieJar newDevice = new CookieJar();
        String newCode = String.valueOf(bootstrap(app, newDevice, nextAddress()).json().get("code"));
        assertThat(register(guardian, newCode).status()).as("사본 없는 옛 기기는 교체").isEqualTo(201);

        Result released = app.get("/api/device/me").cookie("MUJOONG_DEVICE", oldToken).send();
        assertThat(released.status()).as("AC6.1.3 옛 기기 토큰 무효").isEqualTo(401);
        assertThat(released.code()).isEqualTo("DEVICE_NOT_REGISTERED");
        CookieJar oldBootstrap = new CookieJar();
        oldBootstrap.put("MUJOONG_DEVICE_BOOTSTRAP", oldSecret);
        Result closed = app.get("/api/device/bootstrap/status").cookies(oldBootstrap).send();
        assertThat(closed.json().get("status")).as("RELEASED는 EXPIRED로 답한다").isEqualTo("EXPIRED");
    }

    // ------------------------------------------------------------------ 도움

    private static Result bootstrap(CoreWebApp target, CookieJar jar, String forwardedFor) {
        CoreWebApp.Call call = target.post("/api/device/bootstrap").cookies(jar);
        if (forwardedFor != null) {
            call.header("X-Forwarded-For", forwardedFor);
        }
        Result result = call.send();
        if (result.status() == 201) {
            WebFixtures.trackDeviceByCode(db, String.valueOf(result.json().get("code")));
            jar.accept(result);
        }
        return result;
    }

    private static Result register(Guardian guardian, String code) {
        return app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"code\":\"" + code + "\"}").send();
    }

    private static String nextAddress() {
        int n = NEXT_ADDRESS.getAndIncrement();
        return "203.0." + (113 + n / 250) + "." + (1 + n % 250);
    }

    private static void assertBootstrapNotFound(Result result) {
        assertThat(result.status()).isEqualTo(401);
        assertThat(result.code()).isEqualTo("BOOTSTRAP_NOT_FOUND");
        assertThat(result.headerValues("Set-Cookie")).isEmpty();
    }

    private static String storedBootstrapHash(String code) {
        return db.jdbc().queryForObject("select bootstrap_secret_hash from core.core_device where registration_code = :code",
                new MapSqlParameterSource("code", code), String.class);
    }

    private static String storedTokenHash(String code) {
        return db.jdbc().queryForObject("select device_token_hash from core.core_device where registration_code = :code",
                new MapSqlParameterSource("code", code), String.class);
    }

    /**
     * 시험 전용 @Primary 코드 추첨기(설정 클래스 표지가 없어 앱 스캔에 걸리지 않고 이 시험의 앱에만 소스로 들어간다). 강제 코드가 없으면
     * SecureRandom이다.
     */
    static class ForcedCodes {

        private static final AtomicReference<String> FORCED = new AtomicReference<>();
        private static final AtomicInteger POSITION = new AtomicInteger();
        private static final AtomicInteger DRAWS = new AtomicInteger();

        static void force(String code) {
            POSITION.set(0);
            DRAWS.set(0);
            FORCED.set(code);
        }

        static void release() {
            FORCED.set(null);
        }

        static int draws() {
            return DRAWS.get();
        }

        @Bean
        @Primary
        CodeGenerator forcedCodeGenerator() {
            SecureRandom secure = new SecureRandom();
            RandomGenerator random = new RandomGenerator() {
                @Override
                public long nextLong() {
                    return secure.nextLong();
                }

                @Override
                public int nextInt(int bound) {
                    String forced = FORCED.get();
                    if (forced == null) {
                        return secure.nextInt(bound);
                    }
                    int position = POSITION.getAndIncrement() % RegistrationCode.LENGTH;
                    if (position == 0) {
                        DRAWS.incrementAndGet();
                    }
                    return RegistrationCode.ALPHABET.indexOf(forced.charAt(position));
                }
            };
            return new CodeGenerator(random, 5);
        }
    }
}
