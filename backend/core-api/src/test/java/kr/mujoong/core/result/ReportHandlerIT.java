package kr.mujoong.core.result;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

/** REPORT 작업: AI 없이 10항목을 만들고 동의 · 삭제 · 버림 경계를 지킨다(JIRA-267, contracts/deviations.md FT-1023-c). */
@Tag("result-report") @Tag("db") class ReportHandlerIT {
    DbTestSupport db;MutableClock clock;ResultJobRepository jobs;ReportRepository reports;
    SessionResultStateRepository states;ActivityResultRepository results;StructuredExperienceRepository structures;SceneJudgmentRepository scenes;ResultRecordReader reader;JobRunner runner;
    kr.mujoong.core.consent.ConsentPolicyService consent;
    UUID guardian,child,reportId;DataSourceTransactionManager tm;
    @BeforeEach void setup(){db=DbTestSupport.coreApp();clock=new MutableClock();
        jobs=new ResultJobRepository(db.jdbc());reports=new ReportRepository(db.jdbc());states=new SessionResultStateRepository(db.jdbc());results=new ActivityResultRepository(db.jdbc());structures=new StructuredExperienceRepository(db.jdbc());scenes=new SceneJudgmentRepository(db.jdbc());reader=mock(ResultRecordReader.class);tm=new DataSourceTransactionManager(db.dataSource());
        consent=mock(kr.mujoong.core.consent.ConsentPolicyService.class);when(consent.check(any())).thenReturn(granted(true));
        // 연습 목표의 고정 정의가 없으면 7항목에서 뺀다(BR12.2). 시험 목표는 정의가 없다.
        var definitions=mock(BehaviorDefinitionLookup.class);when(definitions.get(anyString(),anyInt())).thenThrow(new IllegalArgumentException("정의 없음"));
        var handler=new ReportHandler(reports,reader,states,results,scenes,structures,definitions,new ReportAssembler(),new ReportRuleSections(new ForbiddenDictionary()),tm,consent);
        runner=new JobRunner(jobs,new ReportJobStateAdapter(reports),new GuardianWriteLock(db.jdbc()),clock,JobRunnerTest.properties(),tm,List.of(handler),mock(EventLogger.class));
        guardian=db.insertGuardian("ACTIVE");child=db.insertChild(guardian);reportId=UUID.randomUUID();
    }
    @AfterEach void close(){db.close();}
    static kr.mujoong.core.consent.ConsentPolicyService.GateResult granted(boolean voiceAndText){return new kr.mujoong.core.consent.ConsentPolicyService.GateResult(voiceAndText,null,new kr.mujoong.core.consent.ConsentPolicyService.Snapshot("v1",voiceAndText,true,true,true));}
    /** 짝수 번째는 자유대화(구조화 · 결과 문장 있음), 홀수 번째는 U5b 형식 연습(장면 1 판정 + FIRST 반응 발화 2). */
    ResultJob prepare(int count,Duration deadline){
        List<ReportRepository.Activity> activities=new ArrayList<>();
        for(int i=0;i<count;i++){UUID session=UUID.randomUUID();boolean free=i%2==0;String type=free?"FREE_TALK":"PRACTICE";UUID goal=UUID.randomUUID();
            var transcript=List.of(Map.of("seq",1,"speaker","CHILD","text","같이 블록 쌓자","uncertain",false,"startedAt",clock.now().toString()),
                Map.of("seq",2,"speaker","CHILD","text","블록 하나 빌려 줄래","uncertain",false,"startedAt",clock.now().plusSeconds(1).toString()));
            String practice=free?null:JsonCodec.shared().write(Map.of("goals",List.of(Map.of("goalSelectionId",goal.toString(),"behaviorKey","ask","behaviorVersion",1,"displayName","부탁하기","cues",List.of(),"responses",List.of(Map.of("responseType","FIRST","utteranceSeq",2))))));
            var record=new ActivityRecordRepository.StoredRecord(session,guardian,child,type,"ENDED","CHILD_STOP",true,clock.now().minusSeconds(120),clock.now(),clock.now(),clock.now().minusSeconds(120),clock.now(),JsonCodec.shared().write(transcript),"[]",practice,"hash",clock.now());
            when(reader.find(session,guardian)).thenReturn(Optional.of(new ResultRecordReader.Input(record,"ENDED",null,true)));
            states.insert(session,guardian,child,type,true,clock.now());var resultJob=jobs.submit("SESSION_RESULT",session,guardian,"SR:"+session,"RECONCILE",null,clock.now());states.attach(session,resultJob.id(),1,clock.now());var claimed=jobs.claim("SESSION_RESULT",1,clock.now(),Duration.ofSeconds(30)).getFirst();
            var result=results.insert(session,claimed.id(),"FULL",free?"[{\"text\":\"2분 동안 이야기를 나눴어요.\"}]":"[]","[]","[]",null,"{}","[]",clock.now());
            if(free)structures.insert(result.id(),session,"{\"situation\":{\"text\":\"같이 블록\",\"source\":\"CHILD_RECALL\",\"utteranceSeq\":1},\"explanation\":{\"text\":\"보호자가 전한 상황\",\"source\":\"GUARDIAN_INPUT\"}}",clock.now());
            else scenes.insert(result.id(),session,new SceneJudgmentRepository.Scene(goal,1,1,"ORIGINAL",null,"functional","IMMEDIATE",null,null,false,null,"DONE_IMMEDIATE"),clock.now());
            jobs.transition(claimed,"DONE",null,null,clock.now(),"SUCCEEDED");
            activities.add(new ReportRepository.Activity(session,clock.now().atZone(ReportService.ZONE).toLocalDate(),type,"DONE"));
        }
        LocalDate date=clock.now().atZone(ReportService.ZONE).toLocalDate();reports.create(reportId,guardian,child,UUID.randomUUID().toString(),"hash",date,date,"민수",7,activities,clock.now());var j=jobs.submit("REPORT",reportId,guardian,"RP:"+reportId,"REPORT_REQUEST",clock.now().plus(deadline),clock.now());reports.attachJob(reportId,j.id());return runner.claim("REPORT").getFirst();
    }
    UUID firstSession(){return reports.activities(reportId).getFirst().sessionId();}
    ReportRepository.Section section(int no){return reports.sections(reportId).stream().filter(s->s.sectionNo()==no).findFirst().orElseThrow();}

