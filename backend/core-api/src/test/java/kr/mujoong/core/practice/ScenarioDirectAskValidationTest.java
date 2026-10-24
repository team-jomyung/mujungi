package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.mujoong.core.practice.ScenarioScriptValidation.Reason;
import kr.mujoong.core.practice.ScenarioScriptValidation.Result;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScript;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 연습 대화 C안 들리는 선택 칸의 칸별 서버 검사(docs/practice-direct-ask.md 4.3 · 4.4, 계약 ai-io.md 10.17). 칸마다 모양(문장부호 · 길이 · 문장 수 ·
 * 역할 말) · 내용(금지 · 이름 · 연락처 · 목표 표현 누설 · 압박) · 칸의 뜻(상대 대사 복사 · 묻기 모양 · 도움 꼬리 · 칭찬과 물음 끝)을 보고 걸린 칸만
 * 사유와 함께 낸다. 장면 판정(INVALID_BASE · INVALID_VARIANT)은 이 칸들을 보지 않는다. 옛 대본(새 칸 없음)은 그대로 통과한다. 합성 문장만 쓴다.
 */
@Tag("scenario-generation")
class ScenarioDirectAskValidationTest {

    /** 상대 말("이제 그만할래.")의 낱말을 살린 간접 인용 전하기. */
    static final String RELAY = "그런데 친구가 블록 놀이를 하다가 이제 그만할 거래.";
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

    /** 칸 이름(계약 이름). */
    static String key(String field) {
        return switch (field) {
            case "relay" -> ScenarioScriptValidation.OPPORTUNITY_RELAY;
            case "ask" -> ScenarioScriptValidation.ASK_PROMPT;
            case "reask" -> ScenarioScriptValidation.REASK_PROMPT;
            case "help" -> ScenarioScriptValidation.HELP_SPEECH;
            default -> ScenarioScriptValidation.FRIEND_REACTION;
        };
    }

    private ScenarioScriptValidation.Inspection inspect(ScenarioScript.Scene... scenes) {
        return validator.inspectDetailed(new ScenarioScript(List.of(scenes)), ScenarioGenerationTest.rules());
    }

    private Map<String, Reason> issues(ScenarioScript.Scene scene) {
        return validator.optionalSpokenIssues(scene, ScenarioGenerationTest.rules());
    }

    /** 칸 하나만 바꾼 장면: 장면 판정은 그대로 통과하고, 바꾼 칸 하나만 사유가 붙는다(사유가 NONE이면 걸린 칸이 없다). */
    private Reason issue(String field, String value) {
        var scene = with(field, value);
        assertThat(inspect(scene).result()).as("장면은 " + field + " 칸 때문에 실패하지 않는다").isEqualTo(Result.VALID);
        var found = issues(scene);
        assertThat(found.keySet()).as("걸린 칸은 바꾼 칸뿐이다").isSubsetOf(Set.of(key(field)));
        return found.getOrDefault(key(field), Reason.NONE);
    }

    @Test
    void aFullDirectAskSceneAndAnOldSceneWithoutTheNewFieldsBothPassWithoutFieldIssues() {
        assertThat(inspect(direct())).isEqualTo(new ScenarioScriptValidation.Inspection(Result.VALID, Reason.NONE));
        assertThat(issues(direct())).isEmpty();
        assertThat(inspect(ScenarioGenerationTest.base()).result()).as("옛 대본(v1 모양)").isEqualTo(Result.VALID);
        assertThat(issues(ScenarioGenerationTest.base())).isEmpty();
        assertThat(issues(ScenarioGenerationTest.base().withDirectAsk(RELAY, ASK, null, null, null))).as("있는 칸만 본다").isEmpty();
    }

