package kr.mujoong.realtime.session;

import java.time.Duration;
import java.util.List;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.testing.FakeJudgeClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;

/** U11 실행기 시험 공통 도구: 가짜 판정기를 단 하네스와 자주 쓰는 읽기 도우미(합성 값만, R-F1). */
final class JudgmentExecutorSupport {

    /** 종료 의도가 불명확해 J6가 열리는 합성 전사. */
    static final String UNCLEAR_STOP = "쉬고 싶어";
    /** 참여 확인에서 불명확해 J1이 열리는 합성 전사. */
    static final String UNCLEAR_JOIN = "음 글쎄";

    final SessionHarness h = new SessionHarness();
    final FakeJudgeClient judge = h.useJudge();

    JudgmentExecutorSupport() {
        h.extraApproved.put(SessionExecutor.STOP_CONFIRM, "이야기를 그만할까?");
        h.extraApproved.put(SessionExecutor.RE_PROMPT, "같이 이야기해 볼까?");
        h.extraApproved.put(SessionExecutor.DECLINE_ALTERNATIVE, "그럼 다른 이야기를 해 볼까?");
    }

    /** 참여 확인까지(WAITING_CHILD) 간다. */
    void waiting() {
        h.boot();
        h.ackAndReady();
    }

    /** J6를 연다(ACTIVE 자유대화). */
    void openStopJudgment() {
        h.activate();
        h.childSays("i-1", UNCLEAR_STOP);
    }

    List<JudgmentLiveRecord> records() {
        h.judgmentRecorder.tick();
        return List.copyOf(h.store.judgments);
    }

    JudgmentLiveRecord onlyRecord() {
        List<JudgmentLiveRecord> records = records();
        org.assertj.core.api.Assertions.assertThat(records).hasSize(1);
        return records.getFirst();
    }

    List<View> views() {
        return h.link.sent(SessionState.class).stream().map(SessionState::view).toList();
    }

    List<SessionEvent> events(String type) {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> event.getEventType().equals(type)).toList();
    }

    List<String> appendedContents() {
        return h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).stream()
                .map(ProviderRequestFactory.InstructionsAppend::content).toList();
    }

    void mark(PlayApprovedContent play, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    void advance(Duration duration) {
        h.advanceAlive(duration);
    }
}
