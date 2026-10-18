package kr.mujoong.realtime.guardrail;

import static kr.mujoong.realtime.guardrail.GuardrailFixtures.POLICY;
import static kr.mujoong.realtime.guardrail.GuardrailFixtures.T0;
import static kr.mujoong.realtime.guardrail.GuardrailFixtures.allow;
import static kr.mujoong.realtime.guardrail.GuardrailFixtures.blocked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * 제브 검사 기록 비동기 기록기 단위 시험 (U13 BR8.2 · NFR8). 일시 실패는 다음 틱 재시도 · 제약 위반과 차단된 보호자는 버림 · 칸에 넣는 쪽은 DB를
 * 기다리지 않음 · 같은 id 덮어쓰기. 저장소는 대역이다(실제 제브 · 실제 DB 아님).
 */
@Tag("jev-guardrail")
class GuardrailEventRecorderTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture logs = LogCapture.detached();
    private final EventLogger events = TestEventLoggers.strict(clock, logs, RealtimeMetrics.inMemory());
    private final UUID guardian = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    /** 대본대로 실패하는 저장소. */
    private static final class ScriptedStore implements GuardrailEventRecorder.Store {
        final List<GuardrailEventRecord> written = new ArrayList<>();
        final List<RuntimeException> failures = new ArrayList<>();
        boolean alwaysFail;

        @Override
        public synchronized void write(GuardrailEventRecord record) {
            if (alwaysFail) {
                throw new TransientDataAccessResourceException("합성 일시 장애");
            }
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
            written.add(record);
        }
    }

    private List<String> errorKinds() {
        return logs.events(EventCode.APP_ERROR).stream().map(event -> (String) LogCapture.attrs(event).get("errorKind")).toList();
    }

    @Test
    void aRecordIsWrittenAndTheSameIdOverwritesItsCell() {
        ScriptedStore store = new ScriptedStore();
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(store, TaskLauncher.inline(), events);
        UUID id = UUID.randomUUID();

        assertThat(recorder.put(blocked(id, guardian, session, GuardrailEventRecord.Decision.TIMEOUT, "GUARDRAIL_TIMEOUT"))).isTrue();
        assertThat(recorder.put(allow(id, guardian, session))).isTrue();

        assertThat(store.written).extracting(GuardrailEventRecord::id).containsExactly(id, id);
        assertThat(store.written.get(1).decision()).isEqualTo(GuardrailEventRecord.Decision.ALLOW);
        assertThat(recorder.held()).isZero();
    }

    @Test
    void aTransientFailureKeepsTheCellAndTheNextTickWritesIt() {
        ScriptedStore store = new ScriptedStore();
        store.failures.add(new TransientDataAccessResourceException("합성 일시 장애"));
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(store, TaskLauncher.inline(), events);
        UUID id = UUID.randomUUID();

        recorder.put(allow(id, guardian, session));

        assertThat(store.written).as("첫 쓰기는 실패").isEmpty();
        assertThat(recorder.held()).isEqualTo(1);
        assertThat(errorKinds()).containsExactly(GuardrailEventRecorder.REASON_WRITE_FAILED);

        recorder.tick();

        assertThat(store.written).extracting(GuardrailEventRecord::id).containsExactly(id);
        assertThat(recorder.held()).isZero();
    }

    @Test
    void foreignKeyAndCheckViolationsAreDroppedWithOnlyAReasonCodeAndNeverRetried() {
        ScriptedStore store = new ScriptedStore();
        store.failures.add(new DataIntegrityViolationException("외래 키", new SQLException("fk", "23503")));
        store.failures.add(new DataIntegrityViolationException("검사", new SQLException("ck", "23514")));
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(store, TaskLauncher.inline(), events);

        recorder.put(allow(UUID.randomUUID(), guardian, session));
        recorder.put(allow(UUID.randomUUID(), guardian, session));
        recorder.tick();

        assertThat(store.written).isEmpty();
        assertThat(recorder.held()).isZero();
        assertThat(errorKinds()).as("같은 사유 줄은 합쳐진다").isNotEmpty().containsOnly(GuardrailEventRecorder.REASON_DISCARDED);
    }

    @Test
    void aBlockedGuardianIsDroppedAndItsPendingCellsAreCleared() {
        ScriptedStore store = new ScriptedStore();
        // 첫 기록은 두 번(자기 쓰기 · 다음 기록이 부른 쓰기) 실패해 칸에 남는다
        store.failures.add(new TransientDataAccessResourceException("합성 일시 장애"));
        store.failures.add(new TransientDataAccessResourceException("합성 일시 장애"));
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(store, TaskLauncher.inline(), events);
        recorder.put(allow(UUID.randomUUID(), guardian, session));
        UUID other = UUID.randomUUID();
        recorder.put(allow(UUID.randomUUID(), other, UUID.randomUUID()));
        assertThat(recorder.held()).as("앞 칸은 일시 실패로 남아 있었고 뒤 칸은 썼다").isEqualTo(1);

        assertThat(recorder.block(guardian, Duration.ofMillis(100))).isTrue();

        assertThat(recorder.held()).isZero();
        assertThat(recorder.put(allow(UUID.randomUUID(), guardian, session))).as("차단 뒤 기록은 받지 않는다").isFalse();
        assertThat(recorder.put(allow(UUID.randomUUID(), other, UUID.randomUUID()))).isTrue();
        assertThat(store.written).hasSize(2);
    }

    @Test
    void putNeverWaitsForTheDatabaseAndBlockWaitsForTheFlushInProgress() throws Exception {
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<GuardrailEventRecord> written = new ArrayList<>();
        GuardrailEventRecorder.Store slow = record -> {
            inWrite.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (written) {
                written.add(record);
            }
        };
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(slow, TaskLauncher.virtualThreads(), events);

        long started = System.nanoTime();
        recorder.put(allow(UUID.randomUUID(), guardian, session));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("저장소가 막혀도 put은 돌아온다").isLessThan(Duration.ofSeconds(2));
        assertThat(inWrite.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(recorder.block(guardian, Duration.ofMillis(50))).as("진행 중 쓰기가 안 끝났으면 기다림 상한 뒤 거짓").isFalse();

        release.countDown();

        assertThat(recorder.block(guardian, Duration.ofSeconds(5))).isTrue();
        synchronized (written) {
            assertThat(written).hasSize(1);
        }
    }

    @Test
    void drainWritesWhatIsLeftOnTheCallingThread() {
        ScriptedStore store = new ScriptedStore();
        store.failures.add(new TransientDataAccessResourceException("합성 일시 장애"));
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(store, TaskLauncher.inline(), events);
        recorder.put(allow(UUID.randomUUID(), guardian, session));
        assertThat(recorder.held()).isEqualTo(1);

        int left = recorder.drain(Duration.ofSeconds(1));

        assertThat(left).isZero();
        assertThat(store.written).hasSize(1);
    }

    @Test
    void theCellListIsBoundedAndANewRecordIsRefusedWhenItIsFull() {
        ScriptedStore store = new ScriptedStore();
        store.alwaysFail = true;
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(store, TaskLauncher.inline(), events);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < GuardrailEventRecorder.MAX_PENDING; i++) {
            ids.add(UUID.randomUUID());
            assertThat(recorder.put(allow(ids.get(i), guardian, session))).isTrue();
        }

        assertThat(recorder.put(allow(UUID.randomUUID(), guardian, session))).isFalse();
        assertThat(recorder.put(allow(ids.get(0), guardian, session))).as("이미 있는 id는 덮어쓴다").isTrue();
        assertThat(errorKinds()).contains(GuardrailEventRecorder.REASON_REJECTED);
    }

    @Test
    void permanentAndTransientFailuresAreToldApart() {
        assertThat(GuardrailEventRecorder.isPermanent(new DataIntegrityViolationException("x", new SQLException("fk", "23503")))).isTrue();
        assertThat(GuardrailEventRecorder.isPermanent(new DataIntegrityViolationException("x", new SQLException("ck", "23514")))).isTrue();
        assertThat(GuardrailEventRecorder.isPermanent(new TransientDataAccessResourceException("x", new SQLException("t", "55P03")))).isFalse();
        assertThat(GuardrailEventRecorder.isPermanent(new IllegalStateException("x"))).isFalse();
        assertThatThrownBy(() -> new GuardrailEventRecorder(null, TaskLauncher.inline(), events)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRecordRefusesValuesTheTableWouldRejectSoABadCallerShowsUpAtOnce() {
        UUID id = UUID.randomUUID();
        GuardrailEventRecord.SourceKind kind = GuardrailEventRecord.SourceKind.NEW_UTTERANCE;
        GuardrailEventRecord.Decision allow = GuardrailEventRecord.Decision.ALLOW;

        assertThatThrownBy(() -> new GuardrailEventRecord(id, guardian, session, GuardrailMode.OFF, kind, allow, "OK", POLICY, 1, T0))
                .as("OFF는 기록이 없다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardrailEventRecord(id, guardian, session, GuardrailMode.REAL, kind, allow, "문장이면 안 된다", POLICY, 1, T0))
                .as("사유 코드 형식").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardrailEventRecord(id, guardian, session, GuardrailMode.REAL, kind, allow, "OK", "", 1, T0))
                .as("빈 정책 판").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuardrailEventRecord(id, guardian, session, GuardrailMode.REAL, kind, allow, "OK", POLICY, -1, T0))
                .as("음수 지연").isInstanceOf(IllegalArgumentException.class);
        assertThat(new GuardrailEventRecord(id, guardian, session, GuardrailMode.STUB, kind, allow, "STUB_ALLOW", POLICY, 0, T0).mode())
                .isEqualTo(GuardrailMode.STUB);
    }
}
