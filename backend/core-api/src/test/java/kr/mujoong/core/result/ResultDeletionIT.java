package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.report.ReportRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
@Tag("result-report") @Tag("db")
class ResultDeletionIT {
    DbTestSupport db;ResultDeletionParticipant deletion;ResultJobRepository jobs;Instant now=Instant.parse("2026-10-15T00:00:00Z");
    @BeforeEach void setup(){db=DbTestSupport.coreApp();jobs=new ResultJobRepository(db.jdbc());deletion=new ResultDeletionParticipant(db.jdbc(),new GuardianWriteLock(db.jdbc()),new DataSourceTransactionManager(db.dataSource()));}
    @AfterEach void close(){db.close();}
    UUID populate(UUID guardian){UUID session=UUID.randomUUID(),child=UUID.randomUUID();var states=new SessionResultStateRepository(db.jdbc());states.insert(session,guardian,child,"PRACTICE",true,now);var j=jobs.submit("SESSION_RESULT",session,guardian,session.toString(),"RECONCILE",null,now);states.attach(session,j.id(),1,now);var running=jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30)).getFirst();var result=new ActivityResultRepository(db.jdbc()).insert(session,j.id(),"PARTIAL","[]","[]","[]",null,"[]","[]",now);new StructuredExperienceRepository(db.jdbc()).insert(result.id(),session,"{}",now);new SceneJudgmentRepository(db.jdbc()).insert(result.id(),session,new SceneJudgmentRepository.Scene(UUID.randomUUID(),1,1,"ORIGINAL",null,null,null,null,null,false,"ACTIVITY_ENDED","NOT_DONE"),now);jobs.transition(running,"DONE",null,null,now,"SUCCEEDED");var reports=new ReportRepository(db.jdbc());UUID report=UUID.randomUUID();reports.create(report,guardian,child,report.toString(),"hash",LocalDate.of(2026,10,15),LocalDate.of(2026,10,15),"아이",8,List.of(new ReportRepository.Activity(session,LocalDate.of(2026,10,15),"PRACTICE","DONE")),now);var rj=jobs.submit("REPORT",report,guardian,report.toString(),"REPORT_REQUEST",now.plusSeconds(60),now);reports.attachJob(report,rj.id());reports.writeSections(report,rj.id(),"PARTIAL",List.of(new ReportRepository.Section(1,"개요","활동",false,true,List.of(new ReportRepository.Citation(session,1L,"함께 해",now,"TRANSCRIPT")))));return session;}
    @Test void deletesAllTenTablesAndKeepsOtherGuardian(){db.inRolledBackTx(t->{UUID g=db.insertGuardian("DELETION_REQUESTED"),other=db.insertGuardian("ACTIVE");populate(g);UUID keep=populate(other);var counts=deletion.deleteByGuardian(g,UUID.randomUUID());assertThat(counts).hasSize(10);assertThat(counts.values()).allMatch(n->n>0);assertThat(new SessionResultStateRepository(db.jdbc()).find(keep,other)).isPresent();assertThat(jobs.unfinished()).noneMatch(j->j.guardianId().equals(g));return null;});}
    @Test void deletionIsIdempotent(){db.inRolledBackTx(t->{UUID g=db.insertGuardian("DELETION_REQUESTED");populate(g);deletion.deleteByGuardian(g,UUID.randomUUID());assertThat(deletion.deleteByGuardian(g,UUID.randomUUID()).values()).allMatch(n->n==0);return null;});}
    @Test void removesRunningJobs(){db.inRolledBackTx(t->{UUID g=db.insertGuardian("DELETION_REQUESTED");var j=jobs.submit("SESSION_RESULT",UUID.randomUUID(),g,UUID.randomUUID().toString(),"RECONCILE",null,now);jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30));deletion.deleteByGuardian(g,UUID.randomUUID());assertThat(jobs.find(j.id())).isEmpty();return null;});}
    @Test void runsBeforeRecordDeletion(){assertThat(DeletionParticipant.ORDER_RESULT).isEqualTo(70).isLessThan(DeletionParticipant.ORDER_ACTIVITY_RECORD);assertThat(ResultDeletionParticipant.class.getAnnotation(org.springframework.core.annotation.Order.class).value()).isEqualTo(70);}
    @Test void deletingGuardianCannotEnqueueNewResults(){
        UUID guardian=db.insertGuardian("DELETION_REQUESTED"),session=UUID.randomUUID();
        var reader=org.mockito.Mockito.mock(kr.mujoong.core.record.ResultRecordReader.class);
        var states=new SessionResultStateRepository(db.jdbc());
        var intake=new ResultIntake(reader,states,jobs,new GuardianWriteLock(db.jdbc()),
                new kr.mujoong.testsupport.time.MutableClock(),JobRunnerTest.properties(),
                new DataSourceTransactionManager(db.dataSource()),
                org.mockito.Mockito.mock(kr.mujoong.core.observability.EventLogger.class));
        intake.accept(session,guardian,"ACTIVITY_RECORDED");
        assertThat(states.find(session,guardian)).isEmpty();
        assertThat(jobs.unfinished()).noneMatch(job->job.guardianId().equals(guardian));
        org.mockito.Mockito.verifyNoInteractions(reader);
    }
}
