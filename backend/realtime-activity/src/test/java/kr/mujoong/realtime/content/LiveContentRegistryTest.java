package kr.mujoong.realtime.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.content.LiveContentRegistry.MatchKind;
import kr.mujoong.realtime.content.LiveContentRegistry.MatchResult;
import kr.mujoong.realtime.content.LiveContentRegistry.MatchSource;
import kr.mujoong.realtime.content.LiveContentRegistry.SessionContentPlan;
import kr.mujoong.realtime.content.LiveContentRegistry.SpeechText;
import kr.mujoong.realtime.content.LiveContentResources.GuardrailMode;
import kr.mujoong.realtime.content.LiveContentResources.JudgmentPoint;
import kr.mujoong.realtime.content.LiveContentResources.LoadException;
import kr.mujoong.realtime.content.LiveContentResources.Problem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판 리소스 적재와 세션 콘텐츠 계획 · 원문 일치 (U8 functional-spec 7절 · 8.3 · BR1.4 · BR2.1 ~ BR2.3 · NFR2.1 ~ NFR2.4 · NFR3.6 · SD5 · SD6).
 */
@Tag("live-content")
@org.junit.jupiter.api.Tag("service-guardrail")
class LiveContentRegistryTest {

    private static final String REST_ACK = "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.";
    private static final String SCENE = "무중이랑 블록 놀이를 한다고 생각해 보자.";

    private final LiveContentResources resources = LiveContentResources.loadDefault();
    private final LiveContentRegistry registry = new LiveContentRegistry(resources);
    private final UUID sessionId = UUID.randomUUID();
    private final UUID restAckVersion = UUID.randomUUID();

    // ------------------------------------------------------------------ 판 리소스

    @Test
    void theShippedResourcesLoadWithJ1ToJ6SevenCategoriesAndThreeModes() {
        assertThat(resources.judgment().version()).isEqualTo("judgment-service-v1");
        assertThat(resources.judgment().points().keySet()).containsExactly(JudgmentPoint.values());
        assertThat(resources.judgment().points().get(JudgmentPoint.J2).optionCodes())
                .containsExactly("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE");
        assertThat(resources.judgment().points().get(JudgmentPoint.J5).optionCodes()).as("J2 여섯 + 코치 표현 그대로 따라 함")
                .startsWith("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE").endsWith("COPIED");
        assertThat(resources.guardrail().version()).isEqualTo("jev-guardrail-v2");
        assertThat(resources.guardrail().categories()).containsExactly("DIAGNOSIS", "TREATMENT", "SCORE", "CAUSE",
                "GENERALIZATION", "EMOTION_CLAIM", "PRESSURE");
        assertThat(resources.guardrail().modes()).containsExactlyInAnyOrder(GuardrailMode.values());
        assertThat(resources.guardrail().instructions()).startsWith("아래 문장이 6~12살 아이와 보호자에게 보이거나 들려도 되는가?")
                .contains("아이가 직접 한 말을 인용 부호 안에 그대로 옮긴 것은 금지가 아니다.").contains("state.candidate");
        assertThat(resources.guardrail().criteria().keySet()).as("제브 선택지 라벨: 7개 범주 + PASS(삽입 순서)")
                .containsExactly("DIAGNOSIS", "TREATMENT", "SCORE", "CAUSE", "GENERALIZATION", "EMOTION_CLAIM", "PRESSURE", "PASS");
        assertThat(resources.judgment().toString()).doesNotContain("아이의 대답");
    }

