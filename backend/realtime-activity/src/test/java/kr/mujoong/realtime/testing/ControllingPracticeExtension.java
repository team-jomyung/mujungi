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
    /** 4회차 R1: '아이 말 시작' 알림을 받은 시각(입력 전사 묶음마다 하나). */
    public final List<java.time.Instant> speechStarts = new ArrayList<>();
    /** 3회차 V1: 참여 확인 때 실행부에 알려 줄 대본 줄(create 전에 넣는다). */
    public final List<SpeechLine> speechLines = new ArrayList<>();
    /** 3회차 V1: speechLines를 물은 횟수. */
    public int speechLinesAsked;
    /** 3회차 V1: 참이면 speechLines가 예외를 던진다. */
    public boolean speechLinesFail;
    /** 이 대역을 돌려주는 공장. */
    public final Factory factory = sessionId -> this;

    @Override
    public List<SpeechLine> speechLines(String practiceJson, java.util.Map<String, String> approvedSpeechTexts) {
        speechLinesAsked++;
        if (speechLinesFail) {
            throw new IllegalStateException("시험용 대본 줄 실패");
        }
        return List.copyOf(speechLines);
    }

    /** 그 발화의 끝 사실(시작 사실 STARTED는 뺀다). */
    public List<PracticePorts.Playback> endFacts(UUID correlationId) {
        return facts.stream().filter(fact -> !fact.started()).filter(fact -> fact.speech() != null)
                .filter(fact -> fact.speech().correlationId().equals(correlationId)).map(PlaybackFact::status).toList();
    }

    @Override
    public boolean controlsOutput() {
        return true;
    }

    /** 4회차 R2: 참이면 실행부가 이 대역의 연습 발화 지시를 공급자가 조용할 때 보낸다(create 전에 바꾼다, 기본은 거짓 = 곧바로 보낸다). */
    public boolean paces;

    @Override
    public boolean pacesSpeech() {
        return paces;
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
    public Signal onChildSpeechStarted(java.time.Instant at, PracticeOutput output) {
        speechStarts.add(at);
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
