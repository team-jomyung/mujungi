package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.mujoong.core.practice.ScenarioScriptValidation.Reason;
import kr.mujoong.core.practice.ScenarioScriptValidation.Result;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScript;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 연습 대화 C안 들리는 칸 다섯 개의 서버 검사(docs/practice-direct-ask.md 4.3 · 4.4). 문장부호 · 길이 · 문장 수 · 역할 말 · 목표 표현 누설 ·
 * 압박 · 상대 대사 복사 · 묻기 모양 · 도움 꼬리 · 친구 반응의 칭찬과 물음 끝을 본다. 옛 대본(새 칸 없음)은 그대로 통과한다. 합성 문장만 쓴다.
 */
@Tag("scenario-generation")
class ScenarioDirectAskValidationTest {

    static final String RELAY = "그런데 친구가 이제 그만하고 싶대. 친구는 놀이를 멈추고 조금 쉬고 싶어 한대.";
    static final String ASK = "너라면 친구한테 뭐라고 말할래?";
    static final String REASK = "친구가 그만하고 싶다고 했지? 그 말을 듣고 친구한테 어떤 말을 해 주면 좋을까?";
    static final String HELP = "친구가 그만하고 싶을 때는 친구 마음을 먼저 받아 주면 돼. 예를 들면 그럼 잠깐 쉬자처럼 말할 수 있어. 네 말로 해 볼래?";
    static final String REACTION = "그렇게 말해 주면 친구도 마음이 편해서 고마워할 거야.";

    private final ScenarioScriptValidation validator = new ScenarioScriptValidation(new ForbiddenExpressionPolicy());

    /** 다섯 칸을 모두 채운 C안 BASE 장면(나머지 칸은 기존 시험 장면). */
    static ScenarioScript.Scene direct() {
        return ScenarioGenerationTest.base().withDirectAsk(RELAY, ASK, REASK, HELP, REACTION);
    }

    /** C안 장면에서 칸 하나만 바꾼다. */
    static ScenarioScript.Scene with(String field, String value) {
        var d = direct();
        return d.withDirectAsk(field.equals("relay") ? value : d.opportunityRelay(), field.equals("ask") ? value : d.askPrompt(),
                field.equals("reask") ? value : d.reaskPrompt(), field.equals("help") ? value : d.helpSpeech(),
                field.equals("reaction") ? value : d.friendReaction());
    }

    private ScenarioScriptValidation.Inspection inspect(ScenarioScript.Scene... scenes) {
        return validator.inspectDetailed(new ScenarioScript(List.of(scenes)), ScenarioGenerationTest.rules());
    }

    private Reason reason(ScenarioScript.Scene scene) {
        return inspect(scene).reason();
    }

    @Test
    void aFullDirectAskSceneAndAnOldSceneWithoutTheNewFieldsBothPass() {
        assertThat(inspect(direct())).isEqualTo(new ScenarioScriptValidation.Inspection(Result.VALID, Reason.NONE));
        assertThat(inspect(ScenarioGenerationTest.base()).result()).as("옛 대본(v1 모양)").isEqualTo(Result.VALID);
        assertThat(inspect(ScenarioGenerationTest.base().withDirectAsk(RELAY, ASK, null, null, null)).result())
                .as("있는 칸만 본다").isEqualTo(Result.VALID);
    }

    @Test
    void everyNewFieldAcceptsOnlyPeriodCommaAndQuestionMark() {
        var marks = List.of("\"", "'", "“", "”", "‘", "’", "!", "~", "…", "(", ")", "·", "-", ":", "？", " ", "​", "♥");
        for (String field : List.of("relay", "ask", "reask", "help", "reaction")) {
            String original = switch (field) {
                case "relay" -> RELAY;
                case "ask" -> ASK;
                case "reask" -> REASK;
                case "help" -> HELP;
                default -> REACTION;
            };
            for (String mark : marks) {
                assertThat(reason(with(field, original.replaceFirst(" ", mark + " ")))).as(field + " 칸의 " + mark)
                        .isEqualTo(Reason.SPOKEN_PUNCTUATION);
            }
        }
    }

