package kr.mujoong.core.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;

/**
 * U2 시험 준비물(시험 소스 전용): 참조 시드 002 적용, 가짜 구글 토큰 엔드포인트, 시험 id_token. 값은 모두 합성이고 비밀 · 실제 도메인이 없다
 * (client id · redirect는 예약 TLD .invalid, client secret은 실행마다 만든 값).
 */
public final class U2Fixtures {

    /** 시험 client id(비밀 아님). */
    public static final String CLIENT_ID = "u2-test-client.apps.invalid";
    /** 시험 redirect uri(허용 origin과 같은 예약 도메인). */
    public static final String REDIRECT_URI = "https://mujoong.invalid/parent/login/callback";
    /** 가짜 토큰 엔드포인트 경로. */
    public static final String TOKEN_PATH = "/token";

    private U2Fixtures() {
    }

    /** 실행마다 만든 client secret(저장소 · 로그에 없는 값). */
    public static String clientSecret() {
        return "s-" + UUID.randomUUID();
    }

    /** 참조 시드 002(약관 4종 · 세션 문구 자리)를 mujoong_owner로 멱등 적용한다. */
    public static void applyU2Seed(MujoongPostgres postgres) {
        try (Connection owner = postgres.connect(Role.OWNER); Statement statement = owner.createStatement()) {
            statement.execute(Files.readString(postgres.repoRoot().resolve("infra/seed/002-u2-reference.sql"),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("U2 참조 시드 적용 실패", e);
        }
    }

    /** 가짜 구글 토큰 엔드포인트(요청 · 응답 기록). */
    public static ContractStubServer googleTokenEndpoint() {
        ContractStubServer google = ContractStubServer.start();
        google.route("POST", TOKEN_PATH).respond(StubResponse.json(400, "{\"error\":\"invalid_grant\"}"));
        return google;
    }

    /** 토큰 엔드포인트가 이 sub의 id_token을 주게 한다. */
    public static void issueFor(ContractStubServer google, String subject, Instant now) {
        google.route("POST", TOKEN_PATH).respond(StubResponse.json(200,
                "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"id_token\":\"" + idToken(CLIENT_ID, subject, now.plusSeconds(300))
                        + "\"}"));
    }

    /** 시험 id_token(서명 칸은 자리표시자, U2-J2). */
    public static String idToken(String audience, String subject, Instant expiresAt) {
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = b64(JsonCodec.shared().write(Map.of("iss", "https://accounts.google.com", "aud", audience,
                "exp", expiresAt.getEpochSecond(), "sub", subject)));
        return header + "." + claims + ".c2ln";
    }

    /** 구글 로그인 요청 본문. */
    public static String loginBody(String code) {
        return "{\"code\":\"" + code + "\",\"redirectUri\":\"" + REDIRECT_URI + "\"}";
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
