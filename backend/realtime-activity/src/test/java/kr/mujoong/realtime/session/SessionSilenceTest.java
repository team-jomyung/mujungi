package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 공백 시계의 실행기 연결 시험 (L4 · FR4.1 · BR5.1 ~ BR5.4 · U4b-J10 · U4b-J11).
 *
 * <p>4분 경고(진행 조회 값 · SILENCE_WARNING 1회) · 6분 CLOSING(SILENCE), 빈 아닌 전사의 리셋, 기기 재생 중 멈춤을 본다. 기기는 HEARTBEAT처럼
 * 살아 있게 둔다(끊김을 보지 않는다).
 */
@Tag("session-runtime")
class SessionSilenceTest {

    private static final String WARNING_TEXT = "괜찮아, 천천히 이야기해도 돼.";

    private final SessionHarness h = new SessionHarness();

    @Test
    void fourMinutesWarnOnceAndSixMinutesCloseWithSilence() {
        h.extraApproved.put(SessionExecutor.SILENCE_WARNING, WARNING_TEXT);
        h.activate();

        h.advanceAlive(Duration.ofSeconds(239));
        assertThat(h.executor.liveState().silenceWarning()).isNull();
        h.advanceAlive(Duration.ofSeconds(2));

        assertThat(h.executor.liveState().silenceWarning()).isNotNull();
        List<PlayApprovedContent> warnings = h.link.sent(PlayApprovedContent.class).stream()
                .filter(play -> play.contentCode().equals(SessionExecutor.SILENCE_WARNING)).toList();
        assertThat(warnings).hasSize(1);
        assertThat(h.store.transcripts).anySatisfy(row -> assertThat(row.text()).isEqualTo(WARNING_TEXT));
        mark(warnings.getFirst());

        h.advanceAlive(Duration.ofSeconds(117));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.advanceAlive(Duration.ofSeconds(4));

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(row.endReason()).isEqualTo(EndReason.SILENCE);
        assertThat(h.link.sent(StopOutput.class)).extracting(StopOutput::reason)
                .containsExactly(StopOutput.Reason.SILENCE);
        assertThat(events(IdleClock.EVENT_SILENCE_WARNED)).hasSize(1);
    }

    @Test
    void aNonEmptyUtteranceResetsTheClockAndClearsTheWarningButAnEmptyOneDoesNot() {
        h.activate();
        h.advanceAlive(Duration.ofSeconds(250));
        assertThat(h.executor.liveState().silenceWarning()).isNotNull();

        h.childSays("i-empty", "");
        assertThat(h.executor.liveState().silenceWarning()).as("빈 전사는 리셋하지 않는다").isNotNull();
        h.childSays("i-1", "공룡 좋아");

        assertThat(h.executor.liveState().silenceWarning()).isNull();
        assertThat(events(IdleClock.EVENT_SILENCE_RESET)).hasSize(1);
        h.advanceAlive(Duration.ofSeconds(200));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void timeWhileTheDevicePlaysIsNotSilence() {
        h.extraApproved.put(SessionExecutor.SILENCE_WARNING, WARNING_TEXT);
        h.activate();
        h.advanceAlive(Duration.ofSeconds(241));
        PlayApprovedContent warning = h.link.sent(PlayApprovedContent.class).getLast();
        // 재생 표식이 오지 않는 동안(재생 중 추적)은 멈춘다 — 출력 상한 20초가 추적을 지운 뒤부터 다시 센다
        h.advanceAlive(Duration.ofSeconds(130));

        assertThat(h.executor.status()).as("재생 추적 20초는 공백에 세지 않는다").isEqualTo(SessionStatus.ACTIVE);
        assertThat(warning.contentCode()).isEqualTo(SessionExecutor.SILENCE_WARNING);
        h.advanceAlive(Duration.ofSeconds(15));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
    }

    @Test
    void withoutTheWarningContentTheClockStillRunsAndReportsTheMissingCodeOnce() {
        h.activate();
        h.advanceAlive(Duration.ofSeconds(361));

        assertThat(h.transitions.find(h.sessionId).orElseThrow().endReason()).isEqualTo(EndReason.SILENCE);
        assertThat(events(SessionExecutor.EVENT_APPROVED_CONTENT_MISSING)).extracting(SessionEvent::getPayloadJson)
                .contains("{\"code\":\"SILENCE_WARNING\"}");
    }

    private void mark(PlayApprovedContent play) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), PlaybackMark.Status.COMPLETED, null, h.clock.now(), null, null),
                h.clock.now()));
        h.drain();
    }

    private List<SessionEvent> events(String type) {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> event.getEventType().equals(type)).toList();
    }
}
