package kr.mujoong.core.report;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
@Tag("u6") class QuoteCandidatePickerTest {
    QuoteCandidatePicker picker=new QuoteCandidatePicker();
    @Test void capsEachSectionAtThirty(){var inputs=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(n->ReportTestData.input(n,"FREE_TALK",6,"DONE","FULL")).toList();assertThat(picker.pick(inputs)).hasSize(30);}
    @Test void capsEachActivityAtFive(){assertThat(picker.pick(List.of(ReportTestData.input(1,"FREE_TALK",10,"DONE","FULL")))).hasSize(5);}
    @Test void meaningfulChildUtterancesPrecedeShortReplies(){assertThat(picker.pick(List.of(ReportTestData.input(1,"FREE_TALK",6,"DONE","FULL")))).extracting(QuoteCandidatePicker.Quote::utteranceSeq).containsExactly(2L,3L,4L,5L,6L);}
    @Test void newestActivityComesFirst(){assertThat(picker.pick(List.of(ReportTestData.input(5,"FREE_TALK",1,"DONE","FULL"),ReportTestData.input(1,"FREE_TALK",1,"DONE","FULL")))).extracting(QuoteCandidatePicker.Quote::activityNo).containsExactly(1,5);}
    @Test void aiMappingContainsLocalOrdinalAndNoSessionIdentity(){var quote=picker.pick(List.of(ReportTestData.input(7,"FREE_TALK",1,"DONE","FULL"))).getFirst();assertThat(quote.ai()).containsEntry("activityNo",7).doesNotContainKeys("sessionId","guardianId","childId");assertThat(quote.citation().sessionId()).isEqualTo(quote.sessionId());}
}