    @Test
    void everyNewFieldAcceptsOnlyPeriodCommaAndQuestionMark() {
        // 공백류는 보이지 않으니 코드로 적는다: 줄바꿈 없는 공백(U+00A0) · 제로폭 공백(U+200B) · 전각 공백(U+3000)
        var marks = List.of("\"", "'", "“", "”", "‘", "’", "!", "~", "…", "(", ")", "·", "-", ":", "？", " ", "​", "　", "♥");
        for (String field : List.of("relay", "ask", "reask", "help", "reaction")) {
            String original = switch (field) {
                case "relay" -> RELAY;
                case "ask" -> ASK;
                case "reask" -> REASK;
                case "help" -> HELP;
                default -> REACTION;
            };
            for (String mark : marks) {
                assertThat(issue(field, original.replaceFirst(" ", mark + " "))).as(field + " 칸의 " + mark)
                        .isEqualTo(Reason.SPOKEN_PUNCTUATION);
            }
        }
    }

    @Test
    void lengthSentenceCountBlankAndControlCharactersAreSpokenFormat() {
        assertThat(issue("relay", "친구가 " + "아주 ".repeat(66) + "아프대.")).as("201자 이상").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(issue("ask", "너라면 " + "가".repeat(96) + "?")).as("askPrompt 100자 초과").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(issue("help", "가".repeat(290) + ". 네 말로 해 볼래?")).as("helpSpeech 300자 초과").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(issue("help", "가".repeat(280) + ". 네 말로 해 볼래?")).as("helpSpeech는 300자까지").isEqualTo(Reason.NONE);
        assertThat(issue("relay", "친구가 쉬고 싶대. 놀이를 멈추고 싶대. 정말 힘들대.")).as("3문장").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(issue("ask", "너라면 어떻게 할래? 뭐라고 말할래?")).as("askPrompt 2문장").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(issue("help", "요점이야. 하나 더. 예를 들면 그럼 쉬자처럼 말할 수 있어. 네 말로 해 볼래?")).as("helpSpeech 4문장")
                .isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(issue("reaction", " ")).as("빈 칸").isEqualTo(Reason.SPOKEN_FORMAT);
        assertThat(issue("reask", "친구가 그만하고 싶다고 했지?\n어떤 말을 해 주면 좋을까?")).as("제어 문자").isEqualTo(Reason.SPOKEN_FORMAT);
    }

    @Test
    void roleTalkIsRejectedInEveryNewField() {
        var phrases = List.of("무중이가 친구 역할을 해 볼게.", "이제 코치가 도와줄게.", "무중이가 친구야.", "무중이 친구 할게.",
                "무중이가 선생님이 될게.");
        for (String field : List.of("relay", "ask", "reask", "help", "reaction")) {
            for (String phrase : phrases) {
                assertThat(issue(field, phrase)).as(field + " · " + phrase).isEqualTo(Reason.ROLE_TALK);
            }
        }
        assertThat(issue("relay", "그런데 친구가 무중이를 보고 이제 그만할 거래.")).as("역할 말이 아닌 무중").isEqualTo(Reason.NONE);
    }

    @Test
    void theTargetExpressionMayNotLeakIntoRelayAskOrReaskButHelpAndReactionMayCarryIt() {
        assertThat(issue("relay", "친구가 잠깐 쉬자고 한대.")).isEqualTo(Reason.TARGET_EXPRESSION_DISCLOSED);
        assertThat(issue("ask", "너라면 잠깐 쉬자고 말할래?")).isEqualTo(Reason.TARGET_EXPRESSION_DISCLOSED);
        assertThat(issue("reask", "잠깐 쉬자는 말은 어때?")).isEqualTo(Reason.TARGET_EXPRESSION_DISCLOSED);
        assertThat(HELP).as("도움 말은 표현 예시를 담는다").contains("잠깐 쉬자");
        assertThat(issue("reaction", "잠깐 쉬자고 말해 주면 친구도 고마워할 거야.")).as("상대 반응은 목표를 이룬 뒤에 들린다")
                .isEqualTo(Reason.NONE);
        assertThat(issues(direct())).isEmpty();
    }

