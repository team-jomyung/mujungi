package kr.mujoong.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import kr.mujoong.core.knowledge.KnowledgeProperties;
import kr.mujoong.core.knowledge.SeedGenerationProperties;
import kr.mujoong.core.luna.LunaHosts;
import kr.mujoong.core.luna.LunaProperties;
import kr.mujoong.core.textai.TextAiProperties;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Core 설정 키 대조(U1 3.5 Step 36 · RD-24 일부 · BR8.11 · NFR6.1 · A6 · SD-10 · SD-14).
 *
 * <p>{@code contracts/timing.md}의 키 · 기본값 ↔ {@code application.yml} ↔ 타입 있는 설정 레코드 {@link CoreProperties}의 이름 ·
 * 기본값이 같은지 본다.
 *
 * <ul>
 *   <li>timing.md에서 소유 앱이 core-api인 키는 application.yml에 같은 표기의 기본값으로 있다.
 *   <li>timing.md가 다루는 묶음(timing · outbox · inbox · …)의 yml 키는 모두 timing.md에 있다(주소 키는 {@code contracts/ports.md}).
 *       Core가 소유하지 않는 키(Realtime · web 전용, U1이 읽지 않는 7절 예약 키)는 yml에 없다.
 *   <li>yml의 {@code mujoong.*} 키 집합 = 설정 레코드가 읽는 키 집합이고, 레코드에 묶인 값이 yml 값과 같다(오타 키가 조용히 버려지지 않음).
 *   <li>A6 · SD-10: bootstrap 발급 한도 상향(600 · 1h)은 {@code application-load-test.yml}에만, demo · prod 파일에는 한도 상향 ·
 *       {@code mujoong.load-test.*} 키가 없다. SD-14 · A2: 어느 프로필 파일도 시험 로그인을 켜지 않는다.
 * </ul>
 */
@Tag("skeleton")
@Tag("service-guardrail")
class ConfigKeyContractTest {

    /** timing.md '소유 앱' 열에서 이 앱을 가리키는 이름. */
    private static final String APP = "core-api";
    /** Core가 읽는 주소 키(contracts/ports.md 2절, J109). */
    private static final Set<String> ADDRESS_KEYS = Set.of("mujoong.internal-http.realtime-base-url");
    /** 레코드에는 있지만 application.yml에 기본값을 두지 않는 키(Core는 없음). */
    private static final Set<String> RECORD_KEYS_WITHOUT_DEFAULT = Set.of();
    /** timing.md가 키 · 기본값의 정본인 묶음(mujoong.<묶음>.*). 나머지(limits · security · …)는 reliability-design ## 3만 정본이다. */
    private static final Set<String> TIMING_GOVERNED_GROUPS = Set.of(
            "timing", "outbox", "inbox", "retention", "cleanup", "session", "device", "bootstrap", "gate",
            "internal-http", "provider", "health", "device-web", "text-ai");

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
    private static final Path MAIN_RESOURCES = REPO_ROOT.resolve("backend/core-api/src/main/resources");
    private static final Path TEST_RESOURCES = REPO_ROOT.resolve("backend/core-api/src/test/resources");

    /** 환경 변수로만 받는 값(비밀이 아닌 시험 값). */
    private static final Map<String, Object> ENV = Map.of(
            "MUJOONG_SECURITY_ALLOWED_ORIGIN", "https://mujoong.invalid",
            "MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET", "");

    // ------------------------------------------------------------------ timing.md ↔ application.yml

    @Test
    void everyTimingKeyCoreOwnsHasTheSameDefaultInApplicationYml() {
        List<TableRow> rows = timingRows();
        Map<String, Object> yml = mujoongKeys(flatten(MAIN_RESOURCES.resolve("application.yml")));

        List<TableRow> owned = rows.stream().filter(row -> row.defaultValue() != null && row.owners().contains(APP)).toList();
        assertThat(owned).as("timing.md에서 core-api가 읽는 키(빈 대조로 통과하지 않음)").hasSizeGreaterThan(30);
        assertThat(defaultMismatches(owned, yml)).as("timing.md 기본값과 다른 application.yml 값").isEmpty();
    }

