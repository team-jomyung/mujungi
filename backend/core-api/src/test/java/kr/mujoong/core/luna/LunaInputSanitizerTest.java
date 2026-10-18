package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 보내기 전 입력 정리 (U9 BR2.1 ~ BR2.4 · SD1 · NFR3.1 · NFR3.2). 입력은 합성 값뿐이다. */
@Tag("luna")
class LunaInputSanitizerTest {

    private final LunaInputSanitizer sanitizer = new LunaInputSanitizer(JsonCodec.shared());

    @Test
    void forbiddenKeysAreRemovedAtEveryDepthRegardlessOfCase() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("text", "블록을 쌓았다");
        nested.put("ChildId", UUID.randomUUID());
        nested.put("audioRefs", List.of("a.wav"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("task", "sentences");
        input.put("guardianId", UUID.randomUUID().toString());
        input.put("turns", List.of(nested, Map.of("email", "x@example.invalid", "speaker", "CHILD")));
        input.put("interests", List.of("공룡"));
        input.put("consentStatus", "ACCEPTED");

        SanitizedInput result = sanitizer.sanitize(input, Map.of());

        String sent = JsonCodec.shared().write(result.payload());
        assertThat(sent).doesNotContain("ChildId", "guardianId", "audioRefs", "email", "interests", "consentStatus", "a.wav");
        assertThat(result.payload()).containsOnlyKeys("task", "turns");
        assertThat(result.sentFieldNames()).containsExactly("task", "turns");
    }

    @Test
    void aliasNamesAreReplacedInEveryStringValueAndBlankNamesAreIgnored() {
        Map<String, Object> input = Map.of("utterance", "하늘이가 하늘이 블록을 줬어", "turns",
                List.of(Map.of("text", "하늘이 안녕")));
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("하늘이", "아이");
        aliases.put(" ", "무시");

        SanitizedInput result = sanitizer.sanitize(input, aliases);

        String sent = JsonCodec.shared().write(result.payload());
        assertThat(sent).doesNotContain("하늘이").doesNotContain("무시").contains("아이가 아이 블록을 줬어", "아이 안녕");
    }

    @Test
    void emailAndMobileShapedValuesAreMaskedAsContact() {
        Map<String, Object> input = Map.of("note", "연락은 parent.test@example.invalid 또는 010-1234-5678, 01098765432로",
                "count", 12345678901L);

        SanitizedInput result = sanitizer.sanitize(input, Map.of());

        assertThat((String) result.payload().get("note"))
                .isEqualTo("연락은 연락처 또는 연락처, 연락처로")
                .doesNotContain("@", "1234-5678");
        assertThat(result.payload().get("count")).as("수 값은 문자열 규칙 대상이 아니다").isEqualTo(12345678901L);
    }

    @Test
    void inputThatCannotBecomeJsonIsRejectedWithoutItsContent() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("broken", new Unserializable());

        assertThatThrownBy(() -> sanitizer.sanitize(input, Map.of()))
                .isInstanceOf(LunaException.class)
                .hasMessage("Luna failure: FORBIDDEN_INPUT")
                .satisfies(e -> assertThat(((LunaException) e).attemptsUsed()).isZero());
    }