    @Test
    void pressurePrivateNamesContactsAndForbiddenWordsAreCheckedInTheNewFields() {
        assertThat(issue("help", "빨리 말해 봐. 예를 들면 그럼 쉬자처럼 말할 수 있어. 네 말로 해 볼래?")).isEqualTo(Reason.PRESSURE_OR_ANSWER);
        assertThat(issue("reaction", "틀리면 친구가 속상할 거야.")).isEqualTo(Reason.PRESSURE_OR_ANSWER);
        assertThat(issue("reaction", "합성아이 덕분에 친구가 고마워할 거야.")).isEqualTo(Reason.PRIVATE_OR_FORBIDDEN_CONTENT);
        assertThat(issue("relay", "친구가 진단을 받았대.")).as("내용 검사가 칸의 뜻 검사보다 먼저다")
                .isEqualTo(Reason.PRIVATE_OR_FORBIDDEN_CONTENT);
        assertThat(issue("reask", "01012345678로 연락할까?")).isEqualTo(Reason.PRIVATE_OR_FORBIDDEN_CONTENT);
    }

    @Test
    void theRelayMayNotCopyThePartnerLine() {
        var line = ScenarioGenerationTest.base().opportunitySentence();
        assertThat(issue("relay", line)).isEqualTo(Reason.RELAY_COPIES_PARTNER_LINE);
        assertThat(issue("relay", "이제, 그만할래?")).as("부호 · 공백만 다른 같은 글자").isEqualTo(Reason.RELAY_COPIES_PARTNER_LINE);
        assertThat(issue("relay", "친구가 이제 그만하고 싶대.")).isEqualTo(Reason.NONE);
    }

    @Test
    void theAskPromptStartsWithNeoramyeonAndEndsWithAQuestionMark() {
        assertThat(issue("ask", "친구한테 뭐라고 말할래?")).isEqualTo(Reason.ASK_PROMPT_FORM);
        assertThat(issue("ask", "너라면 친구한테 뭐라고 말할래.")).isEqualTo(Reason.ASK_PROMPT_FORM);
        assertThat(issue("ask", "너라면 짝꿍한테 뭐라고 말할래?")).isEqualTo(Reason.NONE);
    }

    @Test
    void helpEndsWithTheFixedTailAfterAtLeastOneSentence() {
        assertThat(issue("help", "친구 마음을 먼저 받아 주면 돼.")).isEqualTo(Reason.HELP_TAIL_MISSING);
        assertThat(issue("help", "네 말로 해 볼래?")).as("꼬리만").isEqualTo(Reason.HELP_TAIL_MISSING);
        assertThat(issue("help", "친구 마음을 받아 주면 돼. 네 말로 해 볼래? 좋아.")).as("꼬리가 끝이 아님").isEqualTo(Reason.HELP_TAIL_MISSING);
        assertThat(issue("help", "친구 마음을 받아 주면 돼, 네 말로 해 볼래?")).as("꼬리 앞이 문장 끝이 아님").isEqualTo(Reason.HELP_TAIL_MISSING);
        assertThat(issue("help", "친구 마음을 받아 주면 돼. 예를 들면 그럼 쉬자처럼 말할 수 있어. 네 말로 해볼래?"))
                .as("공백은 원문 대조처럼 보지 않는다").isEqualTo(Reason.NONE);
    }

    @Test
    void theFriendReactionHasNoPraiseAndDoesNotEndWithAQuestion() {
        for (String praise : List.of("잘했어. 친구도 고마워할 거야.", "멋진 말이야.", "정답이야, 친구가 좋아할 거야.", "친구가 최고라고 할 거야.",
                "잘 말했어."))
            assertThat(issue("reaction", praise)).as(praise).isEqualTo(Reason.REACTION_PRAISE);
        assertThat(issue("reaction", "친구도 좋아하겠지?")).isEqualTo(Reason.REACTION_QUESTION);
    }

    @Test
    void severalBadFieldsAreReportedOneReasonPerFieldInContractOrder() {
        var scene = direct().withDirectAsk("친구가 “그만”이래.", "친구한테 말할래?", REASK, "도와줄게.", "잘했어.")
                .withSpokenIntroduction("친구와 놀이가 다를 때.");

        assertThat(inspect(scene).result()).isEqualTo(Result.VALID);
        assertThat(issues(scene)).containsExactly(
                Map.entry(ScenarioScriptValidation.SPOKEN_INTRODUCTION, Reason.SPOKEN_FORMAT),
                Map.entry(ScenarioScriptValidation.OPPORTUNITY_RELAY, Reason.SPOKEN_PUNCTUATION),
                Map.entry(ScenarioScriptValidation.ASK_PROMPT, Reason.ASK_PROMPT_FORM),
                Map.entry(ScenarioScriptValidation.HELP_SPEECH, Reason.HELP_TAIL_MISSING),
                Map.entry(ScenarioScriptValidation.FRIEND_REACTION, Reason.REACTION_PRAISE));
    }