    @Test
    void malformedResourcesAreRefusedWithoutQuotingTheirText() {
        String judgment = judgmentJson();
        String guardrail = guardrailJson();

        assertRefused(judgment.replace("\"point\": \"J3\"", "\"point\": \"J7\""), guardrail, Problem.BAD_JUDGMENT_POINTS);
        assertRefused(judgment.replaceFirst("\"options\": \\[[^\\]]*\\]", "\"options\": []"), guardrail, Problem.BAD_OPTIONS);
        assertRefused(judgment, guardrail.replace("\"PRESSURE\"]", "]").replace("\"EMOTION_CLAIM\",", "\"EMOTION_CLAIM\""),
                Problem.BAD_CATEGORIES);
        assertRefused(judgment, guardrail.replace("\"PRACTICE_COACH\"", "\"PRACTICE_PEER\""), Problem.BAD_MODES);
        assertRefused(judgment, guardrail.replaceFirst("\"instructions\": \"[^\"]*\"", "\"instructions\": \" \""),
                Problem.BAD_INSTRUCTIONS);
        assertRefused(judgment, guardrail.replace("\"PASS\": \"해당 없음. 위 금지 분류 어디에도 해당하지 않는다.\"", "\"NONE\": \"해당 없음\""),
                Problem.BAD_CRITERIA);
        assertRefused(judgment, guardrail.replace("\"PASS\": \"해당 없음. 위 금지 분류 어디에도 해당하지 않는다.\"", "\"PASS\": \" \""),
                Problem.BAD_CRITERIA);
        assertRefused(judgment, guardrail.replace("    \"PASS\": \"해당 없음. 위 금지 분류 어디에도 해당하지 않는다.\"",
                "    \"PASS\": \"해당 없음\",\n    \"EXTRA\": \"더한 라벨\""), Problem.BAD_CRITERIA);
        assertRefused(judgment, guardrail.replaceFirst(",\\s*\"criteria\": \\{[^}]*\\}", ""), Problem.BAD_CRITERIA);
        assertRefused(judgment.replace("\"code\": \"PARTIAL\"", "\"code\": \"FULL\""), guardrail, Problem.BAD_OPTIONS);
        assertRefused("{not json", guardrail, Problem.PARSE_FAILED);
        assertRefused(judgment.replace("\"version\": \"judgment-service-v1\"", "\"version\": \"\""), guardrail, Problem.BAD_VERSION);
    }

    @Test
    void aJudgmentSetWithoutOnePointIsRefused() {
        String withoutJ6 = judgmentJson().replaceFirst(",\\s*\\{\\s*\"point\": \"J6\"[\\s\\S]*?\\]\\s*\\}(?=\\s*\\]\\s*\\}\\s*$)", "");

        assertThat(withoutJ6).doesNotContain("\"J6\"");
        assertRefused(withoutJ6, guardrailJson(), Problem.BAD_JUDGMENT_POINTS);
    }

    // ------------------------------------------------------------------ 세션 고정 · 놓기

    @Test
    void beforePinningAndAfterReleaseTheMatchIsUnknownAndThePlanIsGone() {
        assertThat(registry.matchApproved(sessionId, REST_ACK)).isEqualTo(MatchResult.UNKNOWN);
        assertThat(registry.registerScript(sessionId, UUID.randomUUID(), "script-1", Map.of("intro", SCENE)))
                .as("고정 전 대본 등록은 받지 않는다").isFalse();

        pin();
        assertThat(registry.pinnedCount()).isEqualTo(1);
        registry.release(sessionId);

        assertThat(registry.plan(sessionId)).isEmpty();
        assertThat(registry.pinnedCount()).as("정산 뒤 맵에서 지워진다").isZero();
        assertThat(registry.matchApproved(sessionId, REST_ACK)).isEqualTo(MatchResult.UNKNOWN);
        assertThat(MatchResult.UNKNOWN.allowsGuardrailSkip()).isFalse();
    }

    @Test
    void thePinnedPlanDoesNotChangeDuringTheSession() {
        SessionContentPlan first = pin();

        SessionContentPlan again = registry.pin(sessionId, Map.of("CLOSING", UUID.randomUUID()),
                Map.of("D_REST_ACK", new SpeechText(UUID.randomUUID(), "다른 판")));

        assertThat(again).isSameAs(first);
        assertThat(first.judgmentSetVersion()).isEqualTo("judgment-service-v1");
        assertThat(first.guardrailPolicyVersion()).isEqualTo("jev-guardrail-v2");
        assertThat(first.approvedContentVersionIds()).containsEntry("D_REST_ACK", restAckVersion);
        assertThat(registry.matchApproved(sessionId, "다른 판").kind()).isEqualTo(MatchKind.NOT_MATCHED);
        assertThat(first.toString()).doesNotContain("쉬고");
    }

    // ------------------------------------------------------------------ 원문 일치

