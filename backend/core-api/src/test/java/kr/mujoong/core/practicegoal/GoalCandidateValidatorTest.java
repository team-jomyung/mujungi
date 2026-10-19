package kr.mujoong.core.practicegoal;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import org.junit.jupiter.api.*;
@Tag("goal-result")
class GoalCandidateValidatorTest {
    GoalCandidateValidator validator=new GoalCandidateValidator(new ForbiddenDictionary());
    Map<String,Object> draft(){return new LinkedHashMap<>(Map.of("displayName","물어보기","displayEvidence","합성 놀이 장면","targetExpression","함께 해도 되는지 묻기","fourElements",Map.of("situation","친구와 놀이","partner","친구","intent","놀이 참여","expression","함께 해도 돼?"),"skillRef","NONE","reasonText","함께 놀이한 장면이에요.","evidence",List.of(Map.of("utteranceSeq",1,"quote","같이 할래"))));}
    List<GoalCandidateRepository.Draft> validate(Map<String,Object> d){return validator.validate(List.of(d),Set.of("seed:S1"),Map.of(1L,"같이 할래"));}
    @Test void acceptsGroundedNone(){assertThat(validate(draft())).hasSize(1);}
    @Test void rejectsLongName(){var d=draft();d.put("displayName","가".repeat(13));assertThat(validate(d)).isEmpty();}
    @Test void rejectsLongDisplayEvidence(){var d=draft();d.put("displayEvidence","가".repeat(41));assertThat(validate(d)).isEmpty();}
    @Test void rejectsMissingFourElements(){var d=draft();d.put("fourElements",Map.of("situation","놀이"));assertThat(validate(d)).isEmpty();}
    @Test void rejectsUnknownSeed(){var d=draft();d.put("skillRef","seed:S999");assertThat(validate(d)).isEmpty();}
    @Test void acceptsKnownSeed(){var d=draft();d.put("skillRef","seed:S1");assertThat(validate(d)).singleElement().extracting(GoalCandidateRepository.Draft::skillRef).isEqualTo("seed:S1");}
    @Test void requiresExactAnonymizedQuote(){var d=draft();d.put("evidence",List.of(Map.of("utteranceSeq",1,"quote","같이")));assertThat(validate(d)).isEmpty();}
    @Test void capsAndDeduplicatesValidCandidates(){var d=draft();assertThat(validator.validate(List.of(d,d,d,d),Set.of(),Map.of(1L,"같이 할래"))).hasSize(1);}
    @Test void rejectsForbiddenCandidateText(){var d=draft();d.put("reasonText","자폐 진단을 받았어요.");assertThat(validate(d)).isEmpty();}
    @Test void invalidAbstractionDoesNotInventSignature(){var d=draft();d.put("structureSignature",Map.of("targetSkill","UNKNOWN"));assertThat(validate(d)).singleElement().extracting(GoalCandidateRepository.Draft::structureSignature).isNull();}
}
