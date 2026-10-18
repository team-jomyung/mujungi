package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.ClaimedDevice;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
import kr.mujoong.core.web.support.WebFixtures.IssuedCode;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 보호자 코드 등록 {@code POST /api/device/register} (U1 3.5 Step 34 · 계약 3절 · BR2.1 ~ BR2.7 · AC2.2.2 · AC2.2.3 · AC2.2.5 ~ 2.2.7 ·
 * AC6.1.3 · 편차 (d)(m)).
 *
 * <p>화면 동작(AC2.2.1 · 2.2.4 · 2.2.8 칸 · 단추)은 프런트 시험 몫이고 여기서는 그 화면이 기대는 서버 응답을 본다. 실패 한도(보호자당 10분
 * 10회)가 시험끼리 섞이지 않게 시험마다 새 보호자를 쓴다.
 */
@Tag("skeleton")
@Tag("db")
class DeviceRegisterApiIT {

    private static DbTestSupport db;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static LogCapture logs;

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).start();
        logs = LogCapture.attach();
    }

    @AfterAll
    static void stop() {
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

    @Test
    void registeringAPendingCodeBindsTheDeviceAndReturnsOnlyIdPrefixAndTime() {
        Guardian guardian = WebFixtures.guardian(app, db);
        IssuedCode issued = WebFixtures.issueCode(app, db);
        double failuresBefore = registerCodeFailures();

        Result registered = WebFixtures.register(app, guardian, issued.code());

        assertThat(registered.status()).isEqualTo(201);
        assertThat(registered.json()).containsOnlyKeys("deviceId", "codePrefix", "registeredAt");
        assertThat(registered.json().get("codePrefix")).isEqualTo(issued.code().substring(0, 4));
        assertThat(registered.json().get("registeredAt")).isEqualTo(ApiResponses.time(app.clock().now()));
        assertThat(registered.body()).doesNotContain(issued.code(), "Hash", "hash", "token");
        assertThat(registered.header("Cache-Control")).isEqualTo("no-store");
        assertThat(WebFixtures.schemaErrors("device-register-response.schema.json", registered.body())).isEmpty();
        assertThat(registerCodeFailures()).as("201은 REGISTER_CODE로 세지 않는다").isEqualTo(failuresBefore);

        Map<String, Object> row = deviceRow(issued.code());
        assertThat(row).containsEntry("registration_status", "REGISTERED")
                .containsEntry("guardian_id", guardian.id())
                .containsEntry("id", UUID.fromString(String.valueOf(registered.json().get("deviceId"))));

        Result view = app.get("/api/device").session(guardian.session()).send();
        @SuppressWarnings("unchecked")
        Map<String, Object> registration = (Map<String, Object>) view.json().get("registration");
        assertThat(registration).containsEntry("status", "REGISTERED").containsEntry("codePrefix", issued.code().substring(0, 4));
    }

    @Test
    void inputIsNormalizedBeforeLookup() {
        Guardian guardian = WebFixtures.guardian(app, db);
        IssuedCode issued = WebFixtures.issueCode(app, db);
        String code = issued.code().toLowerCase(java.util.Locale.ROOT);
        String typed = "  " + code.substring(0, 4) + " · " + code.substring(4, 6) + "-" + code.substring(6) + " ";

        Result registered = WebFixtures.register(app, guardian, typed);

        assertThat(registered.status()).as("공백 · 가운뎃점 · 하이픈 · 소문자 (d)").isEqualTo(201);
        assertThat(registered.json().get("codePrefix")).isEqualTo(issued.code().substring(0, 4));
    }

    @Test
    void malformedCodesAre400AndCodesOutsideTheAlphabetOrUnknownAre404() {
        Guardian guardian = WebFixtures.guardian(app, db);
        double failuresBefore = registerCodeFailures();

        for (String malformed : List.of("7F3K", "7F3K92QDX", "7F3K_92QD", "", "가나다라마바사아")) {
            Result result = WebFixtures.register(app, guardian, malformed);
            assertThat(result.status()).as("형식 오류 '%s'", malformed).isEqualTo(400);
            assertThat(result.code()).isEqualTo("VALIDATION_FAILED");
        }
        Result missingField = app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"other\":1}").send();
        assertThat(missingField.status()).isEqualTo(400);
        assertThat(missingField.code()).isEqualTo("VALIDATION_FAILED");

        Result outsideAlphabet = WebFixtures.register(app, guardian, "0O1IL234");
        assertThat(outsideAlphabet.status()).as("발급 알파벳 밖 글자").isEqualTo(404);
        assertThat(outsideAlphabet.code()).isEqualTo("CODE_NOT_FOUND");
        Result unknown = WebFixtures.register(app, guardian, DbTestSupport.randomCode());
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("CODE_NOT_FOUND");
        assertThat(unknown.body()).as("입력을 되비추지 않는다").doesNotContain("0O1IL234");

        assertThat(registerCodeFailures() - failuresBefore).as("400 · 404는 REGISTER_CODE로 센다").isEqualTo(5 + 1 + 2);
    }

    @Test
    void sameGuardianRetryIsTheSame201AndAnotherGuardianGets409CodeAlreadyRegistered() {
        Guardian owner = WebFixtures.guardian(app, db);
        Guardian other = WebFixtures.guardian(app, db);
        IssuedCode issued = WebFixtures.issueCode(app, db);

        Result first = WebFixtures.register(app, owner, issued.code());
        double failuresBefore = registerCodeFailures();
        app.clock().advance(Duration.ofSeconds(30));
        Result retry = WebFixtures.register(app, owner, issued.code());
        assertThat(retry.status()).as("응답 유실 뒤 재시도 (m)").isEqualTo(201);
        assertThat(retry.json()).as("첫 성공과 같은 본문").isEqualTo(first.json());
        assertThat(registerCodeFailures()).isEqualTo(failuresBefore);

        Result taken = WebFixtures.register(app, other, issued.code());
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.code()).isEqualTo("CODE_ALREADY_REGISTERED");
        assertThat(registerCodeFailures() - failuresBefore).as("409는 REGISTER_CODE로 센다").isEqualTo(1.0);
    }

    @Test
    void twoGuardiansRacingForOneCodeLeaveExactlyOneBoundDevice() throws Exception {
        Guardian first = WebFixtures.guardian(app, db);
        Guardian second = WebFixtures.guardian(app, db);
        IssuedCode issued = WebFixtures.issueCode(app, db);

        Future<Result> a = db.start(() -> WebFixtures.register(app, first, issued.code()));
        Future<Result> b = db.start(() -> WebFixtures.register(app, second, issued.code()));
        List<Integer> statuses = List.of(DbTestSupport.await(a).status(), DbTestSupport.await(b).status());

        assertThat(statuses).as("AC2.2.6 한 쪽만 성공").containsExactlyInAnyOrder(201, 409);
        Integer rows = db.jdbc().queryForObject("select count(*) from core.core_device where registration_code = :code",
                new MapSqlParameterSource("code", issued.code()), Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void aConnectedDeviceBlocksReRegistrationAndAnOfflineOneIsReplacedWithDeviceReleased() {
        Guardian guardian = WebFixtures.guardian(app, db);
        ClaimedDevice old = WebFixtures.claimedDevice(app, db, guardian);
        report(old.deviceId(), "CONNECTED");
        IssuedCode replacement = WebFixtures.issueCode(app, db);

        Result blocked = WebFixtures.register(app, guardian, replacement.code());
        assertThat(blocked.status()).as("AC2.2.7 연결된 기기가 있으면 거절").isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("DEVICE_ALREADY_ACTIVE");
        assertThat(deviceRow(replacement.code())).containsEntry("registration_status", "UNREGISTERED");

        app.clock().advance(Duration.ofSeconds(1));
        report(old.deviceId(), "DISCONNECTED");
        Result replaced = WebFixtures.register(app, guardian, replacement.code());
        assertThat(replaced.status()).as("끊긴 기기는 교체(BR2.4)").isEqualTo(201);

        assertThat(deviceRow(old.code())).containsEntry("registration_status", "RELEASED");
        RecordedRequest released = WebFixtures.awaitEvent(realtime, "DeviceReleased", old.deviceId(), Duration.ofSeconds(10))
                .orElseThrow(() -> new AssertionError("DeviceReleased가 Realtime에 가지 않았다"));
        assertThat(released.json().path("payload").path("deviceId").asString()).isEqualTo(old.deviceId().toString());
        assertThat(released.json().path("payload").path("reason").asString()).isEqualTo("REREGISTERED");
        assertThat(released.schemaErrors()).isEmpty();
        assertThat(released.body()).doesNotContain(old.token(), old.code());

        Result oldToken = app.get("/api/device/me").cookie("MUJOONG_DEVICE", old.token()).send();
        assertThat(oldToken.status()).as("AC2.2.5 해제된 토큰 거부").isEqualTo(401);
        Result verify = app.post("/internal/devices/verify-token").internalToken()
                .json("{\"deviceToken\":\"" + old.token() + "\"}").send();
        assertThat(verify.status()).isEqualTo(401);
        assertThat(verify.code()).isEqualTo("INVALID_DEVICE_TOKEN");
    }

    @Test
    void theEleventhFailureWithinTenMinutesIs429EvenForAValidCode() {
        Guardian guardian = WebFixtures.guardian(app, db);
        for (int i = 0; i < 10; i++) {
            assertThat(WebFixtures.register(app, guardian, DbTestSupport.randomCode()).status()).isEqualTo(404);
        }
        IssuedCode valid = WebFixtures.issueCode(app, db);

        Result limited = WebFixtures.register(app, guardian, valid.code());

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(logs.events(EventCode.SECURITY_RATE_LIMITED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("limiter", "REGISTER_FAILURE").containsEntry("statusCode", 429));
        assertThat(deviceRow(valid.code())).containsEntry("registration_status", "UNREGISTERED");

        app.clock().advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(WebFixtures.register(app, guardian, valid.code()).status()).as("창이 지나면 다시 받는다").isEqualTo(201);
    }

    @Test
    void aGuardianWhoseDeletionWasRequestedIs403() {
        Guardian guardian = WebFixtures.guardian(app, db);
        IssuedCode issued = WebFixtures.issueCode(app, db);
        db.jdbc().update("update core.core_guardian set status = 'DELETION_REQUESTED' where id = :id",
                new MapSqlParameterSource("id", guardian.id()));

        Result result = WebFixtures.register(app, guardian, issued.code());

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.code()).isEqualTo("ACCOUNT_DELETION_IN_PROGRESS");
        assertThat(deviceRow(issued.code())).containsEntry("registration_status", "UNREGISTERED");
    }

    // ------------------------------------------------------------------ 도움

    private static double registerCodeFailures() {
        return app.bean(CoreMetrics.class).count("mujoong.security.event", "type", "auth_failed");
    }

    private static Map<String, Object> deviceRow(String code) {
        return db.jdbc().queryForMap("""
                select id, guardian_id, registration_status from core.core_device where registration_code = :code
                order by created_at desc limit 1
                """, new MapSqlParameterSource("code", code));
    }

    private static void report(UUID deviceId, String state) {
        String now = ApiResponses.time(app.clock().now());
        Result result = app.put("/internal/devices/" + deviceId + "/connection").internalToken()
                .json("{\"state\":\"" + state + "\",\"reportedAt\":\"" + now + "\",\"rttMs\":40,\"recentDisconnects\":0,"
                        + "\"since\":\"" + now + "\",\"lastSeenAt\":\"" + now + "\"}")
                .send();
        assertThat(result.status()).as("연결 보고").isEqualTo(204);
    }
}
