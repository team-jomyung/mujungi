package kr.mujoong.realtime.session;

/**
 * 연습 확장점의 기본 구현 (U4b · BR10.2 · U4b-R17). 모든 시점이 '처리 안 함'과 practice 값 null을 돌려준다 — U5b가 빈을 등록하기 전까지 PRACTICE의
 * ACTIVE는 자유대화처럼 동작한다.
 */
public final class NoOpPracticeSessionExtension implements PracticeSessionExtension {

    /** 무동작 확장점(상태 없음, 세션들이 함께 쓴다). */
    public static final NoOpPracticeSessionExtension INSTANCE = new NoOpPracticeSessionExtension();

    /** 무동작 확장점을 돌려주는 기본 공장. */
    public static final Factory FACTORY = sessionId -> INSTANCE;

    private NoOpPracticeSessionExtension() {
    }

    @Override
    public Signal onActive(PracticeContext context, PracticeOutput output) {
        return Signal.NONE;
    }

    @Override
    public Signal onChildUtterance(Utterance utterance, PracticeOutput output) {
        return Signal.NONE;
    }

    @Override
    public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output) {
        return Signal.NONE;
    }

    @Override
    public void onSettle(EndContext end) {
        // 정리할 연습 상태가 없다
    }

    @Override
    public Object practiceRecord() {
        return null;
    }
}
