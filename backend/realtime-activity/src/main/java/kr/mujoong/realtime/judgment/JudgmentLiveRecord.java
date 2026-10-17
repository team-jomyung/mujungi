package kr.mujoong.realtime.judgment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 진행용 판정 기록 한 건 (U11 entities JudgmentLiveRecord · BR2.1 · BR2.2). 판정 턴이 닫힐 때마다 한 건이고 {@code id}는 {@code turnId}다
 * (같은 id 덮어쓰기로 재시도가 멱등). 보호자 화면 · 리포트 · 기록 스냅숏에 쓰지 않고 읽는 외부 경로가 없다. 전사는 모은 출력 전사 하나뿐이고
 * 2000자 이하이며 로그에 쓰지 않는다(R-F14). 값 객체의 일관성 규칙은 V5 CHECK 제약과 같다.
 *
 * @param turnOpened false면 공급자가 없어 턴을 열지 않고 남긴 기록(BR1.6)이다
 * @param createdAt 기록을 만든 시각(MujoongClock, R-F6)
 */
public record JudgmentLiveRecord(
        UUID id,
        UUID guardianId,
        UUID sessionId,
        JudgmentPoint point,
        JudgmentTrigger trigger,
        UUID goalRef,
        UUID sceneRef,
        int inputTranscriptSeq,
        String optionSetVersion,
        String promptVersion,
        String model,
        JudgmentOutcomeKind outcome,
        AbstainReason abstainReason,
        boolean turnOpened,
        String firstOption,
        Double firstProbability,
        String secondOption,
        Double secondProbability,
        long latencyMillis,
        NextAction nextAction,
        String heldOutputTranscript,
        Instant createdAt) {

    /** 모은 출력 전사 상한(글자). */
    public static final int MAX_HELD_CHARS = 2000;

    public JudgmentLiveRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(optionSetVersion, "optionSetVersion");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(nextAction, "nextAction");
        Objects.requireNonNull(createdAt, "createdAt");
        if (inputTranscriptSeq < 0) {
            throw new IllegalArgumentException("입력 전사 순번은 0 이상이다");
        }
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("걸린 시간은 0 이상이다");
        }
        boolean hasOptions = firstOption != null && firstProbability != null && secondOption != null && secondProbability != null;
        boolean anyOption = firstOption != null || firstProbability != null || secondOption != null || secondProbability != null;
        if ((outcome == JudgmentOutcomeKind.CONFIRMED) != hasOptions || (anyOption && !hasOptions)) {
            throw new IllegalArgumentException("확정이어야 1 · 2순위가 모두 있다");
        }
        if ((outcome == JudgmentOutcomeKind.ABSTAINED) != (abstainReason != null)) {
            throw new IllegalArgumentException("보류여야 보류 사유가 있다");
        }
        for (Double probability : new Double[] {firstProbability, secondProbability}) {
            if (probability != null && (probability.isNaN() || probability < 0.0 || probability > 1.0)) {
                throw new IllegalArgumentException("확률은 0 ~ 1이다");
            }
        }
        if (heldOutputTranscript != null && heldOutputTranscript.length() > MAX_HELD_CHARS) {
            throw new IllegalArgumentException("모은 출력 전사는 " + MAX_HELD_CHARS + "자 이하다");
        }
    }

    /** 모은 출력 전사 원문은 문자열 표현에 넣지 않는다(R-F14). */
    @Override
    public String toString() {
        return "JudgmentLiveRecord[id=" + id + ", point=" + point + ", outcome=" + outcome + ", abstainReason=" + abstainReason
                + ", nextAction=" + nextAction + ", heldChars=" + (heldOutputTranscript == null ? 0 : heldOutputTranscript.length())
                + "]";
    }
}
