package kr.mujoong.core.report;

import static kr.mujoong.core.common.db.ClockParams.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** BR10~BR12: 버린 리포트는 머리 묘비만 남긴다. 호출자는 보호자와 작업 잠금을 잡는다. */
@Repository
public class ReportRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ReportRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Report(UUID id,UUID guardianId,UUID childId,String idempotencyKey,String requestHash,String status,
            LocalDate periodFrom,LocalDate periodTo,int activityCount,int citationCount,String level,UUID jobId,
            String childDisplayName,Integer childAge,Instant createdAt,Instant readyAt,Instant savedAt,Instant discardedAt){
        @Override public String toString(){return "Report[id="+id+", status="+status+"]";}
    }
    public record Activity(UUID sessionId,LocalDate activityDate,String activityType,String resultStatus){}
    public record Citation(UUID sessionId,Long utteranceSeq,String quote,Instant at,String source){
        @Override public String toString(){return "Citation[sessionId="+sessionId+"]";}
    }
    public record Section(int sectionNo,String title,String text,boolean missing,boolean templated,List<Citation> citations){
        @Override public String toString(){return "Section[sectionNo="+sectionNo+"]";}
    }
    public Optional<Report> find(UUID id){return query("where id=:id",Map.of("id",id)).stream().findFirst();}
    /** 저장 완료한 본인 리포트만 안정적인 순서로 나눈다. */
    public List<Report> savedPage(UUID guardian,int offset,int limit){return query("where guardian_id=:guardian and status='SAVED' order by saved_at desc,id desc limit :limit offset :offset",Map.of("guardian",guardian,"limit",limit,"offset",offset));}
    public Optional<Report> findOwned(UUID id,UUID guardian){return query("where id=:id and guardian_id=:guardian",Map.of("id",id,"guardian",guardian)).stream().findFirst();}
    public Optional<Report> lock(UUID id){return query("where id=:id for update",Map.of("id",id)).stream().findFirst();}
    public Optional<Report> byKey(UUID guardian,String key){return query("where guardian_id=:guardian and idempotency_key=:key",Map.of("guardian",guardian,"key",key)).stream().findFirst();}
    public Report create(UUID id,UUID guardian,UUID child,String key,String hash,LocalDate from,LocalDate to,
            String name,Integer age,List<Activity> activities,Instant now){
        var p=at(now).addValue("id",id).addValue("guardian",guardian).addValue("child",child).addValue("key",key)
            .addValue("hash",hash).addValue("from",from).addValue("to",to).addValue("count",activities.size());
        text(p,"name",name);integer(p,"age",age);
        jdbc.update("insert into core.core_report(id,guardian_id,child_id,idempotency_key,request_hash,status,period_from,period_to,activity_count,citation_count,child_display_name,child_age,created_at) values(:id,:guardian,:child,:key,:hash,'GENERATING',:from,:to,:count,0,:name,:age,:now)",p);
        for(Activity a:activities)jdbc.update("insert into core.core_report_activity(report_id,session_id,activity_date,activity_type,result_status_at_build) values(:report,:session,:date,:type,:status)",
                Map.of("report",id,"session",a.sessionId(),"date",a.activityDate(),"type",a.activityType(),"status",a.resultStatus()));
        return find(id).orElseThrow();
    }
    public void attachJob(UUID id,UUID job){jdbc.update("update core.core_report set job_id=:job where id=:id and status='GENERATING'",Map.of("id",id,"job",job));}
    public void resultStatus(UUID id,UUID session,String status){jdbc.update("update core.core_report_activity set result_status_at_build=:status where report_id=:id and session_id=:session",Map.of("id",id,"session",session,"status",status));}
    public List<Activity> activities(UUID id){return jdbc.query("select * from core.core_report_activity where report_id=:id order by activity_date,session_id",Map.of("id",id),(r,n)->new Activity(uuid(r,"session_id"),r.getObject("activity_date",LocalDate.class),r.getString("activity_type"),r.getString("result_status_at_build")));}
    public List<Section> sections(UUID id){return jdbc.query("select * from core.core_report_section where report_id=:id order by section_no",Map.of("id",id),(r,n)->new Section(r.getInt("section_no"),r.getString("title"),r.getString("text"),r.getBoolean("missing"),r.getBoolean("templated"),citations(id,r.getInt("section_no"))));}
    private List<Citation> citations(UUID id,int section){return jdbc.query("select * from core.core_report_citation where report_id=:id and section_no=:section order by spoken_at,session_id,utterance_seq",Map.of("id",id,"section",section),(r,n)->new Citation(uuid(r,"session_id"),r.getObject("utterance_seq",Long.class),r.getString("quote"),instant(r,"spoken_at"),r.getString("source")));}
    /** 작업 잠금 이후 호출한다. 버린 머리는 늦은 처리 결과로 되살릴 수 없다. */
    public boolean writeSections(UUID id,UUID job,String level,List<Section> sections){
        Report report=lock(id).orElseThrow();
        if(!report.status().equals("GENERATING")||!job.equals(report.jobId()))return false;
        for(Section s:sections){
            jdbc.update("insert into core.core_report_section(report_id,section_no,title,text,missing,templated) values(:id,:no,:title,:text,:missing,:templated)",Map.of("id",id,"no",s.sectionNo(),"title",s.title(),"text",s.text(),"missing",s.missing(),"templated",s.templated()));
            for(Citation c:s.citations()){
                var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",UUID.randomUUID()).addValue("report",id).addValue("no",s.sectionNo()).addValue("session",c.sessionId()).addValue("seq",c.utteranceSeq(),java.sql.Types.BIGINT).addValue("source",c.source());
                text(p,"quote",c.quote());instant(p,"at",c.at());
                jdbc.update("insert into core.core_report_citation(id,report_id,section_no,session_id,utterance_seq,quote,spoken_at,source) values(:id,:report,:no,:session,:seq,:quote,:at,:source)",p);
            }
        }
        long count=sections.stream().flatMap(s->s.citations().stream()).filter(c->c.utteranceSeq()!=null).map(c->c.sessionId()+":"+c.utteranceSeq()).distinct().count();
        jdbc.update("update core.core_report set level=:level,citation_count=:count where id=:id",Map.of("id",id,"level",level,"count",count));
        return true;
    }
    public boolean saved(UUID id,Instant now){return jdbc.update("update core.core_report set status='SAVED',saved_at=:now where id=:id and status='READY'",at(now).addValue("id",id))==1;}
    public void discard(UUID id,Instant now){
        Report report=lock(id).orElseThrow();
        if(report.status().equals("DISCARDED"))return;
        if(report.status().equals("SAVED"))throw new IllegalStateException("REPORT_SAVED");
        var p=at(now).addValue("id",id);
        jdbc.update("delete from core.core_report_citation where report_id=:id",p);
        jdbc.update("delete from core.core_report_section where report_id=:id",p);
        jdbc.update("delete from core.core_report_activity where report_id=:id",p);
        jdbc.update("update core.core_report set status='DISCARDED',discarded_at=:now,child_display_name=null,child_age=null,level=null,activity_count=null,citation_count=null where id=:id",p);
    }
    public List<Report> unsaved(UUID guardian){return query("where guardian_id=:guardian and status in ('GENERATING','READY','FAILED') order by id",Map.of("guardian",guardian));}
    public List<Report> expired(Instant cutoff){return query("where status in ('READY','FAILED') and coalesce(ready_at,(select finished_at from core.core_result_job j where j.id=core_report.job_id),created_at)<=:cutoff order by id",Map.of("cutoff",timestamptz(cutoff)));}
    private List<Report> query(String suffix,Map<String,?> params){return jdbc.query("select * from core.core_report "+suffix,params,ReportRepository::map);}
    private static Report map(ResultSet r,int n)throws SQLException{return new Report(uuid(r,"id"),uuid(r,"guardian_id"),uuid(r,"child_id"),r.getString("idempotency_key"),r.getString("request_hash"),r.getString("status"),r.getObject("period_from",LocalDate.class),r.getObject("period_to",LocalDate.class),r.getInt("activity_count"),r.getInt("citation_count"),r.getString("level"),uuid(r,"job_id"),r.getString("child_display_name"),r.getObject("child_age",Integer.class),instant(r,"created_at"),instant(r,"ready_at"),instant(r,"saved_at"),instant(r,"discarded_at"));}
}
