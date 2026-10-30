package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.record.ActivityRecordRepository;
import kr.mujoong.core.practicegoal.*;
import kr.mujoong.core.knowledge.StructureSignature;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import org.mockito.ArgumentCaptor;

class ScenarioContinuityTest {
    @Test void onlyActualCertainChildQuotesCanSupplyReflection() {
        var evidence=List.<Map<String,Object>>of(Map.of("utteranceSeq",1,"quote","블록을 했어"),
                Map.of("utteranceSeq",2,"quote","메모 내용"),Map.of("utteranceSeq",3,"quote","추측"),
                Map.of("utteranceSeq",4,"quote","없는 말"));
        var transcript=List.<Map<String,Object>>of(Map.of("seq",1,"speaker","CHILD","text","친구랑 블록을 했어","uncertain",false),
                Map.of("seq",2,"speaker","AI","text","메모 내용"),Map.of("seq",3,"speaker","CHILD","text","추측","uncertain",true));
        assertThat(PracticeContinuity.verifiedChildEvidence(evidence,transcript)).containsExactly("블록을 했어");
    }
    @Test void technicalFailureCannotBecomeEvidenceForMoreDrill() {
        var scene=Map.<String,Object>of("facts",List.of("PLAYBACK_FAILED","FOCUS_HELP"),"liveOutcome",Map.of("timing","AFTER_HELP"));
        assertThat(PracticeContinuity.support(scene,Map.of())).containsEntry("nextRoundStrategy","CHECK_INPUT_OR_PLAYBACK")
                .doesNotContainKey("observedTiming");
    }
    @Test void supportStrategiesDistinguishClarificationSceneExplanationAndCoachHelp() {
        for(var row:Map.of("REASK_RESOLVED","CONTEXTUAL_CLARIFICATION_WITHOUT_DRILL",
                "SITUATION_EXPLAINED","SIMPLER_CONTEXT_SAME_GOAL","FOCUS_HELP","FAMILIAR_CONTEXT_HELP_ONLY_IF_NEEDED").entrySet())
            assertThat(PracticeContinuity.support(Map.of("facts",List.of(row.getKey())),Map.of()))
                    .containsEntry("nextRoundStrategy",row.getValue());
    }
    @Test void prepracticeHistoryStaysVisibleWithoutTurningItIntoInSceneHelp() {
        var facts=List.of("PREPRACTICE_PROVIDED","PREPRACTICE_ATTEMPTED");
        var result=PracticeContinuity.support(Map.of("facts",facts,"liveOutcome",Map.of("timing","IMMEDIATE")),Map.of());
        assertThat(result).containsEntry("nextRoundStrategy","APPLY_AFTER_PREPRACTICE_NOT_BASELINE")
                .containsEntry("observedSupport",facts).containsEntry("observedTiming","IMMEDIATE");
    }
    private void record(StructuredFixture f,UUID session,UUID goal,String facts,String transcript) {
        var l=f.legacy;var now=l.clock.now();String practice=goal==null?null:JsonCodec.shared().write(Map.of("flowVersion","practice-conversation-v1",
                "scenes",List.of(Map.of("sceneRunId",UUID.randomUUID(),"goalRef",goal,"sceneKind","BASE","startedAt",now,
                        "facts",List.of(facts),"liveOutcome",Map.of("timing","AFTER_REASK")))));
        var devices=l.db.jdbc().queryForList("select id from core.core_device where guardian_id=:g",Map.of("g",l.guardian),UUID.class);
        UUID device=devices.isEmpty()?l.db.trackDevice(UUID.randomUUID()):devices.getFirst();
        var p=Map.of("g",l.guardian,"c",l.child,"s",session,"d",device,"code",DbTestSupport.randomCode(),"hash",DbTestSupport.randomHash(),"key",session.toString());
        if(devices.isEmpty())l.db.jdbc().update("insert into core.core_device(id,guardian_id,registration_code,code_issued_at,code_expires_at,bootstrap_secret_hash,registration_status,registered_at,created_at,updated_at) values(:d,:g,:code,now(),now()+interval '1 hour',:hash,'REGISTERED',now(),now(),now())",p);
        l.db.jdbc().update("insert into core.core_activity(session_id,guardian_id,child_id,device_id,activity_type,status,start_idempotency_key,request_hash,requested_at,created_at,updated_at) values(:s,:g,:c,:d,'FREE_TALK','ENDED',:key,:hash,now(),now(),now())",p);
        l.db.inTx(s->{new ActivityRecordRepository(l.db.jdbc()).insertIfAbsent(new ActivityRecordRepository.NewRecord(session,l.guardian,l.child,
                goal==null?"FREE_TALK":"PRACTICE","ENDED","CHILD_STOP",true,now,now,now,now,now,transcript,"[]",practice,"[]",null,
                UUID.randomUUID(),1,"sha256:"+DbTestSupport.randomHash()),now);return null;});
    }
    @Test void nextRoundReadsOnlyTheSameChildAndGoalAndPassesSupportToGenerationAndReview() {
        try(var f=new StructuredFixture()) {
            record(f,UUID.randomUUID(),f.legacy.goal,"REASK_RESOLVED","[]");
            record(f,UUID.randomUUID(),UUID.randomUUID(),"FOCUS_HELP","[]");
            f.legacy.clock.advance(Duration.ofSeconds(1));f.legacy.service.generate(f.legacy.original());
            var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(f.provider).generate(calls.capture());verify(f.provider).review(calls.capture());
            assertThat(calls.getAllValues()).allSatisfy(call->{
                assertThat((List<?>)call.input().get("practiceContinuity")).hasSize(1);
                assertThat(JsonCodec.shared().write(call.input().get("practiceContinuity"))).contains("CONTEXTUAL_CLARIFICATION_WITHOUT_DRILL")
                        .doesNotContain("FOCUS_HELP",f.legacy.child.toString(),f.legacy.guardian.toString());
                assertThat(call.input()).doesNotContainKey("reflectionContext");
            });
            assertThat(f.legacy.repository.continuity(UUID.randomUUID(),f.legacy.child,f.legacy.goal,f.legacy.clock.now(),Duration.ofMillis(500)).supportHistory()).isEmpty();
        }
    }
    @Test void pinnedCandidateReflectionUsesVerifiedSourceRecordAndRespectsOwnerBoundary() {
        try(var f=new StructuredFixture()) {
            UUID source=UUID.randomUUID();String lines=JsonCodec.shared().write(List.of(Map.of("seq",1,"speaker","CHILD","text","친구랑 블록을 했어","uncertain",false)));
            record(f,source,null,"",lines);
            var goals=new GoalCandidateRepository(f.legacy.db.jdbc());
            try {
                UUID selection=f.legacy.db.inTx(s->{var candidate=goals.register(f.legacy.child,source,"synthetic-continuity",
                        new GoalCandidateRepository.Draft("놀이 의견 묻기","블록을 했어","상대가 원하는 놀이를 묻는다",
                                new FourElements("놀이를 고르는 중","친구","상대 의견 확인","원하는 놀이 묻기"),"NONE","아이 발화 근거",
                                List.of(new GoalCandidateRepository.Evidence(1,"블록을 했어")),
                                new StructureSignature("PRODUCE","상대 의견을 확인한다","상대의 의도 1층","YES","YES")),f.legacy.clock.now());
                    return goals.select(f.legacy.guardian,candidate,f.legacy.clock.now());});
                var context=f.legacy.repository.continuity(f.legacy.guardian,f.legacy.child,selection,f.legacy.clock.now().plusSeconds(1),Duration.ofMillis(500));
                assertThat(context.childEvidence()).containsExactly("블록을 했어");
                assertThat(f.legacy.repository.continuity(UUID.randomUUID(),f.legacy.child,selection,f.legacy.clock.now().plusSeconds(1),Duration.ofMillis(500)).childEvidence()).isEmpty();
            } finally {goals.deleteGuardian(f.legacy.guardian);}
        }
    }
}