    @Test
    void aBadNewFieldInAVariantIsReportedForThatVariantOnlyAndTheScriptStaysValid() {
        var variant = new ScenarioScript.Scene("variant", "VARIANT", direct().sceneDescription(), direct().opportunitySentence(),
                direct().reexplanation(), direct().focusPoints(), direct().expressionExamples(), "PEER", "운동장", "놀이", null, null)
                .withDirectAsk(RELAY, ASK, REASK, HELP, "잘했어.");
        var rules = ScenarioGenerationTest.rules();
        var accepting = new ScenarioScriptValidation.Rules(rules.relation(), rules.opportunity(), rules.targetExpression(),
                rules.originalNames(), null, x -> false, (base, other) -> true);

        assertThat(validator.inspectDetailed(new ScenarioScript(List.of(direct(), variant)), accepting))
                .isEqualTo(new ScenarioScriptValidation.Inspection(Result.VALID, Reason.NONE));
        assertThat(validator.optionalSpokenIssues(variant, accepting))
                .containsExactly(Map.entry(ScenarioScriptValidation.FRIEND_REACTION, Reason.REACTION_PRAISE));
        assertThat(validator.optionalSpokenIssues(direct(), accepting)).isEmpty();
    }

    @Test
    void withoutFieldsDropsOnlyTheNamedFields() {
        var scene = direct().withSpokenIntroduction("친구랑 블록 놀이를 하는 이야기야.");

        var dropped = ScenarioScriptValidation.withoutFields(scene,
                Set.of(ScenarioScriptValidation.OPPORTUNITY_RELAY, ScenarioScriptValidation.FRIEND_REACTION));

        assertThat(dropped.opportunityRelay()).isNull();
        assertThat(dropped.friendReaction()).isNull();
        assertThat(dropped.spokenIntroduction()).isEqualTo("친구랑 블록 놀이를 하는 이야기야.");
        assertThat(List.of(dropped.askPrompt(), dropped.reaskPrompt(), dropped.helpSpeech())).containsExactly(ASK, REASK, HELP);
        assertThat(List.of(dropped.sceneDescription(), dropped.opportunitySentence(), dropped.reexplanation()))
                .containsExactly(scene.sceneDescription(), scene.opportunitySentence(), scene.reexplanation());
        assertThat(ScenarioScriptValidation.withoutFields(scene, Set.of(ScenarioScriptValidation.SPOKEN_INTRODUCTION))
                .spokenIntroduction()).isNull();
        assertThat(ScenarioScriptValidation.withoutFields(scene, Set.of())).isSameAs(scene);
        assertThat(ScenarioScriptValidation.OPTIONAL_SPOKEN_FIELDS).containsExactly("spokenIntroduction", "opportunityRelay",
                "askPrompt", "reaskPrompt", "helpSpeech", "friendReaction");
    }

    @Test
    void theLocalLogReaderAllowsEveryFieldDiagnostic() throws java.io.IOException {
        var repo = java.nio.file.Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();
        String tool = java.nio.file.Files.readString(repo.resolve("tools/diagnostics/practice-failures.py"),
                java.nio.charset.StandardCharsets.UTF_8);
        String fields = tool.substring(tool.indexOf("FIELDS = {"), tool.indexOf("}", tool.indexOf("FIELDS = {")));
        String reasons = tool.substring(tool.indexOf("FIELD_REASONS = {"), tool.indexOf("}", tool.indexOf("FIELD_REASONS = {")));
        for (String field : ScenarioScriptValidation.OPTIONAL_SPOKEN_FIELDS) assertThat(fields).contains("\"" + field + "\"");
        for (Reason reason : ScenarioScriptValidation.FIELD_REASONS) assertThat(reasons).contains("\"" + reason.name() + "\"");
        assertThat(tool).contains("\"ScenarioField_\" + field + \"_\" + reason");
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
