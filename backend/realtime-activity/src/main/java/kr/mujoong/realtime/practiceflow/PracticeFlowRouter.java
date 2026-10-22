package kr.mujoong.realtime.practiceflow;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import kr.mujoong.realtime.session.PracticeSessionExtension;

/**
 * Factory가 세션마다 하나 만드는 얇은 분기 확장 (U12 D1 · BR1.4). {@code Factory.create(sessionId)} 시그니처를 바꾸지 않고, {@link #onActive}에서
 * 시작 패키지 {@code practice}의 첫 대본 {@code scriptVersion}을 보고 새 흐름({@link PracticeConversation}) 또는 레거시 확장 한 쪽을 고른다.
 * 고른 뒤의 모든 시점은 그 쪽에 그대로 위임한다.
 *
 * <p>{@link #controlsOutput()}은 분기 전에도 언제나 참이다 — 실행부가 {@code onActive} 전, 첫 응답 요청 전에 묻기 때문이다(레거시도 참이라 바뀌는
 * 동작이 없다). 새 흐름은 {@code scenario-script.v1}일 때만 고른다. {@code scriptVersion}이 없거나 첫 대본이 없는 패키지, 읽을 수 없는 패키지는
 * 레거시가 이전 그대로 받는다. 단 {@code practice} 원문이 아예 비면 레거시로 보내지 않고 '대본 없음'으로 실패시킨다(BR1.2).
 *
 * <p>이 클래스는 레거시 {@code practice} 패키지를 참조하지 않는다 — 레거시 확장은 확장점 인터페이스로만 받는다(BR10.2).
 */
public final class PracticeFlowRouter implements PracticeSessionExtension {

    private final PracticeSessionExtension legacy;
    private final Supplier<PracticeSessionExtension> flow;
    private PracticeSessionExtension delegate;

    /**
     * @param legacy scriptVersion이 없는 패키지를 받는 레거시 확장(세션마다 새로 만든 것)
     * @param flow 새 흐름 확장을 만드는 공급자(새 흐름이 골라졌을 때만 부른다)
     */
    public PracticeFlowRouter(PracticeSessionExtension legacy, Supplier<PracticeSessionExtension> flow) {
        this.legacy = Objects.requireNonNull(legacy, "legacy");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    @Override
    public boolean controlsOutput() {
        return true;
    }

    @Override
    public Signal onActive(PracticeContext context, PracticeOutput output) {
        if (delegate == null) {
            delegate = choose(context.practiceJson());
        }
        return delegate.onActive(context, output);
    }

    private PracticeSessionExtension choose(String practiceJson) {
        if (practiceJson == null || practiceJson.isBlank()) {
            throw new IllegalStateException("연습 대본 없음");
        }
        try {
            return PracticeStartPackage.parse(practiceJson).usesNewFlow() ? flow.get() : legacy;
        } catch (PracticeScriptException unreadable) {
            // 새 흐름이 읽지 못하는 패키지는 이전 확장이 이전 방식으로 받는다(레거시 동작 보존, BR10.1)
            return legacy;
        }
    }

    @Override
    public Signal onChildUtterance(Utterance utterance, PracticeOutput output) {
        return delegate == null ? Signal.NONE : delegate.onChildUtterance(utterance, output);
    }

    @Override
    public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output) {
        return delegate == null ? Signal.NONE : delegate.onExtensionEvent(event, output);
    }

    @Override
    public void onSettle(EndContext end) {
        if (delegate != null) {
            delegate.onSettle(end);
        }
    }

    @Override
    public Object practiceRecord() {
        return delegate == null ? null : delegate.practiceRecord();
    }

    @Override
    public Progress progress() {
        return delegate == null ? null : delegate.progress();
    }

    @Override
    public boolean collectingIntroduction() {
        return delegate != null && delegate.collectingIntroduction();
    }

    @Override
    public void onStopping() {
        if (delegate != null) {
            delegate.onStopping();
        }
    }

    @Override
    public Duration settlementDelay(Instant now) {
        return delegate == null ? Duration.ZERO : delegate.settlementDelay(now);
    }

    @Override
    public String toString() {
        return "PracticeFlowRouter[chosen=" + (delegate == null ? "none" : delegate == legacy ? "legacy" : "flow") + "]";
    }
}