    @Test
    void lengthSentenceCountBlankAndControlCharactersAreSpokenFormat() {
        assertThat(reason(with("relay", "친구가 " + "아주 ".repeat(66) + "아프대."))).as("201자 이상").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(reason(with("ask", "너라면 " + "가".repeat(96) + "?"))).as("askPrompt 100자 초과").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(reason(with("help", "가".repeat(290) + ". 네 말로 해 볼래?"))).as("helpSpeech 300자 초과").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(reason(with("help", "가".repeat(280) + ". 네 말로 해 볼래?"))).as("helpSpeech는 300자까지").isEqualTo(Reason.NONE);
        assertThat(reason(with("relay", "친구가 쉬고 싶대. 놀이를 멈추고 싶대. 정말 힘들대."))).as("3문장").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(reason(with("ask", "너라면 어떻게 할래? 뭐라고 말할래?"))).as("askPrompt 2문장").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(reason(with("help", "요점이야. 하나 더. 예를 들면 그럼 쉬자처럼 말할 수 있어. 네 말로 해 볼래?"))).as("helpSpeech 4문장")
                .isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(reason(with("reaction", " "))).as("빈 칸").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(reason(with("reask", "친구가 그만하고 싶다고 했지?\n어떤 말을 해 주면 좋을까?"))).as("제어 문자").isEqualTo(Reason.SPOKEN_FORMAT);
    }

    @Test
    void roleTalkIsRejectedInEveryNewField() {
        var phrases = List.of("무중이가 친구 역할을 해 볼게.", "이제 코치가 도와줄게.", "무중이가 친구야.", "무중이 친구 할게.",
                "무중이가 선생님이 될게.");
        for (String field : List.of("relay", "ask", "reask", "help", "reaction")) {
            for (String phrase : phrases) {
                assertThat(reason(with(field, phrase))).as(field + " · " + phrase).isEqualTo(Reason.ROLE_TALK);
            }
        }
        assertThat(reason(with("relay", "친구가 무중이를 보고 손을 흔들었대."))).as("역할 말이 아닌 무중").isEqualTo(Reason.NONE);
    }

    @Test
    void theTargetExpressionMayNotLeakIntoRelayAskOrReaskButHelpMayCarryTheExample() {
        assertThat(reason(with("relay", "친구가 잠깐 쉬자고 한대."))).isEqualTo(Reason.TARGET_EXPRESSION_DISCLOSED);
        assertThat(reason(with("ask", "너라면 잠깐 쉬자고 말할래?"))).isEqualTo(Reason.TARGET_EXPRESSION_DISCLOSED);
        assertThat(reason(with("reask", "잠깐 쉬자는 말은 어때?"))).isEqualTo(Reason.TARGET_EXPRESSION_DISCLOSED);
        assertThat(HELP).as("도움 말은 표현 예시를 담는다").contains("잠깐 쉬자");
        assertThat(reason(direct())).isEqualTo(Reason.NONE);
    }

    @Test
    void pressurePrivateNamesContactsAndForbiddenWordsAreCheckedInTheNewFields() {
        assertThat(reason(with("help", "빨리 말해 봐. 예를 들면 그럼 쉬자처럼 말할 수 있어. 네 말로 해 볼래?")))
                .isEqualTo(Reason.PRESSURE_OR_ANSWER);
        assertThat(reason(with("reaction", "틀리면 친구가 속상할 거야."))).isEqualTo(Reason.PRESSURE_OR_ANSWER);
        assertThat(reason(with("reaction", "합성아이 덕분에 친구가 고마워할 거야."))).isEqualTo(Reason.PRIVATE_OR_FORBIDDEN_CONTENT);
        assertThat(reason(with("relay", "친구가 진단을 받았대."))).isEqualTo(Reason.PRIVATE_OR_FORBIDDEN_CONTENT);
        assertThat(reason(with("reask", "01012345678로 연락할까?"))).isEqualTo(Reason.PRIVATE_OR_FORBIDDEN_CONTENT);
    }

