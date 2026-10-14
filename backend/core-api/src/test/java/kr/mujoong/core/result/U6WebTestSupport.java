package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.*;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.record.ActivityRecordRepository;
import kr.mujoong.core.support.*;
import kr.mujoong.core.web.support.*;
import org.junit.jupiter.api.*;
/** 실제 HTTP·보안 체인·계약 스키마를 쓰는 U6 시험 기반. */
public abstract class U6WebTestSupport {
    protected static DbTestSupport db;protected static CoreWebApp app;protected static BusinessFixtures fixtures;protected static OpenApiContracts contracts;
    @BeforeAll static void start(){db=DbTestSupport.coreApp();BusinessFixtures.applyReferenceSeed(db.postgres());U2Fixtures.applyU2Seed(db.postgres());fixtures=new BusinessFixtures(db);contracts=OpenApiContracts.load();app=CoreWebApp.builder(db.postgres()).property("mujoong.timing.session.preparing-timeout","30d").start();app.bean(JobRunnerLoop.class).stop();app.bean(ResultReconciler.class).stop();}
    @AfterAll static void stop(){if(app!=null)app.close();if(db!=null)db.close();}
    @AfterEach void cleanup(){db.cleanUp();}
    protected BusinessFixtures.Family family(){return fixtures.family(app.clock().now().minusSeconds(10));}
    protected UUID copy(BusinessFixtures.Family family,String status,String failure){UUID session=UUID.randomUUID();var devices=db.jdbc().queryForList("select id from core.core_device where guardian_id=:guardian and registration_status='REGISTERED'",Map.of("guardian",family.guardianId()),UUID.class);UUID device=devices.isEmpty()?fixtures.registeredDevice(family.guardianId(),ConnectionState.CONNECTED,app.clock().now(),app.clock().now()):devices.getFirst();app.bean(ActivityCopyRepository.class).insertRequested(new ActivityCopyRepository.NewActivity(session,family.guardianId(),family.childId(),device,ActivityType.FREE_TALK,UUID.randomUUID().toString(),"hash",app.clock().now()));db.jdbc().update("update core.core_activity set status=:status,failure_code=:failure where session_id=:id",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",session).addValue("status",status).addValue("failure",failure,java.sql.Types.VARCHAR));return session;}
    protected void record(BusinessFixtures.Family family,UUID session,String status,int utterances){Instant now=app.clock().now();List<Map<String,Object>> lines=new ArrayList<>();for(int i=1;i<=utterances;i++)lines.add(Map.of("seq",i,"speaker","CHILD","text","같이 블록 쌓자","uncertain",false,"startedAt",now.toString()));app.bean(ActivityRecordRepository.class).insertIfAbsent(new ActivityRecordRepository.NewRecord(session,family.guardianId(),family.childId(),"FREE_TALK",status,"CHILD_STOP",true,null,now,now,now.minusSeconds(60),now,JsonCodec.shared().write(lines),"[]",null,"[]",null,UUID.randomUUID(),1,"sha256:"+JsonCodec.shared().sha256OfCanonical(JsonCodec.shared().write(lines))),now);}
    protected CoreWebApp.Result checked(String operation,CoreWebApp.Call call){var result=call.send();assertThat(contracts.violations(operation,result)).isEmpty();return result;}
}
