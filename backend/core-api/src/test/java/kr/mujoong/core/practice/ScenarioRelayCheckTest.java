package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.mujoong.core.practice.ScenarioScriptValidation.Reason;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScript;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C안 전하기(opportunityRelay)의 충실도 · 해석 말투, 되묻기(reaskPrompt)의 해석 말투, 묻기(askPrompt)의 관계 검사(계약 ai-io.md 10.18). 전하기는 상대가 한
 * 말을 간접 인용으로 전하고(낱말을 거의 그대로 살리고 끝만 '~대' · '~냐고 물어봤어' 등으로 바꾼다) 상대의 속뜻 · 이유 · 감정 · 의도를 풀어 말하지 않는다.
 * 걸린 칸은 발급 때 빠지고 realtime이 대체 문장을 읽는다. 합성 문장만 쓴다.
 */
@Tag("scenario-generation")
class ScenarioRelayCheckTest {

    private final ScenarioScriptValidation validator = new ScenarioScriptValidation(new ForbiddenExpressionPolicy());

    /** 상대 말 · 전하기 · 되묻기만 바꾼 C안 장면(관계는 또래 PEER). */
    private static ScenarioScript.Scene scene(String partnerLine, String relay, String reask) {
        return scene(partnerLine, relay, reask, "PEER", ScenarioDirectAskValidationTest.ASK);
    }

    private static ScenarioScript.Scene scene(String partnerLine, String relay, String reask, String relation, String ask) {
        return new ScenarioScript.Scene("base", "BASE", "그림 놀이 중이야. 친구가 “" + partnerLine + "”라고 말한다.", partnerLine,
                "친구가 그림을 그리다가 말을 했어.", List.of("친구 말을 먼저 들어 보자"), List.of("괜찮아?"), relation, "교실", "그림", null, null)
                .withDirectAsk(relay, ask, reask, ScenarioDirectAskValidationTest.HELP, ScenarioDirectAskValidationTest.REACTION);
    }

    private Reason relay(String partnerLine, String relay) {
        return validator.optionalSpokenIssues(scene(partnerLine, relay, null), ScenarioGenerationTest.rules())
                .getOrDefault(ScenarioScriptValidation.OPPORTUNITY_RELAY, Reason.NONE);
    }

    private Reason reask(String partnerLine, String reask) {
        return validator.optionalSpokenIssues(scene(partnerLine, null, reask), ScenarioGenerationTest.rules())
                .getOrDefault(ScenarioScriptValidation.REASK_PROMPT, Reason.NONE);
    }

    private Reason ask(String relation, String ask) {
        return validator.optionalSpokenIssues(scene("손이 좀 아파.", null, null, relation, ask), ScenarioGenerationTest.rules())
                .getOrDefault(ScenarioScriptValidation.ASK_PROMPT, Reason.NONE);
    }

    @Test
    void theIndirectQuotesOfTheDesignKeepThePartnersWordsAndPass() {
        assertThat(relay("오늘은 손이 좀 아파서 그리기 어려울 것 같아.", "그런데 친구가 오늘은 손이 좀 아파서 그리기 어려울 것 같대."))
                .isEqualTo(Reason.NONE);
        assertThat(relay("주말에 뭐 해?", "그런데 친구가 주말에 뭐 하냐고 물어봤어.")).isEqualTo(Reason.NONE);
        assertThat(relay("스테고사우루스가 어떤 공룡이야?", "그런데 친구가 스테고사우루스가 어떤 공룡이냐고 물어봤어.")).isEqualTo(Reason.NONE);
    }

    @Test
    void aRelayThatInterpretsThePartnersIntentOrFeelingIsRemoved() {
        assertThat(relay("주말에 뭐 해?", "친구가 주말 이야기를 하고 싶은 것 같아서 물어봤대.")).isEqualTo(Reason.PARTNER_INTERPRETED);
        assertThat(relay("스테고사우루스가 어떤 공룡이야?", "친구가 처음 들어서 궁금해한대.")).isEqualTo(Reason.PARTNER_INTERPRETED);
        assertThat(ScenarioScriptValidation.relayFidelity("스테고사우루스가 어떤 공룡이야?", "친구가 처음 들어서 궁금해한대."))
                .as("해석한 전하기는 상대 말도 거의 살리지 않는다").isLessThan(ScenarioScriptValidation.RELAY_BIGRAM_RATIO_MIN);
    }