    @Test
    void residualForbiddenKeysAreDetectedAtAnyDepth() {
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("a", List.of(Map.of("deviceId", "x"))))).isTrue();
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("a", Map.of("APIKEY", "x")))).isTrue();
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("a", List.of(1, "deviceId")))).as("값은 키가 아니다").isFalse();
        assertThat(LunaInputSanitizer.isForbiddenKey(null)).isFalse();
        assertThat(LunaInputSanitizer.FORBIDDEN_KEYS).as("ai-io.md 10.2 목록")
                .contains("childid", "guardianid", "sessionid", "deviceid", "apikey", "audio", "email");
    }

    @Test
    void nullAndRecordValuesAreKeptAsPlainJsonAndToStringHidesThePayload() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("criteria", null);
        input.put("goal", new Goal("부탁하기", 2));

        SanitizedInput result = sanitizer.sanitize(input, Map.of("부탁", "아이"));

        assertThat(result.payload()).containsKey("criteria");
        assertThat(result.payload().get("goal")).isEqualTo(Map.of("name", "아이하기", "level", 2));
        assertThat(result.toString()).isEqualTo("SanitizedInput[redacted]");
    }

    @Test
    void overlappingNamesAreReplacedLongestFirstWhateverTheTableOrderAndNoFragmentRemains() {
        Map<String, Object> input = Map.of("utterance", "민수가 수진이랑 민수야 하고 불렀어", "note", "민준이와 민이 왔다");
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

        assertThat(a.payload()).as("표 순서와 관계없이 같은 결과").isEqualTo(b.payload());
        assertThat(a.payload()).containsEntry("utterance", "아이가 아이진이랑 아이야 하고 불렀어")
                .containsEntry("note", "아이이와 아이이 왔다");
        String sent = JsonCodec.shared().write(a.payload());
        assertThat(sent).doesNotContain("민", "수", "준");
    }

    @Test
    void aReplacedAliasIsNeverMatchedAgainByAShorterName() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("아", "아이");
        aliases.put("하늘", "아이");

        SanitizedInput result = sanitizer.sanitize(Map.of("text", "하늘 아빠"), aliases);

        assertThat(result.payload()).as("'아이' 안의 '아'를 다시 바꾸지 않는다").containsEntry("text", "아이 아이빠");
        assertThat(LunaInputSanitizer.orderedAliases(aliases)).extracting(Map.Entry::getKey).containsExactly("하늘", "아");
        Map<String, String> withNull = new LinkedHashMap<>();
        withNull.put(null, "아이");
        withNull.put("별", null);
        withNull.put("", "아이");
        assertThat(LunaInputSanitizer.orderedAliases(withNull)).isEmpty();
        assertThat(LunaRequest.of(LunaPurpose.RESULT_TEXT, "v1", "i", Map.of(), Map.of("수", "아이", "민수", "아이", " ", "x"),
                java.time.Instant.EPOCH).aliases().keySet()).containsExactly("민수", "수");
    }

    @Test
    void forbiddenKeyVariantsWithUnderscoresHyphensAndCaseAreRemoved() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("Child-Id", "c-1");
        nested.put("guardian-id", "g-1");
        nested.put("text", "블록");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("child_name", "하늘이");
        input.put("api_key", "k-1");
        input.put("DEVICE_ID", "d-1");
        input.put("session-id", "s-1");
        input.put("turns", List.of(nested));
        input.put("goal_name", "부탁하기");

        SanitizedInput result = sanitizer.sanitize(input, Map.of());

        assertThat(result.payload()).containsOnlyKeys("turns", "goal_name");
        assertThat(JsonCodec.shared().write(result.payload())).doesNotContain("하늘이", "k-1", "d-1", "s-1", "c-1", "g-1");
        assertThat(LunaInputSanitizer.isForbiddenKey("Audio_Refs_Json")).isTrue();
        assertThat(LunaInputSanitizer.isForbiddenKey("goal_name")).isFalse();
    }

    @Test
    void theSafetyNetIsAnIndependentCheckThatCatchesVariantsTheFirstPassMisses() {
        assertThat(LunaInputSanitizer.isForbiddenKey("child.name")).as("1차는 _ · -만 지운다").isFalse();
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("a", Map.of("child.name", "x")))).isTrue();
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("a", List.of(Map.of("guardian id", "x"))))).isTrue();
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("api_key", "x"))).isTrue();
        assertThat(LunaInputSanitizer.containsForbiddenKey(Map.of("goal.name", "x", "maxTokens", 1))).isFalse();

        assertThatThrownBy(() -> sanitizer.sanitize(Map.of("child.name", "하늘이"), Map.of()))
                .isInstanceOf(LunaException.class).hasMessage("Luna failure: FORBIDDEN_INPUT")
                .satisfies(e -> assertThat(((LunaException) e).attemptsUsed()).isZero());
    }

    /** 직렬화하면 실패하는 값(합성). */
    static final class Unserializable {
        public String getValue() {
            throw new IllegalStateException("직렬화 실패 대역");
        }
    }

    record Goal(String name, int level) {
    }
}
