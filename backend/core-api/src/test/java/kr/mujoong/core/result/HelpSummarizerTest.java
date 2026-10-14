package kr.mujoong.core.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("u6")
class HelpSummarizerTest {
    @Test void noConsentOrHelpHasNoSummaryEvidence() {
        assertThat(HelpSummarizer.summarize(Map.of("cues",List.of())))
                .isEqualTo(Map.of("count",0,"consent","NONE"));
    }

    @ParameterizedTest
    @ValueSource(strings={"DECLINED","NO_RESPONSE","ACCEPTED"})
    void consentWithoutPlaybackDoesNotCountAsProvidedHelp(String consent) {
        assertThat(HelpSummarizer.summarize(Map.of("cues",List.of(cue(consent,null)))))
                .isEqualTo(Map.of("count",0,"consent",consent));
    }

    @Test void countsActualHelpEvenWhenItDoesNotContainAnAnswerSentence() {
        assertThat(HelpSummarizer.summarize(Map.of("cues",List.of(cue("ACCEPTED","2026-10-14T01:00:00Z")))))
                .isEqualTo(Map.of("count",1,"consent","ACCEPTED"));
    }

    @Test void doesNotCountConsentOnlyRowsAlongsideDeliveredHelp() {
        var cues=List.of(cue("ACCEPTED",null),cue("ACCEPTED","2026-10-14T01:00:00Z"));
        assertThat(HelpSummarizer.summarize(Map.of("cues",cues)))
                .isEqualTo(Map.of("count",1,"consent","ACCEPTED"));
    }

    @Test void laterDeclinedConsentWithoutGivenAtDoesNotReplaceFirstConsent() {
        var cues=List.of(cue("ACCEPTED","2026-10-14T01:00:00Z"),cue("DECLINED",null));
        assertThat(HelpSummarizer.summarize(Map.of("cues",cues)))
                .isEqualTo(Map.of("count",1,"consent","ACCEPTED"));
    }

    private static Map<String,Object> cue(String consent,String givenAt) {
        // CueRecord에서 제공 전 null인 증거 세 칸은 JsonCodec이 JSON에서 생략한다.
        String scenario="00000000-0000-0000-0000-000000000001";
        return givenAt==null?Map.of("scenarioId",scenario,"consentResponse",consent)
                :Map.of("scenarioId",scenario,"consentResponse",consent,"deliveredText","천천히 말해 볼까?",
                        "containsAnswerSentence",false,"givenAt",givenAt);
    }
}
