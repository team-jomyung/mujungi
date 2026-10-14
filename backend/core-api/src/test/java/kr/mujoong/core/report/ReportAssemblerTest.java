package kr.mujoong.core.report;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
@Tag("u6") class ReportAssemblerTest {
    ReportAssembler assembler=new ReportAssembler();UUID goal=UUID.randomUUID();
    @Test void overviewCountsTypesAndRecordedDuration(){var sections=assembler.assemble(ReportTestData.report(),List.of(ReportTestData.input(1,"FREE_TALK",3,"DONE","FULL"),ReportTestData.practice(2,"DONE","FULL",goal,List.of())));assertThat(sections.get(1).text()).contains("자유대화 1회","연습 1회","총 4분");}
    @Test void sceneSectionsContainOnlyChips(){var sections=assembler.assemble(ReportTestData.report(),List.of(ReportTestData.practice(1,"DONE","FULL",goal,List.of())));assertThat(sections.get(3).text()).contains("장면 1","부탁하기").doesNotContain("functional","none","비공개 근거");}
    @Test void oneRecordHasNoComparison(){assertThat(assembler.assemble(ReportTestData.report(),List.of(ReportTestData.practice(1,"DONE","FULL",goal,List.of()))).get(9).missing()).isTrue();}
    @Test void twoRecordsListChronologicalScenes(){var result=assembler.assemble(ReportTestData.report(),List.of(ReportTestData.practice(1,"DONE","FULL",goal,List.of()),ReportTestData.practice(2,"DONE","FULL",UUID.randomUUID(),List.of())));assertThat(result.get(9).missing()).isFalse();assertThat(result.get(9).text()).doesNotContain("향상","성공률");}
    @Test void pendingRecordsOnlyContributeRecordSections(){var result=assembler.assemble(ReportTestData.report(),List.of(ReportTestData.practice(1,"RUNNING","FULL",goal,List.of())));assertThat(result.get(3).missing()).isTrue();assertThat(result.get(9).missing()).isTrue();assertThat(result.get(7).text()).contains("필요한 것을 부탁함");}
    @Test void helpExampleExcludesAnswerSentences(){var cues=List.of(Map.<String,Object>of("consentResponse","ACCEPTED","containsAnswerSentence",true,"deliveredText","금지된 정답 예"),Map.<String,Object>of("consentResponse","DECLINED","containsAnswerSentence",false,"deliveredText","천천히 생각해 봐요"));var result=assembler.assemble(ReportTestData.report(),List.of(ReportTestData.practice(1,"DONE","FULL",goal,cues)));assertThat(result.get(8).text()).contains("도움 2회","동의함 1회","거절함 1회","천천히 생각해 봐요").doesNotContain("금지된 정답 예");}
    @Test void childSectionContainsOnlyNameAndAge(){assertThat(assembler.assemble(ReportTestData.report(),List.of()).get(4).text()).isEqualTo("하늘 · 7세");}
    @Test void levelRequiresAllDoneAndFull(){assertThat(assembler.level(List.of(ReportTestData.input(1,"FREE_TALK",1,"DONE","FULL")))).isEqualTo("FULL");assertThat(assembler.level(List.of(ReportTestData.input(1,"FREE_TALK",1,"RUNNING","FULL")))).isEqualTo("PARTIAL");assertThat(assembler.level(List.of(ReportTestData.input(1,"FREE_TALK",1,"DONE","INSUFFICIENT")))).isEqualTo("INSUFFICIENT");}
}
