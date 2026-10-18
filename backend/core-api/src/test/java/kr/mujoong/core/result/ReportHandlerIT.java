package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.knowledge.BehaviorDefinitionLookup;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.record.*;
import kr.mujoong.core.report.*;
import kr.mujoong.core.result.check.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
@Tag("result-report") @Tag("db") class ReportHandlerIT {
    DbTestSupport db;MutableClock clock;ExecutorService executor;FakeResultTextProvider provider;ResultJobRepository jobs;ReportRepository reports;
    SessionResultStateRepository states;ActivityResultRepository results;StructuredExperienceRepository structures;ResultRecordReader reader;JobRunner runner;
    kr.mujoong.core.consent.ConsentPolicyService consent;
    UUID guardian,child,reportId;DataSourceTransactionManager tm;
    @BeforeEach void setup(){db=DbTestSupport.coreApp();clock=new MutableClock();executor=Executors.newVirtualThreadPerTaskExecutor();provider=new FakeResultTextProvider();provider.response=this::normal;
        jobs=new ResultJobRepository(db.jdbc());reports=new ReportRepository(db.jdbc());states=new SessionResultStateRepository(db.jdbc());results=new ActivityResultRepository(db.jdbc());structures=new StructuredExperienceRepository(db.jdbc());reader=mock(ResultRecordReader.class);tm=new DataSourceTransactionManager(db.dataSource());
        consent=mock(kr.mujoong.core.consent.ConsentPolicyService.class);when(consent.check(any())).thenReturn(new kr.mujoong.core.consent.ConsentPolicyService.GateResult(true,null,new kr.mujoong.core.consent.ConsentPolicyService.Snapshot("v1",true,true,true,true)));
        var ai=new ResultTextGateway(provider,ResultTextGatewayTest.properties(Duration.ofSeconds(10)),clock,executor,JsonCodec.shared());var handler=new ReportHandler(reports,reader,states,results,new SceneJudgmentRepository(db.jdbc()),structures,mock(BehaviorDefinitionLookup.class),new QuoteCandidatePicker(),new ReportAssembler(),ai,new OutputChecker(new ForbiddenDictionary(),ai),tm,consent);
        runner=new JobRunner(jobs,new ReportJobStateAdapter(reports),new GuardianWriteLock(db.jdbc()),clock,JobRunnerTest.properties(),tm,List.of(handler),mock(EventLogger.class));
        guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);reportId=UUID.randomUUID();
    }
    @AfterEach void close(){executor.shutdownNow();db.close();}
    String normal(ResultTextProvider.Request request){if(request.task().equals("classify"))return JsonCodec.shared().write(Map.of("results",ResultData.objects(request.input().get("sentences")).stream().map(s->Map.of("id",s.get("id"),"violation",false)).toList()));
        return JsonCodec.shared().write(Map.of("sections",ResultData.objects(request.input().get("sections")).stream().map(s->{var refs=ResultData.objects(s.get("quotes"));var quote=refs.isEmpty()?List.of():List.of(refs.getFirst());return Map.of("sectionNo",s.get("sectionNo"),"text",quote.isEmpty()?"자료 부족":"\"같이 블록 쌓자\"라고 말했어요.","missing",quote.isEmpty(),"citations",quote);}).toList()));}
    ResultJob prepare(int count,Duration deadline){
        List<ReportRepository.Activity> activities=new ArrayList<>();
        for(int i=0;i<count;i++){UUID session=UUID.randomUUID();String type=i%2==0?"FREE_TALK":"PRACTICE";var transcript=List.of(Map.of("seq",1,"speaker","CHILD","text","같이 블록 쌓자","uncertain",false,"startedAt",clock.now().toString()));
            var record=new ActivityRecordRepository.StoredRecord(session,guardian,child,type,"ENDED","CHILD_STOP",true,clock.now().minusSeconds(120),clock.now(),clock.now(),clock.now().minusSeconds(120),clock.now(),JsonCodec.shared().write(transcript),"[]",null,"hash",clock.now());
            when(reader.find(session,guardian)).thenReturn(Optional.of(new ResultRecordReader.Input(record,"ENDED",null,true)));
            states.insert(session,guardian,child,type,true,clock.now());var resultJob=jobs.submit("SESSION_RESULT",session,guardian,"SR:"+session,"RECONCILE",null,clock.now());states.attach(session,resultJob.id(),1,clock.now());var claimed=jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst();
            var result=results.insert(session,claimed.id(),"FULL","[]","[]","[]",null,"{}","[]",clock.now());structures.insert(result.id(),session,"{\"situation\":{\"text\":\"함께 쌓은 이야기\",\"source\":\"CHILD_RECALL\",\"utteranceSeq\":1},\"explanation\":{\"text\":\"보호자가 전한 상황\",\"source\":\"GUARDIAN_INPUT\"}}",clock.now());jobs.transition(claimed,"DONE",null,null,clock.now(),"SUCCEEDED");
            activities.add(new ReportRepository.Activity(session,clock.now().atZone(ReportService.ZONE).toLocalDate(),type,"DONE"));
        }
        LocalDate date=clock.now().atZone(ReportService.ZONE).toLocalDate();reports.create(reportId,guardian,child,UUID.randomUUID().toString(),"hash",date,date,"민수",7,activities,clock.now());var j=jobs.submit("REPORT",reportId,guardian,"RP:"+reportId,"REPORT_REQUEST",clock.now().plus(deadline),clock.now());reports.attachJob(reportId,j.id());return runner.claim("REPORT").getFirst();
    }
    @Test void twentyActivitiesRecoverAfterFirstFailureWithinSixtySeconds()throws Exception{var job=prepare(20,Duration.ofSeconds(60));AtomicInteger calls=new AtomicInteger();provider.response=r->{try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResultAiException("TIMEOUT");}clock.advance(Duration.ofSeconds(2));if(calls.incrementAndGet()==1)throw new ResultAiException("PROVIDER_ERROR");return normal(r);};long start=System.nanoTime();runner.execute(job);Thread.sleep(5000);clock.advance(Duration.ofSeconds(5));runner.execute(runner.claim("REPORT").getFirst());long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");assertThat(elapsed).isLessThan(60000);assertThat(provider.requests).hasSize(3);System.out.println("U6_REPORT_A elapsed_ms="+elapsed+" activities=20");}
    @Test void absoluteDeadlineStopsDespiteRemainingAttempts(){var job=prepare(1,Duration.ofSeconds(3));provider.response=r->{clock.advance(Duration.ofSeconds(3));throw new ResultAiException("TIMEOUT");};runner.execute(job);assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("DEADLINE_EXCEEDED");assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("FAILED");}
    @Test void nextRetryBeyondDeadlineFailsImmediately(){var job=prepare(1,Duration.ofSeconds(4));provider.response=r->{throw new ResultAiException("PROVIDER_ERROR");};runner.execute(job);assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("FAILED");assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("DEADLINE_EXCEEDED");}
    @Test void citationsDeduplicateAcrossSectionsAndChildIdentityNeverLeaves(){var job=prepare(1,Duration.ofSeconds(60));runner.execute(job);var report=reports.find(reportId).orElseThrow();assertThat(report.status()).isEqualTo("READY");assertThat(report.citationCount()).isEqualTo(1);assertThat(reports.sections(reportId)).hasSize(10);assertThat(JsonCodec.shared().write(provider.requests.getFirst().input())).doesNotContain(guardian.toString(),child.toString(),reportId.toString(),"민수","interests");}
    @Test void badQuoteAndForbiddenBodyFallBackToVerifiedChronologicalQuotes(){var job=prepare(1,Duration.ofSeconds(60));provider.response=r->r.task().equals("report")?"{\"sections\":[{\"sectionNo\":2,\"text\":\"점수가 좋아요\",\"missing\":false,\"citations\":[{\"activityNo\":1,\"utteranceSeq\":1,\"quote\":\"없는 발화\"}]}]}":normal(r);runner.execute(job);var section=reports.sections(reportId).get(1);assertThat(section.templated()).isTrue();assertThat(section.text()).contains("같이 블록 쌓자").doesNotContain("없는 발화","점수");}
    @Test void guardianInputSourceHasNoQuote(){var job=prepare(1,Duration.ofSeconds(60));provider.response=r->r.task().equals("report")?"{\"sections\":[{\"sectionNo\":5,\"text\":\"보호자가 상황을 전했어요.\",\"missing\":false,\"citations\":[{\"activityNo\":1,\"source\":\"GUARDIAN_INPUT\"}]}]}":normal(r);runner.execute(job);var section=reports.sections(reportId).get(4);assertThat(section.citations()).hasSize(1);assertThat(section.citations().getFirst().source()).isEqualTo("GUARDIAN_INPUT");assertThat(section.citations().getFirst().quote()).isNull();}
    @Test void lateCompletionAfterDiscardCannotRestoreChildren(){var job=prepare(1,Duration.ofSeconds(60));provider.response=r->{if(r.task().equals("report"))new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(t->reports.discard(reportId,clock.now()));return normal(r);};runner.execute(job);assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("DISCARDED");assertThat(reports.sections(reportId)).isEmpty();assertThat(reports.activities(reportId)).isEmpty();assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("REPORT_DISCARDED");}
    @Test void missingAiSectionsBecomeMissingAndAlwaysKeepLimitNotice(){var job=prepare(1,Duration.ofSeconds(60));var session=reports.activities(reportId).getFirst().sessionId();var input=reader.find(session,guardian).orElseThrow();var r=input.record();when(reader.find(session,guardian)).thenReturn(Optional.of(new ResultRecordReader.Input(new ActivityRecordRepository.StoredRecord(r.sessionId(),guardian,child,r.activityType(),r.finalStatus(),r.endReason(),true,r.startedAt(),r.endedAt(),r.listedAt(),r.recordFrom(),r.recordTo(),"[]","[]",null,"hash",clock.now()),"ENDED",null,false)));provider.response=q->"{\"sections\":[]}";runner.execute(job);assertThat(reports.sections(reportId).get(1).missing()).isTrue();assertThat(reports.sections(reportId).get(9).text()).contains(ReportAssembler.LIMITATION,"자료 부족");}
    @Test void reportWorkerCompletesWhileThreeSlowSessionsAreQueued()throws Exception{
        var job=prepare(20,Duration.ofSeconds(60));db.jdbc().update("delete from core.core_result_job_attempt where job_id=:id",Map.of("id",job.id()));db.jdbc().update("update core.core_result_job set status='REQUESTED',attempt_count=0,lease_until=null where id=:id",Map.of("id",job.id()));
        CountDownLatch sessionStarted=new CountDownLatch(1),sessionFinished=new CountDownLatch(1);
        runner.register(new JobHandler(){public String jobType(){return "SESSION_RESULT";}public Outcome handle(JobContext context){sessionStarted.countDown();try{Thread.sleep(10000);sessionFinished.countDown();}catch(InterruptedException e){Thread.currentThread().interrupt();return Outcome.failed("TIMEOUT",true);}context.commit(()->{},()->{});return Outcome.done();}});
        for(int i=0;i<3;i++)jobs.submit("SESSION_RESULT",UUID.randomUUID(),guardian,"slow-"+i+reportId,"RECONCILE",null,clock.now());
        provider.response=r->{try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResultAiException("TIMEOUT");}return normal(r);};
        var loop=new JobRunnerLoop(runner,JobRunnerTest.properties(),mock(EventLogger.class));long start=System.nanoTime();
        try{loop.start();assertThat(sessionStarted.await(2,TimeUnit.SECONDS)).isTrue();while(!reports.find(reportId).orElseThrow().status().equals("READY")&&TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<15)Thread.sleep(50);assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");assertThat(sessionFinished.getCount()).isEqualTo(1);System.out.println("U6_REPORT_D elapsed_ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}finally{loop.stop();}
    }

    kr.mujoong.core.account.DeletionRequestRepository requestDeletion(){
        var requests=new kr.mujoong.core.account.DeletionRequestRepository(db.jdbc());
        new kr.mujoong.core.account.AccountDeletionService(requests,mock(kr.mujoong.core.identity.ChildRepository.class),
            mock(kr.mujoong.core.identity.GuardianSessions.class),new GuardianWriteLock(db.jdbc()),db.jdbc(),tm,
            mock(EventLogger.class),clock).request(guardian);
        return requests;
    }
    @Test void realCreatedReportIsNotSentAfterDeletionRequestBeforeParticipants(){
        prepare(1,Duration.ofSeconds(60));
        UUID session=reports.activities(reportId).getFirst().sessionId();var record=reader.find(session,guardian).orElseThrow().record();
        when(reader.reportRecords(guardian,child)).thenReturn(List.of(record));
        var children=mock(kr.mujoong.core.child.ChildProfileService.class);
        when(children.childContext(guardian)).thenReturn(Optional.of(new kr.mujoong.core.child.ChildProfileService.ChildContext(child,"민수",7,List.of(),List.of())));
        var properties=JobRunnerTest.properties();when(properties.timing().report()).thenReturn(new kr.mujoong.core.config.CoreTimingProperties.ReportTiming(Duration.ofSeconds(60),Duration.ofHours(24)));
        var service=new ReportService(reports,reader,children,states,jobs,new GuardianWriteLock(db.jdbc()),clock,properties,JsonCodec.shared(),tm);
        LocalDate today=clock.now().atZone(ReportService.ZONE).toLocalDate();
        var created=service.create(guardian,today,today,List.of(session),UUID.randomUUID().toString());
        var requests=requestDeletion();
        assertThat(requests.findOpenByGuardian(guardian).orElseThrow().coreClearedAt()).isNull();
        assertThat(db.jdbc().queryForObject("select status from core.core_guardian where id=:id",Map.of("id",guardian),String.class)).isEqualTo("DELETION_REQUESTED");
        var job=runner.claim("REPORT").stream().filter(j->j.subjectId().equals(created.reportId())).findFirst().orElseThrow();
        runner.execute(job);
        assertThat(provider.requests).isEmpty();assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("ACCOUNT_DELETING");
        assertThat(reports.find(created.reportId()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(reports.activities(created.reportId())).hasSize(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void withdrawnActivityNeverSuppliesQuotesOrResultsEvenWhenDone(boolean done){
        var job=prepare(2,Duration.ofSeconds(60));var session=reports.activities(reportId).getFirst().sessionId();
        var r=reader.find(session,guardian).orElseThrow().record();
        var withdrawn=new ActivityRecordRepository.StoredRecord(r.sessionId(),guardian,child,r.activityType(),r.finalStatus(),"CONSENT_WITHDRAWN",
            r.confirmed(),r.startedAt(),r.endedAt(),r.listedAt(),r.recordFrom(),r.recordTo(),
            r.transcriptJson().replace("같이 블록 쌓자","철회 비밀 내용"),r.gapsJson(),r.practiceJson(),r.snapshotHash(),r.recordedAt());
        when(reader.find(session,guardian)).thenReturn(Optional.of(new ResultRecordReader.Input(withdrawn,"ENDED",null,true)));
        if(!done)db.jdbc().update("update core.core_session_result_state set job_status='NONE' where session_id=:id",Map.of("id",session));
        runner.execute(job);
        var request=provider.requests.stream().filter(q->q.task().equals("report")).findFirst().orElseThrow();
        assertThat(JsonCodec.shared().write(request.input())).doesNotContain("철회 비밀 내용");
        assertThat(ResultData.objects(request.input().get("activities"))).hasSize(1);
        assertThat(ResultData.objects(request.input().get("sections")).stream().flatMap(s->ResultData.objects(s.get("quotes")).stream()))
            .allSatisfy(q->assertThat(((Number)q.get("activityNo")).intValue()).isNotEqualTo(1));
        assertThat(reports.sections(reportId).stream().flatMap(s->s.citations().stream())).noneMatch(c->c.sessionId().equals(session));
        assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");
        assertThat(reports.activities(reportId).stream().filter(a->a.sessionId().equals(session)).findFirst().orElseThrow().resultStatus()).isEqualTo(done?"DONE":"NONE");
    }
    @Test void missingCurrentConsentStopsBeforeReportInputIsRead(){
        var job=prepare(1,Duration.ofSeconds(60));clearInvocations(reader);
        when(consent.check(guardian)).thenReturn(new kr.mujoong.core.consent.ConsentPolicyService.GateResult(false,null,new kr.mujoong.core.consent.ConsentPolicyService.Snapshot("v1",false,true,true,true)));
        runner.execute(job);assertThat(provider.requests).isEmpty();verifyNoInteractions(reader);
        assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("INTERNAL");
        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("FAILED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void deletionOrWithdrawalAfterReportCallPreventsClassifier(boolean deletion){
        var job=prepare(1,Duration.ofSeconds(60));
        provider.response=r->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(deletion)requestDeletion();else when(consent.check(guardian)).thenReturn(new kr.mujoong.core.consent.ConsentPolicyService.GateResult(false,null,new kr.mujoong.core.consent.ConsentPolicyService.Snapshot("v1",false,true,true,true)));
            return normal(r);
        };
        runner.execute(job);assertThat(provider.requests).extracting(ResultTextProvider.Request::task).containsExactly("report");
        assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo(deletion?"ACCOUNT_DELETING":"INTERNAL");
        assertThat(reports.sections(reportId)).isEmpty();assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("FAILED");
    }
    @Test void withdrawalDuringClassifierPreventsPublishingReport(){
        var job=prepare(1,Duration.ofSeconds(60));
        provider.response=r->{if(r.task().equals("classify"))when(consent.check(guardian)).thenReturn(new kr.mujoong.core.consent.ConsentPolicyService.GateResult(false,null,new kr.mujoong.core.consent.ConsentPolicyService.Snapshot("v1",false,true,true,true)));return normal(r);};
        runner.execute(job);assertThat(provider.requests).extracting(ResultTextProvider.Request::task).containsExactly("report","classify");
        assertThat(reports.sections(reportId)).isEmpty();assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("INTERNAL");
    }
}
