package kr.mujoong.core.activity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 연습 시작 확장점 — 계약 8절 PracticeStart 포트 (U4a는 자리만 둔다 · J17 · U4a-J19).
 *
 * <p>U5 PracticeGoal이 이 인터페이스를 구현해 빈으로 등록하고, {@code POST /api/activities/practice}(U5)가 시작 서비스의 판정(⓪ ~ ③ ·
 * 멱등 · 사본 · 패키지 · Realtime 호출)을 그대로 쓰면서 패키지의 {@code practice}에 이 결과를 싣는다. 판정 기준({@code judgmentCriteria})은
 * 판정 역할에만 쓰고 공급자 요청 · 보호자 응답 · 로그에 넣지 않는다(NFR3.2 · SD-20). U4a에는 구현 · 빈이 없다.
 */
public interface PracticeStart {

    /**
     * 고른 목표로 연습 패키지 조각을 만든다.
     *
     * @throws IllegalStateException 첫 목표 상황을 두 번 만들지 못했을 때(시작 서비스가 422 SCENARIO_GEN_FAILED로 옮긴다)
     */
    PracticePackage buildPackage(UUID guardianId, List<UUID> goalSelectionIds);

    /** 연습 패키지 조각(internal-session-start-request.schema.json practice). */
    record PracticePackage(List<Goal> goals, Map<String, Object> firstScenario) {

        public PracticePackage {
            goals = List.copyOf(goals);
        }

        /** 판정 기준이 담겨 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "PracticePackage[goals=" + goals.size() + "]";
        }
    }

    /** 목표 한 칸. */
    record Goal(
            UUID goalSelectionId,
            int order,
            String displayName,
            String behaviorKey,
            int behaviorVersion,
            String judgmentCriteria) {

        @Override
        public String toString() {
            return "Goal[goalSelectionId=" + goalSelectionId + ", order=" + order + "]";
        }
    }
}
