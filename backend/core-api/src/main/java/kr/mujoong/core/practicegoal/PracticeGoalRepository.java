package kr.mujoong.core.practicegoal;
import kr.mujoong.core.practice.GoalSelection;
import kr.mujoong.core.practice.CandidateRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
/** The sole writer of practice candidate/selection data. Transactions and account locks belong to services. */
public class PracticeGoalRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public PracticeGoalRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SELECT = """
        select s.*,r.source_session_id from core.core_practice_goal_selection s
        join core.core_goal_recommendation r on r.id=s.recommendation_id
        """;
    public List<GoalSelection> list(UUID guardianId) {
        return jdbc.query(SELECT+" where s.guardian_id=:guardian and s.status='ACTIVE' order by s.is_favorite desc,s.sort_order,s.id",
                Map.of("guardian",guardianId),PracticeGoalRepository::selection);
    }
    public Optional<GoalSelection> find(UUID guardianId,UUID selectionId) {
        return jdbc.query(SELECT+" where s.guardian_id=:guardian and s.id=:id",
                Map.of("guardian",guardianId,"id",selectionId),PracticeGoalRepository::selection).stream().findFirst();
    }
    public Optional<GoalSelection> byRecommendation(UUID guardianId,UUID recommendationId) {
        return jdbc.query(SELECT+" where s.guardian_id=:guardian and s.recommendation_id=:id",
                Map.of("guardian",guardianId,"id",recommendationId),PracticeGoalRepository::selection).stream().findFirst();
    }
    public Optional<Recommendation> recommendation(UUID childId,UUID recommendationId) {
        return jdbc.query("select * from core.core_goal_recommendation where child_id=:child and id=:id",
                Map.of("child",childId,"id",recommendationId),PracticeGoalRepository::recommendation).stream().findFirst();
    }
    public UUID insertSelection(UUID guardianId,UUID childId,Recommendation candidate,String displayName,Instant now) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into core.core_practice_goal_selection
            (id,guardian_id,child_id,recommendation_id,behavior_key,behavior_version,display_name,is_favorite,sort_order,status,selected_at)
            select :id,:guardian,:child,:recommendation,:behavior,:version,:name,false,
                   coalesce(max(sort_order),0)+1,'ACTIVE',:now
              from core.core_practice_goal_selection where guardian_id=:guardian
            """,ClockParams.at(now).addValue("id",id).addValue("guardian",guardianId).addValue("child",childId)
                .addValue("recommendation",candidate.id()).addValue("behavior",candidate.behaviorKey())
                .addValue("version",candidate.behaviorVersion()).addValue("name",displayName));
        return id;
    }
    public void favorite(UUID id,boolean favorite) {
        jdbc.update("update core.core_practice_goal_selection set is_favorite=:favorite where id=:id",
                Map.of("id",id,"favorite",favorite));
    }
    public void reactivate(UUID id) {
        jdbc.update("update core.core_practice_goal_selection set status='ACTIVE',deleted_at=null,restore_until=null where id=:id",Map.of("id",id));
    }
    public void softDelete(UUID id,Instant now,Instant until) {
        jdbc.update("""
            update core.core_practice_goal_selection set status='DELETED',deleted_at=:now,restore_until=:until
            where id=:id and status='ACTIVE'
            """,ClockParams.instant(ClockParams.at(now).addValue("id",id),"until",until));
    }
    public UUID register(UUID childId,UUID sessionId,CandidateRegistry.Candidate candidate,String displayName,Instant now) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into core.core_goal_recommendation
            (id,child_id,source_session_id,behavior_key,behavior_version,display_name,reason_text,status,created_at)
            values(:id,:child,:session,:behavior,:version,:name,:reason,'ACTIVE',:now)
            on conflict(child_id,source_session_id,behavior_key,behavior_version) do nothing
            """,ClockParams.at(now).addValue("id",id).addValue("child",childId).addValue("session",sessionId)
            .addValue("behavior",candidate.behaviorKey()).addValue("version",candidate.behaviorVersion())
            .addValue("name",displayName).addValue("reason",candidate.reasonText()));
        UUID stored=jdbc.queryForObject("""
            select id from core.core_goal_recommendation
             where child_id=:child and source_session_id=:session and behavior_key=:behavior and behavior_version=:version
            """,Map.of("child",childId,"session",sessionId,"behavior",candidate.behaviorKey(),"version",candidate.behaviorVersion()),UUID.class);
        if(id.equals(stored)) {
            for(CandidateRegistry.Evidence e:candidate.evidence()) {
                jdbc.update("""
                    insert into core.core_recommendation_evidence
                    (id,recommendation_id,session_id,utterance_sequence_no,evidence_type,created_at)
                    values(:id,:recommendation,:session,:sequence,:type,:now)
                    on conflict(recommendation_id,session_id,utterance_sequence_no,evidence_type) do nothing
                    """,ClockParams.at(now).addValue("id",UUID.randomUUID()).addValue("recommendation",id)
                        .addValue("session",sessionId).addValue("sequence",e.utteranceSeq()).addValue("type",e.kind()));
            }
        }
        return stored;
    }
    public List<CandidateRegistry.CandidateView> candidates(UUID childId,UUID sessionId) {
        return jdbc.query("""
            select r.id,r.display_name,r.reason_text,exists(select 1 from core.core_practice_goal_selection s
                 where s.recommendation_id=r.id and s.status='ACTIVE') selected
            from core.core_goal_recommendation r
            where r.child_id=:child and r.source_session_id=:session and r.status='ACTIVE'
            order by r.created_at,r.id
            """,Map.of("child",childId,"session",sessionId),(rs,n)->new CandidateRegistry.CandidateView(
                rs.getObject("id",UUID.class),rs.getString("display_name"),rs.getString("reason_text"),rs.getBoolean("selected")));
    }
    public Map<String,Integer> deleteByGuardian(UUID guardianId,List<UUID> children) {
        MapSqlParameterSource p=new MapSqlParameterSource("guardian",guardianId).addValue("children",children);
        int preparations=jdbc.update("delete from core.core_practice_preparation where guardian_id=:guardian",p);
        int requests=jdbc.update("delete from core.core_scenario_request where guardian_id=:guardian",p);
        int scenarios=jdbc.update("delete from core.core_scenario where guardian_id=:guardian",p);
        int selections=jdbc.update("delete from core.core_practice_goal_selection where guardian_id=:guardian",p);
        int saved=jdbc.update("delete from core.core_saved_goal where guardian_id=:guardian",p);
        int versions=children.isEmpty() ? 0 : jdbc.update("delete from core.core_goal_candidate_version where child_id in (:children)",p);
        int generated=children.isEmpty() ? 0 : jdbc.update("delete from core.core_goal_candidate where child_id in (:children)",p);
        int evidence=children.isEmpty() ? 0 : jdbc.update("""
            delete from core.core_recommendation_evidence e using core.core_goal_recommendation r
            where e.recommendation_id=r.id and r.child_id in (:children)
            """,p);
        int candidates=children.isEmpty() ? 0 : jdbc.update("""
            delete from core.core_goal_recommendation r
            where r.child_id in (:children)
            """,p);
        return Map.of("practicePreparations",preparations,"scenarioRequests",requests,"scenarios",scenarios,"goalSelections",selections,
                "recommendationEvidence",evidence,"goalRecommendations",candidates,
                "savedGoals",saved,"goalCandidateVersions",versions,"goalCandidates",generated);
    }
    public List<CandidateRegistry.ResultCandidateView> resultCandidates(UUID child,UUID session) {
        return jdbc.query("select r.id,r.display_name,r.reason_text,s.id selection_id from core.core_goal_recommendation r "
                + "left join core.core_practice_goal_selection s on s.recommendation_id=r.id and s.status='ACTIVE' "
                + "where r.child_id=:child and r.source_session_id=:session and r.status='ACTIVE' order by r.created_at,r.id",
                Map.of("child",child,"session",session),(r,n)->{
                    UUID id=r.getObject("id",UUID.class),selected=r.getObject("selection_id",UUID.class);
                    var refs=jdbc.query("select utterance_sequence_no,evidence_type from core.core_recommendation_evidence where recommendation_id=:id order by utterance_sequence_no,evidence_type",
                            Map.of("id",id),(e,i)->new CandidateRegistry.Evidence(e.getLong(1),e.getString(2)));
                    return new CandidateRegistry.ResultCandidateView(id,r.getString("display_name"),r.getString("reason_text"),refs,selected!=null,selected);
                });
    }
    private static GoalSelection selection(ResultSet r,int n) throws SQLException {
        return new GoalSelection(r.getObject("id",UUID.class),r.getObject("guardian_id",UUID.class),
                r.getObject("child_id",UUID.class),r.getObject("recommendation_id",UUID.class),
                r.getObject("source_session_id",UUID.class),r.getString("behavior_key"),r.getInt("behavior_version"),
                r.getString("display_name"),r.getBoolean("is_favorite"),r.getLong("sort_order"),r.getString("status"),
                ClockParams.instant(r,"selected_at"),ClockParams.instant(r,"deleted_at"),ClockParams.instant(r,"restore_until"));
    }
    private static Recommendation recommendation(ResultSet r,int n) throws SQLException {
        return new Recommendation(r.getObject("id",UUID.class),r.getString("behavior_key"),
                r.getInt("behavior_version"),r.getString("status"));
    }
    public record Recommendation(UUID id,String behaviorKey,int behaviorVersion,String status) {}
}