    @Test
    void applicationYmlHoldsOnlyDocumentedTimingKeysThatCoreOwns() {
        List<TableRow> rows = timingRows();
        Map<String, Object> yml = mujoongKeys(flatten(MAIN_RESOURCES.resolve("application.yml")));
        Set<String> owned = new TreeSet<>();
        Set<String> notOwned = new TreeSet<>();
        for (TableRow row : rows) {
            (row.defaultValue() != null && row.owners().contains(APP) ? owned : notOwned).add(row.key());
        }
        assertThat(notOwned).as("예약 키 · 다른 앱 · web 전용 키(빈 대조 아님)")
                .contains("mujoong.timing.session.join-wait", "mujoong.timing.parent.poll-interval",
                        "mujoong.device-web.retry-interval", "mujoong.timing.session.device-ack-timeout");

        assertThat(yml.keySet()).as("Core가 소유하지 않는 timing.md 키").doesNotContainAnyElementsOf(notOwned);
        List<String> undocumented = yml.keySet().stream()
                .filter(key -> TIMING_GOVERNED_GROUPS.contains(group(key)))
                .filter(key -> !owned.contains(key) && !ADDRESS_KEYS.contains(key))
                .toList();
        assertThat(undocumented).as("timing.md에 없는 시간 · 메시징 · 내부 HTTP 키(BR8.11: 7절 밖 키는 timing.md에 더한다)")
                .isEmpty();
        assertThat(rows).as("timing.md 표는 비 timing 키(limits · security · …)를 담지 않는다(RD-24)")
                .allMatch(row -> TIMING_GOVERNED_GROUPS.contains(group(row.key())));
    }

    @Test
    void addressKeysMatchPortsMd() {
        Map<String, Object> yml = mujoongKeys(flatten(MAIN_RESOURCES.resolve("application.yml")));
        Map<String, String> ports = new TreeMap<>();
        for (TableRow row : parseTables(read(REPO_ROOT.resolve("contracts/ports.md")), "설정 키", "기본 주소", null)) {
            if (row.defaultValue() != null) {
                ports.put(row.key(), row.defaultValue());
            }
        }

        assertThat(ports).containsKeys(ADDRESS_KEYS.toArray(String[]::new));
        for (String key : ADDRESS_KEYS) {
            assertThat(yml.get(key)).as(key).isEqualTo(ports.get(key));
        }
    }

    // ------------------------------------------------------------------ application.yml ↔ CoreProperties

