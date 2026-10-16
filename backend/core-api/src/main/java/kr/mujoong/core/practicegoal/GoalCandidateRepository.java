package kr.mujoong.core.practicegoal;

import static kr.mujoong.core.common.db.ClockParams.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.knowledge.StructureSignature;
import kr.mujoong.core.practice.GoalSelection;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** PracticeGoal-owned generated candidate storage. Caller holds the guardian lock. */
@Repository
public class GoalCandidateRepository {
    public record Draft(String displayName,String displayEvidence,String targetExpression,FourElements fourElements,
            String skillRef,String reasonText,List<Evidence> evidence,StructureSignature structureSignature){
        public Draft { evidence=List.copyOf(evidence); }
        @Override public String toString(){return "GoalDraft[redacted]";}
    }
    public record Evidence(long utteranceSeq,String quote){@Override public String toString(){return "GoalEvidence[redacted]";}}
    public record Version(UUID id,UUID candidateId,UUID childId,UUID sessionId,int version,Draft content){}
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json=JsonCodec.shared();
    public GoalCandidateRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public Version register(UUID child,UUID session,String sourceKey,Draft draft,Instant now){
        UUID candidate=UUID.randomUUID();
        jdbc.update("insert into core.core_goal_candidate(id,child_id,source_session_id,source_key,current_version,status,created_at) values(:id,:child,:session,:key,1,'ACTIVE',:now) on conflict(child_id,source_session_id,source_key) do nothing",
            at(now).addValue("id",candidate).addValue("child",child).addValue("session",session).addValue("key",sourceKey));
        UUID stored=jdbc.queryForObject("select id from core.core_goal_candidate where child_id=:child and source_session_id=:session and source_key=:key",Map.of("child",child,"session",session,"key",sourceKey),UUID.class);
        if(candidate.equals(stored))insertVersion(stored,child,1,draft,now);
        return current(child,stored).orElseThrow();
    }
    private UUID insertVersion(UUID candidate,UUID child,int version,Draft draft,Instant now){
        UUID handle=UUID.randomUUID();
        var p=at(now).addValue("id",handle).addValue("candidate",candidate).addValue("child",child).addValue("version",version)
            .addValue("name",draft.displayName()).addValue("display",draft.displayEvidence()).addValue("target",draft.targetExpression())
            .addValue("elements",json.write(draft.fourElements())).addValue("skill",draft.skillRef()).addValue("reason",draft.reasonText())
            .addValue("evidence",json.write(draft.evidence())).addValue("structure",json.write(draft.structureSignature()));
        jdbc.update("insert into core.core_goal_candidate_version(id,candidate_id,child_id,version,display_name,display_evidence,target_expression,four_elements_json,skill_ref,reason_text,evidence_json,structure_signature_json,created_at) values(:id,:candidate,:child,:version,:name,:display,:target,cast(:elements as jsonb),:skill,:reason,cast(:evidence as jsonb),cast(:structure as jsonb),:now)",p);
        return handle;
    }
    private static final String VERSION="select v.*,c.source_session_id from core.core_goal_candidate_version v join core.core_goal_candidate c on c.id=v.candidate_id and c.child_id=v.child_id where c.status='ACTIVE' ";
    public Optional<Version> version(UUID child,UUID handle){return jdbc.query(VERSION+"and v.child_id=:child and v.id=:id",Map.of("child",child,"id",handle),this::version).stream().findFirst();}
    public Optional<Version> current(UUID child,UUID candidate){return jdbc.query(VERSION+"and v.child_id=:child and c.id=:id and v.version=c.current_version",Map.of("child",child,"id",candidate),this::version).stream().findFirst();}
    public List<Version> candidates(UUID child,UUID session){return jdbc.query(VERSION+"and v.child_id=:child and c.source_session_id=:session and v.version=c.current_version order by c.created_at,c.id",Map.of("child",child,"session",session),this::version);}
    public Optional<Version> linkSeed(Version expected,String seed,Instant now){
        var p=Map.of("id",expected.candidateId(),"child",expected.childId(),"version",expected.version());
        int changed=jdbc.update("update core.core_goal_candidate set current_version=current_version+1 where id=:id and child_id=:child and current_version=:version and status='ACTIVE'",p);
        if(changed==0)return Optional.empty();
        Draft d=expected.content();UUID handle=insertVersion(expected.candidateId(),expected.childId(),expected.version()+1,new Draft(d.displayName(),d.displayEvidence(),d.targetExpression(),d.fourElements(),seed,d.reasonText(),d.evidence(),d.structureSignature()),now);
        return version(expected.childId(),handle);
    }
    private Version version(java.sql.ResultSet r,int n)throws java.sql.SQLException{
        Draft content=new Draft(r.getString("display_name"),r.getString("display_evidence"),r.getString("target_expression"),json.read(r.getString("four_elements_json"),FourElements.class),r.getString("skill_ref"),r.getString("reason_text"),Arrays.asList(json.read(r.getString("evidence_json"),Evidence[].class)),json.read(r.getString("structure_signature_json"),StructureSignature.class));
        return new Version(uuid(r,"id"),uuid(r,"candidate_id"),uuid(r,"child_id"),uuid(r,"source_session_id"),r.getInt("version"),content);
    }
    private static final String SELECTION="select s.*,c.source_session_id,v.version,v.target_expression,v.four_elements_json,v.skill_ref from core.core_saved_goal s join core.core_goal_candidate c on c.id=s.candidate_id join core.core_goal_candidate_version v on v.id=s.candidate_version_id ";
    public Optional<GoalSelection> selection(UUID guardian,UUID id){return jdbc.query(SELECTION+"where s.guardian_id=:g and s.id=:id",Map.of("g",guardian,"id",id),this::selection).stream().findFirst();}
    public List<GoalSelection> list(UUID guardian){return jdbc.query(SELECTION+"where s.guardian_id=:g and s.status='ACTIVE' order by s.is_favorite desc,s.sort_order,s.id",Map.of("g",guardian),this::selection);}
    public Optional<GoalSelection> activeSelection(UUID child,UUID candidate){return jdbc.query(SELECTION+"where s.child_id=:child and s.candidate_id=:candidate and s.status='ACTIVE'",Map.of("child",child,"candidate",candidate),this::selection).stream().findFirst();}
    public UUID select(UUID guardian,Version version,Instant now){
        var existing=activeSelection(version.childId(),version.candidateId());if(existing.isPresent())return existing.get().id();
        UUID id=UUID.randomUUID();
        jdbc.update("insert into core.core_saved_goal(id,guardian_id,child_id,candidate_id,candidate_version_id,snapshot_json,display_name,sort_order,status,selected_at) select :id,:guardian,:child,:candidate,:version,cast(:snapshot as jsonb),:name,coalesce(max(sort_order),0)+1,'ACTIVE',:now from core.core_saved_goal where guardian_id=:guardian",
            at(now).addValue("id",id).addValue("guardian",guardian).addValue("child",version.childId()).addValue("candidate",version.candidateId()).addValue("version",version.id()).addValue("snapshot",json.write(version.content())).addValue("name",version.content().displayName()));
        return id;
    }
    public Optional<SavedGoalReader.SavedGoalView> saved(UUID child,UUID selection){
        return jdbc.query(SELECTION+"where s.child_id=:child and s.id=:id and s.status='ACTIVE' and c.status='ACTIVE'",Map.of("child",child,"id",selection),(r,n)->new SavedGoalReader.SavedGoalView(uuid(r,"id"),child,SavedGoalReader.SavedGoalView.Kind.CANDIDATE,r.getString("candidate_id"),r.getInt("version"),r.getString("target_expression"),json.read(r.getString("four_elements_json"),FourElements.class),r.getString("skill_ref"),null,null)).stream().findFirst();
    }
    public void favorite(UUID id,boolean favorite){jdbc.update("update core.core_saved_goal set is_favorite=:f where id=:id",Map.of("id",id,"f",favorite));}
    public void delete(UUID id,Instant now,Instant until){jdbc.update("update core.core_saved_goal set status='DELETED',deleted_at=:now,restore_until=:until where id=:id and status='ACTIVE'",instant(at(now).addValue("id",id),"until",until));}
    public void restore(UUID id){jdbc.update("update core.core_saved_goal set status='ACTIVE',deleted_at=null,restore_until=null where id=:id",Map.of("id",id));}
    private GoalSelection selection(java.sql.ResultSet r,int n)throws java.sql.SQLException{
        return new GoalSelection(uuid(r,"id"),uuid(r,"guardian_id"),uuid(r,"child_id"),uuid(r,"candidate_version_id"),uuid(r,"source_session_id"),null,0,r.getString("display_name"),r.getBoolean("is_favorite"),r.getLong("sort_order"),r.getString("status"),instant(r,"selected_at"),instant(r,"deleted_at"),instant(r,"restore_until"));
    }
    public int deleteGuardian(UUID guardian){
        int count=jdbc.update("delete from core.core_saved_goal where guardian_id=:g",Map.of("g",guardian));
        jdbc.update("delete from core.core_goal_candidate_version where child_id in(select id from core.core_child where guardian_id=:g)",Map.of("g",guardian));
        jdbc.update("delete from core.core_goal_candidate where child_id in(select id from core.core_child where guardian_id=:g)",Map.of("g",guardian));return count;
    }
}
