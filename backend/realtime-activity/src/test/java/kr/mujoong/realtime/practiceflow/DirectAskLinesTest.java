package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C안 장면 안 말 시험 (docs/practice-direct-ask.md 3.2 · 5장 · 7.4). 고정 문장은 시작 패키지 approvedSpeech에서 오고 없으면 같은 문장의 상수로
 * 대신하며, 옛 대본(v1)은 7.4 대체 문장을 쓰고, 이어 읽는 문장은 문장부호 규칙(마침표 · 쉼표 · 물음표)을 지키는지 본다. 문장은 시험용 가짜 값이다.
 */
@Tag("practice-flow")
class DirectAskLinesTest {

    private static final DirectAskLines PACKAGE = new DirectAskLines(PracticeScriptFixtures.FIXED_SPEECH);
    private static final DirectAskLines EMPTY = new DirectAskLines(Map.of());

    /** C안 판 v2 장면(새 칸 다섯 개 + 상황 안내). */
    private static Scene v2() {
        return new Scene("base-1", SceneKind.BASE, "친구와 그림을 그린다.", "손이 좀 아파서 오래 못 그릴 것 같아.",
                "친구가 손이 아파서 그림을 오래 그리기 힘들대.", "같은 반 친구", List.of("친구 마음을 먼저 물어보기"),
                List.of("손 괜찮아?"), "친구", "교실", "그림 그리기", "친구랑 그림을 그리는 이야기야.", null,
                "그런데 친구가 손이 아프대.", "너라면 친구한테 뭐라고 말할래?", "친구 손이 아프다고 했지? 어떤 말을 해 줄까?",
                "친구 마음을 먼저 물어보면 돼. 예를 들면 손 괜찮아처럼 말할 수 있어. 네 말로 해 볼래?",
                "그렇게 말해 주면 친구도 고마워할 거야.");
    }

    /** 옛 대본 v1 장면: 새 칸 없음. 기회 문장에 따옴표 · 느낌표 · 말줄임표가 있다. */
    private static Scene v1(List<String> focusPoints, List<String> examples) {
        return new Scene("base-1", SceneKind.BASE, "친구가 상자 두 개를 들고 있어.", "“그거 줘! 빨리…”",
                "친구는 빨간 상자와 파란 상자를 들고 있어", "같은 반 친구", focusPoints, examples, "친구", "교실", "물건 부탁");
    }

    @Test
    void theFixedSentencesAreTheDesignTableAndTheFixtureSendsTheSameTextsAsTheConstants() {
        assertThat(DirectAskLines.FIXED).containsExactlyInAnyOrderEntriesOf(Map.of(
                "HELP_OFFER", "무중이가 조금 도와줄까?",
                "VARIANT_OFFER", "비슷한 이야기로 한 번 더 해 볼까?",
                "RETRY_PROMPT", "네 말로 한 번 말해 볼래?",
                "INPUT_CONFIRM", "잘 못 들었어. 한 번 더 말해 줄래?",
                "SCENE_CLOSE", "알겠어. 이 이야기는 여기까지 할게.",
                "WAIT_LEAD", "천천히 생각해도 돼.",
                "ASK_DEFAULT", "너라면 친구한테 뭐라고 말할래?"));
        assertThat(PracticeScriptFixtures.FIXED_SPEECH).isEqualTo(DirectAskLines.FIXED);
    }