    @Test
    void applicationYmlAndCorePropertiesNameTheSameKeysWithTheSameValues() {
        Path base = MAIN_RESOURCES.resolve("application.yml");
        Map<String, Object> yml = mujoongKeys(flatten(base));
        Map<String, RecordComponent> leaves = new TreeMap<>(recordLeaves(CoreProperties.class, "mujoong"));
        // U5a: 텍스트 AI · 지식 그래프는 같은 방식의 설정 레코드 둘이 묶는다(U5a 리뷰 5)
        leaves.putAll(recordLeaves(TextAiProperties.class, "mujoong.text-ai"));
        leaves.putAll(recordLeaves(KnowledgeProperties.class, "mujoong.knowledge.neo4j"));
        leaves.putAll(recordLeaves(SeedGenerationProperties.class, "mujoong.knowledge.seed-generation"));
        // U9 SD12: Luna 호출 설정도 같은 방식의 레코드가 묶는다
        leaves.putAll(recordLeaves(LunaProperties.class, "mujoong.luna"));

        assertThat(keyMismatches(yml.keySet(), leaves.keySet(), RECORD_KEYS_WITHOUT_DEFAULT))
                .as("yml 키 ↔ 설정 레코드 키").isEmpty();

        CoreProperties bound = bind(base);
        Map<String, Object> boundValues = new TreeMap<>(recordValues(bound, "mujoong"));
        boundValues.putAll(recordValues(bindRecord(TextAiProperties.class, "mujoong.text-ai", base), "mujoong.text-ai"));
        boundValues.putAll(recordValues(bindRecord(KnowledgeProperties.class, "mujoong.knowledge.neo4j", base),
                "mujoong.knowledge.neo4j"));
        boundValues.putAll(recordValues(bindRecord(SeedGenerationProperties.class, "mujoong.knowledge.seed-generation", base),
                "mujoong.knowledge.seed-generation"));
        boundValues.putAll(recordValues(bindRecord(LunaProperties.class, "mujoong.luna", base), "mujoong.luna"));
        assertThat(valueMismatches(yml, leaves, boundValues, placeholderResolver(base)))
                .as("yml 값과 레코드에 묶인 값").isEmpty();
        assertThat(bound.timing().bootstrap().rateLimit().max()).isEqualTo(20);
        assertThat(bound.timing().bootstrap().rateLimit().window()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void testProfileOverridesOnlyKeysTheRecordReadsAndStillSatisfiesTheInvariants() {
        Path base = MAIN_RESOURCES.resolve("application.yml");
        Path testYml = TEST_RESOURCES.resolve("application-test.yml");
        Map<String, Object> overrides = mujoongKeys(flatten(testYml));
        Map<String, RecordComponent> leaves = new TreeMap<>(recordLeaves(CoreProperties.class, "mujoong"));
        // U9 BR1.4: test 프로필은 Luna 주소를 루프백으로 덮어쓴다(SD12 레코드)
        leaves.putAll(recordLeaves(LunaProperties.class, "mujoong.luna"));

        leaves.putAll(recordLeaves(KnowledgeProperties.class, "mujoong.knowledge.neo4j"));
        assertThat(overrides).as("시험 축소값(빈 대조 아님)").isNotEmpty();
        assertThat(overrides.keySet()).as("설정 레코드가 읽지 않는 시험 키(오타는 조용히 버려진다)")
                .allMatch(leaves::containsKey);

        CoreProperties bound = bind(base, testYml);
        Map<String, Object> boundValues = new TreeMap<>(recordValues(bound, "mujoong"));
        LunaProperties luna = bindRecord(LunaProperties.class, "mujoong.luna", base, testYml);
        boundValues.putAll(recordValues(luna, "mujoong.luna"));
        boundValues.putAll(recordValues(bindRecord(KnowledgeProperties.class, "mujoong.knowledge.neo4j", base, testYml),
                "mujoong.knowledge.neo4j"));
        assertThat(valueMismatches(overrides, leaves, boundValues, placeholderResolver(base, testYml)))
                .as("시험 값이 레코드에 그대로 묶임").isEmpty();
        assertThat(LunaHosts.check(luna.baseUrl(), LunaHosts.Mode.TEST)).as("test 프로필 Luna 주소는 루프백(BR1.4)").isEmpty();
        assertThat(bound.timing().session().preparingTimeout()).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void mujoongPlaceholdersInProfileFilesPointAtDefinedKeys() {
        Set<String> defined = mujoongKeys(flatten(MAIN_RESOURCES.resolve("application.yml"))).keySet();
        List<String> references = new ArrayList<>();
        for (Path file : applicationYmls(MAIN_RESOURCES)) {
            for (Object value : flatten(file).values()) {
                Matcher matcher = Pattern.compile("\\$\\{(mujoong\\.[^}:]+)").matcher(String.valueOf(value));
                while (matcher.find()) {
                    references.add(matcher.group(1));
                }
            }
        }
        assertThat(references).as("세션 쿠키 설정이 읽는 mujoong 키")
                .contains("mujoong.timing.login.session-idle", "mujoong.security.session-cookie-max-age");
        assertThat(references).allMatch(defined::contains);
    }

    // ------------------------------------------------------------------ A6 · SD-10 · SD-14 프로필 파일 규칙

    private static final String RATE_LIMIT = "mujoong.timing.bootstrap.rate-limit";

    @Test
    void bootstrapRateLimitIsRaisedOnlyInTheLoadTestProfile() {
        Map<String, Map<String, Object>> profiles = profileFiles();
        assertThat(profiles).containsKeys("application.yml", "application-dev.yml", "application-demo.yml",
                "application-prod.yml", "application-load-test.yml");

        for (String publicProfile : List.of("application-demo.yml", "application-prod.yml")) {
            assertThat(profiles.get(publicProfile).keySet()).as(publicProfile)
                    .noneMatch(key -> key.startsWith(RATE_LIMIT) || key.startsWith("mujoong.load-test"));
        }
        assertThat(profiles.get("application-load-test.yml")).as("load-test 파일은 스위치와 한도 상향만 둔다")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "mujoong.load-test.enabled", "true",
                        RATE_LIMIT + ".max", "600",
                        RATE_LIMIT + ".window", "1h"));
        assertThat(filesDefining(profiles, RATE_LIMIT)).containsExactlyInAnyOrder("application.yml",
                "application-load-test.yml");
        assertThat(filesDefining(profiles, "mujoong.load-test")).containsExactlyInAnyOrder("application.yml",
                "application-load-test.yml");
        assertThat(profiles.get("application.yml")).containsEntry("mujoong.load-test.enabled", "false");

        CoreProperties loadTest = bind(MAIN_RESOURCES.resolve("application.yml"),
                MAIN_RESOURCES.resolve("application-load-test.yml"));
        assertThat(loadTest.timing().bootstrap().rateLimit().max()).isEqualTo(600);
        assertThat(loadTest.loadTest().enabled()).isTrue();
    }

    @Test
    void noProfileFileEnablesDevLogin() {
        Map<String, Map<String, Object>> profiles = profileFiles();

        List<String> enabling = profiles.entrySet().stream()
                .filter(entry -> "true".equals(entry.getValue().get("mujoong.dev-login.enabled")))
                .map(Map.Entry::getKey)
                .toList();

        assertThat(enabling).as("시험 로그인을 켜는 프로필 파일(환경 변수로만 켠다, SD-14 · A2)").isEmpty();
        assertThat(profiles.get("application.yml")).containsEntry("mujoong.dev-login.enabled", "false");
    }

    // ------------------------------------------------------------------ 대조 도구 자기 시험

    @Test
    void comparatorsReportADefaultDriftATypoKeyAndAMissingKey() {
        String markdown = String.join("\n",
                "## 1. 표",
                "",
                "| 키 | 기본값 | 소유 앱 | 뜻 |",
                "|---|---|---|---|",
                "| `mujoong.outbox.poll-interval` | `500ms` | core-api · realtime | 폴러 |",
                "| `mujoong.outbox.retry-backoff` | `[1s, 2s]` | core-api | 대기 |",
                "| `mujoong.gate.segment-quiet` | `800ms` | realtime | 조용 |");
        List<TableRow> rows = parseTables(markdown, "키", "기본값", "소유 앱");
        Map<String, Object> yml = new LinkedHashMap<>();
        yml.put("mujoong.outbox.poll-interval", "501ms");
        yml.put("mujoong.outbox.retry-backoff", List.of("1s", "2s"));

        assertThat(rows).extracting(TableRow::key).hasSize(3);
        assertThat(defaultMismatches(rows.stream().filter(row -> row.owners().contains(APP)).toList(), yml))
                .singleElement().asString().contains("mujoong.outbox.poll-interval");
        assertThat(keyMismatches(Set.of("mujoong.outbox.poll-intervall", "mujoong.outbox.batch-size"),
                Set.of("mujoong.outbox.poll-interval", "mujoong.outbox.batch-size"), Set.of()))
                .anyMatch(problem -> problem.contains("mujoong.outbox.poll-intervall"))
                .anyMatch(problem -> problem.contains("mujoong.outbox.poll-interval"));
    }

    @Test
    void tableParserRejectsARowWithTheWrongNumberOfCells() {
        String markdown = String.join("\n",
                "| 키 | 기본값 | 소유 앱 |",
                "|---|---|---|",
                "| `mujoong.outbox.batch-size` | `20` | core-api | 넘친 칸 |");

        assertThatThrownBy(() -> parseTables(markdown, "키", "기본값", "소유 앱"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mujoong.outbox.batch-size");
    }

    // ------------------------------------------------------------------ 표 읽기

    /** 표 한 줄. 기본값 열이 없는 표(7절 예약 키)는 {@code defaultValue == null}. */
    record TableRow(String key, String defaultValue, String owners) {
    }

    private static List<TableRow> timingRows() {
        List<TableRow> rows = parseTables(read(REPO_ROOT.resolve("contracts/timing.md")), "키", "기본값", "소유 앱");
        assertThat(rows).as("timing.md 표 줄").hasSizeGreaterThan(50);
        return rows;
    }

    /**
     * 마크다운 표에서 {@code mujoong.*} 키 줄을 읽는다. 머리 줄의 열 이름으로 키 · 기본값 · 소유 열을 찾고, 칸 수가 머리와 다르면
     * 실패한다(칸이 밀려 다른 열을 읽지 않게).
     */
    static List<TableRow> parseTables(String markdown, String keyColumn, String defaultColumn, String ownerColumn) {
        List<TableRow> rows = new ArrayList<>();
        List<String> header = null;
        for (String line : markdown.lines().toList()) {
            if (!line.startsWith("|")) {
                header = null;
                continue;
            }
            List<String> cells = cells(line);
            if (cells.stream().allMatch(cell -> cell.matches(":?-+:?"))) {
                continue;
            }
            if (header == null) {
                header = cells;
                continue;
            }
            int keyIndex = header.indexOf(keyColumn);
            if (keyIndex < 0) {
                continue;
            }
            String key = unquote(cells.get(keyIndex));
            if (key == null || !key.startsWith("mujoong.")) {
                continue;
            }
            if (cells.size() != header.size()) {
                throw new IllegalStateException("표 칸 수가 머리와 다르다: " + key);
            }
            int defaultIndex = header.indexOf(defaultColumn);
            int ownerIndex = ownerColumn == null ? -1 : header.indexOf(ownerColumn);
            rows.add(new TableRow(key, defaultIndex < 0 ? null : unquote(cells.get(defaultIndex)),
                    ownerIndex < 0 ? "" : cells.get(ownerIndex)));
        }
        return rows;
    }

    private static List<String> cells(String line) {
        String inner = line.strip();
        inner = inner.substring(1, inner.endsWith("|") && inner.length() > 1 ? inner.length() - 1 : inner.length());
        List<String> cells = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    /** 칸 전체가 {@code `값`} 하나면 그 값, 아니면 null. */
    private static String unquote(String cell) {
        Matcher matcher = Pattern.compile("^`([^`]+)`$").matcher(cell);
        return matcher.matches() ? matcher.group(1) : null;
    }

    // ------------------------------------------------------------------ 대조

    static List<String> defaultMismatches(List<TableRow> rows, Map<String, Object> yml) {
        List<String> problems = new ArrayList<>();
        for (TableRow row : rows) {
            Object actual = yml.get(row.key());
            Object expected = row.defaultValue().startsWith("[") ? listLiteral(row.defaultValue()) : row.defaultValue();
            if (!Objects.equals(expected, actual)) {
                problems.add(row.key() + ": timing.md " + expected + " ≠ application.yml " + actual);
            }
        }
        return problems;
    }

    static List<String> keyMismatches(Set<String> ymlKeys, Set<String> recordKeys, Set<String> recordKeysWithoutDefault) {
        List<String> problems = new ArrayList<>();
        for (String key : new TreeSet<>(ymlKeys)) {
            if (!recordKeys.contains(key)) {
                problems.add("설정 레코드가 읽지 않는 yml 키: " + key);
            }
        }
        for (String key : new TreeSet<>(recordKeys)) {
            if (!ymlKeys.contains(key) && !recordKeysWithoutDefault.contains(key)) {
                problems.add("yml에 기본값이 없는 레코드 키: " + key);
            }
        }
        return problems;
    }

    static List<String> valueMismatches(Map<String, Object> yml, Map<String, RecordComponent> leaves,
            Map<String, Object> bound, PropertySourcesPlaceholdersResolver resolver) {
        ConversionService conversion = ApplicationConversionService.getSharedInstance();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Object> entry : yml.entrySet()) {
            RecordComponent component = leaves.get(entry.getKey());
            if (component == null) {
                continue; // 키 대조가 따로 잡는다
            }
            Object expected;
            if (entry.getValue() instanceof List<?> values) {
                Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
                expected = values.stream().map(value -> conversion.convert(resolver.resolvePlaceholders(value), element))
                        .toList();
            } else {
                expected = conversion.convert(resolver.resolvePlaceholders(entry.getValue()), component.getType());
            }
            Object actual = bound.get(entry.getKey());
            if (!Objects.equals(expected, actual)) {
                problems.add(entry.getKey() + ": yml " + expected + " ≠ 레코드 " + actual);
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------ yml · 레코드 읽기

    /** yml을 Spring이 읽는 키로 펼친다. 목록({@code a[0]})은 키 하나의 문자열 목록으로 모으고 값은 문자열로 둔다. */
    static Map<String, Object> flatten(Path file) {
        Map<String, Object> flat = new TreeMap<>();
        Map<String, Map<Integer, String>> lists = new TreeMap<>();
        Pattern indexed = Pattern.compile("^(.*)\\[(\\d+)]$");
        for (PropertySource<?> source : load(file)) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                String value = String.valueOf(enumerable.getProperty(name));
                Matcher matcher = indexed.matcher(name);
                if (matcher.matches()) {
                    lists.computeIfAbsent(matcher.group(1), key -> new TreeMap<>())
                            .put(Integer.parseInt(matcher.group(2)), value);
                } else {
                    flat.put(name, value);
                }
            }
        }
        lists.forEach((key, values) -> flat.put(key, List.copyOf(values.values())));
        return flat;
    }

    private static Map<String, Object> mujoongKeys(Map<String, Object> flat) {
        Map<String, Object> mujoong = new TreeMap<>();
        flat.forEach((key, value) -> {
            if (key.startsWith("mujoong.")) {
                mujoong.put(key, value);
            }
        });
        return mujoong;
    }

    private static List<PropertySource<?>> load(Path file) {
        try {
            return new YamlPropertySourceLoader().load(file.getFileName().toString(), new FileSystemResource(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 뒤 파일이 앞 파일을 덮는다(Spring 프로필 순서와 같음). 환경 변수 자리표시자는 {@link #ENV}로 채운다. */
    private static MutablePropertySources sources(Path... files) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("env", ENV));
        for (int i = files.length - 1; i >= 0; i--) {
            load(files[i]).forEach(sources::addLast);
        }
        return sources;
    }

    private static PropertySourcesPlaceholdersResolver placeholderResolver(Path... files) {
        return new PropertySourcesPlaceholdersResolver(sources(files));
    }

    static CoreProperties bind(Path... files) {
        MutablePropertySources sources = sources(files);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources), ApplicationConversionService.getSharedInstance());
        return binder.bind("mujoong", Bindable.of(CoreProperties.class))
                .orElseThrow(() -> new IllegalStateException("mujoong 설정을 묶지 못했다"));
    }

    /** 레코드 구성 요소를 케밥 키로 펼친다(중첩 레코드는 내려간다). */
    static Map<String, RecordComponent> recordLeaves(Class<?> type, String prefix) {
        Map<String, RecordComponent> leaves = new TreeMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            String key = prefix + "." + kebab(component.getName());
            if (component.getType().isRecord()) {
                leaves.putAll(recordLeaves(component.getType(), key));
            } else {
                leaves.put(key, component);
            }
        }
        return leaves;
    }

    static Map<String, Object> recordValues(Object record, String prefix) {
        Map<String, Object> values = new TreeMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            String key = prefix + "." + kebab(component.getName());
            Object value;
            try {
                value = component.getAccessor().invoke(record);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(key, e);
            }
            if (component.getType().isRecord() && value != null) {
                values.putAll(recordValues(value, key));
            } else {
                values.put(key, value);
            }
        }
        return values;
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String group(String key) {
        String rest = key.substring("mujoong.".length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private static List<String> listLiteral(String literal) {
        String inner = literal.substring(1, literal.length() - 1);
        List<String> items = new ArrayList<>();
        for (String item : inner.split(",")) {
            items.add(item.strip());
        }
        return items;
    }

    private static Map<String, Map<String, Object>> profileFiles() {
        Map<String, Map<String, Object>> profiles = new TreeMap<>();
        for (Path file : applicationYmls(MAIN_RESOURCES)) {
            profiles.put(file.getFileName().toString(), mujoongKeys(flatten(file)));
        }
        return profiles;
    }

    private static List<String> filesDefining(Map<String, Map<String, Object>> profiles, String prefix) {
        return profiles.entrySet().stream()
                .filter(entry -> entry.getValue().keySet().stream().anyMatch(key -> key.startsWith(prefix)))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<Path> applicationYmls(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().matches("application.*\\.ya?ml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** U5a 키는 application.yml 한 곳에 있고 타입 있는 설정 레코드로 그대로 묶인다(U5a 리뷰 5 · timing.md 9절). */
    @Test
    @Tag("practice-core")
    void u5aKeysLiveInApplicationYmlAndBindToTypedRecords() {
        Path base = MAIN_RESOURCES.resolve("application.yml");
        try (Stream<Path> files = Files.list(MAIN_RESOURCES)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .as("설정 기본값은 application*.yml에만 둔다(따로 읽는 .properties 없음)")
                    .noneMatch(name -> name.endsWith(".properties"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        TextAiProperties ai = bindRecord(TextAiProperties.class, "mujoong.text-ai", base);
        assertThat(ai.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(ai.result().requestTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(ai.retry().maxRetries()).isEqualTo(1);
        assertThat(ai.retry().backoff()).isEqualTo(Duration.ofMillis(250));
        assertThat(bindRecord(KnowledgeProperties.class, "mujoong.knowledge.neo4j", base).enabled()).isTrue();
        assertThat(bindRecord(KnowledgeProperties.class, "mujoong.knowledge.neo4j", base,
                TEST_RESOURCES.resolve("application-test.yml")).enabled()).isFalse();
        CoreProperties bound = bind(base);
        assertThat(bound.timing().result().attemptTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(bound.timing().practice().scenarioRequestTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(bound.timing().goal().restoreWindow()).isEqualTo(Duration.ofSeconds(5));
    }

    private static <T> T bindRecord(Class<T> type, String prefix, Path... files) {
        MutablePropertySources sources = sources(files);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources),
                ApplicationConversionService.getSharedInstance());
        return binder.bind(prefix, Bindable.of(type)).orElseThrow(() -> new IllegalStateException(prefix + " 설정이 없다"));
    }
}
