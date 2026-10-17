package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PR #2 3차 리뷰 반영 시험(docs/units/u3/report.md 13절). 건마다 먼저 실패하던 재현 시험이다.
 *
 * <ul>
 *   <li>2 해제 조건: 부정형 · 남의 말 속 계속 표현은 종료 의도를 풀지 않고, 흔한 계속 표현("더 해 줘")은 푼다.
 *   <li>4 묶음 경주: 정리가 묶음을 확인한 뒤 새 세션이 같은 연결을 묶어도 그 묶음을 풀지 않는다(비교 후 풀기).
 * </ul>
 */
@Tag("u3")
class SessionExecutorReview3U3Test {

    private final SessionHarness h = new SessionHarness();

    @Test
    void negatedOrReportedContinuationsKeepTheBlockAndACommonOneLiftsIt() {
        h.activate();
        done("i1", "이제 그만할래");
        done("i2", "더 얘기 안 할래");
        done("i3", "형이 더 하자고 했는데 나는 그만할래");
        assertThat(eventTypes()).doesNotContain(SessionExecutor.EVENT_CHILD_STOP_LIFTED);
        emitComplete("r1", "응 그래");
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).isEmpty();

        // U4b(FR11.3 · FD-Q3): 첫 종료 의도에서 곧바로 CLOSING이라 흔한 계속 표현도 출력을 다시 열지 않는다
        done("i4", "더 해 줘");
        assertThat(eventTypes()).doesNotContain(SessionExecutor.EVENT_CHILD_STOP_LIFTED);
        emitComplete("r2", "티라노는 아주 컸어");
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.CLOSING);
    }

    @Test
    void aNewSessionThatBindsTheLinkRightAfterTheEndChecksTheBindingKeepsIt() {
        h.activate();
        UUID next = UUID.randomUUID();
        // 정리가 '이 세션에 묶였다'를 읽은 바로 그 순간 같은 기기의 새 세션이 이 연결을 묶는다
        h.link.afterBoundSessionRead(() -> h.link.bindSession(next));
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.CORE_ABANDONED,
                new CompletableFuture<>()));
        h.drain();

        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(h.link.boundSession()).as("새 세션의 입력 프레임 경로가 남는다").isEqualTo(next);
    }

    private void emitComplete(String responseId, String text) {
        h.speak(text, 4_800);
    }

    private void done(String itemId, String transcript) {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone(itemId, transcript, 0.95));
        h.drain();
    }

    private List<String> eventTypes() {
        h.recorder.tick();
        return h.store.sessionEvents.stream().map(SessionEvent::getEventType).toList();
    }
}
