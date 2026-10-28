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
    /** 진단 코드: 빠진 후보마다 검사 이름과 칸만 돌려준다(원문 없음). */
    @Test void reportsDropReasonAndFieldForEachRejectedCandidate(){
        var number=draft();number.put("reasonText","3번 발화에서 대답했어요.");
        var quote=draft();quote.put("displayName","다른 이름");quote.put("evidence",List.of(Map.of("utteranceSeq",1,"quote","같이")));
        var seed=draft();seed.put("skillRef","seed:S999");
        var forbidden=draft();forbidden.put("targetExpression","자폐 진단을 받았어요.");
        var empty=draft();empty.put("evidence",List.of());
        var ok=draft();
        var checked=validator.check(List.of(number,quote,seed,forbidden,empty,ok,ok),Set.of(),Map.of(1L,"같이 할래"));
        assertThat(checked.kept()).hasSize(1);assertThat(checked.indexes()).containsExactly(5);
        assertThat(checked.dropped()).containsExactly(new GoalCandidateValidator.Drop(0,"NUMBER_GUARD","REASON_TEXT"),new GoalCandidateValidator.Drop(1,"QUOTE_MISMATCH","EVIDENCE"),
                new GoalCandidateValidator.Drop(2,"SKILL_REF_UNKNOWN","SKILL_REF"),new GoalCandidateValidator.Drop(3,"FORBIDDEN_DICTIONARY","TARGET_EXPRESSION"),
                new GoalCandidateValidator.Drop(4,"NO_EVIDENCE","EVIDENCE"),new GoalCandidateValidator.Drop(6,"DUPLICATE","NONE"));
    }
    @Test void candidatesBeyondTheCapAreReportedAsOverLimit(){
        List<Map<String,Object>> drafts=new ArrayList<>();for(int i=0;i<4;i++){var d=draft();d.put("displayName","이름"+"가나다라".charAt(i));drafts.add(d);}
        var checked=validator.check(drafts,Set.of(),Map.of(1L,"같이 할래"));
        assertThat(checked.kept()).hasSize(3);assertThat(checked.dropped()).containsExactly(new GoalCandidateValidator.Drop(3,"OVER_LIMIT","NONE"));
    }
    @Test void lengthDropNamesTheFourElementsField(){var d=draft();d.put("fourElements",Map.of("situation","놀이","partner","친구","intent","의도"));
        assertThat(validator.check(List.of(d),Set.of(),Map.of(1L,"같이 할래")).dropped()).containsExactly(new GoalCandidateValidator.Drop(0,"LENGTH","EXPRESSION"));}
}
