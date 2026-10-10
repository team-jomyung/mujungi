package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kr.mujoong.core.health.DbHealthChecker;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.startup.DrainingLifecycle;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 공개 헬스와 준비 상태 게이트 (U1 3.5 Step 34 · 편차 (t)(n) · OD-11 · RD-16 · RD-13 T1 · R3-13 · J124 · SD-4 순서 1b).
 *
 * <p>이 시험만 쓰는 PostgreSQL 인스턴스에 앱을 띄운다(실제로 DB를 멈춰 '요청마다 DB 미호출'을 보이기 위해). DB 점검 주기는 1시간으로 늘려
 * 스케줄러가 끼어들지 않게 하고 점검자를 시험이 직접 부른다(Retry-After = 점검 주기 초). DRAINING은 되돌아오지 않으므로 마지막에 둔다.
 */
@Tag("u1")
@Tag("db")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthAndReadinessIT {

    private static final String CHECK_INTERVAL_SECONDS = "3600";

    private static MujoongPostgres database;
    private static ContractStubServer realtime;
    private static CoreWebApp app;

    @BeforeAll
    static void start() {
        MujoongPostgres.sharedOrAbort();
        database = MujoongPostgres.builder().start();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app = CoreWebApp.builder(database)
                .internalToken(token)
                .realtime(realtime.baseUrl())
                .property("mujoong.health.db-check-interval", CHECK_INTERVAL_SECONDS + "s")
                .start();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (realtime != null) {
            realtime.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    @Order(1)
    void healthIsUpWithStatusOnlyAndTraceHeader() {
        Result health = app.get("/api/health").send();

        assertThat(health.status()).isEqualTo(200);
        assertThat(health.json()).isEqualTo(Map.of("status", "UP"));
        assertThat(WebFixtures.schemaErrors("health-response.schema.json", health.body())).isEmpty();
        assertThat(health.header("X-Trace-Id")).matches("^[0-9a-f]{32}$");
        assertThat(health.header("Content-Type")).startsWith("application/json");
        assertThat(health.header("Access-Control-Allow-Origin")).isNull();
        assertThat(health.header("Server")).isNull();
    }

    @Test
    @Order(2)
    void managementReadinessIsUpOnTheManagementPortOnlyAndOtherVerbsAreNotPublic() {
        assertThat(app.management("/actuator/health/readiness").status()).isEqualTo(200);
        assertThat(app.management("/actuator/health/liveness").status()).isEqualTo(200);

        Result actuatorOnServicePort = app.get("/actuator/health").send();
        assertThat(actuatorOnServicePort.status()).isEqualTo(404);
        assertThat(actuatorOnServicePort.code()).isEqualTo("NOT_FOUND");
        Result postHealth = app.post("/api/health").send();
        assertThat(postHealth.status()).isEqualTo(401);
        assertThat(postHealth.code()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @Order(3)
    void healthNeverTouchesTheDatabaseAndThreeFailedChecksMakeEveryRequest503BeforeAuthentication() {
        database.close();

        Result whileDatabaseDown = app.get("/api/health").send();
        assertThat(whileDatabaseDown.status()).as("요청마다 DB를 부르지 않는다").isEqualTo(200);

        DbHealthChecker checker = app.bean(DbHealthChecker.class);
        ReadinessState readiness = app.bean(ReadinessState.class);
        assertThat(checker.runOnce()).isEqualTo(ReadinessState.State.READY);
        assertThat(checker.runOnce()).isEqualTo(ReadinessState.State.READY);
        assertThat(checker.runOnce()).isEqualTo(ReadinessState.State.NOT_READY);

        Result health = app.get("/api/health").send();
        assertNotReady(health);
        assertNotReady(app.post("/internal/events").json("{}").send());
        assertNotReady(app.post("/internal/events").internalToken().json("{}").send());
        assertNotReady(app.post("/api/device/register").json("{\"code\":\"7F3K92QD\"}").send());
        assertNotReady(app.post("/api/device/bootstrap").send());
        assertThat(app.management("/actuator/health/readiness").status()).isEqualTo(503);

        assertThat(readiness.recordDbCheck(true)).isEqualTo(ReadinessState.State.READY);
        assertThat(app.get("/api/health").send().status()).as("점검 1회 성공으로 복귀").isEqualTo(200);
    }

    @Test
    @Order(4)
    void drainingAnswers503ToNewRequestsAndNeverReturnsToReady() {
        app.bean(DrainingLifecycle.class).stop();
        ReadinessState readiness = app.bean(ReadinessState.class);

        assertThat(readiness.state()).isEqualTo(ReadinessState.State.DRAINING);
        assertNotReady(app.get("/api/health").send());
        assertNotReady(app.post("/internal/events").internalToken().json("{}").send());
        assertNotReady(app.get("/api/home").send());
        assertThat(app.management("/actuator/health/readiness").status()).isEqualTo(503);
        assertThat(readiness.recordDbCheck(true)).isEqualTo(ReadinessState.State.DRAINING);
        assertNotReady(app.get("/api/health").send());
    }

    private static void assertNotReady(Result result) {
        assertThat(result.status()).isEqualTo(503);
        assertThat(result.code()).isEqualTo("SERVICE_NOT_READY");
        assertThat(result.header("Retry-After")).isEqualTo(CHECK_INTERVAL_SECONDS);
        assertThat(result.json().get("traceId")).isEqualTo(result.header("X-Trace-Id"));
        assertThat(result.json().get("message")).isEqualTo("잠시 뒤 다시 시도해 주세요.");
        assertThat(result.json()).containsOnlyKeys("code", "message", "traceId");
    }
}
