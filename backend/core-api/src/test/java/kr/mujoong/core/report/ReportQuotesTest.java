package kr.mujoong.core.report;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
/** 리포트 인용 글: 잡음 · 비언어 표지를 걷고 원문은 바꾸지 않는다(contracts/ai-io.md 6.5, FT-1023-c). */
@Tag("result-report") class ReportQuotesTest {
    @ParameterizedTest
    @CsvSource(delimiter='|',value={
        ".. 코드를 디버깅하고 있는데 너무 힘들어|코드를 디버깅하고 있는데 너무 힘들어",
        "? 같이 놀자|같이 놀자",
        "] 블록 줄래?|블록 줄래?",
        "[cry 싫어 안 할래|싫어 안 할래",
        "[hum] 같이 하자 [laugh]|같이 하자",
        "블록 하나 빌려 줄래 ]|블록 하나 빌려 줄래",
        "응|응",
        "  같이 블록 쌓자  |같이 블록 쌓자"})
    void cleanStripsEdgeNoiseAndMarkers(String raw,String expected){
        assertThat(ReportQuotes.clean(raw)).isEqualTo(expected);
        // 표지가 앞뒤에만 있으면 인용 글은 원문의 부분 문자열이다.
        assertThat(raw).contains(ReportQuotes.clean(raw));
    }
    @ParameterizedTest
    @ValueSource(strings={"","..","?","]","[cry","[hum]","음","음...","으음 음","[cry 음"})
    void contentlessUtterancesAreNotQuotable(String raw){assertThat(ReportQuotes.clean(raw)).isEmpty();}
    @Test void nullIsEmpty(){assertThat(ReportQuotes.clean(null)).isEmpty();}
    @Test void markerInsideIsRemovedWithoutKeepingBrackets(){assertThat(ReportQuotes.clean("싫어 [cry 안 할래")).isEqualTo("싫어 안 할래");}
    @Test void meaningfulCountsWordsAfterCleaning(){assertThat(ReportQuotes.meaningful(".. 코드 디버깅 힘들어")).isTrue();assertThat(ReportQuotes.meaningful(".. 너무 힘들어")).isFalse();}
    @Test void citationKeepsSessionSequenceCleanQuoteAndSource(){
        var input=ReportTestData.input(7,"FREE_TALK",1,"DONE","FULL");
        var quote=ReportQuotes.quote(input,Map.of("seq",3,"speaker","CHILD","text",".. 같이 놀자","startedAt",ReportTestData.NOW.toString()),"TRANSCRIPT");
        var citation=quote.citation();
        assertThat(citation.sessionId()).isEqualTo(input.record().sessionId());assertThat(citation.utteranceSeq()).isEqualTo(3L);
        assertThat(citation.quote()).isEqualTo("같이 놀자");assertThat(citation.source()).isEqualTo("TRANSCRIPT");assertThat(citation.at()).isEqualTo(ReportTestData.NOW);
    }
    @Test void unreadableTimeFallsBackToListedAt(){
        var input=ReportTestData.input(7,"FREE_TALK",1,"DONE","FULL");
        assertThat(ReportQuotes.quote(input,Map.of("seq",1,"speaker","CHILD","text","같이 놀자"),"TRANSCRIPT").at()).isEqualTo(input.record().listedAt());
    }
}
