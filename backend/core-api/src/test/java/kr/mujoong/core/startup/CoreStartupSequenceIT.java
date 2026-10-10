package kr.mujoong.core.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 실제 DB에서 Core 앱 안 기동 · 정지 순서 (RD-12 S2 ~ S6 · RD-13 T1 · T4 · T5 · BR5.12 · J116 · SD-13). 앱 역할 core_app으로 자기 이력 표를
 * 읽어 S2가 통과하고(afterMigrate 권한), 복구 줄 STARTUP_RECOVERY 한 줄 뒤 READY가 된다. 문맥을 닫으면 가장 높은 phase의 정지가 먼저
 * DRAINING으로 내리고 스케줄러를 멈춘다. 이 시험은 행을 만들지 않는다(공용 DB, 복구 문장은 모두 멱등).
 */
@Tag("u1")
@Tag("db")
class CoreStartupSequenceIT {

    private static MujoongPostgres postgres;

    @BeforeAll
    static void database() {
        postgres = MujoongPostgres.sharedOrAbort();
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

    private ApplicationContextRunner runner() {
        return CoreBusinessContext.runner(postgres, new MutableClock(Instant.parse("2001-09-09T00:00:00Z")),
                        URI.create("http://127.0.0.1:9"), TestSecrets.internalServiceToken())
                .withUserConfiguration(CoreStartupSequence.class, DrainingLifecycle.class);
    }
}
