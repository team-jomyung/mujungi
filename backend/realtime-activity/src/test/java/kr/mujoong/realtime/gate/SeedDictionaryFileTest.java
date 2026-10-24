package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 시드 금지 표현 사전 자원 {@code gate/seed-dictionary.json} 시험(U1 3.5 Step 11 · 13 · A5 · J101 · 사람 결정 12 · SD-18 ·
 * contracts/ai-io.md 4절). 파일 형식만 본다. 로더 · 정규화 · 게이트 판정 시험은 Step 29의 SeedDictionaryTest · OutputGateTest다.
 *
 * <p>3.6 수정(BT-TC-10)에서 더한 둘: 파일이 BOM 없는 올바른 UTF-8이다(로더는 잘못된 바이트를 대체 문자로 바꿔 읽으므로 패턴이 조용히
 * 달라진다) · 키가 문서 모양 그대로다(코덱이 모르는 키를 조용히 무시하므로 '끄기' 같은 키나 오타 키가 효과 없이 남지 않게 한다). appliesTo는
 * 계약(contracts/ai-io.md 1절)의 네 값을 받고 그 밖의 값만 문제로 본다(3.6 수정 J365).
 */
@Tag("skeleton")
class SeedDictionaryFileTest {

    /** 계약 9절 금지 표현 범주 7개 + 욕설(JIRA-266). */
    private static final Set<String> CATEGORIES =
            Set.of("DIAGNOSIS", "TREATMENT", "SCORE", "CAUSE", "GENERALIZATION", "EMOTION_CLAIM", "PRESSURE", "PROFANITY");
    /** 정규화 뒤 형태가 아닌 글자(공백 · 구두점(% 제외) · 라틴 대문자 · 정규식 메타 문자) — SD-18 2.14 로딩 거부 조건. */
    private static final Pattern NOT_NORMALIZED = Pattern.compile("[\\s\\p{Punct}&&[^%#]]|[A-Z]|[\\u200B-\\u200D\\uFEFF]");

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();

    /** 파일 최상위 키(SeedDictionary.FileShape). */
    private static final Set<String> FILE_KEYS = Set.of("version", "level", "entries");
    /** 항목 키(SeedDictionary.RawEntry). */
    private static final Set<String> ENTRY_KEYS = Set.of("id", "pattern", "category", "appliesTo");

    record Entry(String id, String pattern, String category, List<String> appliesTo) {
    }

    record Dictionary(String version, String level, List<Entry> entries) {
    }

    private static Dictionary load() throws IOException {
        return JsonCodec.shared().read(new String(bytes(), StandardCharsets.UTF_8), Dictionary.class);
    }

