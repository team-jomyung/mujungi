package kr.mujoong.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.OpenApiContracts;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 시험 로그인과 계정 삭제(U2 · 2차 리뷰 2 · AC1.6.4 · 편차 (U2-k)).
 *
 * <p>계정 삭제가 접수된 시드 보호자에게 시험 로그인이 세션을 열면 삭제 지휘의 세션 정리와 엇갈려 세션 행이 남고, DevSeedRunner가 같은 id로
 * 보호자를 다시 만들면 그 세션이 되살아난다. 삭제 접수 상태면 403 ACCOUNT_DELETION_IN_PROGRESS이고 세션을 만들지 않는다.
 */
@Tag("parent-account")
@Tag("db")
class DevLoginDeletionIT {

    private static DbTestSupport db;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static OpenApiContracts contracts;

    private final List<String> violations = new ArrayList<>();

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        contracts = OpenApiContracts.load();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        db.trackGuardian(SeedIds.GUARDIAN);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).devLogin(true).start();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (realtime != null) {
            realtime.close();
        }
        if (db != null) {
            WebFixtures.deleteSessions(db, SeedIds.GUARDIAN);
            db.close();
        }
    }

    @AfterEach
    void restore() {
        status(Guardian.STATUS_ACTIVE);
        WebFixtures.deleteSessions(db, SeedIds.GUARDIAN);
        assertThat(violations).as("응답이 parent-api.yaml 연산 계약과 맞다").isEmpty();
    }

    @Test
    void aSeedGuardianWhoseDeletionWasAcceptedGetsNoSession() {
        status(Guardian.STATUS_DELETION_REQUESTED);

        Result refused = login();

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("ACCOUNT_DELETION_IN_PROGRESS");
        assertThat(refused.headerValues("Set-Cookie")).as("세션 쿠키 없음").isEmpty();
        assertThat(sessions()).isZero();
    }

    @Test
    void anActiveSeedGuardianStillLogsIn() {
        Result ok = login();

        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.setCookie("MUJOONG_SESSION")).isPresent();
        assertThat(sessions()).isEqualTo(1);
    }

    private Result login() {
        Result result = app.post("/api/dev/login").send();
        violations.addAll(contracts.violations("POST /api/dev/login", result));
        return result;
    }

    private static void status(String status) {
        db.jdbc().update("update core.core_guardian set status = :s where id = :g",
                new MapSqlParameterSource("s", status).addValue("g", SeedIds.GUARDIAN));
    }

    private static int sessions() {
        Integer count = db.jdbc().queryForObject("select count(*) from core.core_spring_session where principal_name = :g",
                new MapSqlParameterSource("g", SeedIds.GUARDIAN.toString()), Integer.class);
        return count == null ? -1 : count;
    }
}
