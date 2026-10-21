package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.*;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.practice.*;
import kr.mujoong.core.record.*;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.textai.*;
import kr.mujoong.core.web.support.*;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@Tag("practice-legacy") @Tag("db")
class PracticeStartApiIT {
    DbTestSupport db; CoreWebApp app; ContractStubServer realtime;
    BusinessFixtures.Family family; WebFixtures.Guardian guardian; UUID goal; TextAiGateway ai;
    final JsonCodec json=JsonCodec.shared();
    @TestConfiguration(proxyBeanMethods=false)
    static class FakeAi { @Bean @Primary TextAiGateway practiceAi() { return mock(TextAiGateway.class); } }
    @BeforeEach void setup() {
        db=DbTestSupport.coreApp(); BusinessFixtures.applyReferenceSeed(db.postgres());
        String token=TestSecrets.internalServiceToken();realtime=WebFixtures.realtime(token);
        app=CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).source(FakeAi.class).start();
        var fixtures=new BusinessFixtures(db);family=fixtures.family(app.clock().now().minusSeconds(30));
        fixtures.registeredDevice(family.guardianId(),ConnectionState.CONNECTED,app.clock().now(),app.clock().now());
        guardian=WebFixtures.guardianFor(app,family.guardianId());
        UUID candidate=db.inTx(s->app.bean(PracticeGoalRepository.class).register(family.childId(),UUID.randomUUID(),
                new CandidateRegistry.Candidate("ACCEPT_STOP",1,"친구의 말을 듣고 대답한 기록",List.of(new CandidateRegistry.Evidence(1,"CHILD_RECALL"))),
                "친구가 그만하자고 할 때 받아들이기",app.clock().now()));
        goal=app.bean(GoalSelectionService.class).add(guardian.id(),candidate).goalSelectionId();
        ai=app.bean(TextAiGateway.class);
        when(ai.generateScenario(any(),any())).thenAnswer(c->{ScenarioPrompt p=c.getArgument(0);
            return new GeneratedScenario(UUID.randomUUID()+" 장난감 놀이. "+p.opportunityCondition()+" 이제 그만할래.",
                    "이제 그만할래.",p.relationType(),p.opportunityCondition(),"test-model","test-prompt");});
    }
    @AfterEach void cleanup() {
        if(app!=null) {
            if(family!=null) app.bean(PracticeDeletionParticipant.class).deleteByGuardian(family.guardianId(),UUID.randomUUID());
            app.close();
        }
        if(realtime!=null) realtime.close();
        if(db!=null) { if(family!=null) WebFixtures.deleteSessions(db,family.guardianId());db.close(); }
    }
    String body(UUID key, UUID selected) { return json.write(Map.of("idempotencyKey",key,"goalSelectionIds",List.of(selected))); }
    CoreWebApp.Result post(UUID key) {return app.post("/api/activities/practice").session(guardian.session()).csrf(guardian.csrf()).json(body(key,goal)).send();}
    UUID started(UUID key) {var response=post(key);assertThat(response.status()).as(response.body()).isEqualTo(202);return UUID.fromString((String)response.json().get("sessionId"));}
    @Test void authenticatedCsrfStartSendsRealPracticePackageAndReplaysWithoutSecondGeneration() {
        UUID key=UUID.randomUUID();
        assertThat(app.post("/api/activities/practice").json(body(key,goal)).send().status()).isEqualTo(401);
        assertThat(app.post("/api/activities/practice").session(guardian.session()).json(body(key,goal)).send().status()).isEqualTo(403);
        UUID session=started(key);var replay=post(key);
        assertThat(replay.json()).containsOnlyKeys("sessionId","status","goalCount").containsEntry("sessionId",session.toString()).containsEntry("goalCount",1);
        assertThat(replay.header("Cache-Control")).isEqualTo("no-store");
        assertThat(replay.body()).doesNotContain("judgmentCriteria","scenarioText","behaviorKey");
        verify(ai,times(1)).generateScenario(any(),any());
        assertThat(realtime.requests("POST",WebFixtures.RT_START)).hasSize(1);
        var sent=realtime.requests("POST",WebFixtures.RT_START).getFirst().json();
        assertThat(sent.path("activityType").asString()).isEqualTo("PRACTICE");
        assertThat(sent.path("practice").path("goals").get(0).path("firstScenario").path("scenarioId").asString()).isNotBlank();
        assertThat(app.bean(PracticePreparationRepository.class).findSession(session).orElseThrow().goals()).hasSize(1);
    }
    @Test void firstTwoInvalidOutputsAre422NoRequestedCopyAndPermanentForTheKey() {
        doReturn(new GeneratedScenario("잘못된 상황","없는 트리거","PEER","기회","test-model","test-prompt")).when(ai).generateScenario(any(),any());
        UUID key=UUID.randomUUID();assertThat(post(key).status()).isEqualTo(422);assertThat(post(key).code()).isEqualTo("SCENARIO_GEN_FAILED");
        verify(ai,times(2)).generateScenario(any(),any());assertThat(realtime.requests("POST",WebFixtures.RT_START)).isEmpty();
        assertThat(app.bean(PracticePreparationRepository.class).find(guardian.id(),key).orElseThrow().status()).isEqualTo("FAILED");
    }
    @Test void unavailableAiIs503AndSameKeyRecoversWithoutChangingFrozenSession() {
        doThrow(new TextAiException(TextAiException.Reason.UNAVAILABLE)).when(ai).generateScenario(any(),any());
        UUID key=UUID.randomUUID();assertThat(post(key).code()).isEqualTo("SERVICE_NOT_READY");
        var original=app.bean(PracticePreparationRepository.class).find(guardian.id(),key).orElseThrow();
        doAnswer(c->{ScenarioPrompt p=c.getArgument(0);return new GeneratedScenario("놀이. "+p.opportunityCondition()+" 그만할래.","그만할래.","PEER",p.opportunityCondition(),"m","p");}).when(ai).generateScenario(any(),any());
        assertThat(started(key)).isEqualTo(original.sessionId());
    }
    @Test void ownershipMalformedBodyAndChangedSelectionCannotStartAnotherActivity() {
        UUID key=UUID.randomUUID();
        assertThat(app.post("/api/activities/practice").session(guardian.session()).csrf(guardian.csrf()).json("{}").send().status()).isEqualTo(400);
        assertThat(app.post("/api/activities/practice").session(guardian.session()).csrf(guardian.csrf()).json(body(key,UUID.randomUUID())).send().status()).isEqualTo(404);
        started(key);
        var changed=app.post("/api/activities/practice").session(guardian.session()).csrf(guardian.csrf()).json(body(key,UUID.randomUUID())).send();
        assertThat(changed.status()).isEqualTo(409);assertThat(changed.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }
    @Test void actualSessionAdapterRejectsPreparingThenGeneratesC10OnlyAfterActive() {
        UUID session=started(UUID.randomUUID());
        String request=json.write(Map.of("sessionId",session,"goalSelectionId",goal,"kind","NEW_SCENARIO",
                "previousScenarioId",app.bean(PracticePreparationRepository.class).findSession(session).orElseThrow().firstScenarioId()));
        assertThat(app.post("/internal/practice/scenarios").internalToken().json(request).send().code()).isEqualTo("SESSION_NOT_ACTIVE");
        app.bean(ActivityCopyRepository.class).applyStateChange(new ActivityCopyRepository.StateChange(session,SessionStatus.ACTIVE,2,null,null,false,app.clock().now()),app.clock().now());
        var generated=app.post("/internal/practice/scenarios").internalToken().json(request).send();
        assertThat(generated.status()).as(generated.body()).isEqualTo(200);assertThat(generated.json()).containsEntry("kind","NEW_SCENARIO");
    }
    @Test void committedRecordAndRecoveryApplyOnlyProvenExposureOnce() {
        UUID session=started(UUID.randomUUID());var p=app.bean(PracticePreparationRepository.class).findSession(session).orElseThrow();
        Instant now=app.clock().now();String practice=json.write(Map.of("goals",List.of(Map.of("scenarios",List.of(Map.of("scenarioId",p.firstScenarioId(),"exposedAt",now))))));
        db.inTx(s->{app.bean(ActivityRecordRepository.class).insertIfAbsent(new ActivityRecordRepository.NewRecord(session,guardian.id(),family.childId(),
                "PRACTICE","ENDED","CHILD_STOP",true,now,now,now,now,now,"[]","[]",practice,"[]","{}",UUID.randomUUID(),1,
                "sha256:"+json.sha256OfCanonical(practice)),now);return null;});
        var reconciler=app.bean(PracticeExposureReconciler.class);reconciler.runOnce();reconciler.runOnce();
        assertThat(app.bean(ScenarioRepository.class).require(p.firstScenarioId()).status()).isEqualTo("EXPOSED");
        assertThat(db.jdbc().queryForObject("select exposure_reconciled_at is not null from core.core_practice_preparation where session_id=:id",Map.of("id",session),Boolean.class)).isTrue();
        db.inTx(s->{app.context().publishEvent(new ActivityRecorded(session,guardian.id(),family.childId(),"PRACTICE","ENDED",now));return null;});
        assertThat(app.bean(ScenarioRepository.class).require(p.firstScenarioId()).status()).isEqualTo("EXPOSED");
    }
    /** 새 연습 흐름 기록(practice-record-v2, 공유 고정 자료 모양)의 장면 시작으로 실제 DB 정산을 한다. */
    @Test
    void newFlowRecordReconcilesEachIssuedScenarioOnceAtItsEarliestSceneStart() throws Exception {
        UUID session = started(UUID.randomUUID());
        var p = app.bean(PracticePreparationRepository.class).findSession(session).orElseThrow();
        // 장면 3의 대본: 세션을 ACTIVE로 바꾼 뒤 같은 세션에서 내부 경로로 실제 발급한 둘째 대본
        app.bean(ActivityCopyRepository.class).applyStateChange(new ActivityCopyRepository.StateChange(session, SessionStatus.ACTIVE, 2,
                null, null, false, app.clock().now()), app.clock().now());
        String request = json.write(Map.of("sessionId", session, "goalSelectionId", goal, "kind", "NEW_SCENARIO",
                "previousScenarioId", p.firstScenarioId()));
        var generated = app.post("/internal/practice/scenarios").internalToken().json(request).send();
        assertThat(generated.status()).as(generated.body()).isEqualTo(200);
        UUID second = UUID.fromString((String) generated.json().get("scenarioId"));

        Instant now = app.clock().now();
        Instant base = now.minusSeconds(300);
        Instant variant = now.minusSeconds(170);
        Instant next = now.minusSeconds(5);
        @SuppressWarnings("unchecked")
        Map<String, Object> record = json.read(RecordedScenarioExposuresTest.sharedRecordFixture(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) record.get("scenes");
        // 고정 자료의 c01(BASE · VARIANT)은 첫 대본, c02는 둘째 대본으로 바꾼다. 시각은 지금 이전이어야 정산된다
        scenes.get(0).putAll(Map.of("scenarioRef", p.firstScenarioId().toString(), "startedAt", base.toString()));
        scenes.get(1).putAll(Map.of("scenarioRef", p.firstScenarioId().toString(), "startedAt", variant.toString()));
        scenes.get(2).putAll(Map.of("scenarioRef", second.toString(), "startedAt", next.toString()));
        String practice = json.write(record);
        assertThat(WebFixtures.schemaErrors("practice-record-v2.schema.json", practice)).isEmpty();
        db.inTx(s -> {
            app.bean(ActivityRecordRepository.class).insertIfAbsent(new ActivityRecordRepository.NewRecord(session, guardian.id(),
                    family.childId(), "PRACTICE", "ENDED", "CHILD_STOP", true, now, now, now, now, now, "[]", "[]", practice, "[]", "{}",
                    UUID.randomUUID(), 1, "sha256:" + json.sha256OfCanonical(practice)), now);
            return null;
        });

        var reconciler = app.bean(PracticeExposureReconciler.class);
        try (var output = AppOutputCapture.attach()) {
            reconciler.runOnce();
            assertThat(db.jdbc().queryForObject("select exposure_reconciled_at is not null from core.core_practice_preparation "
                    + "where session_id=:id", Map.of("id", session), Boolean.class)).as("첫 주기에 정산된다").isTrue();
            reconciler.runOnce();
            assertThat(output.lines()).noneMatch(line -> line.contains("PracticeExposureReconciliationFailed"));
        }

        var scenarios = app.bean(ScenarioRepository.class);
        assertThat(scenarios.require(p.firstScenarioId()).status()).isEqualTo("EXPOSED");
        assertThat(scenarios.require(second).status()).isEqualTo("EXPOSED");
        String exposedAt = "select exposed_at from core.core_scenario where id=:id";
        assertThat(db.jdbc().queryForObject(exposedAt, Map.of("id", p.firstScenarioId()), java.time.OffsetDateTime.class).toInstant())
                .as("같은 대본은 가장 이른 장면 시작 시각").isEqualTo(base);
        assertThat(db.jdbc().queryForObject(exposedAt, Map.of("id", second), java.time.OffsetDateTime.class).toInstant()).isEqualTo(next);
        var recent = scenarios.recentExposed(guardian.id(), family.childId(), app.clock().now(), java.time.Duration.ofSeconds(5));
        assertThat(recent).extracting(ScenarioRepository.ExposureText::scenarioText).containsExactlyInAnyOrder(
                scenarios.require(p.firstScenarioId()).scenario().scenarioText(), scenarios.require(second).scenario().scenarioText());
    }
    @Test void deletionRemovesPreparationAndLateGenerationCannotRecreateIt() {
        doAnswer(c->{
            db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id",Map.of("id",guardian.id()));
            ScenarioPrompt p=c.getArgument(0);return new GeneratedScenario("놀이. "+p.opportunityCondition()+" 그만할래.","그만할래.","PEER",p.opportunityCondition(),"m","p");
        }).when(ai).generateScenario(any(),any());
        assertThat(post(UUID.randomUUID()).code()).isEqualTo("ACCOUNT_DELETION_IN_PROGRESS");
        var deleted=app.bean(PracticeDeletionParticipant.class).deleteByGuardian(guardian.id(),UUID.randomUUID());
        assertThat(deleted.get("practicePreparations")).isEqualTo(1);
        assertThat(app.bean(PracticeDeletionParticipant.class).deleteByGuardian(guardian.id(),UUID.randomUUID()).get("practicePreparations")).isZero();
        assertThat(realtime.requests("POST",WebFixtures.RT_START)).isEmpty();
    }

    @Test void concurrentSameKeyHasOneFrozenPreparationAndDoesNotHoldGuardianLockAcrossAi() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(c->{ScenarioPrompt p=c.getArgument(0);entered.countDown();
            if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("AI 대역 해제 실패");
            return new GeneratedScenario("함께 놀이. "+p.opportunityCondition()+" 이제 그만할래.","이제 그만할래.","PEER",p.opportunityCondition(),"m","p");
        }).when(ai).generateScenario(any(),any());
        UUID key=UUID.randomUUID();var first=db.start(()->post(key));
        try {
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var duplicate=post(key);assertThat(duplicate.status()).isEqualTo(503);assertThat(duplicate.code()).isEqualTo("SERVICE_NOT_READY");
            app.bean(GoalSelectionService.class).favorite(guardian.id(),goal,true);
        } finally { release.countDown(); }
        assertThat(DbTestSupport.await(first).status()).isEqualTo(202);
        verify(ai,times(1)).generateScenario(any(),any());
        assertThat(db.jdbc().queryForObject("select count(*) from core.core_practice_preparation where guardian_id=:id",Map.of("id",guardian.id()),Integer.class)).isEqualTo(1);
    }
}