    @Test
    void aParaphraseThatKeepsFewOfThePartnersWordsIsRemovedAndACopyStaysACopy() {
        assertThat(relay("오늘은 손이 좀 아파서 그리기 어려울 것 같아.", "그런데 친구가 오늘 그림 그리는 게 힘들대."))
                .isEqualTo(Reason.RELAY_LOW_FIDELITY);
        assertThat(relay("오늘은 손이 좀 아파서 그리기 어려울 것 같아.", "오늘은 손이 좀 아파서 그리기 어려울 것 같아."))
                .isEqualTo(Reason.RELAY_COPIES_PARTNER_LINE);
    }

    @Test
    void aContextSentenceBeforeTheRelayedWordsDoesNotLowerTheRatio() {
        String partner = "오늘은 손이 좀 아파서 그리기 어려울 것 같아.";
        String relayed = "그런데 친구가 오늘은 손이 좀 아파서 그리기 어려울 것 같대.";

        assertThat(relay(partner, "친구랑 그림을 그리고 있었어. " + relayed)).isEqualTo(Reason.NONE);
        assertThat(ScenarioScriptValidation.relayFidelity(partner, "친구랑 그림을 그리고 있었어. " + relayed))
                .as("분모는 상대 말 쪽이다").isEqualTo(ScenarioScriptValidation.relayFidelity(partner, relayed));
    }

    @Test
    void theRatioThresholdIsOneHalfAndInclusive() {
        assertThat(ScenarioScriptValidation.RELAY_BIGRAM_RATIO_MIN).isEqualTo(0.5);
        assertThat(ScenarioScriptValidation.relayFidelity("주말에 뭐 해?", "그런데 친구가 주말에 무엇을 하는지 물어봤어.")).isEqualTo(0.5);
        assertThat(relay("주말에 뭐 해?", "그런데 친구가 주말에 무엇을 하는지 물어봤어.")).isEqualTo(Reason.NONE);
        assertThat(ScenarioScriptValidation.relayFidelity("주말에 뭐 해?", "그런데 친구가 주말 이야기를 했어.")).isEqualTo(0.25);
        assertThat(relay("주말에 뭐 해?", "그런데 친구가 주말 이야기를 했어.")).isEqualTo(Reason.RELAY_LOW_FIDELITY);
    }

    @Test
    void aVeryShortPartnerLineMustAppearWholeInTheRelay() {
        assertThat(relay("싫어.", "그런데 친구가 싫어라고 했어.")).isEqualTo(Reason.NONE);
        assertThat(relay("싫어.", "그런데 친구가 싫대.")).as("조각이 하나뿐이면 통째로 들어 있어야 한다").isEqualTo(Reason.RELAY_LOW_FIDELITY);
        assertThat(relay("응.", "그런데 친구가 응이라고 했어.")).isEqualTo(Reason.NONE);
    }

    @Test
    void wordingThePartnerUsedThemselvesIsNotInterpretation() {
        assertThat(relay("손이 아파서 오래 못 그릴 것 같아.", "그런데 친구가 손이 아파서 오래 못 그릴 것 같대.")).isEqualTo(Reason.NONE);
        assertThat(relay("손이 아파서 오래 못 그려.", "그런데 친구가 손이 아파서 오래 못 그릴 것 같대."))
                .as("상대가 하지 않은 짐작을 무중이 더했다").isEqualTo(Reason.PARTNER_INTERPRETED);
        assertThat(relay("나 궁금해서 물어본 거야.", "그런데 친구가 궁금해서 물어본 거래.")).isEqualTo(Reason.NONE);
        // 끝만 바꾼 간접 인용은 낱말 꼴이 바뀌어도 상대가 한 말이다(줄기로 대조한다)
        assertThat(relay("궁금해.", "그런데 친구가 궁금해한대.")).isEqualTo(Reason.NONE);
        assertThat(relay("오늘 기분 별로야.", "그런데 친구가 오늘 기분이 별로래.")).isEqualTo(Reason.NONE);
        assertThat(relay("이거 어려운 거 같아.", "그런데 친구가 이거 어려운 거 같대.")).isEqualTo(Reason.NONE);
        assertThat(relay("나 놀고 싶어.", "그런데 친구가 놀고 싶어 한대.")).isEqualTo(Reason.NONE);
        assertThat(relay("숙제 다 했나 봐.", "그런데 친구가 숙제 다 했나 보래.")).as("짐작 꼴도 상대가 직접 했으면 통과").isEqualTo(Reason.NONE);
    }

