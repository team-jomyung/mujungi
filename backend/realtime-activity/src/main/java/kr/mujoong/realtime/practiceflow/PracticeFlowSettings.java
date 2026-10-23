package kr.mujoong.realtime.practiceflow;

import java.time.Duration;
import java.util.Objects;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.config.RealtimeTimingProperties;

/**
 * 새 연습 흐름이 읽는 설정 값 객체.
 *
 * <p>시간 값은 {@code mujoong.timing.*}(와 기존 {@code mujoong.gate.segment-quiet})에서만 읽고(R-F7, 코드에 숫자 시간 값 없음), 횟수 한도는 새 키
 * {@code mujoong.practice-flow.*}에서 읽는다. 이 객체는 시계를 걸지 않는다 — 시계는 상태 기계(묶음 B)가 확장점 능력
 * {@code schedule}로 건다.
 *
 * @param noResponseWait 입력을 기다리는 단계에서 첫 재안내까지의 무응답 대기(AC1.9.1)
 * @param focusMax 집중 연습 상한(AC1.5.4)
 * @param goalActiveLimit 목표 하나의 ACTIVE 상한
 * @param slotGuard 재생이 시작된(STARTED) 슬롯의 안전 시계 길이(§3.2 {@code slot-guard-<correlationId>}). 실행부 재생 만료
 *     ({@code SessionExecutor.Settings.playbackExpiry})와 같은 설정 키 {@code mujoong.timing.device.output-queue-max}에서 읽는다 — 한 줄은 이
 *     길이를 넘게 재생되지 않으므로 재생 중에는 만료하지 않고, 끝 사실이 끝내 오지 않으면 실행부 재생 만료가 먼저 실패를 알린다
 * @param retryMax 장면당 다시 해 보기 최대 횟수
 * @param reexplainMax 장면당 상황 다시 설명 최대 횟수
 * @param slotStartGuard 4회차 R4: 슬롯을 연 때부터 재생이 시작되기까지의 안전 시계 길이. 실행부가 그 사이에 쓸 수 있는 시간 — 대본 줄 음성 기다림,
 *     GPT-Live 지시 미룸 · 새 말 기다림(확인 사건 없음 · 새 말 없음 두 시한)을 처음 보냄과 한 번 다시 보냄에 두 번, 새 말이 구간 상한까지 이어진 뒤의
 *     봉인 조용 시간과 제브 검사 한 번 — 을 모두 덮는다({@link #from} 참고)
 */
public record PracticeFlowSettings(Duration noResponseWait, Duration focusMax, Duration goalActiveLimit, Duration slotGuard,
        int retryMax, int reexplainMax, Duration slotStartGuard) {

    public PracticeFlowSettings {
        positive(noResponseWait, "mujoong.timing.conversation.no-response-wait");
        positive(focusMax, "mujoong.timing.conversation.focus-max");
        positive(goalActiveLimit, "mujoong.timing.conversation.goal-active-limit");
        positive(slotGuard, "mujoong.timing.device.output-queue-max");
        positive(slotStartGuard, "mujoong.timing.device.output-queue-max");
        if (retryMax < 0) {
            throw new IllegalArgumentException("설정 키는 0 이상이어야 한다: mujoong.practice-flow.retry-max");
        }
        if (reexplainMax < 0) {
            throw new IllegalArgumentException("설정 키는 0 이상이어야 한다: mujoong.practice-flow.reexplain-max");
        }
    }

    /** 4회차 R4 전 모양: 재생 시작 전 안전 시계도 {@code slotGuard}와 같다(시험 · 기존 조립이 쓴다). */
    public PracticeFlowSettings(Duration noResponseWait, Duration focusMax, Duration goalActiveLimit, Duration slotGuard,
            int retryMax, int reexplainMax) {
        this(noResponseWait, focusMax, goalActiveLimit, slotGuard, retryMax, reexplainMax, slotGuard);
    }

    /**
     * 운영 설정 트리에서 읽는다. 재생 시작 전 안전 시계(4회차 R4)는 새 숫자 없이 기존 키로 셈한다:
     * {@code speech-clip.ready-wait + 2 × (provider.instruction-defer-max + 2 × judgment.response-ready) + device.output-queue-max
     * + gate.segment-quiet + guardrail.jev-budget}(운영 기본값으로 2s + 2 × (3s + 20s) + 20s + 0.8s + 0.8s = 69.6초). 실측 B4에서 지시 →
     * 들림이 14.5 ~ 18.5초라 20초 시계가 도움이 들리는 도중에 끝났다.
     */
    public static PracticeFlowSettings from(RealtimeProperties properties) {
        Objects.requireNonNull(properties, "properties");
        RealtimeTimingProperties timing = properties.timing();
        RealtimeTimingProperties.ConversationTiming conversation = timing.conversation();
        Duration oneAttempt = timing.provider().instructionDeferMax().plus(timing.judgment().responseReady().multipliedBy(2));
        Duration startGuard = timing.speechClip().readyWait().plus(oneAttempt.multipliedBy(2)).plus(timing.device().outputQueueMax())
                .plus(properties.gate().segmentQuiet()).plus(timing.guardrail().jevBudget());
        return new PracticeFlowSettings(conversation.noResponseWait(), conversation.focusMax(),
                conversation.goalActiveLimit(), timing.device().outputQueueMax(),
                properties.practiceFlow().retryMax(),
                properties.practiceFlow().reexplainMax(), startGuard);
    }

    private static void positive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("설정 키는 0보다 커야 한다: " + key);
        }
    }
}