    @Test
    void theConstantsAreWordForWordTheContractTablesOfSection1017() throws java.io.IOException {
        java.nio.file.Path repo = java.nio.file.Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
        String doc = java.nio.file.Files.readString(repo.resolve("contracts/ai-io.md"), java.nio.charset.StandardCharsets.UTF_8);
        String section = doc.substring(doc.indexOf("### 10.17 "));

        for (Map.Entry<String, String> fixed : DirectAskLines.FIXED.entrySet()) {
            assertThat(section).as(fixed.getKey())
                    .containsPattern("\\| `" + fixed.getKey() + "`(\\(\\d+\\))? \\| " + java.util.regex.Pattern.quote(fixed.getValue()) + " \\|");
        }
        assertThat(section).contains("| `spokenIntroduction` | " + DirectAskLines.INTRODUCTION_FALLBACK + " |");
        assertThat(section).contains("| `opportunityRelay` | " + DirectAskLines.RELAY_LEAD + " + `opportunitySentence` |");
        assertThat(section).contains("| `askPrompt` | 고정 `ASK_DEFAULT` |");
        assertThat(section).contains("| `reaskPrompt` | " + DirectAskLines.REASK_FALLBACK + " |");
        assertThat(section).contains("| `helpSpeech` | `focusPoints[0]` + \"" + DirectAskLines.EXAMPLE_LEAD + "\" + `expressionExamples[0]` + \""
                + DirectAskLines.EXAMPLE_TAIL + " " + DirectAskLines.HELP_TAIL + "\" |");
        assertThat(section).contains("| `friendReaction` | " + DirectAskLines.FRIEND_REACTION_FALLBACK + " |");
    }

    @Test
    void aFixedSentenceComesFromThePackageAndAMissingOrBlankCodeFallsBackToTheConstant() {
        Map<String, String> approved = new HashMap<>();
        approved.put("HELP_OFFER", "무중이가 도와줄까?");
        approved.put("SCENE_CLOSE", "  ");
        DirectAskLines lines = new DirectAskLines(approved);

        assertThat(lines.fixed("HELP_OFFER")).as("시드 승인 문장이 앞선다").isEqualTo("무중이가 도와줄까?");
        assertThat(lines.fixed("SCENE_CLOSE")).isEqualTo("알겠어. 이 이야기는 여기까지 할게.");
        assertThat(lines.fixed("RETRY_PROMPT")).isEqualTo("네 말로 한 번 말해 볼래?");
        assertThat(new DirectAskLines(null).fixed("WAIT_LEAD")).isEqualTo("천천히 생각해도 돼.");
        assertThatThrownBy(() -> EMPTY.fixed("COACH_SWITCH")).isInstanceOf(NullPointerException.class);
        approved.put("RETRY_PROMPT", "바뀐 문장");
        assertThat(lines.fixed("RETRY_PROMPT")).as("만든 뒤 바뀐 지도의 값을 읽지 않는다").isEqualTo("네 말로 한 번 말해 볼래?");
    }

    @Test
    void aV2SceneIsReadFieldByFieldAndTwoSentencesShareOneSlot() {
        Scene scene = v2();

        assertThat(PACKAGE.introduction(scene)).isEqualTo("친구랑 그림을 그리는 이야기야.");
        assertThat(PACKAGE.opportunity(scene)).isEqualTo("그런데 친구가 손이 아프대. 너라면 친구한테 뭐라고 말할래?");
        assertThat(PACKAGE.reask(scene)).isEqualTo("친구 손이 아프다고 했지? 어떤 말을 해 줄까?");
        assertThat(PACKAGE.reexplanation(scene)).as("다시 설명 뒤 묻는 말을 다시 읽는다")
                .isEqualTo("친구가 손이 아파서 그림을 오래 그리기 힘들대. 너라면 친구한테 뭐라고 말할래?");
        assertThat(PACKAGE.waitAndAsk(scene)).isEqualTo("천천히 생각해도 돼. 너라면 친구한테 뭐라고 말할래?");
        assertThat(PACKAGE.waitAndReask(scene)).isEqualTo("천천히 생각해도 돼. 친구 손이 아프다고 했지? 어떤 말을 해 줄까?");
        assertThat(PACKAGE.help(scene)).isEqualTo(new DirectAskLines.Help(
                "친구 마음을 먼저 물어보면 돼. 예를 들면 손 괜찮아처럼 말할 수 있어. 네 말로 해 볼래?", true));
        assertThat(PACKAGE.friendReaction(scene)).isEqualTo("그렇게 말해 주면 친구도 고마워할 거야.");
    }

