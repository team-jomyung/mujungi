package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.report.ReportRepository;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
@Tag("result-report") @Tag("db")
class JobRunnerIT {
    DbTestSupport db;ResultJobRepository jobs;ReportRepository reports;JobRunner runner;MutableClock clock;
    @BeforeEach void setup(){db=DbTestSupport.coreApp();jobs=new ResultJobRepository(db.jdbc());reports=new ReportRepository(db.jdbc());clock=new MutableClock();runner=new JobRunner(jobs,new kr.mujoong.core.report.ReportJobStateAdapter(reports),new GuardianWriteLock(db.jdbc()),clock,JobRunnerTest.properties(),new DataSourceTransactionManager(db.dataSource()),List.of(),org.mockito.Mockito.mock(kr.mujoong.core.observability.EventLogger.class));}
    @AfterEach void close(){db.close();}
    ResultJob claim(UUID guardian){jobs.submit("SESSION_RESULT",UUID.randomUUID(),guardian,UUID.randomUUID().toString(),"RECONCILE",null,clock.now());return jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst();}
    void handler(java.util.function.Function<JobContext,Outcome> fn){runner.register(new JobHandler(){public String jobType(){return "SESSION_RESULT";}public Outcome handle(JobContext c){return fn.apply(c);}});}
    @Test void staleAttemptCannotWrite(){db.inRolledBackTx(t->{UUID guardian=db.insertGuardian("ACTIVE");var j=claim(guardian);jobs.transition(j,"FAILED","TIMEOUT",null,clock.now(),"FAILED");var wrote=new AtomicBoolean();handler(c->{assertThat(c.commit(()->wrote.set(true))).isFalse();return Outcome.done();});runner.execute(j);assertThat(wrote).isFalse();return null;});}
    @Test void deletingGuardianCannotWrite(){db.inRolledBackTx(t->{UUID guardian=db.insertGuardian("DELETION_REQUESTED");var j=claim(guardian);handler(c->{c.commit(()->{throw new AssertionError("쓰기 금지");});return Outcome.done();});runner.execute(j);assertThat(jobs.find(j.id()).orElseThrow().lastErrorCode()).isEqualTo("ACCOUNT_DELETING");return null;});}
    @Test void hookFailureKeepsDone(){db.inRolledBackTx(t->{UUID guardian=db.insertGuardian("ACTIVE");var j=claim(guardian);handler(c->{c.commit(()->{},()->{throw new IllegalStateException("등록 실패");});return Outcome.done();});runner.execute(j);assertThat(jobs.find(j.id()).orElseThrow().status()).isEqualTo("DONE");return null;});}
    @Test void reportDiscardedDuringGenerationDoesNotRevive(){db.inRolledBackTx(t->{UUID guardian=db.insertGuardian("ACTIVE"),id=UUID.randomUUID();reports.create(id,guardian,UUID.randomUUID(),id.toString(),"hash",LocalDate.now(java.time.Clock.fixed(clock.now(),ZoneOffset.UTC)),LocalDate.now(java.time.Clock.fixed(clock.now(),ZoneOffset.UTC)),"아이",8,List.of(new ReportRepository.Activity(UUID.randomUUID(),LocalDate.of(2026,10,15),"FREE_TALK","DONE")),clock.now());var pending=jobs.submit("REPORT",id,guardian,id.toString(),"REPORT_REQUEST",clock.now().plusSeconds(60),clock.now());reports.attachJob(id,pending.id());var job=jobs.claim("REPORT",1,clock.now(),Duration.ofSeconds(30)).getFirst();reports.discard(id,clock.now());runner.register(new JobHandler(){public String jobType(){return "REPORT";}public Outcome handle(JobContext c){c.commit(()->{throw new AssertionError("쓰기 금지");});return Outcome.done();}});runner.execute(job);assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("REPORT_DISCARDED");assertThat(reports.find(id).orElseThrow().status()).isEqualTo("DISCARDED");return null;});}
    @Test void passedDeadlineFailsReportAndJobTogether(){db.inRolledBackTx(t->{UUID guardian=db.insertGuardian("ACTIVE"),id=UUID.randomUUID();reports.create(id,guardian,UUID.randomUUID(),id.toString(),"hash",LocalDate.of(2026,10,15),LocalDate.of(2026,10,15),"아이",8,List.of(new ReportRepository.Activity(UUID.randomUUID(),LocalDate.of(2026,10,15),"FREE_TALK","DONE")),clock.now());var job=jobs.submit("REPORT",id,guardian,id.toString(),"REPORT_REQUEST",clock.now().plusSeconds(60),clock.now());reports.attachJob(id,job.id());clock.advance(Duration.ofSeconds(60));runner.expire();assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("DEADLINE_EXCEEDED");assertThat(reports.find(id).orElseThrow().status()).isEqualTo("FAILED");return null;});}
    @Test void restartAbandonsRunningButRetainsRequested(){db.inRolledBackTx(t->{var running=claim(db.insertGuardian("ACTIVE"));var waiting=jobs.submit("SESSION_RESULT",UUID.randomUUID(),running.guardianId(),UUID.randomUUID().toString(),"RECONCILE",null,clock.now());runner.startup();assertThat(jobs.find(running.id()).orElseThrow().lastErrorCode()).isEqualTo("SERVER_RESTART");assertThat(jobs.find(waiting.id()).orElseThrow().status()).isEqualTo("REQUESTED");return null;});}
}
