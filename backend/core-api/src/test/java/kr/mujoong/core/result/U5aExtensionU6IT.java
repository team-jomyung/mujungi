package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.practice.*;
import org.junit.jupiter.api.*;
@Tag("u6") @Tag("db")
class U5aExtensionU6IT {
    DbTestSupport db;Instant now=Instant.parse("2026-10-14T00:00:00Z");
    @BeforeEach void open(){db=DbTestSupport.coreApp();}
    @AfterEach void close(){db.close();}
    @Test void scenarioProjectionOmitsPartnerRole(){var repo=mock(ScenarioRepository.class);UUID id=UUID.randomUUID(),g=UUID.randomUUID(),s=UUID.randomUUID();when(repo.require(id)).thenReturn(new ScenarioRepository.Stored(new Scenario(id,"ORIGINAL","FRIEND","기회","상황","비공개 상대역","시작",null),g,UUID.randomUUID(),s,UUID.randomUUID(),"key",1,"ISSUED"));var value=new ScenarioLookup(repo).get(id,g,s);assertThat(value.scenarioText()).isEqualTo("상황");assertThat(value.triggerScript()).isEqualTo("시작");assertThat(kr.mujoong.contract.json.JsonCodec.shared().write(value)).doesNotContain("partnerRole","비공개 상대역");}
    @Test void scenarioRejectsAnotherOwner(){var repo=mock(ScenarioRepository.class);UUID id=UUID.randomUUID(),s=UUID.randomUUID();when(repo.require(id)).thenReturn(new ScenarioRepository.Stored(null,UUID.randomUUID(),UUID.randomUUID(),s,null,"key",1,"ISSUED"));assertThatThrownBy(()->new ScenarioLookup(repo).get(id,UUID.randomUUID(),s)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void retiredDefinitionRemainsReadable(){db.inRolledBackTx(t->{String key=definition("RETIRED");var repository=new KnowledgeJdbcRepository(db.jdbc());var knowledge=new PragmaticsKnowledge(repository,mock(kr.mujoong.contract.time.MujoongClock.class),new KnowledgeProperties(false),Optional.empty());assertThat(new BehaviorDefinitionLookup(knowledge,repository).get(key,1).status()).isEqualTo("RETIRED");return null;});}
    @Test void activeDefinitionsExcludeDraftAndRetired(){db.inRolledBackTx(t->{String active=definition("ACTIVE"),retired=definition("RETIRED"),draft=definition("DRAFT");assertThat(new KnowledgeJdbcRepository(db.jdbc()).activeDefinitions()).extracting(BehaviorDefinition::behaviorKey).contains(active).doesNotContain(retired,draft);return null;});}
    @Test void onlyApprovedSupportIsReadWithNullableTitle(){db.inRolledBackTx(t->{String key=UUID.randomUUID().toString();for(String status:List.of("DRAFT","APPROVED","RETIRED"))db.jdbc().update("insert into core.core_support_item(id,behavior_key,condition_text,content_text,limitation_text,status,created_at,updated_at) values(:id,:key,'조건','내용','한계',:status,:now,:now)",kr.mujoong.core.common.db.ClockParams.at(now).addValue("id",UUID.randomUUID()).addValue("key",key).addValue("status",status));var library=new SupportLibrary(db.jdbc());assertThat(library.approvedByBehaviorKeys(List.of())).isEmpty();assertThat(library.approvedByBehaviorKeys(List.of(key))).singleElement().satisfies(tip->{assertThat(tip.title()).isNull();assertThat(tip.content()).isEqualTo("내용");});return null;});}
    @Test void resultCandidateIncludesEvidence(){db.inRolledBackTx(t->{UUID guardian=db.insertGuardian("ACTIVE"),child=db.insertChild(guardian),session=UUID.randomUUID();String key=definition("ACTIVE");var repo=new PracticeGoalRepository(db.jdbc());repo.register(child,session,new CandidateRegistry.Candidate(key,1,"이야기를 나눴어요",List.of(new CandidateRegistry.Evidence(2,"CHILD_RECALL"))),"목표",now);assertThat(repo.resultCandidates(child,session)).singleElement().satisfies(candidate->{assertThat(candidate.evidence()).containsExactly(new CandidateRegistry.Evidence(2,"CHILD_RECALL"));assertThat(candidate.selected()).isFalse();assertThat(candidate.goalSelectionId()).isNull();});assertThat(repo.resultCandidates(UUID.randomUUID(),session)).isEmpty();return null;});}
    String definition(String status){String key=UUID.randomUUID().toString();db.jdbc().update("insert into core.core_behavior_definition(id,behavior_key,version,display_name,behavior_definition_text,opportunity_condition,scenario_condition,judgment_criteria,status,created_at) values(:id,:key,1,'목표','정의','기회','상황','기준',:status,:now)",kr.mujoong.core.common.db.ClockParams.at(now).addValue("id",UUID.randomUUID()).addValue("key",key).addValue("status",status));return key;}
}