    @Test
    void asciiWhitespaceDifferencesStillMatchTheApprovedSpeech() {
        pin();

        MatchResult result = registry.matchApproved(sessionId, " 잠깐\t쉬고 싶구나.\n천천히 해도괜찮아. ");

        assertThat(result.kind()).isEqualTo(MatchKind.MATCHED);
        assertThat(result.source()).isEqualTo(MatchSource.APPROVED_SPEECH);
        assertThat(result.version()).isEqualTo(restAckVersion.toString());
        assertThat(result.allowsGuardrailSkip()).isTrue();
    }

    @Test
    void oneDifferentCharacterOrNonAsciiSpacesAreNotAMatch() {
        pin();

        assertThat(registry.matchApproved(sessionId, "잠깐 쉬고 싶구나. 천천히 해도 괜찮아!").kind()).isEqualTo(MatchKind.NOT_MATCHED);
        assertThat(registry.matchApproved(sessionId, "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.").kind())
                .as("NBSP").isEqualTo(MatchKind.NOT_MATCHED);
        assertThat(registry.matchApproved(sessionId, "잠깐　쉬고 싶구나. 천천히 해도 괜찮아.").kind())
                .as("전각 공백").isEqualTo(MatchKind.NOT_MATCHED);
        assertThat(registry.matchApproved(sessionId, " ").kind()).isEqualTo(MatchKind.NOT_MATCHED);
        assertThat(registry.matchApproved(sessionId, "잠깐 쉬고 싶구나. 천천히 해도 괜찮아!").allowsGuardrailSkip()).isFalse();
    }

    @Test
    void aScriptSentenceMatchesOnlyAfterItIsRegistered() {
        pin();
        UUID scenario = UUID.randomUUID();

        assertThat(registry.matchApproved(sessionId, SCENE)).as("대본 등록 전은 새 발화").isEqualTo(MatchResult.NOT_MATCHED);
        assertThat(registry.registerScript(sessionId, scenario, "script-1", Map.of("intro", SCENE, "opportunity", "이것 봐, 높지?")))
                .isTrue();

        MatchResult result = registry.matchApproved(sessionId, "무중이랑 블록 놀이를 한다고 생각해 보자.");
        assertThat(result.kind()).isEqualTo(MatchKind.MATCHED);
        assertThat(result.source()).isEqualTo(MatchSource.SESSION_SCRIPT);
        assertThat(result.version()).isEqualTo("script-1");
        assertThat(registry.plan(sessionId)).get().extracting(SessionContentPlan::scriptVersion).isEqualTo("script-1");
        assertThat(registry.plan(sessionId).get().judgmentSetVersion()).as("대본 등록은 판 번호를 바꾸지 않는다")
                .isEqualTo("judgment-service-v1");
    }

    @Test
    void matchResultsKeepTheirShape() {
        assertThatThrownBy(() -> new MatchResult(MatchKind.MATCHED, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchResult(MatchKind.NOT_MATCHED, MatchSource.APPROVED_SPEECH, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeechText(UUID.randomUUID(), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SpeechText(UUID.randomUUID(), REST_ACK).toString()).doesNotContain("쉬고");
    }

    // ------------------------------------------------------------------ 도움

    private SessionContentPlan pin() {
        return registry.pin(sessionId, Map.of("CLOSING", UUID.randomUUID()),
                Map.of("D_REST_ACK", new SpeechText(restAckVersion, REST_ACK)));
    }

    private static void assertRefused(String judgment, String guardrail, Problem problem) {
        assertThatThrownBy(() -> LiveContentResources.parse(judgment, guardrail))
                .isInstanceOf(LoadException.class)
                .satisfies(e -> assertThat(((LoadException) e).problem()).isEqualTo(problem))
                .hasMessageNotContaining("아이의 대답").hasMessageNotContaining("인용 부호");
    }

    private static String judgmentJson() {
        return resource(LiveContentResources.JUDGMENT_RESOURCE);
    }

    private static String guardrailJson() {
        return resource(LiveContentResources.GUARDRAIL_RESOURCE);
    }

    private static String resource(String name) {
        try (var in = LiveContentRegistryTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