    @Test void handlerHasNoAiDependency(){
        // 리포트 생성 경로에 텍스트 AI · 금지 표현 2차 분류 · Luna 호출부가 들어오지 않는다.
        var forbidden=Set.<Class<?>>of(ResultTextGateway.class,ResultTextProvider.class,OutputChecker.class,RejudgmentGateway.class,kr.mujoong.core.luna.CoreLunaClient.class);
        for(var constructor:ReportHandler.class.getConstructors())assertThat(constructor.getParameterTypes()).doesNotContainAnyElementsOf(forbidden);
        for(var constructor:ReportRuleSections.class.getConstructors())assertThat(constructor.getParameterTypes()).doesNotContainAnyElementsOf(forbidden);
    }
    @Test void twentyActivitiesRecoverAfterFirstFailureWithinSixtySeconds()throws Exception{
        var job=prepare(20,Duration.ofSeconds(60));UUID session=firstSession();var input=reader.find(session,guardian).orElseThrow();AtomicInteger reads=new AtomicInteger();
        when(reader.find(session,guardian)).thenAnswer(call->{if(reads.incrementAndGet()==1)throw new IllegalStateException("일시 읽기 실패");return Optional.of(input);});
        long start=System.nanoTime();runner.execute(job);clock.advance(Duration.ofSeconds(5));runner.execute(runner.claim("REPORT").getFirst());long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");assertThat(elapsed).isLessThan(60000);assertThat(reports.sections(reportId)).hasSize(10);
        System.out.println("U6_REPORT_A elapsed_ms="+elapsed+" activities=20");
    }
    @Test void absoluteDeadlineStopsDespiteRemainingAttempts(){var job=prepare(1,Duration.ofSeconds(3));when(reader.find(firstSession(),guardian)).thenAnswer(call->{clock.advance(Duration.ofSeconds(3));throw new IllegalStateException("느린 읽기");});runner.execute(job);assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("DEADLINE_EXCEEDED");assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("FAILED");}
    @Test void nextRetryBeyondDeadlineFailsImmediately(){var job=prepare(1,Duration.ofSeconds(4));when(reader.find(firstSession(),guardian)).thenThrow(new IllegalStateException("읽기 실패"));runner.execute(job);assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("FAILED");assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("DEADLINE_EXCEEDED");}
    @Test void ruleSectionsAreWrittenFromStoredRecordsAndResults(){
        var job=prepare(2,Duration.ofSeconds(60));runner.execute(job);
        var report=reports.find(reportId).orElseThrow();assertThat(report.status()).isEqualTo("READY");assertThat(reports.sections(reportId)).hasSize(10);
        // 2항목: 연습 장면 1의 FIRST 반응 발화(2)만 인용한다. 연습 인용이 있으면 자유대화 발화는 쓰지 않는다.
        var practiceSession=reports.activities(reportId).stream().filter(a->a.activityType().equals("PRACTICE")).findFirst().orElseThrow().sessionId();
        assertThat(section(2).citations()).singleElement().satisfies(c->{assertThat(c.sessionId()).isEqualTo(practiceSession);assertThat(c.utteranceSeq()).isEqualTo(2L);assertThat(c.quote()).isEqualTo("블록 하나 빌려 줄래");});
        assertThat(section(2).text()).contains("부탁하기 · 장면 1 · 바로 전달");
        // 5항목: 아이 회상은 칸 글. 보호자 입력은 줄 없이 quote 없는 출처 인용만.
        assertThat(section(5).text()).contains("상황 · “같이 블록”").doesNotContain("보호자");
        assertThat(section(5).citations()).extracting(ReportRepository.Citation::source).containsExactlyInAnyOrder("CHILD_RECALL","GUARDIAN_INPUT");
        assertThat(section(5).citations()).filteredOn(c->c.source().equals("GUARDIAN_INPUT")).singleElement().satisfies(c->assertThat(c.quote()).isNull());
        // 6항목: 결과 문장이 있는 자유대화만. 결과 문장 없는 연습은 줄 · 인용이 없다.
        assertThat(section(6).text().lines()).singleElement().satisfies(line->assertThat(line).endsWith("자유대화 · 2분 동안 이야기를 나눴어요."));
        assertThat(section(6).citations()).isEmpty();
        assertThat(section(10).text()).startsWith(ReportAssembler.LIMITATION).contains("끝난 이유 · 아이가 끝냈어요 2회");
        assertThat(report.citationCount()).isEqualTo(2);
    }
    @Test void lateCompletionAfterDiscardCannotRestoreChildren(){var job=prepare(1,Duration.ofSeconds(60));var session=firstSession();var input=reader.find(session,guardian).orElseThrow();when(reader.find(session,guardian)).thenAnswer(call->{new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(db.dataSource())).executeWithoutResult(t->reports.discard(reportId,clock.now()));return Optional.of(input);});runner.execute(job);assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("DISCARDED");assertThat(reports.sections(reportId)).isEmpty();assertThat(reports.activities(reportId)).isEmpty();assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("REPORT_DISCARDED");}
    @Test void emptyTranscriptLeavesQuoteSectionsMissingAndAlwaysKeepsLimitNotice(){var job=prepare(1,Duration.ofSeconds(60));var session=firstSession();var input=reader.find(session,guardian).orElseThrow();var r=input.record();when(reader.find(session,guardian)).thenReturn(Optional.of(new ResultRecordReader.Input(new ActivityRecordRepository.StoredRecord(r.sessionId(),guardian,child,r.activityType(),r.finalStatus(),r.endReason(),true,r.startedAt(),r.endedAt(),r.listedAt(),r.recordFrom(),r.recordTo(),"[]","[]",null,"hash",clock.now()),"ENDED",null,false)));db.jdbc().update("delete from core.core_structured_experience where session_id=:id",Map.of("id",session));runner.execute(job);assertThat(section(2).missing()).isTrue();assertThat(section(5).missing()).isTrue();assertThat(section(10).missing()).isFalse();
        // 자유대화만 고른 리포트라 연습 고정 문장은 붙이지 않는다.
        assertThat(section(10).text()).doesNotContain(ReportAssembler.LIMITATION).contains("끝난 이유");}
    @Test void reportWorkerCompletesWhileThreeSlowSessionsAreQueued()throws Exception{
        var job=prepare(20,Duration.ofSeconds(60));db.jdbc().update("delete from core.core_result_job_attempt where job_id=:id",Map.of("id",job.id()));db.jdbc().update("update core.core_result_job set status='REQUESTED',attempt_count=0,lease_until=null where id=:id",Map.of("id",job.id()));
        CountDownLatch sessionStarted=new CountDownLatch(1),sessionFinished=new CountDownLatch(1);
        runner.register(new JobHandler(){public String jobType(){return "SESSION_RESULT";}public Outcome handle(JobContext context){sessionStarted.countDown();try{Thread.sleep(10000);sessionFinished.countDown();}catch(InterruptedException e){Thread.currentThread().interrupt();return Outcome.failed("TIMEOUT",true);}context.commit(()->{},()->{});return Outcome.done();}});
        for(int i=0;i<3;i++)jobs.submit("SESSION_RESULT",UUID.randomUUID(),guardian,"slow-"+i+reportId,"RECONCILE",null,clock.now());
        var loop=new JobRunnerLoop(runner,JobRunnerTest.properties(),mock(EventLogger.class));long start=System.nanoTime();
        try{loop.start();assertThat(sessionStarted.await(2,TimeUnit.SECONDS)).isTrue();while(!reports.find(reportId).orElseThrow().status().equals("READY")&&TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<15)Thread.sleep(50);assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");assertThat(sessionFinished.getCount()).isEqualTo(1);System.out.println("U6_REPORT_D elapsed_ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}finally{loop.stop();}
    }

    void resultStatus(UUID session,String status){db.jdbc().update("update core.core_session_result_state set job_status=:status where session_id=:id",Map.of("id",session,"status",status));}
    UUID practiceSession(){return reports.activities(reportId).stream().filter(a->a.activityType().equals("PRACTICE")).findFirst().orElseThrow().sessionId();}
    ResultJob nextAttempt(ResultJob job){var waiting=jobs.find(job.id()).orElseThrow();clock.advance(Duration.between(clock.now(),waiting.nextAttemptAt()));return runner.claim("REPORT").getFirst();}

    /** 실제 사례(JIRA-267 12:59): 세션 끝 15초 뒤 리포트를 만들면 그 활동이 아직 정리 중(RUNNING)이다. 정리가 끝날 때까지 미뤘다가 만든다. */
    @Test void settlingActivityDefersReportUntilResultIsDone(){
        var job=prepare(2,Duration.ofSeconds(60));var practice=practiceSession();resultStatus(practice,"RUNNING");
        runner.execute(job);
        var waiting=jobs.find(job.id()).orElseThrow();
        assertThat(waiting.status()).isEqualTo("REQUESTED");assertThat(waiting.lastErrorCode()).isNull();
        assertThat(waiting.nextAttemptAt()).isEqualTo(clock.now().plusSeconds(5));
        assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("GENERATING");assertThat(reports.sections(reportId)).isEmpty();
        resultStatus(practice,"DONE");
        runner.execute(nextAttempt(job));
        assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");
        assertThat(section(2).citations()).singleElement().satisfies(c->assertThat(c.sessionId()).isEqualTo(practice));
        assertThat(section(10).text()).doesNotContain("정리 중인 활동");
        assertThat(reports.activities(reportId)).allSatisfy(a->assertThat(a.resultStatus()).isEqualTo("DONE"));
    }
    /** 마지막 시도까지 정리가 끝나지 않으면 그때 있는 자료로 만들고 10항목에 정리 중 활동 수를 남긴다. */
    @Test void stillSettlingAtLastAttemptBuildsWithAvailableRecords(){
        var job=prepare(2,Duration.ofSeconds(60));var practice=practiceSession();resultStatus(practice,"RUNNING");
        runner.execute(job);var second=nextAttempt(job);runner.execute(second);var third=nextAttempt(job);
        assertThat(third.attemptCount()).isEqualTo(3);runner.execute(third);
        assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");
        assertThat(section(2).missing()).isTrue();assertThat(section(2).text()).isEqualTo("자료 부족");
        assertThat(section(10).text()).contains("정리 중인 활동 1개");
        assertThat(reports.activities(reportId).stream().filter(a->a.sessionId().equals(practice)).findFirst().orElseThrow().resultStatus()).isEqualTo("RUNNING");
    }
    /** 결과 접수 전(결과 머리 없음)도 기다리지만, 정리하지 못한(FAILED) 활동 · 결과 대상이 아닌(NONE) 활동은 기다리지 않는다. */
    @Test void failedOrIneligibleActivitiesAreNotAwaitedButUnacceptedOnesAre(){
        var job=prepare(2,Duration.ofSeconds(60));var practice=practiceSession();
        db.jdbc().update("delete from core.core_session_result_state where session_id=:id",Map.of("id",practice));
        runner.execute(job);
        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("REQUESTED");
        states.insert(practice,guardian,child,"PRACTICE",false,clock.now());
        runner.execute(nextAttempt(job));
        assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");
    }
    @Test void failedActivityIsNotAwaited(){
        var job=prepare(2,Duration.ofSeconds(60));resultStatus(practiceSession(),"FAILED");
        runner.execute(job);
        assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");
        assertThat(section(10).text()).contains("정리하지 못한 활동 1개");
    }

    kr.mujoong.core.account.DeletionRequestRepository requestDeletion(){
        var requests=new kr.mujoong.core.account.DeletionRequestRepository(db.jdbc());
        new kr.mujoong.core.account.AccountDeletionService(requests,mock(kr.mujoong.core.identity.ChildRepository.class),
            mock(kr.mujoong.core.identity.GuardianSessions.class),new GuardianWriteLock(db.jdbc()),db.jdbc(),tm,
            mock(EventLogger.class),clock).request(guardian);
        return requests;
    }
    @Test void realCreatedReportIsNotBuiltAfterDeletionRequestBeforeParticipants(){
        prepare(1,Duration.ofSeconds(60));
        UUID session=firstSession();var record=reader.find(session,guardian).orElseThrow().record();
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
        clearInvocations(reader);runner.execute(job);
        verify(reader,never()).find(any(),any());assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("ACCOUNT_DELETING");
        assertThat(reports.find(created.reportId()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(reports.sections(created.reportId())).isEmpty();
        assertThat(reports.activities(created.reportId())).hasSize(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void withdrawnActivityNeverSuppliesQuotesOrResultsEvenWhenDone(boolean done){
        var job=prepare(2,Duration.ofSeconds(60));var session=firstSession();
        var r=reader.find(session,guardian).orElseThrow().record();
        var withdrawn=new ActivityRecordRepository.StoredRecord(r.sessionId(),guardian,child,r.activityType(),r.finalStatus(),"CONSENT_WITHDRAWN",
            r.confirmed(),r.startedAt(),r.endedAt(),r.listedAt(),r.recordFrom(),r.recordTo(),
            r.transcriptJson().replace("같이 블록 쌓자","철회 비밀 내용"),r.gapsJson(),r.practiceJson(),r.snapshotHash(),r.recordedAt());
        when(reader.find(session,guardian)).thenReturn(Optional.of(new ResultRecordReader.Input(withdrawn,"ENDED",null,true)));
        db.jdbc().update("update core.core_activity_result set sentences_json='[{\"text\":\"철회 비밀 문장\"}]'::jsonb where session_id=:id",Map.of("id",session));
        if(!done)db.jdbc().update("update core.core_session_result_state set job_status='NONE' where session_id=:id",Map.of("id",session));
        runner.execute(job);
        assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("READY");
        assertThat(reports.sections(reportId).stream().flatMap(s->s.citations().stream())).noneMatch(c->c.sessionId().equals(session));
        assertThat(reports.sections(reportId)).allSatisfy(s->assertThat(s.text()).doesNotContain("철회 비밀 내용","철회 비밀 문장"));
        assertThat(section(10).text()).contains("동의가 철회되어 끝났어요 1회");
        assertThat(reports.activities(reportId).stream().filter(a->a.sessionId().equals(session)).findFirst().orElseThrow().resultStatus()).isEqualTo(done?"DONE":"NONE");
    }
    @Test void missingCurrentConsentStopsBeforeReportInputIsRead(){
        var job=prepare(1,Duration.ofSeconds(60));clearInvocations(reader);
        when(consent.check(guardian)).thenReturn(granted(false));
        runner.execute(job);verifyNoInteractions(reader);
        assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo("INTERNAL");
        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo("FAILED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void deletionOrWithdrawalAfterReadingPreventsPublishingReport(boolean deletion){
        var job=prepare(1,Duration.ofSeconds(60));var session=firstSession();var input=reader.find(session,guardian).orElseThrow();
        when(reader.find(session,guardian)).thenAnswer(call->{
            if(deletion)db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id",Map.of("id",guardian));
            else when(consent.check(guardian)).thenReturn(granted(false));
            return Optional.of(input);
        });
        runner.execute(job);
        assertThat(jobs.find(job.id()).orElseThrow().lastErrorCode()).isEqualTo(deletion?"ACCOUNT_DELETING":"INTERNAL");
        assertThat(reports.sections(reportId)).isEmpty();assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo("FAILED");
    }
}