    @Test
    void contractedFormsOfTheGuessesAreCaughtToo() {
        for (String guess : List.of("친구가 궁금해했대.", "친구가 궁금해한대.", "친구는 쉬고 싶어 한대.", "친구가 쉬고 싶어 했대.",
                "친구가 매운 걸 싫어한대.", "친구가 그 말을 싫어해서 그래.", "친구가 화난 거 같대.", "친구가 블록을 빌리려고 물어봤대.")) {
            assertThat(relay("이제 그만할래.", guess)).as(guess).isEqualTo(Reason.PARTNER_INTERPRETED);
        }
    }

    @Test
    void theInterpretiveWordingDoesNotCatchOrdinarySentences() {
        for (String plain : List.of("내일 공원에서 만나 봐.", "일어나 봐.", "나 봐 줘.", "한번 가 봐.", "그것 같이 쓰자.",
                "친구가 주말에 뭐 하냐고 물어봤어.", "그런데 친구가 손이 아파서 그리기 어렵대.", "형이 색연필을 아직 쓰고 있대.",
                "친구가 다른 색을 건넸지?", "친구가 그림을 다 그렸대.")) {
            assertThat(ScenarioScriptValidation.interpretiveMarks(plain)).as(plain).isEmpty();
        }
        assertThat(relay("내일 공원에서 만나.", "그런데 친구가 내일 공원에서 만나 보재.")).as("'만나 봐'는 짐작 꼴이 아니다").isEqualTo(Reason.NONE);
    }

    @Test
    void theInterpretiveWordingCatchesGuessesAboutIntentReasonOrFeeling() {
        for (String guess : List.of("친구가 화났나 봐.", "친구가 아픈가 봐.", "친구가 블록을 좋아하나 봐.", "친구가 혼자 놀고 싶나 봐.",
                "친구가 그 공룡을 모르나 봐.", "친구는 사실은 속상해서 그래.", "친구가 혼날까 봐 걱정한대.", "친구가 같이 하려는 거래.",
                "친구 기분이 안 좋대.", "친구는 그만하고 싶어 한대.", "친구가 네 말을 다르게 알아들은 것 같고, 다른 색을 건넸대.",
                "친구가 아무 일도 없는 것처럼 말했대.", "친구가 몰라서 물어봤대.", "친구가 서운해서 그래.")) {
            assertThat(ScenarioScriptValidation.interpretiveMarks(guess)).as(guess).isNotEmpty();
        }
    }

    @Test
    void theReaskGetsTheSameInterpretationCheckButNoFidelityCheck() {
        assertThat(reask("이제 그만할래.", "친구가 그만하고 싶어 하는 것 같지? 친구한테 어떤 말을 해 줄까?")).isEqualTo(Reason.PARTNER_INTERPRETED);
        assertThat(reask("이제 그만할래.", "친구가 뭐라고 했지? 친구한테 어떤 말을 해 줄까?")).as("상대 말과 겹치지 않아도 된다")
                .isEqualTo(Reason.NONE);
        assertThat(reask("이제 그만할래.", ScenarioDirectAskValidationTest.REASK)).isEqualTo(Reason.NONE);
    }

    @Test
    void theAskPromptNamesTheTeacherOnlyInATeacherScene() {
        assertThat(ask("TEACHER", "너라면 선생님께 뭐라고 말씀드릴래?")).isEqualTo(Reason.NONE);
        assertThat(ask("TEACHER", "너라면 친구한테 뭐라고 말할래?")).isEqualTo(Reason.ASK_PROMPT_RELATION);
        assertThat(ask("PEER", "너라면 선생님께 뭐라고 말씀드릴래?")).isEqualTo(Reason.ASK_PROMPT_RELATION);
        assertThat(ask("CLASSMATE", "너라면 친구한테 뭐라고 말할래?")).isEqualTo(Reason.NONE);
        assertThat(ask("담임 선생님", "너라면 선생님께 뭐라고 말씀드릴래?")).as("관계 이름에 '선생님'이 든 장면").isEqualTo(Reason.NONE);
        assertThat(ask("동아리 형", "너라면 동아리 형한테 뭐라고 말할래?")).isEqualTo(Reason.NONE);
        assertThat(ScenarioScriptValidation.teacherRelation("TEACHER")).isTrue();
        assertThat(ScenarioScriptValidation.teacherRelation("교사")).isTrue();
        for (String other : List.of("FRIEND", "FRIEND_CLOSE", "CLASSMATE", "CLUB_SENIOR", "FAMILY", "동아리 형", ""))
            assertThat(ScenarioScriptValidation.teacherRelation(other)).as(other).isFalse();
        assertThat(ScenarioScriptValidation.teacherRelation(null)).isFalse();
    }
}
