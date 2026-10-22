package kr.mujoong.realtime.session;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.testing.FakeJudgeClient;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;

/**
 * U11 실행기 시험 공통 도구: 가짜 판정기를 단 하네스와 자주 쓰는 읽기 도우미(합성 값만, R-F1).
 *
 * <p>실행부가 스스로 여는 판정(참여 J1 · 종료 J6)은 2026-10-22 결정으로 없앴다. 판정 턴은 연습 확장점이 {@code requestJudgment}로 여는
 * J2 ~ J5만 남았으므로, 일반 판정 턴 기계 시험은 연습 확장점 대역으로 J2 턴을 연다.
 */
final class JudgmentExecutorSupport {

    /** 판정을 요청할 합성 아이 전사. */
    static final String CHILD_SAYS = "그거 줘";

    final SessionHarness h = new SessionHarness();
    final FakeJudgeClient judge = h.useJudge();
    final RecordingPracticeExtension extension = new RecordingPracticeExtension();
    final UUID correlation = UUID.randomUUID();

    /** 참여 확인까지(WAITING_CHILD) 간다. */
    void waiting() {
        h.boot();
        h.ackAndReady();
        h.completeNotice();
    }

    /** 연습 확장점 대역을 단 연습 활동을 ACTIVE까지 연다. */
    void practice() {
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        h.practice = extension.factory;
        h.activate();
    }

    /** 연습 확장점이 방금 확정된 아이 전사로 J2 판정을 요청한다(턴이 열렸으면 true). */
    boolean requestJudgment() {
        h.childSays("i-1", CHILD_SAYS);
        long seq = extension.utterances.getLast().seq();
        boolean accepted = extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria());
        h.drain();
        return accepted;
    }

    /** 연습 활동을 열고 확장 소유 J2 판정 턴을 연다. */
    void openExtensionJudgment() {
        practice();
        org.assertj.core.api.Assertions.assertThat(requestJudgment()).as("J2 판정 턴이 열린다").isTrue();
    }

    static Map<String, Object> criteria() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("coreFunction", "필요한 것과 이유를 함께 말해 부탁한다");
        criteria.put("judgmentCriteria", "무엇이 필요한지와 왜 필요한지가 드러나면 충분히 전달");
        return criteria;
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
