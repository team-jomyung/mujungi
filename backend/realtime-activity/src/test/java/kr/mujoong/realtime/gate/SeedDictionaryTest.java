package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import kr.mujoong.realtime.gate.SeedDictionary.Category;
import kr.mujoong.realtime.gate.SeedDictionary.LoadException;
import kr.mujoong.realtime.gate.SeedDictionary.Problem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 시드 금지 표현 사전 로더 · 정규화 · 찾기 시험 (U1 3.5 Step 29 · A5 · NFR1.2 · SD-18 2.14 · J101).
 *
 * <p>7범주 매개변수화 · 빈 범주 · 중복 id 거부, 항목마다 원형 · 조사 결합 · 띄어쓰기 변형 문장이 걸리는지, 금지어 없는 일상 문장이 통과하는지,
 * 정규화(NFKC · 제로폭 · 구두점 · 대소문자)와 {@code #} 숫자 자리표시, 정규식 메타 문자 · 공백을 담은 패턴의 로딩 거부를 본다.
 */
@Tag("skeleton")
class SeedDictionaryTest {

    private static final SeedDictionary DEFAULT = SeedDictionary.loadDefault();

    @ParameterizedTest
    @EnumSource(Category.class)
    void theShippedDictionaryHasAnOutputGateEntryInEveryCategory(Category category) {
        assertThat(DEFAULT.entries()).anyMatch(entry -> entry.category() == category
                && entry.appliesTo().contains(SeedDictionary.APPLIES_TO_OUTPUT_GATE));
        assertThat(DEFAULT.isSeedLevel()).isTrue();
        assertThat(DEFAULT.version()).isNotBlank();
    }

    /** JIRA-266: 욕설은 파일 대표 항목에 더해 공통 욕설 사전으로 걸리고(변형 포함), 일반 문장은 걸리지 않는다. */
    @Test
    void profanityIsFoundThroughTheSharedLexiconIncludingVariantsAndOrdinarySentencesPass() {
        assertThat(DEFAULT.findMatch(".씨발아, 불렀어")).map(SeedDictionary.Match::category).contains(Category.PROFANITY);
        assertThat(DEFAULT.findMatch("이 병신아")).hasValueSatisfying(match -> {
            assertThat(match.category()).isEqualTo(Category.PROFANITY);
            assertThat(match.id()).isEqualTo(SeedDictionary.LEXICON_PROFANITY_ID);
        });
        assertThat(DEFAULT.findMatch("개 새 끼")).map(SeedDictionary.Match::category).contains(Category.PROFANITY);
        assertThat(DEFAULT.findMatch("시발점에서 출발했어")).isEmpty();
        assertThat(DEFAULT.findMatch("아저씨 발이 커요")).isEmpty();
        assertThat(DEFAULT.findMatch("수박씨 발라 먹었어")).isEmpty();
    }

    static Stream<Arguments> variants() {
        List<Arguments> cases = new ArrayList<>();
        for (SeedDictionary.Entry entry : DEFAULT.entries()) {
            String word = entry.pattern();
            cases.add(Arguments.of(entry.id(), entry.category(), word));
            cases.add(Arguments.of(entry.id(), entry.category(), "우리 " + word + "이에요"));
            cases.add(Arguments.of(entry.id(), entry.category(), spaced(word) + "!"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("variants")
    void everyEntryCatchesItsPlainParticleAndSpacingVariants(String id, Category category, String sentence) {
        assertThat(DEFAULT.findMatch(sentence)).hasValueSatisfying(match -> {
            assertThat(match.category()).isEqualTo(category);
            assertThat(match.id()).isEqualTo(id);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"오늘 공룡 이야기 재미있었어?", "같이 그림을 그려 볼까?", "좋아, 천천히 말해 줘.", ""})
    void everydaySentencesWithoutForbiddenWordsPass(String sentence) {
        assertThat(DEFAULT.findMatch(sentence)).isEmpty();
        assertThat(DEFAULT.findMatch(null)).isEmpty();
    }

    @Test
    void normalizationFoldsWidthZeroWidthPunctuationAndLatinCaseButKeepsPercent() {
        assertThat(SeedDictionary.normalize("Ａ b​c, D!")).isEqualTo("abcd");
        assertThat(SeedDictionary.normalize("100% 맞아요.")).isEqualTo("100%맞아요");
        assertThat(DEFAULT.findMatch("치​료가 필요해요")).isPresent();
        assertThat(DEFAULT.findMatch("「진·단」")).isPresent();
    }

    @Test
    void theHashPlaceholderMatchesOneOrMoreDigitsOnly() {
        SeedDictionary dictionary = SeedDictionary.parse(json(entry("X-1", "점수는#점", "SCORE"), others("SCORE")));

        assertThat(dictionary.findMatch("점수는 85점이야")).isPresent();
        assertThat(dictionary.findMatch("점수는 7 점")).isPresent();
        assertThat(dictionary.findMatch("점수는 몇점")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"진 단", "진단.*", "(진단)", "Diagnosis", "진단?", "##", "#1점", ""})
    void patternsThatAreNotInNormalizedLiteralFormAreRejectedAtLoad(String pattern) {
        String json = json(entry("BAD-1", pattern, "DIAGNOSIS"), others("DIAGNOSIS"));

        assertThatThrownBy(() -> SeedDictionary.parse(json)).isInstanceOf(LoadException.class)
                .satisfies(e -> assertThat(((LoadException) e).problem()).isEqualTo(Problem.BAD_PATTERN));
    }

    @Test
    void anEmptyCategoryAndADuplicateIdAreRejected() {
        String missingPressure = json(others("PRESSURE"));
        String duplicate = json(entry("DUP", "진단", "DIAGNOSIS"), entry("DUP", "치료", "TREATMENT"),
                others("DIAGNOSIS", "TREATMENT"));

        assertThatThrownBy(() -> SeedDictionary.parse(missingPressure))
                .satisfies(e -> assertThat(((LoadException) e).problem()).isEqualTo(Problem.EMPTY_CATEGORY));
        assertThatThrownBy(() -> SeedDictionary.parse(duplicate))
                .satisfies(e -> assertThat(((LoadException) e).problem()).isEqualTo(Problem.DUPLICATE_ID));
    }

    @Test
    void malformedFilesAndUnknownCategoriesAreRejectedWithoutEchoingPatterns() {
        assertThatThrownBy(() -> SeedDictionary.parse("{not json"))
                .satisfies(e -> assertThat(((LoadException) e).problem()).isEqualTo(Problem.PARSE_FAILED));
        assertThatThrownBy(() -> SeedDictionary.parse("{\"version\":\"v\",\"level\":\"SEED\"}"))
                .satisfies(e -> assertThat(((LoadException) e).problem()).isEqualTo(Problem.PARSE_FAILED));
        String unknown = json(entry("U-1", "비밀단어", "WEATHER"), others());
        assertThatThrownBy(() -> SeedDictionary.parse(unknown))
                .satisfies(e -> {
                    assertThat(((LoadException) e).problem()).isEqualTo(Problem.BAD_ENTRY);
                    assertThat(e.getMessage()).doesNotContain("비밀단어");
                });
    }

    private static String spaced(String word) {
        StringBuilder out = new StringBuilder();
        word.codePoints().forEach(cp -> out.appendCodePoint(cp).append(' '));
        return out.toString().trim();
    }

    private static String entry(String id, String pattern, String category) {
        return "{\"id\":\"" + id + "\",\"pattern\":\"" + pattern.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"category\":\"" + category + "\",\"appliesTo\":[\"OUTPUT_GATE\"]}";
    }

    /** 빠진 범주를 뺀 나머지 범주마다 정상 항목 하나. */
    private static String others(String... skip) {
        List<String> entries = new ArrayList<>();
        int n = 0;
        for (Category category : Category.values()) {
            if (List.of(skip).contains(category.name())) {
                continue;
            }
            entries.add(entry("OK-" + (++n), "금지어" + n, category.name()));
        }
        return String.join(",", entries);
    }

    private static String json(String... entries) {
        List<String> nonEmpty = Stream.of(entries).filter(e -> !e.isEmpty()).toList();
        return "{\"version\":\"t-1\",\"level\":\"SEED\",\"entries\":[" + String.join(",", nonEmpty) + "]}";
    }
}
