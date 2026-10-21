package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Realtime 입력 정리 · 스키마 짝 · 요청 스키마 (U9 BR2.1 ~ BR2.4 · BR4.2 · BR4.5 · SD1 · SD2 · NFR3.1). 합성 값만 쓴다. */
@Tag("luna")
class RealtimeLunaInputAndSchemaTest {

    private static final JsonCodec JSON = JsonCodec.shared();
    private final LunaInputSanitizer sanitizer = new LunaInputSanitizer(JSON);

    @Test
    void forbiddenKeysAreRemovedAtEveryDepthAndContactsMasked() {
        Map<String, Object> input = new LinkedHashMap<>(RealtimeLunaTestFixtures.judgmentInput("010-1234-5678로 전화해"));
        input.put("sessionId", "00000000-0000-4000-8000-000000000001");
        input.put("recentTurns", List.of(Map.of("speaker", "CHILD", "text", "안녕", "deviceId", "d-1")));

        SanitizedInput result = sanitizer.sanitize(input, Map.of());

        String sent = JSON.write(result.payload());
        assertThat(sent).doesNotContain("sessionId", "deviceId", "d-1", "1234-5678").contains("연락처로 전화해");
        assertThat(result.sentFieldNames()).containsExactly("judgmentPoint", "optionSetVersion", "options", "childUtterance",
                "recentTurns", "criteria");
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("x", List.of(Map.of("APIKey", 1))))).isTrue();
        assertThat(LunaInputSanitizer.isForbiddenKey(null)).isFalse();
        assertThat(result.toString()).isEqualTo("SanitizedInput[redacted]");
    }

    @Test
    void aliasesAreAppliedAndUnserializableInputIsRejected() {
        Map<String, Object> input = Map.of("childUtterance", "별이 블록 줘", "note", "a@b.example");
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("별이", "아이");
        aliases.put("", "무시");

        SanitizedInput result = sanitizer.sanitize(input, aliases);

        assertThat(result.payload()).containsEntry("childUtterance", "아이 블록 줘").containsEntry("note", "연락처");
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("x", new Unserializable());
        assertThatThrownBy(() -> sanitizer.sanitize(broken, Map.of())).isInstanceOf(LunaException.class)
                .hasMessage("Luna failure: FORBIDDEN_INPUT");
    }

    @Test
    void overlappingNamesAreReplacedLongestFirstAndNoFragmentRemains() {
        Map<String, Object> input = Map.of("childUtterance", "민수가 수진이랑 민준이랑 민이를 불렀어");
        Map<String, String> shortFirst = new LinkedHashMap<>();
        shortFirst.put("수", "아이");
        shortFirst.put("민", "아이");
        shortFirst.put("민수", "아이");
        shortFirst.put("민준", "아이");
        Map<String, String> longFirst = new LinkedHashMap<>();
        longFirst.put("민준", "아이");
        longFirst.put("민수", "아이");
        longFirst.put("민", "아이");
        longFirst.put("수", "아이");

        SanitizedInput a = sanitizer.sanitize(input, shortFirst);
        SanitizedInput b = sanitizer.sanitize(input, longFirst);

        assertThat(a.payload()).isEqualTo(b.payload())
                .containsEntry("childUtterance", "아이가 아이진이랑 아이이랑 아이이를 불렀어");
        assertThat(JSON.write(a.payload())).doesNotContain("민", "수", "준");
        Map<String, String> reMatch = new LinkedHashMap<>();
        reMatch.put("아", "아이");
        reMatch.put("별이", "아이");
        assertThat(sanitizer.sanitize(Map.of("t", "별이 아빠"), reMatch).payload()).as("바꾼 '아이'를 다시 바꾸지 않는다")
                .containsEntry("t", "아이 아이빠");
        assertThat(new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "v1", "i", Map.of(), shortFirst, java.time.Instant.EPOCH)
                .aliases().keySet()).containsExactly("민수", "민준", "민", "수");
    }

    @Test
    void forbiddenKeyVariantsAreRemovedAndTheSafetyNetChecksIndependently() {
        Map<String, Object> input = new LinkedHashMap<>(RealtimeLunaTestFixtures.judgmentInput("그거 줘."));
        input.put("child_name", "별이");
        input.put("Device-Id", "d-1");
        input.put("recentTurns", List.of(Map.of("speaker", "CHILD", "text", "안녕", "guardian_id", "g-1", "API-KEY", "k-1")));

        SanitizedInput result = sanitizer.sanitize(input, Map.of());

        assertThat(JSON.write(result.payload())).doesNotContain("별이", "d-1", "g-1", "k-1");
        assertThat(result.sentFieldNames()).doesNotContain("child_name", "Device-Id");
        assertThat(LunaInputSanitizer.isForbiddenKey("session_id")).isTrue();
        assertThat(LunaInputSanitizer.isForbiddenKey("child.name")).isFalse();
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("x", List.of(Map.of("child.name", 1))))).isTrue();
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("judgment_point", "J2"))).isFalse();
        assertThatThrownBy(() -> sanitizer.sanitize(Map.of("device id", "d-1"), Map.of())).isInstanceOf(LunaException.class)
                .hasMessage("Luna failure: FORBIDDEN_INPUT");
    }

    @Test
    void theJudgmentRequestCopyKeepsOnlyProviderKeywordsAndIsStrict() {
        LunaSchemas schemas = LunaSchemas.loadDefault(JSON);
        LunaSchemas.OutputSchemaPair pair = schemas.pair(LunaPurpose.JUDGMENT_LIVE);

        String copy = JSON.write(pair.requestSchema());
        assertThat(copy).doesNotContain("\"pattern\"", "\"minimum\"", "\"maximum\"", "\"$id\"", "\"$schema\"", "\"examples\"",
                "\"title\"").contains("\"$ref\":\"#/$defs/RankedOption\"");
        assertThat(pair.strict()).as("판정 출력은 모든 객체가 닫히고 모든 속성이 required다").isTrue();
        assertThat(pair.file()).isEqualTo("ai-judgment-output.schema.json");
        assertThat(LunaSchemas.isStrict(Map.of("properties", Map.of("a", Map.of()), "required", List.of("a")))).isFalse();
    }

    @Test
    void outputValidationUsesTheFullContractIncludingRangesAndPatterns() {
        LunaSchemas schemas = LunaSchemas.loadDefault(JSON);
        String valid = RealtimeLunaTestFixtures.example("ai-judgment-output.schema.json");

        assertThat(schemas.isValidOutput(LunaPurpose.JUDGMENT_LIVE, valid)).isTrue();
        assertThat(schemas.isValidOutput(LunaPurpose.JUDGMENT_LIVE, valid.replace("0.71", "1.5"))).as("maximum").isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.JUDGMENT_LIVE, valid.replace("\"PARTIAL\"", "\"partial\""))).as("pattern")
                .isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.JUDGMENT_LIVE, "{\"first\":{\"option\":\"FULL\",\"probability\":0.5}}"))
                .isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.JUDGMENT_LIVE, "nope")).isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.JUDGMENT_LIVE, "[]")).isFalse();
        assertThat(schemas.isValidOutput(LunaPurpose.JUDGMENT_LIVE, null)).isFalse();
    }

    @Test
    void requestValidationRejectsExtraFieldsAndOutOfRangeValues() {
        LunaSchemas schemas = LunaSchemas.loadDefault(JSON);
        Map<String, Object> valid = RealtimeLunaTestFixtures.judgmentInput("그거 줘.");
        Map<String, Object> extra = new LinkedHashMap<>(valid);
        extra.put("note", "x");
        Map<String, Object> badPoint = new LinkedHashMap<>(valid);
        badPoint.put("judgmentPoint", "J7");
        Map<String, Object> nullCriteria = new LinkedHashMap<>(valid);
        nullCriteria.put("criteria", null);

        assertThat(schemas.isValidRequest(LunaPurpose.JUDGMENT_LIVE, JSON.write(valid))).isTrue();
        assertThat(schemas.isValidRequest(LunaPurpose.JUDGMENT_LIVE, JSON.write(nullCriteria))).as("criteria null 허용").isTrue();
        assertThat(schemas.isValidRequest(LunaPurpose.JUDGMENT_LIVE, JSON.write(extra))).isFalse();
        assertThat(schemas.isValidRequest(LunaPurpose.JUDGMENT_LIVE, JSON.write(badPoint))).isFalse();
    }

    @Test
    void aJoinJudgmentWithTheCoachPromptAndAnEarlierChildLinePassesTheRequestSchema() {
        // 참여 관문 안정화 (5): J1 요청의 COACH 줄(승인 안내 글) + CHILD 줄(앞 전사)이 입력 정리와 요청 스키마를 통과한다(합성 값)
        LunaSchemas schemas = LunaSchemas.loadDefault(JSON);
        kr.mujoong.realtime.judgment.JudgeRequest request = new kr.mujoong.realtime.judgment.JudgeRequest(
                kr.mujoong.realtime.judgment.JudgmentPoint.J1, "judgment-v1",
                List.of("PARTICIPATE", "REST", "DECLINE", "UNCLEAR", "UNREADABLE"), "응 들려",
                List.of(new kr.mujoong.realtime.judgment.JudgeRequest.RecentTurn(kr.mujoong.realtime.judgment.JudgeRequest.Speaker.COACH,
                                "안녕, 무중이야. 나는 사람이 아니라 AI야. 그만하고 싶으면 \"무중아 그만할래\"라고 말해 줘. 오늘 나랑 이야기할래?"),
                        new kr.mujoong.realtime.judgment.JudgeRequest.RecentTurn(kr.mujoong.realtime.judgment.JudgeRequest.Speaker.CHILD,
                                "내 말이 들리니?")),
                null, Map.of("하늘", "아이"), false, java.time.Instant.parse("2026-10-21T09:00:04Z"));

        SanitizedInput sanitized = sanitizer.sanitize(request.toLunaInput(), request.aliases());

        assertThat(schemas.isValidRequest(LunaPurpose.JUDGMENT_LIVE, JSON.write(sanitized.payload()))).isTrue();
        assertThat(JSON.write(sanitized.payload())).contains("\"speaker\":\"COACH\"").contains("\"speaker\":\"CHILD\"");
    }

    @Test
    void missingOrBrokenSchemaFilesRefuseToLoad() {
        Function<String, String> noOutput = name -> name.equals("ai-judgment-output.schema.json") ? null
                : RealtimeLunaTestFixtures.contractFile(name);
        Function<String, String> noRequest = name -> name.equals("ai-judgment-request.schema.json") ? null
                : RealtimeLunaTestFixtures.contractFile(name);
        Function<String, String> badExample = name -> name.equals("ai-judgment-output.schema.json")
                ? RealtimeLunaTestFixtures.contractFile(name).replace("\"examples\": [", "\"examples\": [{},")
                : RealtimeLunaTestFixtures.contractFile(name);
        Function<String, String> unreadable = name -> name.equals("ai-judgment-request.schema.json") ? "[["
                : RealtimeLunaTestFixtures.contractFile(name);

        assertThatThrownBy(() -> LunaSchemas.load(JSON, noOutput)).hasMessage("SCHEMA_MISSING: ai-judgment-output.schema.json");
        assertThatThrownBy(() -> LunaSchemas.load(JSON, noRequest)).hasMessage("SCHEMA_MISSING: ai-judgment-request.schema.json");
        assertThatThrownBy(() -> LunaSchemas.load(JSON, badExample))
                .hasMessage("SCHEMA_EXAMPLE_INVALID: ai-judgment-output.schema.json");
        assertThatThrownBy(() -> LunaSchemas.load(JSON, unreadable)).isInstanceOf(LunaSchemas.LoadException.class);
    }

    /** 직렬화하면 실패하는 값(합성). */
    static final class Unserializable {
        public String getValue() {
            throw new IllegalStateException("직렬화 실패 대역");
        }
    }
}
