package kr.mujoong.core.admin;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 연습 장면 판정 조회 (JIRA-280). 활동마다 현재 결과({@code core_session_result_state.current_result_id})의 장면 판정만 읽는다. 결과 작업이
 * 아직 끝나지 않은 활동은 장면이 없다.
 */
@Repository
public class AdminSceneRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminSceneRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 장면 판정 한 행. 목표 이름은 저장 목표 · 목표 선택 가운데 있는 쪽이다(지워진 목표면 null).
     *
     * @param sceneRunId Realtime 장면 실행 id(새 흐름만, 레거시 흐름은 null)
     */
    public record SceneRow(
            UUID id,
            UUID sessionId,
            int goalOrder,
            int sceneNo,
            String exposureType,
            String value,
            String timing,
            String rationale,
            String notDoneReason,
            UUID sceneRunId,
            String variantSkipReason,
            String goalName) {

        @Override
        public String toString() {
            return "SceneRow[id=" + id + ", sessionId=" + sessionId + "]";
        }
    }

    public List<SceneRow> scenes(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select j.id, j.session_id, j.goal_order, j.scene_no, j.exposure_type, j.value, j.timing, j.rationale,
                    j.not_done_reason, j.scene_run_id, j.variant_skip_reason,
                    coalesce(sg.display_name, gs.display_name) as goal_name
                from core.core_session_result_state st
                join core.core_scene_judgment j on j.result_id = st.current_result_id
                left join core.core_saved_goal sg on sg.id = j.goal_selection_id
                left join core.core_practice_goal_selection gs on gs.id = j.goal_selection_id
                where st.session_id = any(:sessions)
                order by j.session_id, j.goal_order, j.scene_no
                """, ClockParams.uuidArray(new MapSqlParameterSource(), "sessions", sessionIds),
                (rs, n) -> new SceneRow(ClockParams.uuid(rs, "id"), ClockParams.uuid(rs, "session_id"), rs.getInt("goal_order"),
                        rs.getInt("scene_no"), rs.getString("exposure_type"), rs.getString("value"), rs.getString("timing"),
                        rs.getString("rationale"), rs.getString("not_done_reason"), ClockParams.uuid(rs, "scene_run_id"),
                        rs.getString("variant_skip_reason"), rs.getString("goal_name")));
    }
}