    private static byte[] bytes() throws IOException {
        try (InputStream in = SeedDictionaryFileTest.class.getResourceAsStream("/gate/seed-dictionary.json")) {
            assertThat(in).as("classpath gate/seed-dictionary.json").isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void everyCategoryHasAtLeastOneSeedEntryForTheOutputGate() throws IOException {
        Dictionary dictionary = load();
        assertThat(dictionary.version()).isEqualTo("seed-1");
        assertThat(dictionary.level()).isEqualTo("SEED");
        // A5: 8범주 각 1항목 이상(비면 Realtime 기동 거부)
        assertThat(dictionary.entries().stream().map(Entry::category).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(CATEGORIES);
        assertThat(dictionary.entries()).allSatisfy(entry -> assertThat(entry.appliesTo()).contains("OUTPUT_GATE"));
    }

    @Test
    void theFileCategoriesAreExactlyTheLoaderCategoriesAndTheContractRow() throws IOException {
        assertThat(Arrays.stream(SeedDictionary.Category.values()).map(Enum::name).collect(Collectors.toSet()))
                .isEqualTo(CATEGORIES);
        String row = Files.readAllLines(REPO_ROOT.resolve("contracts/ai-io.md"), StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("| 금지 표현 사전 항목 |")).findFirst().orElseThrow();
        Matcher matcher = Pattern.compile("category: ([A-Z_\\\\|]+)").matcher(row);
        assertThat(matcher.find()).as("계약 행의 category 목록").isTrue();
        assertThat(Arrays.stream(matcher.group(1).split("\\\\\\|")).collect(Collectors.toSet())).isEqualTo(CATEGORIES);
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
                .containsEntry("PRESSURE", "꼭해야해요")
                // JIRA-266: 욕설 범주는 범주 표지인 대표 항목 하나만 두고 어휘 · 변형은 공통 욕설 사전(contract-model)이 맡는다.
                // 이 파일은 공백을 지우고 찾으므로 '아저씨 발'처럼 낱말 경계를 넘는 짧은 항목('씨발')을 두지 않는다
                .containsEntry("PROFANITY", "씨발새끼");
        // J101 알려진 틈: '점수' · '정상이에요' · '정상입니다'는 정식 사전 v1에서 더한다(시드에 없음)
        assertThat(byCategory.values().stream().flatMap(value -> Arrays.stream(value.split("\\|"))))
                .doesNotContain("점수", "정상이에요", "정상입니다");
    }

    @Test
    void theFileIsStrictUtf8WithoutABomSoNoPatternIsSilentlyReplaced() throws IOException {
        assertThat(encodingProblems(bytes())).as("사전 파일 인코딩 문제").isEmpty();

        // 검사기가 실제로 잡는다: BOM · 잘린 한글(3바이트 중 2바이트) · 대체 문자(U+FFFD)
        byte[] valid = "{\"version\":\"seed-1\"}".getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[valid.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(valid, 0, bom, 3, valid.length);
        byte[] hangul = "진단".getBytes(StandardCharsets.UTF_8);
        byte[] truncated = Arrays.copyOf(hangul, hangul.length - 1);
        byte[] replaced = "진\uFFFD단".getBytes(StandardCharsets.UTF_8);
        assertThat(encodingProblems(valid)).isEmpty();
        assertThat(encodingProblems(bom)).containsExactly("BOM");
        assertThat(encodingProblems(truncated)).containsExactly("MALFORMED");
        assertThat(encodingProblems(replaced)).containsExactly("REPLACEMENT_CHARACTER");
    }

    @Test
    void everyObjectInTheFileHasExactlyTheDocumentedKeys() throws IOException {
        String json = new String(bytes(), StandardCharsets.UTF_8);

        assertThat(shapeProblems(json)).as("문서 모양 밖 키 · 빠진 키 · 계약 밖 appliesTo 값").isEmpty();

        // 검사기가 실제로 잡는다: 오타 키(applies_to) · 효과 없는 키(enabled) · 빠진 키 · 계약 밖 적용 범위 · 최상위 덧붙인 키
        String drifted = "{\"version\":\"v\",\"level\":\"SEED\",\"note\":\"x\",\"entries\":["
                + "{\"id\":\"A-1\",\"pattern\":\"가\",\"category\":\"SCORE\",\"applies_to\":[\"OUTPUT_GATE\"]},"
                + "{\"id\":\"A-2\",\"pattern\":\"나\",\"category\":\"SCORE\",\"appliesTo\":[\"INPUT_GATE\"],"
                + "\"enabled\":false}]}";
        assertThat(shapeProblems(drifted)).containsExactlyInAnyOrder("file+note", "A-1+applies_to", "A-1-appliesTo",
                "A-2+enabled", "A-2:INPUT_GATE");
    }

    /**
     * 3.6 수정 J365: appliesTo는 계약(contracts/ai-io.md 1절 '금지 표현 사전 항목')의 네 값 OUTPUT_GATE · RESULT_TEXT · PDF ·
     * APPROVED_CONTENT를 모두 받는다 — 후속 유닛이 같은 파일에 결과 문장 · PDF · 승인 콘텐츠용 항목을 더하고(4절), 로더도 그런 항목을 받아
     * OUTPUT_GATE 항목만 게이트에 쓴다. 계약 밖 값만 문제로 본다.
     */
    @Test
    void appliesToAcceptsEveryContractScopeAndFlagsOnlyValuesOutsideTheContract() throws IOException {
        assertThat(contractAppliesTo()).as("계약 행에서 읽은 적용 범위").contains(SeedDictionary.APPLIES_TO_OUTPUT_GATE);
        String contractScopes = "{\"version\":\"v\",\"level\":\"SEED\",\"entries\":["
                + "{\"id\":\"B-1\",\"pattern\":\"가\",\"category\":\"SCORE\","
                + "\"appliesTo\":[\"OUTPUT_GATE\",\"RESULT_TEXT\",\"PDF\",\"APPROVED_CONTENT\"]},"
                + "{\"id\":\"B-2\",\"pattern\":\"나\",\"category\":\"CAUSE\",\"appliesTo\":[\"RESULT_TEXT\",\"PDF\"]},"
                + "{\"id\":\"B-3\",\"pattern\":\"다\",\"category\":\"PRESSURE\",\"appliesTo\":[\"APPROVED_CONTENT\"]}]}";
        String outsideTheContract = "{\"version\":\"v\",\"level\":\"SEED\",\"entries\":["
                + "{\"id\":\"C-1\",\"pattern\":\"가\",\"category\":\"SCORE\",\"appliesTo\":[\"OUTPUT_GATE\",\"INPUT_GATE\"]},"
                + "{\"id\":\"C-2\",\"pattern\":\"나\",\"category\":\"CAUSE\",\"appliesTo\":[\"output_gate\",\"REPORT\"]}]}";

        assertThat(shapeProblems(contractScopes)).as("계약 값은 문제가 아니다").isEmpty();
        assertThat(shapeProblems(outsideTheContract)).containsExactlyInAnyOrder("C-1:INPUT_GATE", "C-2:output_gate",
                "C-2:REPORT");
        // 로더도 계약 값 항목을 받는다(게이트는 OUTPUT_GATE 항목만 쓴다)
        assertThat(SeedDictionary.loadDefault().entries()).isNotEmpty();
        assertThat(SeedDictionary.parse(seedWith("{\"id\":\"SEED-SCORE-9\",\"pattern\":\"결과문장전용\",\"category\":\"SCORE\","
                + "\"appliesTo\":[\"RESULT_TEXT\",\"PDF\",\"APPROVED_CONTENT\"]}")).findMatch("결과문장전용")).isEmpty();
    }

    /** 실제 시드 항목에 {@code extra} 항목 하나를 더한 사전 본문. */
    private static String seedWith(String extra) throws IOException {
        String json = new String(bytes(), StandardCharsets.UTF_8);
        int close = json.lastIndexOf(']');
        return json.substring(0, close).stripTrailing() + ",\n    " + extra + "\n  " + json.substring(close);
    }

    /** 계약(contracts/ai-io.md) '금지 표현 사전 항목' 행의 {@code appliesTo: [ … ]} 값. */
    private static Set<String> contractAppliesTo() throws IOException {
        String row = Files.readAllLines(REPO_ROOT.resolve("contracts/ai-io.md"), StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("| 금지 표현 사전 항목 |"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("contracts/ai-io.md에 '금지 표현 사전 항목' 행이 없다"));
        Matcher matcher = Pattern.compile("appliesTo: \\[([A-Z_, ]+)]").matcher(row);
        if (!matcher.find()) {
            throw new IllegalStateException("'금지 표현 사전 항목' 행에 appliesTo 목록이 없다");
        }
        return Arrays.stream(matcher.group(1).split(",")).map(String::strip).collect(Collectors.toUnmodifiableSet());
    }

    /** 엄격한 UTF-8 해석 문제(BOM · 잘못된 바이트 · 대체 문자). */
    private static List<String> encodingProblems(byte[] raw) {
        List<String> problems = new ArrayList<>();
        if (raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF) {
            problems.add("BOM");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
            if (text.indexOf('\uFFFD') >= 0) {
                problems.add("REPLACEMENT_CHARACTER");
            }
        } catch (CharacterCodingException e) {
            problems.add("MALFORMED");
        }
        return problems;
    }

    /**
     * 문서 모양과 다른 곳: {@code 대상+키}(덧붙은 키) · {@code 대상-키}(빠진 키) · {@code id:값}(계약 밖 appliesTo 값, 3.6 수정 J365 — 계약
     * 네 값은 받는다).
     */
    @SuppressWarnings("unchecked")
    private static List<String> shapeProblems(String json) throws IOException {
        Set<String> contractScopes = contractAppliesTo();
        Map<String, Object> file = JsonCodec.shared().read(json, Map.class);
        List<String> problems = new ArrayList<>(keyProblems("file", file.keySet(), FILE_KEYS));
        for (Object item : (List<Object>) file.getOrDefault("entries", List.of())) {
            Map<String, Object> entry = (Map<String, Object>) item;
            String id = String.valueOf(entry.get("id"));
            problems.addAll(keyProblems(id, entry.keySet(), ENTRY_KEYS));
            Object appliesTo = entry.get("appliesTo");
            if (appliesTo instanceof List<?> scopes) {
                for (Object scope : scopes) {
                    if (!contractScopes.contains(scope)) {
                        problems.add(id + ":" + scope);
                    }
                }
            }
        }
        return problems;
    }

    private static List<String> keyProblems(String owner, Set<String> actual, Set<String> expected) {
        List<String> problems = new ArrayList<>();
        actual.stream().filter(key -> !expected.contains(key)).sorted().forEach(key -> problems.add(owner + "+" + key));
        expected.stream().filter(key -> !actual.contains(key)).sorted().forEach(key -> problems.add(owner + "-" + key));
        return problems;
    }
}
