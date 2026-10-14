package kr.mujoong.core.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.core.web.InFlightRequests;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 실제 DB에서 Core 앱 안 기동 · 정지 순서 (RD-12 S2 ~ S6 · RD-13 T1 · T4 · T5 · BR5.12 · J116 · SD-13). 앱 역할 core_app으로 자기 이력 표를
 * 읽어 S2가 통과하고(afterMigrate 권한), 복구 줄 STARTUP_RECOVERY 한 줄 뒤 READY가 된다. 문맥을 닫으면 가장 높은 phase의 정지가 먼저
 * DRAINING으로 내리고 스케줄러를 멈춘다. 이 시험은 행을 만들지 않는다(공용 DB, 복구 문장은 모두 멱등).
 *
 * <p>3.6 수정 분량(J342): S2를 실제 이력 표로 본다 — 성공한 버전이 없거나 낮으면, 앱 역할이 이력 표를 읽지 못하면 기동이 S2에서 멈추고 복구(S3)는
 * 돌지 않는다. 앱보다 앞선 이력(다음 배포가 올린 버전)은 통과하고 실패한 행은 세지 않는다. 이력 표를 바꾸는 이 세 시험은 공용 인스턴스가 아닌
 * 별도 인스턴스를 쓰고 바꾼 것을 시험마다 되돌린다.
 */
@Tag("u1")
@Tag("db")
class CoreStartupSequenceIT {

    private static MujoongPostgres postgres;
    /** S2 시험 전용 인스턴스(이력 표를 바꾼다). 처음 쓸 때 띄운다. */
    private static MujoongPostgres separate;

    @BeforeAll
    static void database() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @AfterAll
    static void stopSeparate() {
        if (separate != null) {
            separate.close();
        }
    }

    @Test
    void contextRefreshRunsSchemaCheckRecoveryAndBecomesReadyThenDrainsOnClose() {
        try (LogCapture capture = LogCapture.attach()) {
            runner().run(context -> {
                assertThat(context).hasNotFailed();
                ReadinessState readiness = context.getBean(ReadinessState.class);
                CoreStartupSequence sequence = context.getBean(CoreStartupSequence.class);
                DrainingLifecycle draining = context.getBean(DrainingLifecycle.class);

                assertThat(readiness.state()).isEqualTo(ReadinessState.State.READY);
                assertThat(sequence.isRunning()).isTrue();
                assertThat(draining.isRunning()).isTrue();
                assertThat(context.getBean(SchemaVersionCheck.class).verify()).isEqualTo(SchemaVersionCheck.expectedVersion());
                Map<String, Object> recovery = LogCapture.attrs(capture.last(EventCode.STARTUP_RECOVERY).orElseThrow());
                assertThat(recovery).containsKeys("recoveredSessions", "reopenedInboxRows", "resetConnections",
                        "rearmedControlEvents");
                assertThat(((Number) recovery.get("recoveredSessions")).longValue()).isZero();
                assertThat(((Number) recovery.get("resetConnections")).longValue()).isZero();
                assertThat(capture.events(EventCode.STARTUP_RECOVERY)).hasSize(1);

                context.close();

                assertThat(readiness.state()).isEqualTo(ReadinessState.State.DRAINING);
                assertThat(readiness.isReady()).isFalse();
                assertThat(sequence.isRunning()).isFalse();
                assertThat(draining.isRunning()).isFalse();
            });
        }
    }

    @Test
    void stoppingTheSchedulersTwiceIsHarmlessAndKeepsDraining() {
        runner().run(context -> {
            CoreStartupSequence sequence = context.getBean(CoreStartupSequence.class);
            ReadinessState readiness = context.getBean(ReadinessState.class);

            context.getBean(DrainingLifecycle.class).stop();
            sequence.stopSchedulers();

            assertThat(readiness.state()).isEqualTo(ReadinessState.State.DRAINING);
            assertThat(sequence.isRunning()).isFalse();
            assertThat(readiness.recordDbCheck(true)).isEqualTo(ReadinessState.State.DRAINING);
        });
    }

