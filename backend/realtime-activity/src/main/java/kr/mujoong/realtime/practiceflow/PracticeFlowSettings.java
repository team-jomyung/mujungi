package kr.mujoong.realtime.practiceflow;

import java.time.Duration;
import java.util.Objects;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.config.RealtimeTimingProperties;

/**
 * 새 연습 흐름이 읽는 설정 값 객체.
 *
 * <p>시간 값은 {@code mujoong.timing.conversation.*}에서만 읽고(R-F7, 코드에 숫자 시간 값 없음), 횟수 한도와 경로 선택은
 * {@code mujoong.practice-flow.*}에서 읽는다. 이 객체는 시계를 걸지 않는다 — 시계는 상태 기계(묶음 B)가 확장점 능력
 * {@code schedule}로 건다.
 *
 * @param noResponseWait 입력을 기다리는 단계에서 첫 재안내까지의 무응답 대기(AC1.9.1)
 * @param focusMax 집중 연습 상한(AC1.5.4)
 * @param goalActiveLimit 목표 하나의 ACTIVE 상한
 * @param slotGuard 슬롯 안전 시계 길이(§3.2 {@code slot-guard-<correlationId>}). 실행부 재생 만료({@code SessionExecutor.Settings.playbackExpiry})와
 *     같은 설정 키 {@code mujoong.timing.device.output-queue-max}에서 읽는다
 * @param retryMax 장면당 다시 해 보기 최대 횟수
 * @param reexplainMax 장면당 상황 다시 설명 최대 횟수
 * @param flexibleDialogue 참이면 유연 대화 경로, 거짓이면 C안 '무중이 직접 묻기'(docs/practice-direct-ask.md, 운영 기본)
 */
public record PracticeFlowSettings(Duration noResponseWait, Duration focusMax, Duration goalActiveLimit, Duration slotGuard,
        int retryMax, int reexplainMax, boolean flexibleDialogue) {

    /** 경로를 고르지 않는 기존 호출처(시험)는 지금처럼 유연 대화를 받는다. 운영은 {@link #from}이 설정 키로 고른다. */
    public PracticeFlowSettings(Duration noResponseWait, Duration focusMax, Duration goalActiveLimit, Duration slotGuard,
            int retryMax, int reexplainMax) {
        this(noResponseWait, focusMax, goalActiveLimit, slotGuard, retryMax, reexplainMax, true);
    }

    public PracticeFlowSettings {
        positive(noResponseWait, "mujoong.timing.conversation.no-response-wait");
        positive(focusMax, "mujoong.timing.conversation.focus-max");
        positive(goalActiveLimit, "mujoong.timing.conversation.goal-active-limit");
        positive(slotGuard, "mujoong.timing.device.output-queue-max");
        if (retryMax < 0) {
            throw new IllegalArgumentException("설정 키는 0 이상이어야 한다: mujoong.practice-flow.retry-max");
        }
        if (reexplainMax < 0) {
            throw new IllegalArgumentException("설정 키는 0 이상이어야 한다: mujoong.practice-flow.reexplain-max");
        }
    }

    /** 운영 설정 트리에서 읽는다. 경로는 {@code mujoong.practice-flow.flexible-dialogue}(기본 거짓 = C안)가 고른다. */
    public static PracticeFlowSettings from(RealtimeProperties properties) {
        Objects.requireNonNull(properties, "properties");
        RealtimeTimingProperties.ConversationTiming conversation = properties.timing().conversation();
        return new PracticeFlowSettings(conversation.noResponseWait(), conversation.focusMax(),
                conversation.goalActiveLimit(), properties.timing().device().outputQueueMax(),
                properties.practiceFlow().retryMax(),
                properties.practiceFlow().reexplainMax(), properties.practiceFlow().flexibleDialogue());
    }

    /** C안 '무중이 직접 묻기' 경로인가(유연 대화가 아니면 C안이다). */
    public boolean directAsk() {
        return !flexibleDialogue;
    }

    private static void positive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("설정 키는 0보다 커야 한다: " + key);
        }
    }
}