    @Test
    void anOldV1SceneUsesTheFallbackSentencesOfTheDesign() {
        Scene scene = v1(List.of("어느 것인지 말하기"), List.of("“파란 상자를 줘.”"));

        assertThat(EMPTY.introduction(scene)).isEqualTo("상상 속 이야기를 떠올려 봐. 무중이가 이야기를 들려줄게.");
        assertThat(EMPTY.relay(scene)).as("기회 문장을 이으며 따옴표 · 느낌표 · 말줄임표를 덜어 낸다")
                .isEqualTo("친구가 이렇게 말했어. 그거 줘. 빨리.");
        assertThat(EMPTY.ask(scene)).as("묻는 말은 고정 ASK_DEFAULT").isEqualTo("너라면 친구한테 뭐라고 말할래?");
        assertThat(EMPTY.opportunity(scene)).isEqualTo("친구가 이렇게 말했어. 그거 줘. 빨리. 너라면 친구한테 뭐라고 말할래?");
        assertThat(EMPTY.reask(scene)).isEqualTo("조금 더 자세히 말해 줄래?");
        assertThat(EMPTY.reexplanation(scene)).as("마침표가 없는 다시 설명에도 마침표를 채운다")
                .isEqualTo("친구는 빨간 상자와 파란 상자를 들고 있어. 너라면 친구한테 뭐라고 말할래?");
        assertThat(EMPTY.help(scene)).isEqualTo(new DirectAskLines.Help(
                "어느 것인지 말하기. 예를 들면 파란 상자를 줘처럼 말할 수 있어. 네 말로 해 볼래?", true));
        assertThat(EMPTY.friendReaction(scene)).isEqualTo("그렇게 말해 주면 친구도 네 마음을 알 거야.");
    }

    @Test
    void anOldSceneWithoutFocusPointsOrExamplesStillHelpsWithTheReexplanationAndRecordsNoExample() {
        DirectAskLines.Help bare = EMPTY.help(v1(List.of(), List.of()));
        assertThat(bare.text()).isEqualTo("친구는 빨간 상자와 파란 상자를 들고 있어. 네 말로 해 볼래?");
        assertThat(bare.exampleGiven()).isFalse();

        DirectAskLines.Help blankExample = EMPTY.help(v1(List.of(" ", "요점"), List.of("  ")));
        assertThat(blankExample.text()).as("빈 요점은 건너뛴다").isEqualTo("요점. 네 말로 해 볼래?");
        assertThat(blankExample.exampleGiven()).isFalse();
    }

    @Test
    void joinFillsAFullStopOnlyWhereASentenceDoesNotEndAndSkipsEmptyParts() {
        assertThat(DirectAskLines.join("하나", "둘?")).isEqualTo("하나. 둘?");
        assertThat(DirectAskLines.join("하나?", "둘")).isEqualTo("하나? 둘");
        assertThat(DirectAskLines.join("하나,", "둘")).isEqualTo("하나, 둘");
        assertThat(DirectAskLines.join(" 하나. ", null, "", "  ", "둘")).isEqualTo("하나. 둘");
        assertThat(DirectAskLines.join()).isEmpty();
    }

    @Test
    void plainKeepsOnlyTheAllowedPunctuation() {
        assertThat(DirectAskLines.plain("“그거 줘!” (빨리) ~ 알았지…")).isEqualTo("그거 줘. 빨리 알았지.");
        assertThat(DirectAskLines.plain("'좋아'... 그래  !")).isEqualTo("좋아. 그래 .");
        assertThat(DirectAskLines.plain(null)).isEmpty();
    }

    @Test
    void theStringFormsNeverCarryTheSentences() {
        assertThat(PACKAGE.toString()).isEqualTo("DirectAskLines[approvedCodes=7]");
        assertThat(PACKAGE.help(v2()).toString()).doesNotContain("친구").contains("exampleGiven=true");
    }
}
