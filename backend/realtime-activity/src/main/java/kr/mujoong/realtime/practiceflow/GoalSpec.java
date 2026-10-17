package kr.mujoong.realtime.practiceflow;

import java.util.Objects;
import java.util.UUID;

/**
 * 연습 목표 한 개 (U12 entities.md GoalSpec). 시작 패키지에는 표시 이름과 판정 기준 문자열 하나만 온다(결정 D2: {@code coreFunction}은 이
 * 판정 기준 문자열에서 만든다). 판정 기준은 판정 역할에만 쓰고 공급자 요청 · 로그 · 기록에 넣지 않으므로(NFR3.2) 문자열 표현에 내지 않는다.
 *
 * @param goalSelectionId 시작 패키지 목표의 goalSelectionId(기록의 goalRef)
 * @param goalIndex 0부터의 순서(패키지 order 순)
 * @param goalName 표시 이름(보호자 화면 진행 표시용)
 * @param judgmentCriteria 판정 기준 문자열
 */
public record GoalSpec(UUID goalSelectionId, int goalIndex, String goalName, String judgmentCriteria) {

    public GoalSpec {
        Objects.requireNonNull(goalSelectionId, "goalSelectionId");
        if (goalIndex < 0) {
            throw new IllegalArgumentException("goalIndex는 0 이상이어야 한다");
        }
        if (goalName == null || goalName.isBlank()) {
            throw new IllegalArgumentException("goalName이 비었다");
        }
        if (judgmentCriteria == null || judgmentCriteria.isBlank()) {
            throw new IllegalArgumentException("judgmentCriteria가 비었다");
        }
    }

    @Override
    public String toString() {
        return "GoalSpec[goalSelectionId=" + goalSelectionId + ", goalIndex=" + goalIndex + "]";
    }
}
