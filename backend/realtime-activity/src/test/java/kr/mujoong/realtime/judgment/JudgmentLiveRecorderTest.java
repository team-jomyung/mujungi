package kr.mujoong.realtime.judgment;

import static kr.mujoong.realtime.judgment.JudgmentFixtures.abstained;
import static kr.mujoong.realtime.judgment.JudgmentFixtures.confirmed;
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
 * 판정 기록 비동기 기록기 단위 시험 (U11 BR2.1 · BR2.5 · NFR8). 일시 실패는 다음 틱 재시도 · 제약 위반과 차단된 보호자는 버림 · 칸에 넣는 쪽은
 * DB를 기다리지 않음 · 같은 id 덮어쓰기. 저장소는 대역이다.
 */
@Tag("judgment-turn")
class JudgmentLiveRecorderTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture logs = LogCapture.detached();
    private final EventLogger events = TestEventLoggers.strict(clock, logs, RealtimeMetrics.inMemory());
    private final UUID guardian = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    /** 대본대로 실패하는 저장소. */
    private static final class ScriptedStore implements JudgmentLiveRecorder.Store {
        final List<JudgmentLiveRecord> written = new ArrayList<>();
        final List<RuntimeException> failures = new ArrayList<>();
        boolean alwaysFail;

        @Override
        public synchronized void write(JudgmentLiveRecord record) {
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
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(store, TaskLauncher.inline(), events);
        UUID id = UUID.randomUUID();

        assertThat(recorder.put(abstained(id, guardian, session, AbstainReason.TIMEOUT))).isTrue();
        assertThat(recorder.put(confirmed(id, guardian, session))).isTrue();

        assertThat(store.written).extracting(JudgmentLiveRecord::id).containsExactly(id, id);
        assertThat(store.written.get(1).outcome()).isEqualTo(JudgmentOutcomeKind.CONFIRMED);
        assertThat(recorder.held()).isZero();
    }

    @Test
    void aTransientFailureKeepsTheCellAndTheNextTickWritesIt() {
        ScriptedStore store = new ScriptedStore();
        store.failures.add(new TransientDataAccessResourceException("합성 일시 장애"));
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(store, TaskLauncher.inline(), events);
        UUID id = UUID.randomUUID();

        recorder.put(confirmed(id, guardian, session));

        assertThat(store.written).as("첫 쓰기는 실패").isEmpty();
        assertThat(recorder.held()).isEqualTo(1);
        assertThat(errorKinds()).containsExactly(JudgmentLiveRecorder.REASON_WRITE_FAILED);

        recorder.tick();

        assertThat(store.written).extracting(JudgmentLiveRecord::id).containsExactly(id);
        assertThat(recorder.held()).isZero();
    }

    @Test
    void foreignKeyAndCheckViolationsAreDroppedWithOnlyAReasonCodeAndNeverRetried() {
        ScriptedStore store = new ScriptedStore();
        store.failures.add(new DataIntegrityViolationException("외래 키", new SQLException("fk", "23503")));
        store.failures.add(new DataIntegrityViolationException("검사", new SQLException("ck", "23514")));
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(store, TaskLauncher.inline(), events);

        recorder.put(confirmed(UUID.randomUUID(), guardian, session));
        recorder.put(confirmed(UUID.randomUUID(), guardian, session));
        recorder.tick();

        assertThat(store.written).isEmpty();
        assertThat(recorder.held()).isZero();
        assertThat(errorKinds()).as("같은 사유 줄은 합쳐진다").isNotEmpty().containsOnly(JudgmentLiveRecorder.REASON_DISCARDED);
        assertThat(logs.lines()).noneMatch(line -> line.contains("모은 출력 합성 전사"));
    }

    @Test
    void aBlockedGuardianIsDroppedAndItsPendingCellsAreCleared() {
        ScriptedStore store = new ScriptedStore();
        // 첫 기록은 두 번(자기 쓰기 · 다음 기록이 부른 쓰기) 실패해 칸에 남는다
        store.failures.add(new TransientDataAccessResourceException("합성 일시 장애"));
        store.failures.add(new TransientDataAccessResourceException("합성 일시 장애"));
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(store, TaskLauncher.inline(), events);
        recorder.put(confirmed(UUID.randomUUID(), guardian, session));
        UUID other = UUID.randomUUID();
        recorder.put(confirmed(UUID.randomUUID(), other, UUID.randomUUID()));
        assertThat(recorder.held()).as("앞 칸은 일시 실패로 남아 있었고 뒤 칸은 썼다").isEqualTo(1);

        assertThat(recorder.block(guardian, Duration.ofMillis(100))).isTrue();

        assertThat(recorder.held()).isZero();
        assertThat(recorder.put(confirmed(UUID.randomUUID(), guardian, session))).as("차단 뒤 기록은 받지 않는다").isFalse();
        assertThat(recorder.put(confirmed(UUID.randomUUID(), other, UUID.randomUUID()))).isTrue();
        assertThat(store.written).hasSize(2);
    }

    @Test
    void putNeverWaitsForTheDatabaseAndBlockWaitsForTheFlushInProgress() throws Exception {
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<JudgmentLiveRecord> written = new ArrayList<>();
        JudgmentLiveRecorder.Store slow = record -> {
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
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(slow, TaskLauncher.virtualThreads(), events);

        long started = System.nanoTime();
        recorder.put(confirmed(UUID.randomUUID(), guardian, session));
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
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(store, TaskLauncher.inline(), events);
        recorder.put(confirmed(UUID.randomUUID(), guardian, session));
        assertThat(recorder.held()).isEqualTo(1);

        int left = recorder.drain(Duration.ofSeconds(1));

        assertThat(left).isZero();
        assertThat(store.written).hasSize(1);
    }

    @Test
    void theCellListIsBoundedAndANewRecordIsRefusedWhenItIsFull() {
        ScriptedStore store = new ScriptedStore();
        store.alwaysFail = true;
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(store, TaskLauncher.inline(), events);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < JudgmentLiveRecorder.MAX_PENDING; i++) {
            ids.add(UUID.randomUUID());
            assertThat(recorder.put(confirmed(ids.get(i), guardian, session))).isTrue();
        }

        assertThat(recorder.put(confirmed(UUID.randomUUID(), guardian, session))).isFalse();
        assertThat(recorder.put(confirmed(ids.get(0), guardian, session))).as("이미 있는 id는 덮어쓴다").isTrue();
        assertThat(errorKinds()).contains(JudgmentLiveRecorder.REASON_REJECTED);
    }

    @Test
    void permanentAndTransientFailuresAreToldApart() {
        assertThat(JudgmentLiveRecorder.isPermanent(new DataIntegrityViolationException("x", new SQLException("fk", "23503")))).isTrue();
        assertThat(JudgmentLiveRecorder.isPermanent(new DataIntegrityViolationException("x", new SQLException("ck", "23514")))).isTrue();
        assertThat(JudgmentLiveRecorder.isPermanent(new TransientDataAccessResourceException("x", new SQLException("t", "55P03")))).isFalse();
        assertThat(JudgmentLiveRecorder.isPermanent(new IllegalStateException("x"))).isFalse();
        assertThatThrownBy(() -> new JudgmentLiveRecorder(null, TaskLauncher.inline(), events)).isInstanceOf(NullPointerException.class);
    }
}