    @Test
    void theRelayMayNotCopyThePartnerLine() {
        var line = ScenarioGenerationTest.base().opportunitySentence();
        assertThat(reason(with("relay", line))).isEqualTo(Reason.RELAY_COPIES_PARTNER_LINE);
        assertThat(reason(with("relay", "이제, 그만할래?"))).as("부호 · 공백만 다른 같은 글자").isEqualTo(Reason.RELAY_COPIES_PARTNER_LINE);
        assertThat(reason(with("relay", "친구가 이제 그만하고 싶대."))).isEqualTo(Reason.NONE);
    }

    @Test
    void theAskPromptStartsWithNeoramyeonAndEndsWithAQuestionMark() {
        assertThat(reason(with("ask", "친구한테 뭐라고 말할래?"))).isEqualTo(Reason.ASK_PROMPT_FORM);
        assertThat(reason(with("ask", "너라면 친구한테 뭐라고 말할래."))).isEqualTo(Reason.ASK_PROMPT_FORM);
        assertThat(reason(with("ask", "너라면 선생님께 뭐라고 말씀드릴래?"))).as("어른 높임말").isEqualTo(Reason.NONE);
    }

    @Test
    void helpEndsWithTheFixedTailAfterAtLeastOneSentence() {
        assertThat(reason(with("help", "친구 마음을 먼저 받아 주면 돼."))).isEqualTo(Reason.HELP_TAIL_MISSING);
        assertThat(reason(with("help", "네 말로 해 볼래?"))).as("꼬리만").isEqualTo(Reason.HELP_TAIL_MISSING);
        assertThat(reason(with("help", "친구 마음을 받아 주면 돼. 네 말로 해 볼래? 좋아."))).as("꼬리가 끝이 아님")
                .isEqualTo(Reason.HELP_TAIL_MISSING);
        assertThat(reason(with("help", "친구 마음을 받아 주면 돼, 네 말로 해 볼래?"))).as("꼬리 앞이 문장 끝이 아님")
                .isEqualTo(Reason.HELP_TAIL_MISSING);
        assertThat(reason(with("help", "친구 마음을 받아 주면 돼. 예를 들면 그럼 쉬자처럼 말할 수 있어. 네 말로 해볼래?")))
                .as("공백은 원문 대조처럼 보지 않는다").isEqualTo(Reason.NONE);
    }

    @Test
    void theFriendReactionHasNoPraiseAndDoesNotEndWithAQuestion() {
        for (String praise : List.of("잘했어. 친구도 고마워할 거야.", "멋진 말이야.", "정답이야, 친구가 좋아할 거야.", "친구가 최고라고 할 거야.",
                "잘 말했어."))
            assertThat(reason(with("reaction", praise))).as(praise).isEqualTo(Reason.REACTION_PRAISE);
        assertThat(reason(with("reaction", "친구도 좋아하겠지?"))).isEqualTo(Reason.REACTION_QUESTION);
    }

    @Test
    void aBadNewFieldInAVariantRejectsOnlyTheVariantWithTheDirectAskReason() {
        var variant = new ScenarioScript.Scene("variant", "VARIANT", direct().sceneDescription(), direct().opportunitySentence(),
                direct().reexplanation(), direct().focusPoints(), direct().expressionExamples(), "PEER", "운동장", "놀이", null, null)
                .withDirectAsk(RELAY, ASK, REASK, HELP, "잘했어.");
        assertThat(inspect(direct(), variant))
                .isEqualTo(new ScenarioScriptValidation.Inspection(Result.INVALID_VARIANT, Reason.REACTION_PRAISE));
    }

    @Test
    void theServerAssignedRoleAndDescriptionRepairKeepTheNewFields() {
        var scene = direct();
        var withRole = scene.withPartnerRole(ScenarioContentPolicy.PARTNER_ROLE);
        var repaired = scene.withDescription("새 설명. 이제 그만할래.");
        for (var copy : List.of(withRole, repaired)) {
            assertThat(copy.opportunityRelay()).isEqualTo(RELAY);
            assertThat(copy.askPrompt()).isEqualTo(ASK);
            assertThat(copy.reaskPrompt()).isEqualTo(REASK);
            assertThat(copy.helpSpeech()).isEqualTo(HELP);
            assertThat(copy.friendReaction()).isEqualTo(REACTION);
        }
        assertThat(scene.toString()).doesNotContain("친구", "그만");
    }
}
