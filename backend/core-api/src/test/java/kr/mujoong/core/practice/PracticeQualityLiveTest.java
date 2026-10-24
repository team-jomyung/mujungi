package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.consent.ConsentStatusRule;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.identity.SeedIds;
import kr.mujoong.core.knowledge.ChildKnowledgeGraphPort;
import kr.mujoong.core.knowledge.ChildKnowledgeService;
import kr.mujoong.core.knowledge.KnowledgeAccessPolicy;
import kr.mujoong.core.knowledge.KnowledgeLedgerRepository;
import kr.mujoong.core.knowledge.KnowledgeSeed;
import kr.mujoong.core.knowledge.MemoryChildKnowledgeGraph;
import kr.mujoong.core.knowledge.Neo4jChildKnowledgeGraph;
import kr.mujoong.core.knowledge.SeedArchiveLoader;
import kr.mujoong.core.knowledge.SeedCatalog;
import kr.mujoong.core.knowledge.VariantPlanner;
import kr.mujoong.core.luna.CoreLunaClient;
import kr.mujoong.core.luna.LunaCallBudget;
import kr.mujoong.core.luna.LunaException;
import kr.mujoong.core.luna.LunaFailureReason;
import kr.mujoong.core.luna.LunaHosts;
import kr.mujoong.core.luna.LunaInputSanitizer;
import kr.mujoong.core.luna.LunaProperties;
import kr.mujoong.core.luna.LunaPurpose;
import kr.mujoong.core.luna.LunaRequest;
import kr.mujoong.core.luna.LunaResult;
import kr.mujoong.core.luna.LunaSchemas;
import kr.mujoong.core.luna.LunaTransport;
import kr.mujoong.core.luna.adapter.LunaTextAiProvider;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.practice.start.FrozenGoal;
import kr.mujoong.core.practice.start.PreparedPracticePackageMapper;
import kr.mujoong.core.practice.start.WeightedGoalOrder;
import kr.mujoong.core.practicegoal.FourElements;
import kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView;
import kr.mujoong.core.result.ResultData;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실호출 연습 대본 하니스 — core, 연습 대화 C안(docs/practice-direct-ask.md 11장 5회차 실측). 실제 Luna로 C안 대본
 * ({@value ScenarioScriptProvider#SCRIPT_VERSION}, 생성 {@value ScenarioScriptProvider#GENERATION_PROMPT_VERSION} · 검토
 * {@value ScenarioScriptProvider#REVIEW_PROMPT_VERSION})을 운영 생성 경로 그대로 만들고, realtime 음성 하니스가 읽을 시작 패키지를 파일로 남긴다.
 *
 * <ul>
 *   <li>기본 {@code test} · {@code check} · CI에서는 돌지 않는다({@code @Tag("luna-live")}: build-logic 규약이 기본 test에서 빼고 키도 지운다).
 *       사람이 env {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew :core-api:lunaLiveTest --tests "*PracticeQualityLiveTest*"}로만 돈다(R-F1).
 *   <li>키가 없으면 요약 파일에 {@code SKIPPED_NO_KEY}만 쓰고 건너뛴다(통과로 보고하지 않음). 키는 env에 있는지만 보고 값을 들고 있지 않는다.
 *   <li>경로: 실제 PostgreSQL · Neo4j(Testcontainers) + 지식 그래프 기본 씨앗 14개 → 운영 조립 그대로의 {@link ScenarioService}
 *       ({@code connectStructured}가 만드는 {@link StructuredScenarioFlow} · {@link ScenarioScriptGenerator} · {@link ScenarioGraphContext}) →
 *       {@link LunaTextAiProvider} → {@link CoreLunaClient}(STRICT 주소 규칙) → {@link ScenarioRepository}(요청 선점 · 발급 저장) →
 *       {@link PreparedPracticePackageMapper#c8}. 시작 준비(PracticeStartService.prepare)처럼 {@link ScenarioService#generatePrepared}로 부른다.
 *   <li>입력은 합성 자료뿐이다(합성 아동 프로필 · 하니스가 쓴 합성 4요소). 출력에는 평가용 합성 대본 · 보낸 입력(정리 뒤) · 응답 JSON을 담고 키 · 요청
 *       머리 · 주소는 담지 않는다(R-F3 · R-F14). 콘솔에는 개수와 파일 위치만 쓴다. 증거 종류는 '실제 API · 합성 자료'다(R-M11).
 *   <li>운영 시한(상황 요청 · 호출 · 그래프 · 이력)은 바꾸지 않는다. 하니스는 느린 컨테이너와 실호출을 위해 넉넉한 시한으로 돌고, 실제로 걸린 시간과 운영
 *       예산 안이었는지를 함께 적는다. 재시도 · 추론 강도 · 출력 한도 · 판은 application.yml 값 그대로다.
 *   <li>OpenAI 호출은 하니스 전체에서 {@value #CALL_CAP}회를 넘지 않는다.
 * </ul>
 *
 * <p>출력 위치: 시스템 속성 {@code mujoong.live.eval.out} → env {@code MUJOONG_LIVE_EVAL_OUT} → {@code build/live-eval/} 순으로 고른다. 그 아래
 * {@value #SUMMARY_FILE}(요약)과 {@value #PACKAGE_DIR}{@code /<목표>.json}(시작 요청 {@code internal-session-start-request}의 {@code practice}
 * 조각 = {@link PreparedPracticePackageMapper#c8} 출력, 목표 하나 · 첫 대본 포함)을 쓴다. 지난 결과는 {@value #PREVIOUS_DIR}{@code /}로 옮긴다.
 */
@Tag("luna-live")
class PracticeQualityLiveTest {

    static final String SUMMARY_FILE = "core-practice-packages.json";
    static final String PACKAGE_DIR = "practice-start-packages";
    static final String PREVIOUS_DIR = "previous-run";
    static final String SKIPPED = "SKIPPED_NO_KEY";
    static final String EVIDENCE = "REAL_API_SYNTHETIC";
    static final String EVIDENCE_LABEL = "실제 API · 합성 자료(테스트 대역 · 실제 기기 · 실제 아이 증거 아님)";

    /** 하니스 전체 OpenAI 호출 상한(목표 6개 × 생성기 한 번의 호출 상한 6 = 36, 여유 포함). */
    static final int CALL_CAP = 40;
    /** 운영 생성기({@link ScenarioScriptGenerator}) 한 번이 쓸 수 있는 공급자 호출 수(생성 · 검토 · 보완 · 기술 재시도 포함). */
    static final int CALLS_PER_GOAL = 6;

    // 하니스 전용 넉넉한 시한. 운영 값은 application.yml에서 읽어 비교만 한다.
    // 호출 하나가 시한을 넘으면 그 목표 전체가 실패하므로(시간 초과는 생성기에서 영구 실패) 호출 시한을 넉넉히 둔다
    static final Duration LUNA_CALL_LIMIT = Duration.ofSeconds(90);
    static final Duration SCENARIO_LIMIT = Duration.ofMinutes(10);
    static final Duration KNOWLEDGE_LIMIT = Duration.ofSeconds(30);
    static final Duration KNOWLEDGE_INIT_LIMIT = Duration.ofSeconds(60);
    static final Duration HISTORY_LIMIT = Duration.ofSeconds(10);
    static final Duration NEO4J_STARTUP = Duration.ofMinutes(4);

    /** 합성 아동 프로필(실제 아동 아님). 이름은 별칭 '아이'로 바뀌는지 보기 위한 합성 값이다. */
    static final String SYNTHETIC_NAME = "합성아이";
    static final int SYNTHETIC_AGE = 8;
    static final List<String> SYNTHETIC_INTERESTS = List.of("공룡", "그림");

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Test
    void realLunaBuildsDirectAskStartPackagesThroughTheProductionGenerationPath() throws IOException {
        Path out = outputDirectory();
        boolean keyPresent = keyPresent();
        if (!keyPresent) {
            writeSkipped(out);
        }
        assumeTrue(keyPresent, SKIPPED + ": env OPENAI_API_KEY 없음(실호출 안 함)");

        Harness.Outcome outcome = new Harness(out).run();

        // 콘솔에는 개수와 파일 위치만 쓴다(생성 원문은 파일에만, R-F14)
        System.out.println(outcome.consoleLine());
        assertThat(outcome.issued()).as("발급된 시작 패키지 수(요약 " + outcome.summary() + ")").isPositive();
    }

    // ------------------------------------------------------------------ 출력 위치 · 키 없음

    static Path outputDirectory() {
        String property = System.getProperty("mujoong.live.eval.out");
        String env = System.getenv("MUJOONG_LIVE_EVAL_OUT");
        String chosen = property != null && !property.isBlank() ? property
                : env != null && !env.isBlank() ? env : "build/live-eval";
        return Path.of(chosen).toAbsolutePath().normalize();
    }

    /** 키가 있는지만 본다. 값은 변수에 담지 않는다. */
    static boolean keyPresent() {
        return Optional.ofNullable(System.getenv(LunaTransport.API_KEY_ENV)).filter(value -> !value.isBlank()).isPresent();
    }

    /** 키 없음: 요약 파일에 SKIPPED_NO_KEY만 쓴다. 지난 실호출 요약이 있으면 덮기 전에 previous-run/으로 옮긴다. */
    static void writeSkipped(Path out) throws IOException {
        Files.createDirectories(out);
        Path summary = out.resolve(SUMMARY_FILE);
        if (Files.exists(summary) && !Files.readString(summary, StandardCharsets.UTF_8).contains(SKIPPED)) {
            Path previous = Files.createDirectories(out.resolve(PREVIOUS_DIR));
            Files.move(summary, previous.resolve(SUMMARY_FILE), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(summary, "{\"outcome\":\"" + SKIPPED + "\"}" + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 합성 목표

    /**
     * 평가할 목표 하나. 지식 그래프 기본 씨앗({@code seedId})에 하니스가 합성 4요소를 붙인 후보 목표다(운영에서 목표 후보 생성이 씨앗 목록의 seedId를
     * skillRef로 고른 상태와 같다). {@code why}는 고른 까닭이다.
     */
    record GoalSpec(String key, String seedId, String displayName, String targetExpression, FourElements elements, String why) {
    }

    /**
     * 기능 묶음이 서로 다른 씨앗 6개. 1 ~ 4회차 하니스와 같은 씨앗 · 같은 합성 4요소라 회차끼리 견줄 수 있다. 상황 · 표현은 합성이다. 상대 이름은 목표 후보
     * 출력에 올 수 있는 말을 그대로 썼다(관계 코드로 바뀌는지는 운영 graphRelation이 정한다).
     */
    static List<GoalSpec> seedGoals() {
        return List.of(
                new GoalSpec("s1-softened-refusal", "seed:S1", "돌려 말한 거절 알기", "그럼 다음에 같이 그리자",
                        new FourElements("같이 공룡 그림을 그리자고 했는데 친구가 오늘은 손이 좀 아프다고 말할 때", "친구",
                                "친구가 지금은 같이 그리고 싶지 않다는 뜻을 알아차리고 받아들인다", "그럼 다음에 같이 그리자"),
                        "돌려 말한 뜻을 알아차리고 받아들이기(RECOGNIZE). 설계 2장 예시 대화와 같은 짜임이라 C안 기준 대화와 바로 견줄 수 있다"),
                new GoalSpec("s4-listener-knowledge", "seed:S4", "모르는 친구에게 설명", "스테고사우루스는 등에 뾰족한 판이 있는 공룡이야",
                        new FourElements("공룡을 잘 모르는 친구에게 내가 좋아하는 공룡 이야기를 꺼낼 때", "친구",
                                "친구가 모를 수 있는 공룡 이름을 먼저 짧게 알려 주고 이야기한다", "스테고사우루스는 등에 뾰족한 판이 있는 공룡이야"),
                        "상대가 모르는 것을 짐작해 먼저 알려 주기(PRODUCE). 상대의 지식 상태를 읽는 유일한 묶음이다"),
                new GoalSpec("s6-clarify-repair", "seed:S6", "다시 말해 바로잡기", "아니, 내가 말한 건 파란색이야",
                        new FourElements("모둠 그림을 그리다가 파란색을 달라고 했는데 친구가 초록색을 건넬 때", "모둠 친구",
                                "친구가 내 말을 다르게 알아들었다는 걸 알고 다시 분명하게 말한다", "아니, 내가 말한 건 파란색이야"),
                        "내 말을 다르게 알아들은 상대에게 다시 말하기(REPAIR). 고친 말을 해야 하는 유일한 REPAIR 기능이다"),
                new GoalSpec("s8-topic-shift-signal", "seed:S8", "화제 바꾸는 신호 알기", "주말에는 그림 그릴 거야. 너는 뭐 해?",
                        new FourElements("내가 공룡 이야기를 오래 하는데 친구가 그런데 주말에 뭐 하냐고 물을 때", "친구",
                                "친구가 다른 이야기를 하고 싶어 한다는 걸 알아차리고 친구의 질문에 답한다", "주말에는 그림 그릴 거야. 너는 뭐 해?"),
                        "상대가 화제를 옮기고 싶어 하는 신호 알아채기(RECOGNIZE). 대화 흐름을 다루는 묶음이다"),
                new GoalSpec("s10-gentle-request", "seed:S10", "부담 없이 부탁하기", "형, 다 쓰고 나서 잠깐 빌려줄 수 있어?",
                        new FourElements("그리기 동아리에서 형이 쓰고 있는 색연필을 잠깐 빌리고 싶을 때", "동아리 형",
                                "형이 부담스럽지 않게 부드럽게 부탁한다", "형, 다 쓰고 나서 잠깐 빌려줄 수 있어?"),
                        "윗사람에게 부담 없이 부탁하기(PRODUCE). 관계에 맞춰 말 수위를 고르는 묶음이고 askPrompt 호칭이 relation에 맞는지 본다"),
                new GoalSpec("x4-answer-request-first", "seed:X4", "부탁에 먼저 대답하기", "응, 여기 있어. 다 쓰면 돌려줘",
                        new FourElements("그리기 동아리에서 그림을 그리는데 동아리 친구가 크레파스를 빌려 달라고 할 때", "동아리 친구",
                                "친구의 부탁을 듣고 먼저 대답한 뒤에 내 이야기를 한다", "응, 여기 있어. 다 쓰면 돌려줘"),
                        "상대 부탁에 먼저 대답하기(PRODUCE). 주고받기 차례를 지키는 묶음이다"));
    }

    // ------------------------------------------------------------------ 하니스

    /** 조립 · 실행 · 기록. */
    static final class Harness {

        record Outcome(int issued, int variants, int lunaCalls, Path summary) {
            String consoleLine() {
                return "PracticeQualityLiveTest: 발급 " + issued + "건(VARIANT 포함 " + variants + "건), Luna 호출 " + lunaCalls
                        + "회, 증거=" + EVIDENCE + ", 요약=" + summary;
            }
        }

        private final Path out;
        private final MujoongClock clock = new SystemMujoongClock();
        private final LunaInputSanitizer sanitizer = new LunaInputSanitizer(JSON);
        private final DiagnosticCodes diagnostics = new DiagnosticCodes(clock);
        // 운영 ScenarioPrivacyContextAdapter 자리: 프로필 이름(합성)을 별칭 '아이'로 바꾸는 표만 준다
        private final ScenarioPrivacyContextPort privacy = (guardianId, childId) -> new ScenarioPrivacyContextPort.Context(SYNTHETIC_NAME);
        private final Map<String, Object> summary = new LinkedHashMap<>();
        private final List<AutoCloseable> closers = new ArrayList<>();
        private final List<UUID> owners = new ArrayList<>();
        private ProductionBudgets production;
        private RecordingLunaClient luna;
        private DbTestSupport db;
        private ScenarioRepository repository;
        private UUID guardian;
        private UUID child;
        private SeedCatalog catalog;
        private ChildKnowledgeService knowledge;
        private ExecutorService workers;

        Harness(Path out) {
            this.out = out;
        }

        Outcome run() throws IOException {
            prepareOutput();
            summary.put("harness", "PracticeQualityLiveTest(core, 연습 대화 C안)");
            summary.put("evidence", EVIDENCE);
            summary.put("evidenceLabel", EVIDENCE_LABEL);
            summary.put("startedAt", clock.now().toString());
            summary.put("notes", notes());
            summary.put("versions", versions());
            List<Map<String, Object>> goals = new ArrayList<>();
            try {
                production = ProductionBudgets.load();
                summary.put("budgets", budgets());
                workers = Executors.newVirtualThreadPerTaskExecutor();
                luna = client();
                Map<String, Object> model = new LinkedHashMap<>();
                model.put("requested", luna.requestedModel());
                model.put("baseUrlRule", "STRICT(LunaHosts 허용 목록, 주소는 적지 않는다)");
                summary.put("model", model);
                summary.put("syntheticChild", syntheticChild());
                openDatabase();
                summary.put("graph", openGraph());
                summary.put("goalSelection", goalSelection());
                summary.put("goals", goals);
                ScenarioService scenarios = scenarioService();
                for (GoalSpec spec : seedGoals()) {
                    goals.add(runGoal(spec, scenarios));
                }
            } catch (RuntimeException | IOException failure) {
                // 중간에 끊겨도 그때까지 모은 기록은 남긴다(원인은 예외 종류만)
                summary.put("aborted", failure.getClass().getSimpleName());
                throw failure;
            } finally {
                summary.putIfAbsent("goals", goals);
                summary.put("finishedAt", clock.now().toString());
                try {
                    summary.put("totals", totals(goals));
                } catch (RuntimeException failure) {
                    // 합계 계산이 깨져도 목표별 기록은 파일로 남긴다
                    summary.put("totalsError", failure.getClass().getSimpleName());
                }
                writePretty(out.resolve(SUMMARY_FILE), summary);
                closeAll();
            }
            int issued = (int) goals.stream().filter(g -> "ISSUED".equals(g.get("result"))).count();
            int variants = (int) goals.stream()
                    .filter(g -> Boolean.TRUE.equals(ResultData.object(g.get("graph")).get("variantIssued"))).count();
            return new Outcome(issued, variants, luna == null ? 0 : luna.sent(), out.resolve(SUMMARY_FILE));
        }

        // -------------------------------------------------------------- 조립

        /** 지난 실행 결과는 지우지 않고 previous-run/으로 옮긴다(realtime 하니스가 오래된 패키지를 읽지 않게). */
        private void prepareOutput() throws IOException {
            Files.createDirectories(out);
            Path previous = out.resolve(PREVIOUS_DIR);
            Path summaryFile = out.resolve(SUMMARY_FILE);
            Path packages = out.resolve(PACKAGE_DIR);
            if (Files.exists(summaryFile) || Files.exists(packages)) {
                deleteTree(previous);
                Files.createDirectories(previous);
                if (Files.exists(summaryFile)) {
                    Files.move(summaryFile, previous.resolve(SUMMARY_FILE));
                }
                if (Files.exists(packages)) {
                    Files.move(packages, previous.resolve(PACKAGE_DIR));
                }
            }
            Files.createDirectories(packages);
        }

        private RecordingLunaClient client() {
            LunaProperties properties = lunaProperties(production, System.getenv("OPENAI_ANALYSIS_MODEL"));
            LunaHosts.Mode mode = LunaHosts.modeFor();
            if (mode != LunaHosts.Mode.STRICT || LunaHosts.check(properties.baseUrl(), mode).isPresent()) {
                throw new IllegalStateException("STRICT 주소 규칙이 아니다");
            }
            var transport = new LunaTransport(properties.baseUrl(), mode, () -> System.getenv(LunaTransport.API_KEY_ENV),
                    properties.maxResponseBytes());
            closers.add(transport);
            return new RecordingLunaClient(properties, harnessTiming(production.textAi()), transport, new TestEvents(clock), clock);
        }

        private void openDatabase() {
            db = DbTestSupport.coreApp();
            closers.add(db);
            // 이 하니스가 만든 상황 행(요청 선점 · 발급)은 DbTestSupport 정리 대상이 아니라 따로 지운다(닫을 때 db보다 먼저 돈다)
            closers.add(this::deleteScenarioRows);
            repository = new ScenarioRepository(db.jdbc(), JSON);
            newOwner();
        }

        /** 합성 보호자 · 아이(보호자당 아이 한 명). 운영 계정 포트(JdbcTestPracticeAccounts)가 이 행에서 나이 · 관심사를 읽는다. */
        private void newOwner() {
            guardian = db.insertGuardian("ACTIVE");
            child = db.insertChild(guardian);
            owners.add(guardian);
            db.jdbc().update("""
                    update core.core_child set display_name = :name, age = :age, interests_json = cast(:interests as jsonb)
                     where id = :id
                    """, new MapSqlParameterSource("name", SYNTHETIC_NAME).addValue("age", SYNTHETIC_AGE)
                    .addValue("interests", JSON.write(SYNTHETIC_INTERESTS)).addValue("id", child));
        }

        private void deleteScenarioRows() {
            if (owners.isEmpty()) {
                return;
            }
            var params = new MapSqlParameterSource("ids", owners.toArray(UUID[]::new));
            db.jdbc().update("delete from core.core_scenario_request where guardian_id = any(:ids)", params);
            db.jdbc().update("delete from core.core_scenario where guardian_id = any(:ids)", params);
        }

        /**
         * 지식 그래프: Neo4j 컨테이너 → (실패하면) 메모리 그래프 → (그것도 실패하면) 그래프 없음. 어느 쪽인지와 까닭을 기록한다. 메모리 그래프는 운영이
         * Neo4j를 끈 설정에서 쓰는 같은 포트 구현이다.
         */
        private Map<String, Object> openGraph() throws IOException {
            Map<String, Object> report = new LinkedHashMap<>();
            try (InputStream source = PracticeQualityLiveTest.class.getResourceAsStream("/knowledge/social-comm-poc.zip")) {
                if (source == null) {
                    throw new IllegalStateException("씨앗 원천 자원이 없다");
                }
                catalog = new SeedArchiveLoader().load(source, "poc-v1");
            }
            report.put("catalogVersion", catalog.version());
            report.put("catalogSeeds", catalog.seeds().size());
            List<String> fallbacks = new ArrayList<>();
            String backend = null;
            try {
                if (initialize(neo4j(), true)) {
                    backend = "NEO4J_TESTCONTAINER";
                } else {
                    fallbacks.add("NEO4J:INITIALIZATION_FAILED");
                }
            } catch (RuntimeException failure) {
                fallbacks.add("NEO4J:" + failure.getClass().getSimpleName());
            }
            if (backend == null) {
                // 실패한 Neo4j 시도의 장부 상태와 섞이지 않게 새 보호자 · 아이로 다시 시작한다
                newOwner();
                try {
                    if (initialize(new MemoryChildKnowledgeGraph(), true)) {
                        backend = "MEMORY_FALLBACK";
                    } else {
                        fallbacks.add("MEMORY:INITIALIZATION_FAILED");
                    }
                } catch (RuntimeException failure) {
                    fallbacks.add("MEMORY:" + failure.getClass().getSimpleName());
                }
            }
            if (backend == null) {
                initialize(new MemoryChildKnowledgeGraph(), false);
                backend = "NONE";
                report.put("effect", "그래프 맥락 없이 BASE만 만드는 경로로 돈다(VARIANT 없음)");
            }
            report.put("backend", backend);
            report.put("fallbacks", fallbacks);
            report.put("childSeeds", knowledge.seedSummaries(guardian, child).size());
            return report;
        }

        private ChildKnowledgeGraphPort neo4j() {
            String password = UUID.randomUUID().toString();
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("neo4j:5.26.31-community"))
                    .withEnv("NEO4J_AUTH", "neo4j/" + password).withExposedPorts(7474)
                    .withEnv("NEO4J_server_memory_heap_initial__size", "256m")
                    .withEnv("NEO4J_server_memory_heap_max__size", "512m")
                    .withEnv("NEO4J_server_memory_pagecache_size", "128m")
                    .waitingFor(Wait.forHttp("/").forPort(7474).forStatusCode(200).withStartupTimeout(NEO4J_STARTUP));
            closers.add(container::stop);
            container.start();
            var graph = new Neo4jChildKnowledgeGraph(URI.create("http://" + container.getHost() + ":" + container.getMappedPort(7474)),
                    "neo4j/" + password, KNOWLEDGE_LIMIT, KNOWLEDGE_INIT_LIMIT, clock);
            graph.prepareSchema();
            return graph;
        }

        /** GraphWorkflowIT와 같은 조립. 접근 정책만 보호자 잠금을 거는 대역이다(동의 · 계정 조회는 이 하니스 범위 밖). */
        private boolean initialize(ChildKnowledgeGraphPort graph, boolean enabled) {
            var ledger = new KnowledgeLedgerRepository(db.jdbc());
            var access = mock(KnowledgeAccessPolicy.class);
            var lock = new GuardianWriteLock(db.jdbc());
            UUID owner = guardian;
            UUID kid = child;
            doAnswer(c -> {
                lock.lockActive(owner);
                return null;
            }).when(access).requireAllowed(eq(owner), any());
            doAnswer(c -> {
                lock.statusForUpdate(owner);
                return null;
            }).when(access).requireDeletionOwner(eq(owner), any());
            if (knowledge != null) {
                knowledge.close();
            }
            knowledge = new ChildKnowledgeService(ledger, access, graph, db.tx().getTransactionManager(), clock, catalog, enabled,
                    KNOWLEDGE_LIMIT);
            ChildKnowledgeService service = knowledge;
            closers.add(() -> cleanKnowledge(service, ledger, owner, kid));
            return enabled && knowledge.initializeChild(owner, kid) && knowledge.seedSummaries(owner, kid).size() == catalog.seeds().size();
        }

        private void cleanKnowledge(ChildKnowledgeService service, KnowledgeLedgerRepository ledger, UUID owner, UUID kid) {
            try {
                if (service.enabled()) {
                    service.deleteChild(owner, kid);
                }
            } catch (RuntimeException ignored) {
                // 정리 실패는 평가 결과에 영향이 없다. 아래 장부 정리와 DB 정리가 이어서 돈다
            } finally {
                service.close();
                db.inTx(t -> {
                    ledger.deleteLedger(kid, null);
                    ledger.forgetDeletedChildren(owner);
                    return null;
                });
            }
        }

        /**
         * 운영 조립 그대로의 상황 서비스. {@code connectStructured}가 운영과 같은 부품(생성기 · 서버 검사 · 그래프 맥락 · 저장소)을 만들고, 시한만 하니스
         * 값이다. 생성기의 진단 코드는 {@code connectDiagnostics}로 받는다(운영은 사건 로그 APP_ERROR). 동의는 '있음' 대역이다.
         */
        private ScenarioService scenarioService() {
            // 후보 목표만 쓰므로 행동 정의 고정(PragmaticsKnowledge) · 옛 생성 경로(TextAiGateway)는 부르지 않는다
            var service = new ScenarioService(repository, sessionId -> {
                throw new IllegalStateException("하니스는 시작 준비 경로(generatePrepared)만 쓴다");
            }, sessionId -> null, new JdbcTestPracticeAccounts(db.jdbc()), null, null, clock, db.tx(),
                    new ScenarioConfiguration.ScenarioLimits(SCENARIO_LIMIT));
            var consent = mock(ConsentPolicyService.class);
            when(consent.check(any())).thenReturn(new ConsentPolicyService.GateResult(true, ConsentStatusRule.Status.GIVEN, null));
            service.connectConsent(consent);
            service.connectDiagnostics(diagnostics);
            var properties = mock(CoreProperties.class, RETURNS_DEEP_STUBS);
            when(properties.timing().knowledge().graphBudget()).thenReturn(KNOWLEDGE_LIMIT);
            when(properties.timing().practice().scenarioHistoryTimeout()).thenReturn(HISTORY_LIMIT);
            service.connectStructured(new LunaTextAiProvider(luna, clock, JSON), privacy, sanitizer, knowledge, new ForbiddenExpressionPolicy(),
                    harnessTiming(production.textAi()), workers, properties, JSON);
            return service;
        }

        // -------------------------------------------------------------- 목표 하나: 상황 대본 → 시작 패키지

        private Map<String, Object> runGoal(GoalSpec spec, ScenarioService scenarios) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", spec.key());
            row.put("result", "RUNNING");
            KnowledgeSeed seed = seed(spec.seedId());
            if (luna.remaining() < CALLS_PER_GOAL) {
                row.put("result", "SKIPPED_CALL_CAP");
                return row;
            }
            luna.startBucket();
            diagnostics.start();
            // PracticeStartService.selection과 같은 정리: 표시 이름 · 4요소의 아동 이름을 별칭으로 바꾸고 불투명 식별자는 그대로 둔다
            Map<String, String> aliases = privacy.require(guardian, child).aliases();
            GoalContext original = new GoalContext(SavedGoalView.Kind.CANDIDATE, "live-" + spec.key(), 1, spec.targetExpression(),
                    spec.elements(), spec.seedId(), null, null);
            GoalContext goal = original.mapContent(text -> clean(text, aliases));
            String displayName = clean(spec.displayName(), aliases);
            UUID selection = UUID.randomUUID();
            UUID session = UUID.randomUUID();
            List<FrozenGoal> frozen = new WeightedGoalOrder().freeze(guardian, child, List.of(new WeightedGoalOrder.Selection(guardian,
                    child, selection, displayName, goal.carrierKey(), goal.carrierVersion(), goal.criteria(), 1, false, goal)),
                    RandomGenerator.getDefault());
            row.put("goal", goalReport(displayName, goal, seed));
            // PracticeStartService.context와 같은 세션 사본: 후보의 상대 문구가 관계가 되고, 준비 중이라 active=false다
            var context = new PracticeSessionContextPort.SessionContext(session, guardian, child, false, List.of(
                    new PracticeSessionContextPort.GoalSnapshot(selection, goal.carrierKey(), goal.carrierVersion(),
                            goal.fourElements().partner(), goal)));
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("ageYears", SYNTHETIC_AGE);
            input.put("interests", SYNTHETIC_INTERESTS);
            input.put("relation", goal.fourElements().partner());
            input.put("opportunityCondition", goal.fourElements().situation());
            input.put("targetExpression", goal.targetExpression());
            row.put("input", input);
            long startedNanos = clock.monotonicNanos();
            Scenario scenario = null;
            try {
                scenario = scenarios.generatePrepared(new ScenarioService.Request(session, selection, "ORIGINAL", null),
                        new ScenarioService.PreparedContext(clock.now().plus(SCENARIO_LIMIT), () -> context));
            } catch (ScenarioService.GenerationFailure failure) {
                Map<String, Object> reason = failureRecord(failure.code().name(), "GENERATION_FAILED");
                reason.put("permanent", failure.permanent());
                row.put("failure", reason);
            } catch (CoreApiException failure) {
                row.put("failure", failureRecord(failure.code().name(), "REQUEST_FAILED"));
            } catch (RuntimeException failure) {
                row.put("failure", failureRecord(failure.getClass().getSimpleName(), "UNEXPECTED"));
            }
            Duration elapsed = clock.elapsedSince(startedNanos);
            List<Map<String, Object>> calls = luna.finishBucket();
            List<String> codes = diagnostics.finish();
            if (scenario != null) {
                row.put("result", "ISSUED");
                try {
                    // 발급 대본 → 준비 원본 → C8 조각. PracticeStartService.prepare · start와 같은 조립이다
                    Map<String, Object> pack = new PreparedPracticePackageMapper().c8(new PreparedPracticePackageMapper.PreparedSnapshot(
                            session, UUID.randomUUID(), frozen, scenario));
                    Path file = out.resolve(PACKAGE_DIR).resolve(spec.key() + ".json");
                    writePretty(file, pack);
                    row.put("startPackageFile", out.relativize(file).toString());
                    row.put("script", scriptReport(scenario, pack));
                } catch (RuntimeException | IOException failure) {
                    row.put("result", "PACKAGE_FAILED");
                    row.put("failure", failureRecord(failure.getClass().getSimpleName(), "PACKAGE"));
                }
                row.put("heard", HeardLines.of(scenario, goal.targetExpression()));
            } else {
                row.put("result", "FAILED");
            }
            row.put("attempts", Attempts.of(calls, codes));
            row.put("timing", timing(elapsed, calls));
            row.put("graph", GraphReport.of(calls, scenario));
            row.put("calls", calls);
            return row;
        }

        /** 실패 기록(사유 코드 · 종류만, 예외 메시지는 담지 않는다). */
        private static Map<String, Object> failureRecord(String code, String kind) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("code", code);
            failure.put("kind", kind);
            return failure;
        }

        private KnowledgeSeed seed(String seedId) {
            return catalog.seeds().stream().filter(s -> s.seedId().equals(seedId)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("기본 씨앗이 없다: " + seedId));
        }

        private String clean(String text, Map<String, String> aliases) {
            return (String) sanitizer.sanitize(Map.of("text", text), aliases, LunaPurpose.SCENARIO_GENERATION).payload().get("text");
        }

        private Map<String, Object> goalReport(String displayName, GoalContext goal, KnowledgeSeed seed) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("displayName", displayName);
            report.put("skillRef", goal.skillRef());
            report.put("behaviorDefinition", goal.criteria());
            report.put("targetExpression", goal.targetExpression());
            report.put("fourElements", plain(goal.fourElements()));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("seedId", seed.seedId());
            s.put("label", seed.label());
            s.put("family", seed.family());
            s.put("targetSkill", seed.signature().targetSkill());
            s.put("requiredInference", seed.signature().requiredInference());
            s.put("invariantSentence", seed.invariantSentence());
            s.put("preservationRule", seed.preservationRule());
            s.put("relationship", seed.relationship());
            s.put("situation", seed.situation());
            report.put("seed", s);
            return report;
        }

        private Map<String, Object> scriptReport(Scenario scenario, Map<String, Object> pack) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("scriptVersion", scenario.scriptVersion());
            report.put("expectedScriptVersion", ScenarioScriptProvider.SCRIPT_VERSION);
            report.put("sceneKinds", scenes(scenario).stream().map(ScenarioScript.Scene::sceneKind).toList());
            report.put("generation", plain(scenario.generation()));
            report.put("selfReview", plain(scenario.selfReview()));
            report.put("storedRoundTrip", storedRoundTrip(scenario));
            report.put("firstScenarioSchemaErrors", schemaErrors(pack));
            report.put("scenes", plain(scenario.scenes()));
            return report;
        }

        /** 발급 대본을 DB에서 다시 읽은 값(시작 준비 재사용 경로가 읽는 값)과 같은가. */
        private Object storedRoundTrip(Scenario scenario) {
            try {
                return JSON.write(repository.require(scenario.scenarioId()).scenario()).equals(JSON.write(scenario));
            } catch (RuntimeException failure) {
                return "UNAVAILABLE:" + failure.getClass().getSimpleName();
            }
        }

        /** 시작 패키지 첫 대본이 계약 practice-scenario.schema.json을 지키는가(어긴 곳 목록, 비면 통과). */
        private static Object schemaErrors(Map<String, Object> pack) {
            try {
                Object first = ResultData.objects(pack.get("goals")).getFirst().get("firstScenario");
                return WebFixtures.schemaErrors("practice-scenario.schema.json", JSON.write(first));
            } catch (RuntimeException failure) {
                return "UNAVAILABLE:" + failure.getClass().getSimpleName();
            }
        }

        // -------------------------------------------------------------- 기록 정리

        private Map<String, Object> timing(Duration elapsed, List<Map<String, Object>> calls) {
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("elapsedMs", elapsed.toMillis());
            timing.put("productionBudgetMs", production.scenarioRequest().toMillis());
            timing.put("withinProductionBudget", elapsed.compareTo(production.scenarioRequest()) <= 0);
            timing.put("productionPerCallTimeoutMs", production.textAiRequest().toMillis());
            // 성공 호출은 공급자 지연, 실패 호출은 호출부에서 잰 시간
            List<Long> latencies = snapshot(calls).stream().map(c -> c.get("latencyMs") instanceof Number n ? n.longValue()
                    : c.get("wallMs") instanceof Number n ? n.longValue() : -1L).toList();
            timing.put("callLatenciesMs", latencies);
            timing.put("callsOverProductionTimeout", latencies.stream().filter(l -> l > production.textAiRequest().toMillis()).count());
            timing.put("lunaLatencySumMs", latencies.stream().filter(l -> l >= 0).mapToLong(Long::longValue).sum());
            return timing;
        }

        private Map<String, Object> totals(List<Map<String, Object>> goals) {
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("lunaCalls", luna == null ? 0 : luna.sent());
            totals.put("callCap", CALL_CAP);
            totals.put("goals", goals.size());
            totals.put("issued", count(goals, g -> "ISSUED".equals(g.get("result"))));
            totals.put("notIssued", goals.stream().filter(g -> !"ISSUED".equals(g.get("result")))
                    .map(g -> g.get("key") + ":" + g.get("result")).toList());
            totals.put("variantIssued", count(goals, g -> Boolean.TRUE.equals(ResultData.object(g.get("graph")).get("variantIssued"))));
            Map<String, Integer> versions = new LinkedHashMap<>();
            goals.forEach(g -> {
                Object version = ResultData.object(g.get("script")).get("scriptVersion");
                if (version != null) {
                    versions.merge(String.valueOf(version), 1, Integer::sum);
                }
            });
            totals.put("scriptVersions", versions);
            totals.put("withinProductionScenarioBudget",
                    count(goals, g -> Boolean.TRUE.equals(ResultData.object(g.get("timing")).get("withinProductionBudget"))));
            // 계약 대조 결과가 빈 목록이 아닌 목표(어긴 곳이 있거나 대조하지 못함)
            totals.put("schemaErrorGoals", count(goals, g -> {
                Map<String, Object> script = ResultData.object(g.get("script"));
                return script.containsKey("firstScenarioSchemaErrors")
                        && !(script.get("firstScenarioSchemaErrors") instanceof List<?> errors && errors.isEmpty());
            }));
            totals.put("storedRoundTripMismatch",
                    count(goals, g -> ResultData.object(g.get("script")).containsKey("storedRoundTrip")
                            && !Boolean.TRUE.equals(ResultData.object(g.get("script")).get("storedRoundTrip"))));
            totals.put("attempts", Attempts.totals(goals));
            totals.put("heardChecks", HeardLines.totals(goals));
            return totals;
        }

        private Map<String, Object> goalSelection() {
            Map<String, Object> selection = new LinkedHashMap<>();
            selection.put("rule", "지식 그래프 기본 씨앗 14개(기능 묶음 7종) 가운데 기능 묶음(skill_family)이 서로 다른 6개를 골랐다. "
                    + "옛 하니스(1 ~ 4회차)와 같은 씨앗 · 같은 합성 4요소라 회차끼리 견줄 수 있다.");
            selection.put("omitted", List.of(
                    "information_quantity(S5 청자 지식 과다): 필요한 추론('상대가 무엇을 아는지 짐작하기')이 S4와 같아 S4로 대신한다",
                    "같은 묶음의 나머지 씨앗(S2 · S3 · S7 · S9 · X1 · X2 · X3): 묶음마다 하나만 고른다"));
            Map<String, Integer> skills = new LinkedHashMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (GoalSpec spec : seedGoals()) {
                KnowledgeSeed seed = seed(spec.seedId());
                skills.merge(seed.signature().targetSkill(), 1, Integer::sum);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", spec.key());
                row.put("seedId", seed.seedId());
                row.put("label", seed.label());
                row.put("family", seed.family());
                row.put("targetSkill", seed.signature().targetSkill());
                row.put("relationship", seed.relationship());
                row.put("partner", spec.elements().partner());
                row.put("why", spec.why());
                rows.add(row);
            }
            selection.put("targetSkills", skills);
            selection.put("goals", rows);
            return selection;
        }

        private Map<String, Object> budgets() {
            Map<String, Object> harness = new LinkedHashMap<>();
            harness.put("lunaCallLimitMs", LUNA_CALL_LIMIT.toMillis());
            harness.put("scenarioLimitMs", SCENARIO_LIMIT.toMillis());
            harness.put("knowledgeLimitMs", KNOWLEDGE_LIMIT.toMillis());
            harness.put("knowledgeInitializationLimitMs", KNOWLEDGE_INIT_LIMIT.toMillis());
            harness.put("historyLimitMs", HISTORY_LIMIT.toMillis());
            harness.put("callCap", CALL_CAP);
            Map<String, Object> prod = new LinkedHashMap<>();
            prod.put("scenarioRequestTimeoutMs", production.scenarioRequest().toMillis());
            prod.put("textAiRequestTimeoutMs", production.textAiRequest().toMillis());
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("maxRetries", production.textAi().retry().maxRetries());
            retry.put("backoffMs", production.textAi().retry().backoff().toMillis());
            prod.put("textAiRetry", retry);
            prod.put("graphBudgetMs", production.graphBudget().toMillis());
            prod.put("graphInitializationBudgetMs", production.graphInitialization().toMillis());
            prod.put("scenarioHistoryTimeoutMs", production.historyTimeout().toMillis());
            // 목적별 추론 강도 · 출력 한도도 application.yml 값을 그대로 쓰므로, 어느 설정을 잰 실행인지 결과에 남긴다
            Map<String, Object> effort = new LinkedHashMap<>();
            Map<String, Object> tokens = new LinkedHashMap<>();
            for (LunaPurpose purpose : List.of(LunaPurpose.SCENARIO_GENERATION, LunaPurpose.SCENARIO_REVIEW)) {
                effort.put(purpose.name(), production.purposes().of(purpose).reasoningEffort());
                tokens.put(purpose.name(), production.purposes().of(purpose).maxOutputTokens());
            }
            prod.put("lunaReasoningEffort", effort);
            prod.put("lunaMaxOutputTokens", tokens);
            Map<String, Object> budgets = new LinkedHashMap<>();
            budgets.put("harness", harness);
            budgets.put("production(application.yml)", prod);
            budgets.put("rule", "운영 값은 바꾸지 않았다. 하니스는 시한만 넉넉히 두고(재시도 · 추론 강도 · 출력 한도 · 판은 운영 값) "
                    + "timing에 운영 예산 안이었는지를 적는다");
            return budgets;
        }

        private static Map<String, Object> syntheticChild() {
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("ageYears", SYNTHETIC_AGE);
            child.put("interests", SYNTHETIC_INTERESTS);
            child.put("aliasRule", SYNTHETIC_NAME + " → 아이");
            child.put("kind", "합성 프로필(실제 아동 아님)");
            return child;
        }

        private static Map<String, Object> versions() {
            Map<String, Object> versions = new LinkedHashMap<>();
            versions.put("generationPrompt", ScenarioScriptProvider.GENERATION_PROMPT_VERSION);
            versions.put("reviewPrompt", ScenarioScriptProvider.REVIEW_PROMPT_VERSION);
            versions.put("scriptVersion", ScenarioScriptProvider.SCRIPT_VERSION);
            versions.put("generationInstructions", LunaTextAiProvider.SCRIPT_INSTRUCTIONS);
            versions.put("reviewInstructions", LunaTextAiProvider.REVIEW_INSTRUCTIONS);
            return versions;
        }

        private static List<String> notes() {
            return List.of(
                    "증거 종류: 실제 API · 합성 자료. 테스트 대역 성공 · 실제 기기 성공 · 실제 아이 성공이 아니다(R-M11).",
                    "입력은 모두 합성이다(합성 아동 프로필 · 하니스가 쓴 합성 4요소). 실제 아동 데이터는 없다.",
                    "목표는 지식 그래프 기본 씨앗에 하니스가 합성 4요소를 붙인 후보 목표다. 운영에서 목표 후보 생성이 seedId를 skillRef로 고른 상태와 같다. "
                            + "옛 하니스의 목표 후보 사슬(결과 작업 structure → GOAL_CANDIDATES → classify)은 C안이 바꾸지 않는 경로라 돌리지 않는다.",
                    "경로: 시작 준비처럼 ScenarioService.generatePrepared(요청 선점 → StructuredScenarioFlow → 생성 · 별도 검토 · 서버 검사 → 발급 저장)를 "
                            + "부르고 발급 대본으로 PreparedPracticePackageMapper.c8 조각을 만든다. 시작 준비 원본 행 · StartActivityService · 실제 동의 "
                            + "조회는 거치지 않았다(동의는 '있음' 대역, 내용에는 영향 없음).",
                    "노출 이력과 이전 연습 기록은 비어 있다(아이의 첫 연습). 그래서 변형 거리는 SAME_CONTEXT가 기대값이다.",
                    "관계: 운영 StructuredScenarioFlow.graphRelation은 '친구' 같은 정해진 이름만 그래프 관계 코드로 바꾼다. 그 밖의 이름('모둠 친구' · "
                            + "'동아리 형' · '동아리 친구')은 그대로 가서 변형 계획을 받지 못할 수 있고, 그러면 운영 생성기가 그래프 없이 다시 만든다(BASE만). "
                            + "goals[].graph.relationSent · regeneratedWithoutGraph로 보인다.",
                    "heard는 아이가 듣는 순서(C안, 설계 3.2): 상황 안내(spokenIntroduction) → 상대의 말 전하기 + 묻기(opportunityRelay + askPrompt) → "
                            + "갈래별 되묻기(reaskPrompt) · 다시 설명 + 묻기(reexplanation + askPrompt) · 도움(helpSpeech) · 상대 반응 전하기(friendReaction). "
                            + "변형은 고정 승인 문장 VARIANT_OFFER 뒤 변형 장면의 같은 칸들이다. 장면 밖 말(목표 안내 · 회고)과 그 밖의 고정 문장"
                            + "(WAIT_LEAD · HELP_OFFER · SCENE_CLOSE 등)은 펼치지 않았다.",
                    "heard 칸마다의 checks는 하니스 참고 점검이다(운영 검사 아님): 문장부호(마침표 · 쉼표 · 물음표 밖의 부호), 길이(설계 4.3 · 생성 v3 "
                            + "칸별 상한의 글자 수 · 문장 수), 역할 말(운영 ROLE_TALK와 같은 모양), 목표 표현 · 표현 예시 누설(leakForbidden 칸만 합계에 센다. "
                            + "도움 · 상대 반응 칸 값은 참고), 칭찬 · 평가어. spokenIntroduction · reexplanation의 문장부호 · 길이 규칙은 생성 지시에만 있고 "
                            + "서버 검사에는 없다. 칸 없음(missing)은 발급 때 칸별 서버 검사에 걸려 뺀 칸이다(attempts.strippedFields).",
                    "검토: 별도 검토는 main 검토 지시(review.v1)이고 C안 칸 다섯 개를 뺀 장면만 본다. C안 칸과 상황 안내는 운영 생성기가 칸마다 서버 검사하고 "
                            + "걸린 칸만 뺀 채 장면을 발급한다(장면은 버리지 않는다). 뺀 칸은 realtime이 대체 문장으로 읽는다.",
                    "attempts: serverCheckReasons는 운영 생성기가 장면 판정에 걸릴 때마다 낸 진단 코드(ScenarioRule_<사유>)를 센 값이다(걸리면 장면 실패 · "
                            + "보완). strippedFields는 발급 때 뺀 칸의 진단 코드(ScenarioField_<칸>_<사유>, 고정 코드뿐이고 대본 글은 없다)다. "
                            + "reviewVerdicts · reviewReasons는 별도 검토 응답, correctionsSent는 다음 생성에 실제로 보낸 보완 사유다.",
                    "script.storedRoundTrip은 발급 대본을 DB에서 다시 읽어 같은지, firstScenarioSchemaErrors는 시작 패키지 첫 대본이 계약 "
                            + "practice-scenario.schema.json을 지키는지(빈 목록이면 통과)다.");
        }

        private void closeAll() {
            Collections.reverse(closers);
            for (AutoCloseable closer : closers) {
                try {
                    closer.close();
                } catch (Exception ignored) {
                    // 정리 하나가 실패해도 나머지 정리(컨테이너 · DB)는 이어서 한다
                }
            }
            if (workers != null) {
                workers.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------ Luna 호출 기록

    /**
     * 모든 Luna 호출을 지금 목표 칸에 적고 하니스 전체 상한을 지킨다. 적는 것: 목적 · 판 · 단계 · 보낸 입력(정리 뒤, 합성 자료) · 보완 사유 · 결과 사유 ·
     * 응답 모델 · 지연 · 응답 JSON(합성 대본). 키 · 요청 머리 · 주소는 적지 않는다.
     */
    static final class RecordingLunaClient extends CoreLunaClient {

        private final LunaProperties properties;
        private final MujoongClock clock;
        private final LunaInputSanitizer sanitizer = new LunaInputSanitizer(JSON);
        private final AtomicInteger sent = new AtomicInteger();
        private volatile List<Map<String, Object>> bucket = Collections.synchronizedList(new ArrayList<>());

        RecordingLunaClient(LunaProperties properties, TextAiProperties timing, LunaTransport transport, TestEvents events,
                MujoongClock clock) {
            super(properties, timing, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON), transport, events.logger(), clock, JSON,
                    CoreLunaClient.SYSTEM_SLEEPER);
            this.properties = properties;
            this.clock = clock;
        }

        String requestedModel() {
            return properties.model();
        }

        int sent() {
            return sent.get();
        }

        int remaining() {
            return CALL_CAP - sent.get();
        }

        void startBucket() {
            bucket = Collections.synchronizedList(new ArrayList<>());
        }

        /** 목표 칸을 닫는다. 돌려준 목록은 시한으로 끊긴 호출이 늦게 끝나도 그 목표에 적히도록 같은 목록이다. */
        List<Map<String, Object>> finishBucket() {
            List<Map<String, Object>> done = bucket;
            bucket = Collections.synchronizedList(new ArrayList<>());
            return done;
        }

        @Override
        public LunaResult call(LunaRequest request, LunaCallBudget budget) {
            // 시작할 때의 목표 칸에 적는다
            List<Map<String, Object>> target = bucket;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("purpose", request.purpose().name());
            row.put("promptVersion", request.promptVersion());
            row.put("step", step(request));
            row.put("flags", flags(request));
            Object reasons = request.input().get("correctionReasons");
            if (reasons != null) {
                row.put("correctionReasons", plain(reasons));
            }
            row.put("input", sentInput(request));
            if (sent.get() >= CALL_CAP) {
                row.put("outcome", "CALL_CAP_REACHED");
                record(target, row);
                throw new LunaException(LunaFailureReason.REQUEST_REJECTED, 0);
            }
            long started = clock.monotonicNanos();
            try {
                LunaResult result = super.call(request, budget);
                sent.addAndGet(result.attemptsUsed());
                row.put("outcome", "OK");
                row.put("responseModel", result.responseModel());
                row.put("attempts", result.attemptsUsed());
                row.put("latencyMs", result.latency().toMillis());
                row.put("output", parse(result.outputJson()));
                return result;
            } catch (LunaException failure) {
                sent.addAndGet(failure.attemptsUsed());
                row.put("outcome", failure.reason().name());
                row.put("attempts", failure.attemptsUsed());
                throw failure;
            } finally {
                row.put("wallMs", clock.elapsedSince(started).toMillis());
                record(target, row);
            }
        }

        private static void record(List<Map<String, Object>> target, Map<String, Object> row) {
            synchronized (target) {
                row.put("index", target.size() + 1);
                target.add(row);
            }
        }

        /** 호출부가 보내는 것과 같은 정리 결과(금지 키 제거 · 별칭 치환). 정리에 실패하면 그 사실만 적는다. */
        private Object sentInput(LunaRequest request) {
            try {
                return sanitizer.sanitize(request.input(), request.aliases(), request.purpose()).payload();
            } catch (LunaException rejected) {
                return Map.of("rejected", rejected.reason().name());
            }
        }

        /** 운영 생성기가 이 호출에 붙인 단계(첫 BASE · 변형 계획 · 그래프 없이 다시 · 검토 대상 장면). */
        static String step(LunaRequest request) {
            Map<String, Object> input = request.input();
            return switch (request.purpose()) {
                case SCENARIO_GENERATION -> input.containsKey("variantPlan") ? "GENERATE_WITH_VARIANT_PLAN"
                        : Boolean.TRUE.equals(input.get("baseOnly"))
                                ? input.containsKey("structureContext") ? "GENERATE_BASE_ONLY" : "GENERATE_BASE_ONLY_NO_GRAPH"
                                : "GENERATE";
                case SCENARIO_REVIEW -> "REVIEW_" + (input.get("script") instanceof ScenarioScript script
                        ? String.join("_", script.scenes().stream().map(ScenarioScript.Scene::sceneKind).toList()) : "SCRIPT");
                default -> request.purpose().name();
            };
        }

        private static Map<String, Object> flags(LunaRequest request) {
            Map<String, Object> input = request.input();
            Map<String, Object> flags = new LinkedHashMap<>();
            flags.put("structureContext", input.containsKey("structureContext"));
            flags.put("variantPlan", input.containsKey("variantPlan"));
            flags.put("baseToPreserve", input.containsKey("baseToPreserve"));
            flags.put("correction", Boolean.TRUE.equals(input.get("correction")));
            return flags;
        }

        private static Object parse(String output) {
            try {
                return JSON.read(output, Object.class);
            } catch (RuntimeException unreadable) {
                return Map.of("raw", output);
            }
        }
    }

    /**
     * 운영 생성기의 고정 진단 코드를 목표마다 모은다. 운영 {@link ScenarioService}는 진단 코드를 {@code EventLogger.appError}로 남기는데(반복 병합 대상),
     * 하니스는 같은 자리에서 코드를 하나도 합치지 않고 받는다. 줄은 쓰지 않는다(값은 사유 코드뿐이다).
     */
    static final class DiagnosticCodes extends EventLogger {

        private List<String> bucket = new ArrayList<>();

        DiagnosticCodes(MujoongClock clock) {
            super(clock, OwnerHasher.ephemeral(), LogCapture.detached(), CoreMetrics.inMemory(), EventLog.Strictness.STRICT,
                    Duration.ofSeconds(10), 60);
        }

        @Override
        public synchronized void appError(EventLog.Component component, String errorKind) {
            bucket.add(errorKind);
        }

        synchronized void start() {
            bucket = new ArrayList<>();
        }

        synchronized List<String> finish() {
            List<String> done = List.copyOf(bucket);
            bucket = new ArrayList<>();
            return done;
        }
    }

    // ------------------------------------------------------------------ 생성 시도 · 보완 사유

    /** 목표 하나의 생성 시도와 보완 사유(서버 검사 · 별도 검토 · 공급자 실패) · 발급 때 뺀 칸, 그리고 목표들의 합계. */
    static final class Attempts {

        static final String RULE = "ScenarioRule_";
        static final String RESULT = "ScenarioValidation_";
        static final String PROVIDER = "ScenarioProvider_";
        /** 발급 때 칸별 서버 검사에 걸려 뺀 들리는 칸(ScenarioField_<칸>_<사유>, 고정 코드뿐이다). */
        static final String FIELD = "ScenarioField_";

        private Attempts() {
        }

        static Map<String, Object> of(List<Map<String, Object>> recorded, List<String> codes) {
            List<Map<String, Object>> calls = snapshot(recorded);
            Map<String, Object> attempts = new LinkedHashMap<>();
            attempts.put("lunaCalls", calls.size());
            attempts.put("generationCalls", calls.stream().filter(c -> purpose(c, LunaPurpose.SCENARIO_GENERATION)).count());
            attempts.put("reviewCalls", calls.stream().filter(c -> purpose(c, LunaPurpose.SCENARIO_REVIEW)).count());
            attempts.put("failedCalls", calls.stream().filter(c -> !"OK".equals(c.get("outcome")))
                    .map(c -> c.get("purpose") + ":" + c.get("outcome")).toList());
            attempts.put("serverCheckReasons", tally(codes, RULE));
            attempts.put("serverCheckResults", tally(codes, RESULT));
            Map<String, Integer> verdicts = new LinkedHashMap<>();
            Map<String, Integer> reasons = new LinkedHashMap<>();
            for (Map<String, Object> call : calls) {
                if (!purpose(call, LunaPurpose.SCENARIO_REVIEW) || !"OK".equals(call.get("outcome"))) {
                    continue;
                }
                Map<String, Object> output = ResultData.object(call.get("output"));
                verdicts.merge(String.valueOf(output.get("verdict")), 1, Integer::sum);
                if (output.get("reasons") instanceof List<?> list) {
                    list.forEach(reason -> reasons.merge(String.valueOf(reason), 1, Integer::sum));
                }
            }
            attempts.put("reviewVerdicts", verdicts);
            attempts.put("reviewReasons", reasons);
            attempts.put("providerFailures", tally(codes, PROVIDER));
            attempts.put("correctionsSent", calls.stream()
                    .filter(c -> purpose(c, LunaPurpose.SCENARIO_GENERATION) && c.get("correctionReasons") != null)
                    .map(c -> c.get("correctionReasons")).toList());
            attempts.put("strippedFields", codes.stream().filter(code -> code.startsWith(FIELD)).toList());
            attempts.put("diagnostics", codes);
            return attempts;
        }

        /**
         * 목표들의 합계. serverCheckReasons는 장면 판정 사유(걸리면 장면 실패 · 보완), strippedFields는 발급 때 뺀 칸이다: byCode는 진단 코드
         * (ScenarioField_ 뒤 칸_사유)별, byField는 칸별, byReason은 칸별 판정 사유를 모두(0 포함) 보인다.
         */
        static Map<String, Object> totals(List<Map<String, Object>> goals) {
            Map<String, Integer> server = new LinkedHashMap<>();
            Map<String, Integer> results = new LinkedHashMap<>();
            Map<String, Integer> verdicts = new LinkedHashMap<>();
            Map<String, Integer> reasons = new LinkedHashMap<>();
            Map<String, Integer> providers = new LinkedHashMap<>();
            List<String> stripped = new ArrayList<>();
            long generations = 0;
            long reviews = 0;
            for (Map<String, Object> goal : goals) {
                Map<String, Object> attempts = ResultData.object(goal.get("attempts"));
                generations += ResultData.number(attempts, "generationCalls");
                reviews += ResultData.number(attempts, "reviewCalls");
                merge(server, attempts.get("serverCheckReasons"));
                merge(results, attempts.get("serverCheckResults"));
                merge(verdicts, attempts.get("reviewVerdicts"));
                merge(reasons, attempts.get("reviewReasons"));
                merge(providers, attempts.get("providerFailures"));
                if (attempts.get("strippedFields") instanceof List<?> codes) {
                    codes.forEach(code -> stripped.add(String.valueOf(code)));
                }
            }
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("generationCalls", generations);
            totals.put("reviewCalls", reviews);
            totals.put("serverCheckReasons", server);
            totals.put("serverCheckResults", results);
            totals.put("reviewVerdicts", verdicts);
            totals.put("reviewReasons", reasons);
            totals.put("providerFailures", providers);
            totals.put("strippedFields", strippedTotals(stripped));
            return totals;
        }

        /** 뺀 칸 진단 코드 합계(코드 · 칸 · 사유별). 칸 이름에는 밑줄이 없어 첫 밑줄 앞이 칸, 뒤가 사유다. */
        static Map<String, Object> strippedTotals(List<String> codes) {
            Map<String, Integer> byCode = new LinkedHashMap<>();
            Map<String, Integer> byField = new LinkedHashMap<>();
            ScenarioScriptValidation.OPTIONAL_SPOKEN_FIELDS.forEach(field -> byField.put(field, 0));
            Map<String, Integer> byReason = new LinkedHashMap<>();
            ScenarioScriptValidation.FIELD_REASONS.forEach(reason -> byReason.put(reason.name(), 0));
            for (String code : codes) {
                String rest = code.substring(FIELD.length());
                byCode.merge(rest, 1, Integer::sum);
                int split = rest.indexOf('_');
                if (split > 0) {
                    byField.merge(rest.substring(0, split), 1, Integer::sum);
                    byReason.merge(rest.substring(split + 1), 1, Integer::sum);
                }
            }
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("count", codes.size());
            totals.put("byCode", byCode);
            totals.put("byField", byField);
            totals.put("byReason", byReason);
            return totals;
        }

        private static boolean purpose(Map<String, Object> call, LunaPurpose purpose) {
            return purpose.name().equals(call.get("purpose"));
        }

        private static Map<String, Integer> tally(List<String> codes, String prefix) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            codes.stream().filter(code -> code.startsWith(prefix))
                    .forEach(code -> counts.merge(code.substring(prefix.length()), 1, Integer::sum));
            return counts;
        }

        private static void merge(Map<String, Integer> into, Object counts) {
            ResultData.object(counts).forEach((key, value) -> into.merge(key, value instanceof Number n ? n.intValue() : 0, Integer::sum));
        }
    }

    // ------------------------------------------------------------------ 그래프 맥락 · 변형

    /** 그래프 맥락과 변형이 어떻게 됐는지(기록된 호출과 발급 대본으로만 정한다, 하니스 추정 없음). */
    static final class GraphReport {

        private GraphReport() {
        }

        static Map<String, Object> of(List<Map<String, Object>> recorded, Scenario scenario) {
            List<Map<String, Object>> generations = snapshot(recorded).stream()
                    .filter(c -> LunaPurpose.SCENARIO_GENERATION.name().equals(c.get("purpose"))).toList();
            Map<String, Object> report = new LinkedHashMap<>();
            Object relation = generations.isEmpty() ? null : input(generations.getFirst()).get("relation");
            report.put("relationSent", relation);
            report.put("relationIsGraphCode", relation instanceof String text && KnowledgeSeed.RELATIONSHIPS.contains(text));
            int firstWithGraph = -1;
            for (int i = 0; i < generations.size() && firstWithGraph < 0; i++) {
                if (input(generations.get(i)).containsKey("structureContext")) {
                    firstWithGraph = i;
                }
            }
            report.put("structureContextOffered", firstWithGraph >= 0);
            report.put("regeneratedWithoutGraph", firstWithGraph >= 0 && generations.subList(firstWithGraph + 1, generations.size()).stream()
                    .anyMatch(c -> !input(c).containsKey("structureContext")));
            report.put("variantPlanRequested", generations.stream().anyMatch(c -> input(c).containsKey("variantPlan")));
            report.put("variantGenerated", generations.stream().anyMatch(c -> hasVariant(c.get("output"))));
            report.put("variantIssued", scenes(scenario).stream().anyMatch(s -> "VARIANT".equals(s.sceneKind())));
            if (scenario != null && scenario.generation() != null) {
                report.put("graphContextUsed", scenario.generation().graphContextUsed());
                report.put("kgVersion", scenario.generation().kgVersion());
            }
            if (firstWithGraph >= 0) {
                // 첫 BASE 좌표가 변형 계획기 어휘 안인가(밖이면 계획이 없어 그래프 없이 다시 만든다)
                generations.stream().filter(c -> "OK".equals(c.get("outcome")) && input(c).containsKey("structureContext"))
                        .map(c -> ResultData.objects(ResultData.object(c.get("output")).get("scenes")))
                        .filter(scenes -> !scenes.isEmpty()).map(List::getFirst).findFirst()
                        .ifPresent(base -> report.put("firstBase", coordinates(base)));
            }
            return report;
        }

        private static Map<String, Object> coordinates(Map<String, Object> base) {
            Map<String, Object> preservation = ResultData.object(base.get("preservation"));
            Map<String, Object> coordinates = new LinkedHashMap<>();
            coordinates.put("topic", base.get("topic"));
            coordinates.put("place", base.get("place"));
            coordinates.put("participants", preservation.get("participants"));
            coordinates.put("relation", base.get("relation"));
            coordinates.put("situation", preservation.get("situation"));
            List<String> outside = new ArrayList<>();
            vocabulary(outside, "topic", base.get("topic"), VariantPlanner.TOPICS);
            vocabulary(outside, "place", base.get("place"), VariantPlanner.PLACES);
            vocabulary(outside, "participants", preservation.get("participants"), VariantPlanner.PARTICIPANTS);
            vocabulary(outside, "relation", base.get("relation"), KnowledgeSeed.RELATIONSHIPS);
            vocabulary(outside, "situation", preservation.get("situation"), KnowledgeSeed.SITUATIONS);
            coordinates.put("outsidePlannerVocabulary", outside);
            return coordinates;
        }

        private static void vocabulary(List<String> outside, String field, Object value, Set<String> allowed) {
            if (!(value instanceof String text) || !allowed.contains(text)) {
                outside.add(field);
            }
        }

        private static Map<String, Object> input(Map<String, Object> call) {
            return ResultData.object(call.get("input"));
        }

        private static boolean hasVariant(Object output) {
            return ResultData.objects(ResultData.object(output).get("scenes")).stream().anyMatch(s -> "VARIANT".equals(s.get("sceneKind")));
        }
    }

    // ------------------------------------------------------------------ 아이가 듣는 순서 · 참고 점검

    /**
     * 발급 대본의 장면 말을 C안에서 아이가 듣는 순서로 펼치고 칸마다 하니스 참고 점검을 붙인다(운영 검사 아님). 순서는 설계 3.2 · 계약 10.17이다: 상황 안내 →
     * 상대의 말 전하기 + 묻기(한 슬롯) → 갈래별 되묻기 · 다시 설명 + 묻기 · 도움 · 상대 반응 전하기, 변형은 고정 승인 문장 VARIANT_OFFER 뒤 같은 칸들.
     */
    static final class HeardLines {

        static final String SPEAKER = "무중: ";
        static final String MISSING = "(칸 없음: 발급 때 서버 검사에 걸려 뺀 칸이다. realtime이 대체 문장을 읽는다)";
        /** 들리는 말에서 찾는 칭찬 · 평가어(운영 REACTION_PRAISE 목록과 옛 하니스 점검표를 합친 참고 목록, 공백을 뺀 글에서 찾는다). */
        static final List<String> PRAISE_WORDS = List.of("잘했", "잘한", "잘하네", "잘말했", "멋지", "멋져", "멋진", "멋있", "정답", "훌륭",
                "최고", "대단", "똑똑", "완벽", "칭찬", "맞았", "맞혔", "틀렸", "100점");
        /** 무중이 다른 사람 역할을 맡는다는 말(운영 ScenarioScriptValidation ROLE_TALK와 같은 모양, 공백을 뺀 글에서 찾는다). */
        static final Pattern ROLE_TALK = Pattern.compile("역할|코치|무중이?(?:가|는)?"
                + "(?:친구|상대방?|선생님|엄마|아빠|형|오빠|누나|언니|동생|짝꿍)"
                + "(?:야|이야|예요|이에요|이다|라고|처럼|인척|할게|해볼게|맡을게|가될게|이될게)");

        /**
         * 들리는 칸과 참고 상한(설계 4.3 · 계약 10.17 · 생성 v3 '공통 길이'). leakForbidden 칸에는 목표 표현 · 표현 예시가 들리면 안 된다(도움 칸은 예시를 담고,
         * 상대 반응은 목표를 이룬 뒤에 들린다).
         */
        enum Field {
            SPOKEN_INTRODUCTION("spokenIntroduction", 200, 2, true),
            OPPORTUNITY_RELAY("opportunityRelay", 200, 2, true),
            ASK_PROMPT("askPrompt", 100, 1, true),
            REASK_PROMPT("reaskPrompt", 200, 2, true),
            REEXPLANATION("reexplanation", 200, 2, true),
            HELP_SPEECH("helpSpeech", 300, 3, false),
            FRIEND_REACTION("friendReaction", 200, 2, false);

            final String key;
            final int maxChars;
            final int maxSentences;
            final boolean leakForbidden;

            Field(String key, int maxChars, int maxSentences, boolean leakForbidden) {
                this.key = key;
                this.maxChars = maxChars;
                this.maxSentences = maxSentences;
                this.leakForbidden = leakForbidden;
            }

            String of(ScenarioScript.Scene scene) {
                return switch (this) {
                    case SPOKEN_INTRODUCTION -> scene.spokenIntroduction();
                    case OPPORTUNITY_RELAY -> scene.opportunityRelay();
                    case ASK_PROMPT -> scene.askPrompt();
                    case REASK_PROMPT -> scene.reaskPrompt();
                    case REEXPLANATION -> scene.reexplanation();
                    case HELP_SPEECH -> scene.helpSpeech();
                    case FRIEND_REACTION -> scene.friendReaction();
                };
            }
        }

        /** 장면 하나에서 아이가 듣는 자리(갈래). */
        enum Slot {
            SCENE_INTRO("장면 시작: 상황 안내", Field.SPOKEN_INTRODUCTION),
            OPPORTUNITY("기회: 상대의 말 전하기 + 묻기(한 슬롯으로 이어 읽는다)", Field.OPPORTUNITY_RELAY, Field.ASK_PROMPT),
            REASK("갈래 J2 PARTIAL → 되묻기", Field.REASK_PROMPT),
            REEXPLAIN("갈래 J2 NOT_UNDERSTOOD · IRRELEVANT → 다시 설명 + 묻기", Field.REEXPLANATION, Field.ASK_PROMPT),
            HELP("갈래 도움 요청 · 도움 제안 수락(FOCUS) → 도움", Field.HELP_SPEECH),
            SUCCESS("갈래 목표 달성(J2 FULL · J3 DELIVERED · J5 FULL · COPIED) → 상대 반응 전하기", Field.FRIEND_REACTION);

            final String when;
            final List<Field> fields;

            Slot(String when, Field... fields) {
                this.when = when;
                this.fields = List.of(fields);
            }
        }

        private HeardLines() {
        }

        static List<Map<String, Object>> of(Scenario scenario, String targetExpression) {
            List<Map<String, Object>> heard = new ArrayList<>();
            for (ScenarioScript.Scene scene : scenes(scenario)) {
                if ("VARIANT".equals(scene.sceneKind())) {
                    Map<String, Object> offer = new LinkedHashMap<>();
                    offer.put("field", "VARIANT_OFFER");
                    offer.put("fixedApprovedSpeech", true);
                    offer.put("text", SeedIds.VARIANT_OFFER_TEXT);
                    heard.add(entry(scene, "VARIANT_OFFER", "변형 제안: 고정 승인 문장(수락하면 아래 변형 장면이 이어진다)", List.of(offer)));
                }
                for (Slot slot : Slot.values()) {
                    List<Map<String, Object>> parts = new ArrayList<>();
                    for (Field field : slot.fields) {
                        parts.add(part(field, field.of(scene), targetExpression, scene.expressionExamples()));
                    }
                    heard.add(entry(scene, slot.name(), slot.when, parts));
                }
            }
            return heard;
        }

        private static Map<String, Object> entry(ScenarioScript.Scene scene, String slot, String when, List<Map<String, Object>> parts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("scene", scene.sceneKind());
            entry.put("sceneKey", scene.sceneKey());
            entry.put("slot", slot);
            entry.put("when", when);
            entry.put("line", SPEAKER + String.join(" ", parts.stream().map(p -> p.get("text") instanceof String text ? text : MISSING).toList()));
            entry.put("parts", parts);
            return entry;
        }

        static Map<String, Object> part(Field field, String text, String target, List<String> examples) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("field", field.key);
            if (text == null || text.isBlank()) {
                part.put("missing", true);
                return part;
            }
            part.put("text", text);
            part.put("checks", checks(field, text, target, examples));
            return part;
        }

        static Map<String, Object> checks(Field field, String text, String target, List<String> examples) {
            int chars = text.length();
            int sentences = sentences(text);
            Map<String, Object> checks = new LinkedHashMap<>();
            checks.put("chars", chars);
            checks.put("sentences", sentences);
            checks.put("maxChars", field.maxChars);
            checks.put("maxSentences", field.maxSentences);
            checks.put("withinLength", chars <= field.maxChars && sentences <= field.maxSentences);
            checks.put("punctuation", punctuation(text));
            checks.put("roleTalk", roleTalk(text));
            checks.put("praise", PRAISE_WORDS.stream().filter(compact(text)::contains).toList());
            checks.put("leakForbidden", field.leakForbidden);
            checks.put("targetLeak", contains(text, target, 1));
            checks.put("exampleLeak", examples.stream().filter(example -> contains(text, example, 4)).toList());
            return checks;
        }

        /** 마침표 · 쉼표 · 물음표 · 공백(U+0020) 밖의 부호 · 기호(처음 나온 순서, 공백류 · 제어 문자는 U+XXXX). 운영 SPOKEN_PUNCTUATION과 같은 기준이다. */
        static List<String> punctuation(String text) {
            Set<String> found = new LinkedHashSet<>();
            text.codePoints().filter(c -> !(Character.isLetterOrDigit(c) || c == ' ' || c == '.' || c == ',' || c == '?'))
                    .forEach(c -> found.add(Character.isWhitespace(c) || Character.isISOControl(c) ? String.format("U+%04X", c)
                            : Character.toString(c)));
            return List.copyOf(found);
        }

        static List<String> roleTalk(String text) {
            Set<String> found = new LinkedHashSet<>();
            Matcher matcher = ROLE_TALK.matcher(compact(text));
            while (matcher.find()) {
                found.add(matcher.group());
            }
            return List.copyOf(found);
        }

        /** 마침표 · 물음표로 나눈 비지 않은 조각 수(운영 검사와 같은 셈). */
        static int sentences(String text) {
            int count = 0;
            for (String piece : text.split("[.?]")) {
                if (!piece.isBlank()) {
                    count++;
                }
            }
            return count;
        }

        /** 글자 · 숫자만 남긴 비교(운영 ScenarioContentPolicy.normalize)로 needle이 들어 있나. 너무 짧은 needle은 보지 않는다. */
        static boolean contains(String text, String needle, int minLength) {
            if (needle == null) {
                return false;
            }
            String normalized = ScenarioContentPolicy.normalize(needle);
            return normalized.length() >= minLength && ScenarioContentPolicy.normalize(text).contains(normalized);
        }

        private static String compact(String text) {
            return text.replaceAll("\\s", "");
        }

        /** 목표들의 점검 합계(목표 안에서 장면 · 칸마다 한 번씩 센다, 다시 읽는 askPrompt를 두 번 세지 않는다). */
        static Map<String, Object> totals(List<Map<String, Object>> goals) {
            Map<String, Integer> overall = counter();
            Map<String, Map<String, Integer>> byField = new LinkedHashMap<>();
            for (Map<String, Object> goal : goals) {
                Set<String> seen = new HashSet<>();
                for (Map<String, Object> entry : ResultData.objects(goal.get("heard"))) {
                    for (Map<String, Object> part : ResultData.objects(entry.get("parts"))) {
                        if (Boolean.TRUE.equals(part.get("fixedApprovedSpeech")) || !seen.add(entry.get("sceneKey") + "/" + part.get("field"))) {
                            continue;
                        }
                        Map<String, Integer> field = byField.computeIfAbsent(String.valueOf(part.get("field")), key -> counter());
                        for (String name : flagged(part)) {
                            overall.merge(name, 1, Integer::sum);
                            field.merge(name, 1, Integer::sum);
                        }
                    }
                }
            }
            Map<String, Object> totals = new LinkedHashMap<>(overall);
            totals.put("byField", byField);
            return totals;
        }

        private static Map<String, Integer> counter() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String name : List.of("fields", "missing", "punctuation", "overLength", "roleTalk", "praise", "targetLeak", "exampleLeak")) {
                counts.put(name, 0);
            }
            return counts;
        }

        private static List<String> flagged(Map<String, Object> part) {
            List<String> names = new ArrayList<>(List.of("fields"));
            if (Boolean.TRUE.equals(part.get("missing"))) {
                names.add("missing");
                return names;
            }
            Map<String, Object> checks = ResultData.object(part.get("checks"));
            boolean leakForbidden = Boolean.TRUE.equals(checks.get("leakForbidden"));
            addIf(names, "punctuation", nonEmpty(checks.get("punctuation")));
            addIf(names, "overLength", Boolean.FALSE.equals(checks.get("withinLength")));
            addIf(names, "roleTalk", nonEmpty(checks.get("roleTalk")));
            addIf(names, "praise", nonEmpty(checks.get("praise")));
            addIf(names, "targetLeak", leakForbidden && Boolean.TRUE.equals(checks.get("targetLeak")));
            addIf(names, "exampleLeak", leakForbidden && nonEmpty(checks.get("exampleLeak")));
            return names;
        }

        private static void addIf(List<String> names, String name, boolean condition) {
            if (condition) {
                names.add(name);
            }
        }

        private static boolean nonEmpty(Object value) {
            return value instanceof List<?> list && !list.isEmpty();
        }
    }

    // ------------------------------------------------------------------ 운영 값 · 공용 도구

    /** application.yml의 운영 시한 · 재시도 · 목적 설정(바꾸지 않고 읽기만 한다). */
    record ProductionBudgets(Duration scenarioRequest, Duration historyTimeout, Duration textAiRequest, Duration graphBudget,
            Duration graphInitialization, TextAiProperties textAi, URI lunaBaseUrl, LunaProperties.Purposes purposes, int maxResponseBytes) {

        static ProductionBudgets load() {
            List<PropertySource<?>> sources;
            try {
                sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
            } catch (IOException unreadable) {
                throw new IllegalStateException("application.yml을 읽지 못했다");
            }
            Binder binder = new Binder(ConfigurationPropertySources.from(sources), null, ApplicationConversionService.getSharedInstance());
            return new ProductionBudgets(
                    duration(binder, "mujoong.timing.practice.scenario-request-timeout"),
                    duration(binder, "mujoong.timing.practice.scenario-history-timeout"),
                    duration(binder, "mujoong.text-ai.request-timeout"),
                    duration(binder, "mujoong.timing.knowledge.graph-budget"),
                    duration(binder, "mujoong.timing.knowledge.graph-initialization-budget"),
                    bound(binder, "mujoong.text-ai", Bindable.of(TextAiProperties.class)),
                    bound(binder, "mujoong.luna.base-url", Bindable.of(URI.class)),
                    bound(binder, "mujoong.luna.purpose", Bindable.of(LunaProperties.Purposes.class)),
                    bound(binder, "mujoong.luna.max-response-bytes", Bindable.of(Integer.class)));
        }

        private static Duration duration(Binder binder, String key) {
            return bound(binder, key, Bindable.of(Duration.class));
        }

        private static <T> T bound(Binder binder, String key, Bindable<T> type) {
            return binder.bind(key, type).orElseThrow(() -> new IllegalStateException(key + " 없음"));
        }
    }

    /** 운영 재시도 · 온도 · 판 값은 그대로 두고 시한만 넉넉히 바꾼다. */
    static TextAiProperties harnessTiming(TextAiProperties production) {
        TextAiProperties.Result result = production.result();
        return new TextAiProperties(LUNA_CALL_LIMIT, production.retry(), new TextAiProperties.Result(LUNA_CALL_LIMIT,
                result.temperatureJudgment(), result.temperatureGeneration(), result.promptVersion()));
    }

    /** 운영 기준 주소 · 응답 상한 · 목적별 출력 한도 · 추론 강도 그대로. 모델은 env(OPENAI_ANALYSIS_MODEL, 비면 운영 기본값)다. */
    static LunaProperties lunaProperties(ProductionBudgets production, String model) {
        return new LunaProperties(model, production.lunaBaseUrl(), production.maxResponseBytes(), production.purposes());
    }

    static List<ScenarioScript.Scene> scenes(Scenario scenario) {
        return scenario == null || scenario.scenes() == null ? List.of() : scenario.scenes();
    }

    /** 다른 스레드가 늦게 적는 중일 수 있는 호출 기록의 사본. */
    static List<Map<String, Object>> snapshot(List<Map<String, Object>> calls) {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    static long count(List<Map<String, Object>> goals, Predicate<Map<String, Object>> condition) {
        return goals.stream().filter(condition).count();
    }

    /** 레코드 · 맵을 평범한 JSON 값으로 바꾼다(운영 JsonCodec 직렬화 그대로, null 칸은 빠진다). */
    static Object plain(Object value) {
        return value == null ? null : JSON.read(JSON.write(value), Object.class);
    }

    static void writePretty(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, PRETTY.writeValueAsString(PRETTY.readTree(JSON.write(value))) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
