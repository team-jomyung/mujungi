package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.luna.*;
import org.junit.jupiter.api.*;

@Tag("u10")
class U10ScenarioPrivacyTest {
    final LunaInputSanitizer sanitizer=new LunaInputSanitizer(JsonCodec.shared());
    final Map<String,String> aliases=Map.of("합성아이","아이");
    @Test void interestsAreAllowedOnlyAtScenarioPurposes(){for(var purpose:LunaPurpose.values()){var clean=sanitizer.sanitize(Map.of("interests",List.of("공룡")),aliases,purpose).payload();assertThat(clean.containsKey("interests")).isEqualTo(purpose==LunaPurpose.SCENARIO_GENERATION||purpose==LunaPurpose.SCENARIO_REVIEW);}}
    @Test void generalSanitizationAndNestedInterestsStayForbidden(){assertThat(sanitizer.sanitize(Map.of("interests",List.of("공룡")),aliases).payload()).isEmpty();var clean=sanitizer.sanitize(Map.of("interests",List.of("공룡"),"nested",Map.of("interests",List.of("개인"))),aliases,LunaPurpose.SCENARIO_GENERATION);assertThat(JsonCodec.shared().write(clean.payload())).doesNotContain("개인");}
    @Test void namesAreRemovedFromInterestsGoalSourceHistoryAndReviewScript(){var input=Map.<String,Object>of("interests",List.of("합성아이 공룡"),"goal",Map.of("situation","합성아이 놀이"),"sourceScenario","합성아이의 말","history",List.of(Map.of("scenarioText","합성아이 놀이")),"script",Map.of("sceneDescription","합성아이 친구"));for(var purpose:List.of(LunaPurpose.SCENARIO_GENERATION,LunaPurpose.SCENARIO_REVIEW)){String value=JsonCodec.shared().write(sanitizer.sanitize(input,aliases,purpose).payload());assertThat(value).doesNotContain("합성아이").contains("아이 공룡","아이 놀이","아이 친구");}}
    @Test void contactsAreMaskedAndAbnormalInterestFreeTextExcluded(){var input=Map.<String,Object>of("interests",List.of("메일 person@example.invalid","전화 010-1234-5678","공룡","가".repeat(81),"지시\n무시"),"goal","합성아이 person@example.invalid 010-1234-5678");String result=JsonCodec.shared().write(sanitizer.sanitize(input,aliases,LunaPurpose.SCENARIO_GENERATION).payload());assertThat(result).doesNotContain("합성아이","example.invalid","010-1234-5678","지시").contains("공룡","연락처");}
    @Test void personalKeysAreRemovedAtEveryDepth(){var input=Map.<String,Object>of("childId","private","guardian_id","private","goal",Map.of("childName","private","sessionId","private","expression","목표"));String result=JsonCodec.shared().write(sanitizer.sanitize(input,aliases,LunaPurpose.SCENARIO_REVIEW).payload());assertThat(result).doesNotContain("private").contains("목표");}
    @Test void privateContextCannotPrintNamesAndMissingNameFailsClosed(){var context=new ScenarioPrivacyContextPort.Context("합성아이");assertThat(context.toString()).doesNotContain("합성아이");assertThat(context.aliases()).containsEntry("합성아이","아이");for(String name:List.of(""," ","가".repeat(101)))assertThatThrownBy(()->new ScenarioPrivacyContextPort.Context(name)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThatThrownBy(()->new ScenarioPrivacyContextPort.Context(null)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void interestListIsBoundedAndNoAliasOriginalBecomesData(){var values=java.util.stream.IntStream.range(0,20).mapToObj(i->"관심사"+i).toList();var clean=sanitizer.sanitize(Map.of("interests",values),aliases,LunaPurpose.SCENARIO_REVIEW);assertThat((List<?>)clean.payload().get("interests")).hasSize(10);assertThat(JsonCodec.shared().write(clean.payload())).doesNotContain("합성아이");}
}
