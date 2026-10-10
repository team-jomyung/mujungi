package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 시드 금지 표현 사전 자원 {@code gate/seed-dictionary.json} 시험(U1 3.5 Step 11 · 13 · A5 · J101 · 사람 결정 12 · SD-18 ·
 * contracts/ai-io.md 4절). 파일 형식만 본다. 로더 · 정규화 · 게이트 판정 시험은 Step 29의 SeedDictionaryTest · OutputGateTest다.
 */
@Tag("u1")
class SeedDictionaryFileTest {

    /** 계약 9절 금지 표현 범주 7개. */
    private static final Set<String> CATEGORIES =
            Set.of("DIAGNOSIS", "TREATMENT", "SCORE", "CAUSE", "GENERALIZATION", "EMOTION_CLAIM", "PRESSURE");
    /** 정규화 뒤 형태가 아닌 글자(공백 · 구두점(% 제외) · 라틴 대문자 · 정규식 메타 문자) — SD-18 2.14 로딩 거부 조건. */
    private static final Pattern NOT_NORMALIZED = Pattern.compile("[\\s\\p{Punct}&&[^%#]]|[A-Z]|[\\u200B-\\u200D\\uFEFF]");

    record Entry(String id, String pattern, String category, List<String> appliesTo) {
    }

    record Dictionary(String version, String level, List<Entry> entries) {
    }

    private static Dictionary load() throws IOException {
        try (InputStream in = SeedDictionaryFileTest.class.getResourceAsStream("/gate/seed-dictionary.json")) {
            assertThat(in).as("classpath gate/seed-dictionary.json").isNotNull();
            return JsonCodec.shared().read(new String(in.readAllBytes(), StandardCharsets.UTF_8), Dictionary.class);
        }
    }

    @Test
    void everyCategoryHasAtLeastOneSeedEntryForTheOutputGate() throws IOException {
        Dictionary dictionary = load();
        assertThat(dictionary.version()).isEqualTo("seed-1");
        assertThat(dictionary.level()).isEqualTo("SEED");
        // A5: 7범주 각 1항목 이상(비면 Realtime 기동 거부)
        assertThat(dictionary.entries().stream().map(Entry::category).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(CATEGORIES);
        assertThat(dictionary.entries()).allSatisfy(entry -> assertThat(entry.appliesTo()).contains("OUTPUT_GATE"));
    }

    @Test
    void idsAreUniqueAndPatternsAreAlreadyInNormalizedForm() throws IOException {
        List<Entry> entries = load().entries();
        Set<String> ids = new HashSet<>();
        assertThat(entries).allSatisfy(entry -> assertThat(ids.add(entry.id())).as("중복 id " + entry.id()).isTrue());
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.pattern()).isNotBlank();
            assertThat(Normalizer.normalize(entry.pattern(), Normalizer.Form.NFKC)).isEqualTo(entry.pattern());
            assertThat(NOT_NORMALIZED.matcher(entry.pattern()).find()).as(entry.id() + " 패턴 형태").isFalse();
            assertThat(entry.id()).startsWith("SEED-" + entry.category() + "-");
        });
    }

    @Test
    void scoreUsesTheHumanDecidedPatternAndKnownGapsStayOut() throws IOException {
        Map<String, String> byCategory = load().entries().stream()
                .collect(Collectors.toMap(Entry::category, Entry::pattern, (a, b) -> a + "|" + b));
        // 사람 결정 12: SCORE 시드는 '점수'가 아니라 '비정상'
        assertThat(byCategory.get("SCORE")).isEqualTo("비정상");
        assertThat(byCategory).containsEntry("DIAGNOSIS", "진단")
                .containsEntry("TREATMENT", "치료")
                .containsEntry("CAUSE", "핵심원인")
                .containsEntry("GENERALIZATION", "학교에서도그럴")
                .containsEntry("EMOTION_CLAIM", "화가났군요")
                .containsEntry("PRESSURE", "꼭해야해요");
        // J101 알려진 틈: '점수' · '정상이에요' · '정상입니다'는 정식 사전 v1에서 더한다(시드에 없음)
        assertThat(byCategory.values().stream().flatMap(value -> Arrays.stream(value.split("\\|"))))
                .doesNotContain("점수", "정상이에요", "정상입니다");
    }
}
