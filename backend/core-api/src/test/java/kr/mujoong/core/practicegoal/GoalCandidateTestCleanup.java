package kr.mujoong.core.practicegoal;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 시험이 끝난 뒤 그 보호자의 저장 목표 · 생성 후보 행만 지운다. 운영 삭제는 {@code PracticeGoalRepository.deleteByGuardian}이 더 많은 표와
 * 함께 맡으므로, 시험 정리용으로만 둔다(옛 {@code GoalCandidateRepository.deleteGuardian}과 같은 범위).
 */
public final class GoalCandidateTestCleanup {

    private GoalCandidateTestCleanup() {
    }

    public static void deleteGuardian(NamedParameterJdbcTemplate jdbc, UUID guardian) {
        Map<String, UUID> p = Map.of("g", guardian);
        jdbc.update("delete from core.core_saved_goal where guardian_id=:g", p);
        jdbc.update("delete from core.core_goal_candidate_version where child_id in(select id from core.core_child where guardian_id=:g)", p);
        jdbc.update("delete from core.core_goal_candidate where child_id in(select id from core.core_child where guardian_id=:g)", p);
    }
}
