package kr.mujoong.realtime.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import kr.mujoong.realtime.session.PracticeSessionExtension;

/**
 * 시험용 연습 확장점 대역(U4b BR10.1 ~ BR10.6). 들어온 시점 · 사건을 기록하고, 시험이 정한 함수로 신호를 돌려주며, 받은 출력 능력을 시험이 쓸 수
 * 있게 남긴다. 예외를 던지게 할 수도 있다. 출력권({@link #controls})은 기본이 거짓이라 켠 시험만 바뀐다. 운영 소스에는 무동작 구현만 있다.
 */
public final class RecordingPracticeExtension implements PracticeSessionExtension {

    public final List<String> calls = new ArrayList<>();
    public final List<ExtensionEvent> events = new ArrayList<>();
    public final List<Utterance> utterances = new ArrayList<>();
    /** 실행부가 알린 '아이 말 시작' 시각(4회차 R1). 출력권이 있을 때만 온다. */
    public final List<Instant> speechStarts = new ArrayList<>();
    public PracticeContext context;
    public EndContext end;
    public PracticeOutput output;
    public Object record;
    public Signal onActive = Signal.NONE;
    /** 참이면 출력권을 가진 연습처럼 군다(새 연습 흐름과 같다). */
    public boolean controls;
    public BiFunction<Utterance, PracticeOutput, Signal> onUtterance = (utterance, out) -> Signal.NONE;
    public BiFunction<Instant, PracticeOutput, Signal> onSpeechStarted = (at, out) -> Signal.NONE;
    public BiFunction<ExtensionEvent, PracticeOutput, Signal> onEvent = (event, out) -> Signal.NONE;
    public RuntimeException throwOnUtterance;
    public RuntimeException throwOnSettle;

    public int created;
    /** 이 대역을 돌려주는 공장(만든 횟수를 센다). */
    public final Factory factory = sessionId -> {
        this.created++;
        return this;
    };

    @Override
    public Signal onActive(PracticeContext value, PracticeOutput out) {
        calls.add("onActive");
        this.context = value;
        this.output = out;
        return onActive;
    }

    @Override
    public Signal onChildUtterance(Utterance utterance, PracticeOutput out) {
        calls.add("onChildUtterance");
        utterances.add(utterance);
        if (throwOnUtterance != null) {
            throw throwOnUtterance;
        }
        return onUtterance.apply(utterance, out);
    }

    @Override
    public boolean controlsOutput() {
        return controls;
    }

    @Override
    public Signal onChildSpeechStarted(Instant at, PracticeOutput out) {
        calls.add("onChildSpeechStarted");
        speechStarts.add(at);
        return onSpeechStarted.apply(at, out);
    }

    @Override
    public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput out) {
        calls.add("onExtensionEvent");
        events.add(event);
        return onEvent.apply(event, out);
    }

    @Override
    public void onSettle(EndContext value) {
        calls.add("onSettle");
        this.end = value;
        if (throwOnSettle != null) {
            throw throwOnSettle;
        }
    }

    @Override
    public Object practiceRecord() {
        calls.add("practiceRecord");
        return record;
    }
}
