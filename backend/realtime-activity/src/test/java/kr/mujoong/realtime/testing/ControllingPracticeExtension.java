package kr.mujoong.realtime.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.practicelegacy.PracticePorts;
import kr.mujoong.realtime.session.PracticeSessionExtension;

/**
 * 출력권을 쥐는 최소 연습 확장점 대역(시험 소스 전용). 받은 출력 능력을 시험이 쓰게 남기고, 확장점 사건 · 재생 사실을 기록한다. 실제 연습 흐름은
 * {@code practiceflow.PracticeConversation}이고 이 대역은 실행부 쪽 규칙만 시험한다(테스트 대역 증거, R-M11).
 */
public final class ControllingPracticeExtension implements PracticeSessionExtension {

    public PracticeOutput out;
    public final List<ExtensionEvent> events = new ArrayList<>();
    public final List<PlaybackFact> facts = new ArrayList<>();
    public final List<Utterance> utterances = new ArrayList<>();
    /** 이 대역을 돌려주는 공장. */
    public final Factory factory = sessionId -> this;

    /** 그 발화의 끝 사실(시작 사실 STARTED는 뺀다). */
    public List<PracticePorts.Playback> endFacts(UUID correlationId) {
        return facts.stream().filter(fact -> !fact.started()).filter(fact -> fact.speech() != null)
                .filter(fact -> fact.speech().correlationId().equals(correlationId)).map(PlaybackFact::status).toList();
    }

    @Override
    public boolean controlsOutput() {
        return true;
    }

    @Override
    public Signal onActive(PracticeContext context, PracticeOutput output) {
        out = output;
        return Signal.NONE;
    }

    @Override
    public Signal onChildUtterance(Utterance utterance, PracticeOutput output) {
        utterances.add(utterance);
        return Signal.NONE;
    }

    @Override
    public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output) {
        events.add(event);
        if (event.value() instanceof PlaybackFact fact) {
            facts.add(fact);
        }
        return Signal.NONE;
    }

    @Override
    public void onSettle(EndContext end) {
        // 정리할 것이 없다
    }

    @Override
    public Object practiceRecord() {
        return null;
    }
}