    @Test
    void startupStopsAtS2WhenTheHistoryHasNoSuccessfulVersionOrOnlyAnOlderOne() throws SQLException {
        MujoongPostgres database = separate();
        try (LogCapture capture = LogCapture.attach()) {
            withHistoryChange(database, "update flyway.core_schema_history set success = false",
                    "update flyway.core_schema_history set success = true",
                    () -> assertRefusedAtS2(database, "성공한 버전이 없다"));
            // 적용된 버전(V1 · U2 V2 · U4a V3 · U5a V4 · U7 V6)을 모두 앱 기대값보다 낮게 바꾼다(최고 0.9)
            withHistoryChange(database,
                    "update flyway.core_schema_history set version = case version when '1' then '0.5' when '2' then '0.6'"
                            + " when '3' then '0.7' when '4' then '0.8' when '6' then '0.9' else version end",
                    "update flyway.core_schema_history set version = case version when '0.5' then '1' when '0.6' then '2'"
                            + " when '0.7' then '3' when '0.8' then '4' when '0.9' then '6' else version end",
                    () -> assertRefusedAtS2(database, "버전 0.9 < 기대 " + SchemaVersionCheck.expectedVersion()));

            assertThat(capture.events(EventCode.STARTUP_RECOVERY)).as("S2에서 멈춰 복구(S3)는 돌지 않았다").isEmpty();
        }
    }

    @Test
    void startupStopsAtS2WhenTheAppRoleCannotReadItsOwnHistoryTable() throws SQLException {
        MujoongPostgres database = separate();
        try (LogCapture capture = LogCapture.attach()) {
            withHistoryChange(database, "revoke select on flyway.core_schema_history from core_app",
                    "grant select on flyway.core_schema_history to core_app",
                    () -> assertRefusedAtS2(database, "core_schema_history를 읽지 못했다"));

            assertThat(capture.events(EventCode.STARTUP_RECOVERY)).isEmpty();
        }
    }

    @Test
    void aHistoryAheadOfTheAppStartsAndFailedNewerRowsDoNotCount() throws SQLException {
        MujoongPostgres database = separate();
        // 다음 배포가 앱(V6까지, U7)보다 앞선 V7을 이미 올렸고, 그보다 높은 V8은 실패한 채 남았다
        withHistoryChange(database, """
                insert into flyway.core_schema_history (installed_rank, version, description, type, script, checksum,
                                                        installed_by, installed_on, execution_time, success)
                values (100, '7', 'newer app', 'SQL', 'V7__newer_app.sql', 0, 'mujoong_owner',
                        timestamp '2001-09-09 00:00:00', 0, true),
                       (101, '8', 'failed', 'SQL', 'V8__failed.sql', 0, 'mujoong_owner',
                        timestamp '2001-09-09 00:00:00', 0, false)
                """, "delete from flyway.core_schema_history where installed_rank in (100, 101)",
                () -> runner(database).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ReadinessState.class).state()).isEqualTo(ReadinessState.State.READY);
                    assertThat(context.getBean(SchemaVersionCheck.class).verify()).as("성공한 최고 버전(실패한 8은 세지 않는다)")
                            .isEqualTo("7");
                }));
    }

    private static synchronized MujoongPostgres separate() {
        if (separate == null) {
            separate = MujoongPostgres.builder().start();
        }
        return separate;
    }

    /** mujoong_owner로 이력 표를 바꾸고 {@code body} 뒤 되돌린다. */
    private static void withHistoryChange(MujoongPostgres database, String change, String restore, Runnable body)
            throws SQLException {
        execute(database, change);
        try {
            body.run();
        } finally {
            execute(database, restore);
        }
    }

    private static void execute(MujoongPostgres database, String sql) throws SQLException {
        try (Connection owner = database.connect(Role.OWNER); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertRefusedAtS2(MujoongPostgres database, String reason) {
        runner(database).run(context -> {
            assertThat(context).as(reason).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(reason);
        });
    }

    private ApplicationContextRunner runner() {
        return runner(postgres);
    }

    private ApplicationContextRunner runner(MujoongPostgres database) {
        return CoreBusinessContext.runner(database, new MutableClock(Instant.parse("2001-09-09T00:00:00Z")),
                        URI.create("http://127.0.0.1:9"), TestSecrets.internalServiceToken())
                // T6(3.6 수정 J321)가 기다리는 진행 중 요청 수는 웹 패키지 빈이라 업무 문맥 스캔 밖이다
                .withUserConfiguration(CoreStartupSequence.class, DrainingLifecycle.class, InFlightRequests.class);
    }
}
