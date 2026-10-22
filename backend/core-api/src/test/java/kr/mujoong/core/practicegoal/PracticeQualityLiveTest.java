package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianWriteLock;
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
import kr.mujoong.core.luna.adapter.LunaResultTextProvider;
import kr.mujoong.core.luna.adapter.LunaTextAiProvider;
import kr.mujoong.core.practice.GoalContext;
import kr.mujoong.core.practice.PracticeAccountPort;
import kr.mujoong.core.practice.PracticeSessionContextPort;
import kr.mujoong.core.practice.Scenario;
import kr.mujoong.core.practice.ScenarioContentPolicy;
import kr.mujoong.core.practice.ScenarioDeadline;
import kr.mujoong.core.practice.ScenarioGraphContext;
import kr.mujoong.core.practice.ScenarioPrivacyContextPort;
import kr.mujoong.core.practice.ScenarioRepository;
import kr.mujoong.core.practice.ScenarioScriptGenerator;
import kr.mujoong.core.practice.ScenarioScriptValidation;
import kr.mujoong.core.practice.StructuredScenarioFlow;
import kr.mujoong.core.practice.start.FrozenGoal;
import kr.mujoong.core.practice.start.PreparedPracticePackageMapper;
import kr.mujoong.core.practice.start.WeightedGoalOrder;
import kr.mujoong.core.result.ResultAiException;
import kr.mujoong.core.result.ResultData;
import kr.mujoong.core.result.ResultTextGateway;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import kr.mujoong.core.result.check.OutputChecker;
import kr.mujoong.core.result.check.QuoteMatcher;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.testsupport.dialogue.HeardWords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실호출 연습 품질 하니스 — core. "만들어진 연습이 6~12세 아이의 화용 능력을 연습할 수 있는 연습이 되는가"를 사람이 읽어 평가할 수 있게, 실제
 * Luna로 '목표 → 상황 대본(BASE + VARIANT) → 시작 패키지'를 운영 생성 경로 그대로 만들어 파일로 남긴다.
 *
 * <ul>
 *   <li>기본 {@code test} · {@code check} · CI에서는 돌지 않는다({@code @Tag("luna-live")}, build-logic 규약이 기본 test에서 뺀다).
 *       사람이 env {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew :core-api:lunaLiveTest --tests "*PracticeQualityLiveTest*"}로만 돈다.
 *   <li>키가 없으면 요약 파일에 {@code SKIPPED_NO_KEY}만 쓰고 건너뛴다(통과로 보고하지 않음). 키는 env에서 있는지만 보고 값을 들고 있지 않는다.
 *   <li>경로: 실제 PostgreSQL · Neo4j(Testcontainers)와 지식 그래프 기본 씨앗 14개 → {@link StructuredScenarioFlow} →
 *       {@link ScenarioGraphContext} · {@link VariantPlanner} → {@link ScenarioScriptGenerator} → {@link LunaTextAiProvider} →
 *       {@link CoreLunaClient}(STRICT, https + api.openai.com) → {@link ScenarioRepository#issued} → {@link PreparedPracticePackageMapper#c8}.
 *       목표 후보는 결과 작업과 같은 'structure → GOAL_CANDIDATES → classify' 사슬로 한 번 만든다.
 *   <li>입력은 합성 자료뿐이다(합성 아동 프로필 · 합성 자유대화). 출력에는 평가용으로 생성된 합성 대본 · 발화 원문을 담고, 키 · 요청 헤더 · 주소는
 *       담지 않는다. 증거 종류는 '실제 API · 합성 자료'다(테스트 대역 · 실제 기기 증거 아님, R-M11).
 *   <li>운영 시한(상황 생성 15초 · 호출 3초 등)은 바꾸지 않는다. 하니스는 느린 컨테이너와 실호출을 위해 넉넉한 시한으로 돌리고, 실제로 걸린 시간과
 *       운영 예산 안이었는지를 함께 적는다.
 *   <li>OpenAI 호출은 하니스 전체에서 {@value #CALL_CAP}회를 넘지 않는다(realtime 하니스 몫을 남긴다).
 * </ul>
 *
 * <p>출력 위치: 시스템 속성 {@code mujoong.live.eval.out} → env {@code MUJOONG_LIVE_EVAL_OUT} → {@code build/live-eval/} 순으로 고른다.
 * 그 아래 {@code core-practice-packages.json}(요약)과 {@code practice-start-packages/<목표>.json}(realtime 하니스가 읽는 시작 패키지, 모양은
 * {@code contracts/fixtures/practice-start-package.json}과 같다)을 쓴다. 요약의 목표마다 {@code heard}는 장면 말을 아이가 듣는 순서(무중의
 * 설명 → 친구 첫 대사 → 친구 기회 문장, 변형은 무중의 제안 → 첫 대사 → 기회 문장)로 펼치고 참고 점검을 붙인다({@link HeardLines}).
 */
@Tag("luna-live")
class PracticeQualityLiveTest {

    static final String SUMMARY_FILE = "core-practice-packages.json";
    static final String PACKAGE_DIR = "practice-start-packages";
    static final String PREVIOUS_DIR = "previous-run";
    static final String SKIPPED = "SKIPPED_NO_KEY";

    /** 하니스 전체 OpenAI 호출 상한(목표 6개 × 최대 6 + 후보 사슬 3 + 후보 목표 2개 × 최대 6 = 51, 여유 포함). */
    static final int CALL_CAP = 90;
    /** 후보 사슬에서 나온 후보 중 상황까지 만들어 볼 개수. */
    static final int CANDIDATE_GOALS = 2;

    // 하니스 전용 넉넉한 시한. 운영 값은 application.yml에서 읽어 비교만 한다.
    // 호출 하나가 시한을 넘으면 그 목표 전체가 실패하므로(시간 초과는 생성기에서 영구 실패) 호출 시한을 넉넉히 둔다
    static final Duration LUNA_CALL_LIMIT = Duration.ofSeconds(90);
    static final Duration SCENARIO_LIMIT = Duration.ofMinutes(10);
    static final Duration KNOWLEDGE_LIMIT = Duration.ofSeconds(30);
    static final Duration HISTORY_LIMIT = Duration.ofSeconds(10);
    static final Duration CANDIDATE_JOB_LIMIT = Duration.ofMinutes(4);
    static final Duration NEO4J_STARTUP = Duration.ofMinutes(4);

    /** 합성 아동 프로필(실제 아동 아님). 이름은 별칭 '아이'로 바뀌는지 보기 위한 합성 값이다. */
    static final String SYNTHETIC_NAME = "합성아이";
    static final int SYNTHETIC_AGE = 8;
    static final List<String> SYNTHETIC_INTERESTS = List.of("공룡", "그림");

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Test
    void realLunaBuildsPracticeStartPackagesThroughTheProductionGenerationPath() throws IOException {
        Path out = outputDirectory();
        boolean keyPresent = keyPresent();
        if (!keyPresent) {
            writeSkipped(out);
        }
        assumeTrue(keyPresent, SKIPPED + ": env OPENAI_API_KEY 없음(실호출 안 함)");

        Harness.Outcome outcome = new Harness(out, null).run();

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

    // ------------------------------------------------------------------ 합성 입력

    /**
     * 평가할 목표 하나. 씨앗 목표는 하니스가 카탈로그에서 고른 기술에 합성 4요소를 붙인 후보 목표이고(운영에서 후보 생성이 seedSummaries의 seedId를
     * skillRef로 고른 상태와 같다), 후보 목표는 이번 실행의 GOAL_CANDIDATES 출력이다.
     */
    record GoalSpec(String key, String source, String skillRef, String displayName, String targetExpression, FourElements elements) {}

    /**
     * 서로 다른 화용 기술 6개(카탈로그 label: 완화된 거절 · 청자 지식 과소 · 명료화 필요 · 화제 전환 신호 · 완화 표지 산출 · 요청에 대답 없이 내 얘기,
     * RECOGNIZE 2 · PRODUCE 3 · REPAIR 1, 기술 묶음 6가지). 상황 · 표현은 합성이다.
     */
    static List<GoalSpec> seedGoals() {
        return List.of(
                new GoalSpec("s1-softened-refusal", "HARNESS_SELECTED_SEED", "seed:S1", "돌려 말한 거절 알기", "그럼 다음에 같이 그리자",
                        new FourElements("같이 공룡 그림을 그리자고 했는데 친구가 오늘은 손이 좀 아프다고 말할 때", "친구",
                                "친구가 지금은 같이 그리고 싶지 않다는 뜻을 알아차리고 받아들인다", "그럼 다음에 같이 그리자")),
                new GoalSpec("s4-listener-knowledge", "HARNESS_SELECTED_SEED", "seed:S4", "모르는 친구에게 설명", "스테고사우루스는 등에 뾰족한 판이 있는 공룡이야",
                        new FourElements("공룡을 잘 모르는 친구에게 내가 좋아하는 공룡 이야기를 꺼낼 때", "친구",
                                "친구가 모를 수 있는 공룡 이름을 먼저 짧게 알려 주고 이야기한다", "스테고사우루스는 등에 뾰족한 판이 있는 공룡이야")),
                new GoalSpec("s6-clarify-repair", "HARNESS_SELECTED_SEED", "seed:S6", "다시 말해 바로잡기", "아니, 내가 말한 건 파란색이야",
                        new FourElements("모둠 그림을 그리다가 파란색을 달라고 했는데 친구가 초록색을 건넬 때", "모둠 친구",
                                "친구가 내 말을 다르게 알아들었다는 걸 알고 다시 분명하게 말한다", "아니, 내가 말한 건 파란색이야")),
                new GoalSpec("s8-topic-shift-signal", "HARNESS_SELECTED_SEED", "seed:S8", "화제 바꾸는 신호 알기", "주말에는 그림 그릴 거야. 너는 뭐 해?",
                        new FourElements("내가 공룡 이야기를 오래 하는데 친구가 그런데 주말에 뭐 하냐고 물을 때", "친구",
                                "친구가 다른 이야기를 하고 싶어 한다는 걸 알아차리고 친구의 질문에 답한다", "주말에는 그림 그릴 거야. 너는 뭐 해?")),
                new GoalSpec("s10-gentle-request", "HARNESS_SELECTED_SEED", "seed:S10", "부담 없이 부탁하기", "형, 다 쓰고 나서 잠깐 빌려줄 수 있어?",
                        new FourElements("그리기 동아리에서 형이 쓰고 있는 색연필을 잠깐 빌리고 싶을 때", "동아리 형",
                                "형이 부담스럽지 않게 부드럽게 부탁한다", "형, 다 쓰고 나서 잠깐 빌려줄 수 있어?")),
                new GoalSpec("x4-answer-request-first", "HARNESS_SELECTED_SEED", "seed:X4", "부탁에 먼저 대답하기", "응, 여기 있어. 다 쓰면 돌려줘",
                        new FourElements("그리기 동아리에서 그림을 그리는데 동아리 친구가 크레파스를 빌려 달라고 할 때", "동아리 친구",
                                "친구의 부탁을 듣고 먼저 대답한 뒤에 내 이야기를 한다", "응, 여기 있어. 다 쓰면 돌려줘")));
    }

    /** 합성 자유대화 전사(8세 합성 아동, 관심사 공룡 · 그림). 화제 끊기 · 부탁에 대답 없이 내 얘기 · 돌려 말한 뜻 놓치기를 담았다. */
    static List<Map<String, Object>> syntheticTranscript() {
        String[][] lines = {
                {"AI", "안녕! 오늘 학교에서 뭐 했어?"},
                {"CHILD", "공룡 그렸어. 티라노사우루스 그렸어."},
                {"AI", "와, 티라노사우루스를 그렸구나. 무슨 색으로 칠했어?"},
                {"CHILD", "근데 브라키오사우루스는 목이 엄청 길어. 나무 꼭대기 잎도 먹어."},
                {"AI", "목이 길구나. 그런데 아까 티라노는 무슨 색이었어?"},
                {"CHILD", "몰라. 짝꿍이 내 크레파스 빌려 달라고 했는데 나 그냥 계속 그렸어."},
                {"AI", "짝꿍이 크레파스를 빌려 달라고 했구나. 그때 짝꿍은 어땠어?"},
                {"CHILD", "짝꿍이 그 색 진짜 예쁘다 그랬어. 그래서 나 고맙다고 했어."},
                {"AI", "그렇구나. 짝꿍은 그 색을 써 보고 싶었던 걸까?"},
                {"CHILD", "응? 무슨 말이야? 아 그리고 내일은 스테고사우루스 그릴 거야."},
                {"AI", "스테고사우루스도 멋지지. 오늘 이야기 들려줘서 고마워."},
                {"CHILD", "응 안녕. 다음에 또 공룡 얘기 하자."}};
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            rows.add(Map.of("seq", i + 1, "speaker", lines[i][0], "text", lines[i][1], "uncertain", false));
        }
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------ 하니스

    /** 조립 · 실행 · 기록. {@code replacement}는 이 하니스 배선 점검용 가짜 응답 주입 지점이고, 기본 실행은 null(실제 전송)이다. */
    static final class Harness {

        record Outcome(int issued, int variants, int lunaCalls, String evidence, Path summary) {
            String consoleLine() {
                return "PracticeQualityLiveTest: 발급 " + issued + "건(VARIANT 포함 " + variants + "건), Luna 호출 " + lunaCalls
                        + "회, 증거=" + evidence + ", 요약=" + summary;
            }
        }

        private final Path out;
        private final CoreLunaClient replacement;
        private final MujoongClock clock = new SystemMujoongClock();
        private final LunaInputSanitizer sanitizer = new LunaInputSanitizer(JSON);
        private final Map<String, String> aliases = Map.of(SYNTHETIC_NAME, "아이");
        private final Map<String, Object> summary = new LinkedHashMap<>();
        private final List<AutoCloseable> closers = new ArrayList<>();
        private ProductionBudgets production;
        private RecordingLunaClient luna;
        private DbTestSupport db;
        private UUID guardian;
        private UUID child;
        private ChildKnowledgeService knowledge;
        private SeedCatalog catalog;
        private ExecutorService workers;

        Harness(Path out, CoreLunaClient replacement) {
            this.out = out;
            this.replacement = replacement;
        }

        Outcome run() throws IOException {
            prepareOutput();
            summary.put("harness", "PracticeQualityLiveTest(core)");
            summary.put("evidence", evidence());
            summary.put("startedAt", clock.now().toString());
            summary.put("notes", notes());
            List<Map<String, Object>> goals = new ArrayList<>();
            summary.put("goals", goals);
            try {
                production = ProductionBudgets.load();
                summary.put("budgets", budgets());
                workers = Executors.newVirtualThreadPerTaskExecutor();
                luna = client();
                summary.put("model", Map.of("requested", luna.requestedModel(), "baseUrlRule", "STRICT(https + api.openai.com)"));
                summary.put("syntheticChild", Map.of("ageYears", SYNTHETIC_AGE, "interests", SYNTHETIC_INTERESTS,
                        "aliasRule", SYNTHETIC_NAME + " → 아이", "kind", "합성 프로필(실제 아동 아님)"));
                openDatabase();
                summary.put("graph", openGraph());
                var flow = flow();
                for (GoalSpec spec : seedGoals()) {
                    goals.add(runGoal(spec, flow));
                }
                var candidates = runCandidates();
                summary.put("goalCandidates", candidates.report());
                for (GoalSpec spec : candidates.goals()) {
                    goals.add(runGoal(spec, flow));
                }
            } catch (RuntimeException | IOException failure) {
                // 중간에 끊겨도 그때까지 모은 기록은 남긴다(원인은 예외 종류만)
                summary.put("aborted", failure.getClass().getSimpleName());
                throw failure;
            } finally {
                summary.put("finishedAt", clock.now().toString());
                summary.put("totals", totals(goals));
                writePretty(out.resolve(SUMMARY_FILE), summary);
                closeAll();
            }
            int issued = (int) goals.stream().filter(g -> "ISSUED".equals(g.get("result"))).count();
            int variants = (int) goals.stream().filter(g -> Boolean.TRUE.equals(ResultData.object(g.get("variant")).get("issued"))).count();
            return new Outcome(issued, variants, luna == null ? 0 : luna.sent(), evidence(), out.resolve(SUMMARY_FILE));
        }

        /** 실제 전송이면 '실제 API · 합성 자료', 배선 점검용 가짜 응답이면 그렇게 적는다(대역 성공을 실호출 성공으로 적지 않음, R-M11). */
        private String evidence() {
            return replacement == null ? "REAL_API_SYNTHETIC" : "FAKE_PROVIDER_SELF_CHECK";
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
            LunaProperties properties = new LunaProperties(System.getenv("OPENAI_ANALYSIS_MODEL"), URI.create("https://api.openai.com/v1"),
                    production.maxResponseBytes(), production.purposes());
            LunaHosts.Mode mode = LunaHosts.modeFor();
            if (mode != LunaHosts.Mode.STRICT || LunaHosts.check(properties.baseUrl(), mode).isPresent()) {
                throw new IllegalStateException("STRICT 주소 규칙이 아니다");
            }
            var transport = new LunaTransport(properties.baseUrl(), mode, () -> System.getenv(LunaTransport.API_KEY_ENV),
                    properties.maxResponseBytes());
            closers.add(transport);
            var events = new TestEvents(clock);
            return new RecordingLunaClient(properties, harnessTiming(), transport, events, clock, replacement);
        }

        /** 운영 재시도 · 온도 · 판 값은 그대로 두고 시한만 넉넉히 바꾼다. */
        private TextAiProperties harnessTiming() {
            var p = production.textAi();
            return new TextAiProperties(LUNA_CALL_LIMIT, p.retry(), new TextAiProperties.Result(LUNA_CALL_LIMIT,
                    p.result().temperatureJudgment(), p.result().temperatureGeneration(), p.result().promptVersion()));
        }

        private void openDatabase() {
            db = DbTestSupport.coreApp();
            closers.add(db);
            guardian = db.insertGuardian("ACTIVE");
            child = db.insertChild(guardian);
        }

        /**
         * 지식 그래프: Neo4j 컨테이너 → (실패하면) 메모리 그래프 → (그것도 실패하면) 그래프 없음. 어느 쪽인지와 까닭을 기록한다. 메모리 그래프는 운영이
         * Neo4j를 끈 설정에서 쓰는 같은 포트 구현이다.
         */
        private Map<String, Object> openGraph() throws IOException {
            Map<String, Object> report = new LinkedHashMap<>();
            try (InputStream source = getClass().getResourceAsStream("/knowledge/social-comm-poc.zip")) {
                if (source == null) {
                    throw new IllegalStateException("씨앗 원천 자원이 없다");
                }
                catalog = new SeedArchiveLoader().load(source, "poc-v1");
            }
            report.put("catalogVersion", catalog.version());
            report.put("catalogSeeds", catalog.seeds().size());
            List<String> fallbacks = new ArrayList<>();
            try {
                if (initialize(neo4j(), true)) {
                    report.put("backend", "NEO4J_TESTCONTAINER");
                }
            } catch (RuntimeException failure) {
                fallbacks.add("NEO4J:" + failure.getClass().getSimpleName());
            }
            if (!report.containsKey("backend")) {
                if (fallbacks.isEmpty()) {
                    fallbacks.add("NEO4J:INITIALIZATION_FAILED");
                }
                try {
                    child = db.insertChild(guardian);
                    if (initialize(new MemoryChildKnowledgeGraph(), true)) {
                        report.put("backend", "MEMORY_FALLBACK");
                    } else {
                        fallbacks.add("MEMORY:INITIALIZATION_FAILED");
                    }
                } catch (RuntimeException failure) {
                    fallbacks.add("MEMORY:" + failure.getClass().getSimpleName());
                }
            }
            if (!report.containsKey("backend")) {
                initialize(new MemoryChildKnowledgeGraph(), false);
                report.put("backend", "NONE");
                report.put("effect", "그래프 맥락 없이 BASE만 만드는 경로로 돈다(VARIANT 없음)");
            }
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
                    "neo4j/" + password, KNOWLEDGE_LIMIT);
            graph.prepareSchema();
            return graph;
        }

        /** GraphWorkflowIT와 같은 조립. 접근 정책만 보호자 잠금을 거는 대역이다(동의 · 계정 조회는 이 하니스 범위 밖). */
        private boolean initialize(ChildKnowledgeGraphPort graph, boolean enabled) {
            var ledger = new KnowledgeLedgerRepository(db.jdbc());
            var access = mock(KnowledgeAccessPolicy.class);
            var lock = new GuardianWriteLock(db.jdbc());
            doAnswer(c -> {
                lock.lockActive(guardian);
                return null;
            }).when(access).requireAllowed(eq(guardian), any());
            doAnswer(c -> {
                lock.statusForUpdate(guardian);
                return null;
            }).when(access).requireDeletionOwner(eq(guardian), any());
            if (knowledge != null) {
                knowledge.close();
            }
            knowledge = new ChildKnowledgeService(ledger, access, graph, db.tx().getTransactionManager(), clock, catalog, enabled,
                    KNOWLEDGE_LIMIT);
            UUID owner = child;
            ChildKnowledgeService service = knowledge;
            closers.add(() -> cleanKnowledge(service, ledger, owner));
            return enabled && knowledge.initializeChild(guardian, child)
                    && knowledge.seedSummaries(guardian, child).size() == catalog.seeds().size();
        }

        private void cleanKnowledge(ChildKnowledgeService service, KnowledgeLedgerRepository ledger, UUID owner) {
            try {
                if (service.enabled()) {
                    service.deleteChild(guardian, owner);
                }
            } catch (RuntimeException ignored) {
                // 정리 실패는 평가 결과에 영향이 없다. 아래 장부 정리와 DB 정리가 이어서 돈다
            } finally {
                service.close();
                db.inTx(t -> {
                    ledger.deleteLedger(owner, null);
                    ledger.forgetDeletedChildren(guardian);
                    return null;
                });
            }
        }

        /** 운영 ScenarioService.connectStructured와 같은 부품. 시한만 하니스 값이다. */
        private StructuredScenarioFlow flow() {
            var provider = new LunaTextAiProvider(luna, clock, JSON);
            var generator = new ScenarioScriptGenerator(provider, new ScenarioScriptValidation(new ForbiddenExpressionPolicy()),
                    harnessTiming(), workers, CoreLunaClient.SYSTEM_SLEEPER::sleep);
            ScenarioPrivacyContextPort privacy = (g, c) -> new ScenarioPrivacyContextPort.Context(SYNTHETIC_NAME);
            return new StructuredScenarioFlow(generator, privacy, sanitizer, new ScenarioGraphContext(knowledge, workers, clock,
                    KNOWLEDGE_LIMIT), new ScenarioRepository(db.jdbc(), JSON), workers, HISTORY_LIMIT, JSON);
        }

        // -------------------------------------------------------------- 목표 하나: 상황 대본 → 시작 패키지

        private Map<String, Object> runGoal(GoalSpec spec, StructuredScenarioFlow flow) throws IOException {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", spec.key());
            row.put("source", spec.source());
            KnowledgeSeed seed = catalog.seeds().stream().filter(s -> s.seedId().equals(spec.skillRef())).findFirst().orElse(null);
            if (luna.remaining() < 6) {
                row.put("result", "SKIPPED_CALL_CAP");
                return row;
            }
            luna.startBucket();
            // PracticeStartService.selection과 같은 정리: 표시 이름 · 4요소의 아동 이름을 별칭으로 바꾸고 불투명 식별자는 그대로 둔다
            GoalContext original = new GoalContext(SavedGoalReader.SavedGoalView.Kind.CANDIDATE, "live-" + spec.key(), 1,
                    spec.targetExpression(), spec.elements(), spec.skillRef(), null, null);
            GoalContext goal = original.mapContent(this::clean);
            String displayName = clean(spec.displayName());
            UUID selection = UUID.randomUUID();
            UUID session = UUID.randomUUID();
            List<FrozenGoal> frozen = new WeightedGoalOrder().freeze(guardian, child, List.of(new WeightedGoalOrder.Selection(guardian,
                    child, selection, displayName, goal.carrierKey(), goal.carrierVersion(), goal.criteria(), 1, false, goal)),
                    RandomGenerator.getDefault());
            row.put("goal", goalReport(displayName, goal, seed));
            // PracticeStartService.context와 같은 세션 사본: 후보의 상대 문구가 관계가 되고, 준비 중이라 active=false다
            var snapshot = new PracticeSessionContextPort.GoalSnapshot(selection, goal.carrierKey(), goal.carrierVersion(),
                    goal.fourElements().partner(), goal);
            var context = new PracticeSessionContextPort.SessionContext(session, guardian, child, false, List.of(snapshot));
            var account = new PracticeAccountPort.AccountContext(guardian, child, SYNTHETIC_AGE, SYNTHETIC_INTERESTS);
            row.put("input", Map.of("ageYears", SYNTHETIC_AGE, "interests", SYNTHETIC_INTERESTS, "relation", goal.fourElements().partner(),
                    "opportunityCondition", goal.fourElements().situation(), "targetExpression", goal.targetExpression()));
            Instant started = clock.now();
            long startedNanos = clock.monotonicNanos();
            Scenario scenario = null;
            List<ScenarioScriptGenerator.Note> notes = List.of();
            try {
                ScenarioScriptGenerator.Result result = flow.generate(context, snapshot, null, null, account, started,
                        new ScenarioDeadline(clock, started.plus(SCENARIO_LIMIT)));
                notes = result.notes();
                // 발급 대본 · 준비 원본 · C8 조각은 저장과 교차 계약 시험(PracticeLinkContractTest)이 쓰는 같은 조립이다
                scenario = ScenarioRepository.issued(UUID.randomUUID(), "ORIGINAL", result.legacy(), result.details());
                var pack = new PreparedPracticePackageMapper().c8(new PreparedPracticePackageMapper.PreparedSnapshot(session, UUID.randomUUID(),
                        frozen, scenario));
                Path file = out.resolve(PACKAGE_DIR).resolve(spec.key() + ".json");
                writePretty(file, plain(pack));
                row.put("result", "ISSUED");
                row.put("startPackageFile", out.relativize(file).toString());
                row.put("sceneKinds", scenario.scenes().stream().map(s -> s.sceneKind()).toList());
                row.put("heard", HeardLines.of(scenario, goal.targetExpression()));
                row.put("startPackage", plain(pack));
                row.put("selfReview", plain(scenario.selfReview()));
                row.put("generation", plain(scenario.generation()));
            } catch (ScenarioScriptGenerator.ContentExhausted exhausted) {
                notes = exhausted.notes();
                row.put("result", "FAILED");
                row.put("failure", Map.of("code", exhausted.code().name(), "kind", "CONTENT_EXHAUSTED", "permanent", exhausted.permanent()));
            } catch (CoreApiException failure) {
                row.put("result", "FAILED");
                row.put("failure", Map.of("code", failure.code().name(), "kind", "REQUEST_FAILED"));
            } catch (RuntimeException failure) {
                row.put("result", "FAILED");
                row.put("failure", Map.of("code", failure.getClass().getSimpleName(), "kind", "UNEXPECTED"));
            }
            Duration elapsed = clock.elapsedSince(startedNanos);
            List<Map<String, Object>> recorded = luna.finishBucket();
            row.put("timing", timing(elapsed, recorded, production.scenarioRequest(), production.textAiRequest()));
            row.put("luna", callCounts(recorded));
            row.put("reviews", reviews(recorded));
            row.put("graphContextUsed", scenario != null && scenario.generation() != null && scenario.generation().graphContextUsed());
            row.put("graphContextOffered", recorded.stream().anyMatch(c -> ResultData.object(c.get("input")).containsKey("structureContext")));
            row.put("generatorNotes", notes.stream().map(Enum::name).toList());
            row.put("variant", VariantDiagnosis.of(scenario, spec.skillRef(), seed, recorded, notes));
            row.put("calls", recorded);
            return row;
        }

        private String clean(String text) {
            return (String) sanitizer.sanitize(Map.of("text", text), aliases, LunaPurpose.SCENARIO_GENERATION).payload().get("text");
        }

        private Map<String, Object> goalReport(String displayName, GoalContext goal, KnowledgeSeed seed) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("displayName", displayName);
            report.put("skillName", seed == null ? null : seed.label());
            report.put("skillRef", goal.skillRef());
            report.put("behaviorDefinition", goal.criteria());
            report.put("targetExpression", goal.targetExpression());
            report.put("fourElements", plain(goal.fourElements()));
            if (seed != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("seedId", seed.seedId());
                s.put("label", seed.label());
                s.put("family", seed.family());
                s.put("signature", plain(seed.signature()));
                s.put("invariantSentence", seed.invariantSentence());
                s.put("preservationRule", seed.preservationRule());
                s.put("relationship", seed.relationship());
                s.put("situation", seed.situation());
                report.put("seed", s);
            }
            return report;
        }

        // -------------------------------------------------------------- 결과 → 목표 후보(한 번)

        record Candidates(Map<String, Object> report, List<GoalSpec> goals) {}

        /**
         * 자유대화 결과 작업(SessionResultHandler)과 같은 사슬: structure(경험 구조) → GOAL_CANDIDATES(GoalCandidateGateway: 씨앗 목록 · 입력 허용
         * 목록 · 검증기) → classify(OutputChecker 2차 분류). 문장 생성(sentences)은 후보 입력이 아니라서 부르지 않는다.
         */
        private Candidates runCandidates() {
            Map<String, Object> report = new LinkedHashMap<>();
            if (luna.remaining() < 3) {
                report.put("result", "SKIPPED_CALL_CAP");
                return new Candidates(report, List.of());
            }
            luna.startBucket();
            var transcript = syntheticTranscript();
            report.put("transcript", transcript);
            var quotes = ResultData.childQuotes(transcript);
            Instant deadline = clock.now().plus(CANDIDATE_JOB_LIMIT);
            long startedNanos = clock.monotonicNanos();
            var resultText = new ResultTextGateway(new LunaResultTextProvider(luna, clock), harnessTiming(), clock, workers, JSON);
            var dictionary = new ForbiddenDictionary();
            var gateway = new GoalCandidateGateway(Optional.of(luna), knowledge, new GoalCandidateValidator(dictionary),
                    new OutputChecker(dictionary, resultText));
            Map<String, Object> common = new LinkedHashMap<>();
            common.put("transcript", transcript);
            common.put("endReason", "CHILD_STOP");
            Map<String, Object> experience = Map.of();
            try {
                experience = verifiedExperience(resultText.call("structure", common, aliases, deadline), quotes);
            } catch (ResultAiException failure) {
                // 운영은 여기서 시도 실패로 끝난다. 하니스는 빈 경험 구조로 후보 단계까지 이어 보고 그 사실을 적는다
                report.put("structureFailure", failure.code());
            }
            report.put("structured", experience);
            Map<String, Object> input = new LinkedHashMap<>(common);
            input.put("structured", experience);
            input.put("scenes", List.of());
            List<GoalCandidateRepository.Draft> drafts = List.of();
            try {
                drafts = gateway.generate(guardian, child, input, aliases, deadline, () -> { });
                report.put("result", "OK");
            } catch (ResultAiException failure) {
                report.put("result", "FAILED");
                report.put("failure", failure.code());
            } catch (RuntimeException failure) {
                report.put("result", "FAILED");
                report.put("failure", failure.getClass().getSimpleName());
            }
            List<Map<String, Object>> recorded = luna.finishBucket();
            report.put("kept", plain(drafts));
            report.put("timing", timing(clock.elapsedSince(startedNanos), recorded, production.resultAttempt(), production.resultRequest()));
            report.put("luna", callCounts(recorded));
            report.put("calls", recorded);
            List<GoalSpec> goals = new ArrayList<>();
            var ordered = new ArrayList<>(drafts);
            // 씨앗에 이어진 후보를 먼저 고른다(그래프 경로 · VARIANT를 볼 수 있는 쪽)
            ordered.sort((a, b) -> Boolean.compare("NONE".equals(a.skillRef()), "NONE".equals(b.skillRef())));
            for (var draft : ordered) {
                if (goals.size() == CANDIDATE_GOALS) {
                    break;
                }
                goals.add(new GoalSpec("candidate-" + (goals.size() + 1), "GOAL_CANDIDATES_OUTPUT", draft.skillRef(), draft.displayName(),
                        draft.targetExpression(), draft.fourElements()));
            }
            report.put("followedUp", goals.stream().map(GoalSpec::key).toList());
            report.put("notFollowedUp", "NONE 후보의 새 씨앗 생성(SEED_GENERATION · CandidateSeedDelivery)은 이 하니스에서 돌리지 않는다. "
                    + "NONE 후보는 그래프 없이 BASE만 나온다");
            return new Candidates(report, List.copyOf(goals));
        }

        // -------------------------------------------------------------- 기록 정리

        private Map<String, Object> budgets() {
            Map<String, Object> harness = new LinkedHashMap<>();
            harness.put("lunaCallLimitMs", LUNA_CALL_LIMIT.toMillis());
            harness.put("scenarioLimitMs", SCENARIO_LIMIT.toMillis());
            harness.put("knowledgeLimitMs", KNOWLEDGE_LIMIT.toMillis());
            harness.put("historyLimitMs", HISTORY_LIMIT.toMillis());
            harness.put("candidateJobLimitMs", CANDIDATE_JOB_LIMIT.toMillis());
            harness.put("callCap", CALL_CAP);
            Map<String, Object> prod = new LinkedHashMap<>();
            prod.put("scenarioRequestTimeoutMs", production.scenarioRequest().toMillis());
            prod.put("textAiRequestTimeoutMs", production.textAiRequest().toMillis());
            prod.put("resultRequestTimeoutMs", production.resultRequest().toMillis());
            prod.put("resultAttemptTimeoutMs", production.resultAttempt().toMillis());
            prod.put("graphBudgetMs", production.graphBudget().toMillis());
            prod.put("scenarioHistoryTimeoutMs", production.historyTimeout().toMillis());
            // 목적별 추론 강도도 application.yml 값을 그대로 쓰므로, 어느 설정을 잰 실행인지 결과에 남긴다
            Map<String, Object> effort = new LinkedHashMap<>();
            for (LunaPurpose purpose : LunaPurpose.values()) {
                effort.put(purpose.name(), production.purposes().of(purpose).reasoningEffort());
            }
            prod.put("lunaReasoningEffort", effort);
            return Map.of("harness", harness, "production(application.yml)", prod,
                    "rule", "운영 값은 바꾸지 않았다. 하니스는 넉넉한 시한으로 돌고 timing에 운영 예산 안이었는지를 적는다");
        }

        private static List<String> notes() {
            return List.of(
                    "증거 종류: 실제 API · 합성 자료. 테스트 대역 성공 · 실제 기기 성공이 아니다(R-M11).",
                    "입력은 모두 합성이다(합성 아동 프로필 · 합성 자유대화 · 하니스가 쓴 합성 4요소). 실제 아동 데이터는 없다.",
                    "씨앗 목표(HARNESS_SELECTED_SEED)는 카탈로그 기술(label)에 하니스가 합성 4요소를 붙인 후보 목표다. 운영에서 후보 생성이 seedId를 skillRef로 고른 상태와 같다.",
                    "시작 패키지는 ScenarioRepository.issued → PreparedPracticePackageMapper.c8 조립이고 DB 저장 · 준비 원본 임대(PracticeStartService) · 동의 확인은 거치지 않았다(내용에는 영향 없음).",
                    "노출 이력은 비어 있다(아이의 첫 연습). 그래서 변형 거리는 SAME_CONTEXT가 기대값이다.",
                    "generatorNotes · variant.generatorPath는 운영 생성기(ScenarioScriptGenerator.Note)가 실제로 낸 사유 코드와 그 뜻이다(예: BASE 바꿔 "
                            + "끼움 · BASE만 발급(추가 검토 없음)). variant.diagnosis는 그 사유 코드로 정하고(source GENERATOR_NOTES), 사유 코드가 "
                            + "말하지 않는 앞 단계(그래프 · 계획 · 모델 출력)만 기록된 호출로 하니스가 정한다(source RECORDED_CALLS).",
                    "heard는 아이가 듣는 순서(무중의 설명 → 친구 첫 대사 → 친구 기회 문장, 다시 설명은 무중, 변형은 무중의 제안 '… 이어서 해 볼까?' 뒤 "
                            + "첫 대사 → 기회 문장)로 펼친 장면 말과 하니스 참고 점검(들리는 말에 목표 표현 · 표현 예시가 글자 그대로 있나, 설명이 기회 "
                            + "조건을 옮겨 썼나, 칭찬 · 채점 말, BASE 첫 대사, 설명이 기회 문장의 핵심 낱말을 미리 말하나, 빈칸 예문 칸 · '뭐뭐' · 비운 말, "
                            + "VARIANT 기회 문장이 BASE와 같거나 같은 핵심 낱말을 쓰나)이다. 핵심 낱말은 띄어쓰기 낱말에서 끝 조사 하나를 뗀 글자 비교"
                            + "(test-support HeardWords)라 참고 값이다. 운영 검사가 아니다.");
        }

        private static Map<String, Object> timing(Duration elapsed, List<Map<String, Object>> calls, Duration productionTotal,
                Duration productionCall) {
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("elapsedMs", elapsed.toMillis());
            timing.put("productionBudgetMs", productionTotal.toMillis());
            timing.put("withinProductionBudget", elapsed.compareTo(productionTotal) <= 0);
            timing.put("productionPerCallTimeoutMs", productionCall.toMillis());
            List<Long> latencies = calls.stream().map(c -> c.get("latencyMs") instanceof Number n ? n.longValue() : -1L).toList();
            timing.put("callLatenciesMs", latencies);
            timing.put("callsOverProductionTimeout", latencies.stream().filter(l -> l > productionCall.toMillis()).count());
            timing.put("lunaLatencySumMs", latencies.stream().filter(l -> l >= 0).mapToLong(Long::longValue).sum());
            return timing;
        }

        private static Map<String, Object> callCounts(List<Map<String, Object>> calls) {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("total", calls.size());
            for (var call : calls) {
                counts.merge(String.valueOf(call.get("purpose")), 1, (a, b) -> (Integer) a + (Integer) b);
            }
            counts.put("failed", calls.stream().filter(c -> !"OK".equals(c.get("outcome"))).map(c -> c.get("purpose") + ":" + c.get("outcome"))
                    .toList());
            return counts;
        }

        private static List<Map<String, Object>> reviews(List<Map<String, Object>> calls) {
            List<Map<String, Object>> reviews = new ArrayList<>();
            for (var call : calls) {
                if (!LunaPurpose.SCENARIO_REVIEW.name().equals(call.get("purpose"))) {
                    continue;
                }
                var output = ResultData.object(call.get("output"));
                Map<String, Object> review = new LinkedHashMap<>();
                review.put("phase", call.get("phase"));
                review.put("outcome", call.get("outcome"));
                review.put("verdict", output.get("verdict"));
                review.put("reasons", output.get("reasons"));
                reviews.add(review);
            }
            return reviews;
        }

        private Map<String, Object> totals(List<Map<String, Object>> goals) {
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("lunaCalls", luna == null ? 0 : luna.sent());
            totals.put("callCap", CALL_CAP);
            totals.put("goals", goals.size());
            totals.put("issued", goals.stream().filter(g -> "ISSUED".equals(g.get("result"))).count());
            totals.put("variantIssued", goals.stream().filter(g -> Boolean.TRUE.equals(ResultData.object(g.get("variant")).get("issued"))).count());
            totals.put("withinProductionScenarioBudget", goals.stream()
                    .filter(g -> Boolean.TRUE.equals(ResultData.object(g.get("timing")).get("withinProductionBudget"))).count());
            totals.put("heardChecks", HeardLines.totals(goals));
            return totals;
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
     * 모든 Luna 호출을 지금 목표 칸에 적고 하니스 전체 상한을 지킨다. 적는 것: 목적 · 판 · 단계 · 보낸 입력(정리 뒤, 합성 자료) · 결과 사유 · 응답
     * 모델 · 지연 · 응답 JSON(합성 대본). 키 · 요청 헤더 · 주소는 적지 않는다.
     */
    static final class RecordingLunaClient extends CoreLunaClient {

        private final LunaProperties properties;
        private final MujoongClock clock;
        private final CoreLunaClient replacement;
        private final LunaInputSanitizer sanitizer = new LunaInputSanitizer(JSON);
        private final AtomicInteger sent = new AtomicInteger();
        private List<Map<String, Object>> bucket = new ArrayList<>();

        RecordingLunaClient(LunaProperties properties, TextAiProperties timing, LunaTransport transport, TestEvents events,
                MujoongClock clock, CoreLunaClient replacement) {
            super(properties, timing, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON), transport, events.logger(), clock, JSON,
                    CoreLunaClient.SYSTEM_SLEEPER);
            this.properties = properties;
            this.clock = clock;
            this.replacement = replacement;
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

        synchronized void startBucket() {
            bucket = new ArrayList<>();
        }

        synchronized List<Map<String, Object>> finishBucket() {
            List<Map<String, Object>> done = List.copyOf(bucket);
            bucket = new ArrayList<>();
            return done;
        }

        private synchronized void record(Map<String, Object> row) {
            row.put("index", bucket.size() + 1);
            bucket.add(row);
        }

        @Override
        public LunaResult call(LunaRequest request, LunaCallBudget budget) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("purpose", request.purpose().name());
            row.put("promptVersion", request.promptVersion());
            row.put("phase", phase(request));
            row.put("input", sentInput(request));
            if (sent.get() >= CALL_CAP) {
                row.put("outcome", "CALL_CAP_REACHED");
                record(row);
                throw new LunaException(LunaFailureReason.REQUEST_REJECTED, 0);
            }
            long started = clock.monotonicNanos();
            try {
                LunaResult result = replacement == null ? super.call(request, budget) : replacement.call(request, budget);
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
                record(row);
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

        private static String phase(LunaRequest request) {
            var input = request.input();
            String step = switch (request.purpose()) {
                case SCENARIO_GENERATION -> input.containsKey("variantPlan") ? "GENERATE_WITH_VARIANT_PLAN"
                        : Boolean.TRUE.equals(input.get("baseOnly")) ? "GENERATE_BASE_ONLY" : "GENERATE";
                // 검토 단계는 검토한 대본의 장면 종류로 적는다(첫 BASE 검토에도 계획이 붙어 갈 수 있다)
                case SCENARIO_REVIEW -> "REVIEW_" + (input.get("script") instanceof ScenarioScript script
                        ? String.join("_", script.scenes().stream().map(ScenarioScript.Scene::sceneKind).toList()) : "SCRIPT")
                        + (input.containsKey("variantPlan") ? "+variantPlan" : "");
                case RESULT_TEXT -> input.containsKey("sentences") ? "RESULT_TEXT_CLASSIFY"
                        : input.containsKey("transcript") ? "RESULT_TEXT_STRUCTURE" : "RESULT_TEXT";
                default -> request.purpose().name();
            };
            List<String> flags = new ArrayList<>();
            if (Boolean.TRUE.equals(input.get("correction"))) {
                flags.add("correction");
            }
            if (input.containsKey("baseToPreserve")) {
                flags.add("baseToPreserve");
            }
            return flags.isEmpty() ? step : step + "+" + String.join("+", flags);
        }

        private static Object parse(String output) {
            try {
                return JSON.read(output, Object.class);
            } catch (RuntimeException unreadable) {
                return Map.of("raw", output);
            }
        }
    }

    // ------------------------------------------------------------------ 아이가 듣는 순서 · 참고 점검

    /**
     * 발급 대본의 장면 말을 아이가 듣는 순서로 펼치고 하니스 참고 점검을 붙인다(운영 검사 아님, 정규화는 core {@link ScenarioContentPolicy#normalize}).
     * 점검: 아이가 대답하기 전에 들리는 말(설명 · 첫 대사 · 기회 문장 · 다시 설명)에 목표 표현 · 장면 표현 예시가 글자 그대로 있나, BASE 설명이 기회
     * 조건을 옮겨 썼나(판 v5까지의 모양), 칭찬 · 채점 말이 있나, BASE 첫 대사가 있나. 실측 F2 · F5(docs/practice-dialogue-standard.md 14절)로 더한
     * 참고 점검: 설명이 기회 문장의 핵심 낱말(단서)을 미리 말하나, 빈칸 예문 칸이 있고 예시와 1:1 · '뭐뭐'를 담나 · 무엇을 비웠나, VARIANT 기회 문장이
     * BASE와 같거나 같은 핵심 낱말을 쓰나(소재가 그대로인가). 핵심 낱말은 {@link HeardWords} 글자 비교다.
     */
    static final class HeardLines {

        /** 들리는 말에서 찾는 칭찬 · 채점 말(realtime 대화 하니스 점검표와 같은 목록). */
        static final List<String> PRAISE_WORDS = List.of("잘했", "잘 했", "잘 마쳤", "훌륭", "멋지", "최고", "대단", "정답", "맞았어", "맞아요",
                "틀렸", "100점", "칭찬");
        /** 서버가 변형 제안에 붙이는 고정 꼬리(realtime PracticeInstructions.VARIANT_OFFER_QUESTION과 같은 글). */
        static final String VARIANT_OFFER_QUESTION = "이어서 해 볼까?";

        private HeardLines() {
        }

        static List<Map<String, Object>> of(Scenario scenario, String targetExpression) {
            List<Map<String, Object>> scenes = new ArrayList<>();
            if (scenario == null || scenario.scenes() == null) {
                return scenes;
            }
            ScenarioScript.Scene base = scenario.scenes().stream().filter(s -> "BASE".equals(s.sceneKind())).findFirst().orElse(null);
            for (ScenarioScript.Scene scene : scenario.scenes()) {
                boolean variant = "VARIANT".equals(scene.sceneKind());
                boolean opener = scene.partnerOpener() != null && !scene.partnerOpener().isBlank();
                List<String> order = new ArrayList<>();
                order.add("무중: " + scene.sceneDescription() + (variant ? " " + VARIANT_OFFER_QUESTION : ""));
                if (opener) {
                    order.add("친구: " + scene.partnerOpener());
                }
                order.add("친구: " + scene.opportunitySentence());
                List<String> heard = new ArrayList<>();
                for (String line : new String[] {scene.sceneDescription(), scene.partnerOpener(), scene.opportunitySentence(),
                        scene.reexplanation()}) {
                    if (line != null && !line.isBlank()) {
                        heard.add(line);
                    }
                }
                List<String> targets = new ArrayList<>();
                if (targetExpression != null) {
                    targets.add(targetExpression);
                }
                targets.addAll(scene.expressionExamples());
                Map<String, Object> checks = new LinkedHashMap<>();
                checks.put("targetHeard", targets.stream().map(ScenarioContentPolicy::normalize).filter(t -> t.length() >= 4)
                        .anyMatch(t -> heard.stream().anyMatch(h -> ScenarioContentPolicy.normalize(h).contains(t))));
                String condition = scenario.opportunityCondition();
                checks.put("opportunityConditionCopied", !variant && condition != null && ScenarioContentPolicy.normalize(condition).length() >= 4
                        && ScenarioContentPolicy.normalize(scene.sceneDescription()).contains(ScenarioContentPolicy.normalize(condition)));
                checks.put("praiseWords", PRAISE_WORDS.stream().filter(word -> heard.stream().anyMatch(h -> h.contains(word))).toList());
                if (!variant) {
                    checks.put("partnerOpenerPresent", opener);
                }
                checks.put("descriptionMentionsOpportunityWords",
                        HeardWords.mentioned(HeardWords.keyWords(scene.opportunitySentence()), scene.sceneDescription()));
                checks.put("expressionFrames", frames(scene));
                if (variant && base != null) {
                    Map<String, Object> same = new LinkedHashMap<>();
                    same.put("sameAsBase", HeardWords.same(base.opportunitySentence(), scene.opportunitySentence()));
                    same.put("sharedKeyWords", HeardWords.sharedKeyWords(base.opportunitySentence(), scene.opportunitySentence()));
                    checks.put("variantOpportunity", same);
                }
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("sceneKind", scene.sceneKind());
                view.put("heardOrder", order);
                view.put("reexplanation", "무중: " + scene.reexplanation());
                view.put("checks", checks);
                scenes.add(view);
            }
            return scenes;
        }

        /** 빈칸 예문 칸 보기: 있나, 예시와 1:1인가, 모두 '뭐뭐'를 담나, 예시마다 무엇을 비웠나(맞춰 볼 수 없으면 UNALIGNED). */
        static Map<String, Object> frames(ScenarioScript.Scene scene) {
            Map<String, Object> view = new LinkedHashMap<>();
            List<String> frames = scene.expressionFrames();
            view.put("present", frames != null);
            if (frames == null) {
                return view;
            }
            List<String> examples = scene.expressionExamples();
            view.put("oneToOne", frames.size() == examples.size());
            view.put("allBlanked", frames.stream().allMatch(frame -> frame.contains(HeardWords.BLANK)));
            List<Object> blanked = new ArrayList<>();
            for (int i = 0; i < Math.min(frames.size(), examples.size()); i++) {
                blanked.add(HeardWords.blankedWords(examples.get(i), frames.get(i)).<Object>map(words -> words).orElse("UNALIGNED"));
            }
            view.put("blankedWords", blanked);
            return view;
        }

        /** 목표들의 점검 합계(장면 수 기준). */
        static Map<String, Object> totals(List<Map<String, Object>> goals) {
            int scenes = 0;
            int targetHeard = 0;
            int conditionCopied = 0;
            int praise = 0;
            int missingOpener = 0;
            int cueInDescription = 0;
            int framesMissing = 0;
            int framesOff = 0;
            int variantSame = 0;
            int variantShared = 0;
            for (Map<String, Object> goal : goals) {
                for (Map<String, Object> scene : ResultData.objects(goal.get("heard"))) {
                    Map<String, Object> checks = ResultData.object(scene.get("checks"));
                    scenes++;
                    targetHeard += Boolean.TRUE.equals(checks.get("targetHeard")) ? 1 : 0;
                    conditionCopied += Boolean.TRUE.equals(checks.get("opportunityConditionCopied")) ? 1 : 0;
                    praise += checks.get("praiseWords") instanceof List<?> words && !words.isEmpty() ? 1 : 0;
                    missingOpener += Boolean.FALSE.equals(checks.get("partnerOpenerPresent")) ? 1 : 0;
                    cueInDescription += checks.get("descriptionMentionsOpportunityWords") instanceof List<?> cue && !cue.isEmpty() ? 1 : 0;
                    Map<String, Object> frames = ResultData.object(checks.get("expressionFrames"));
                    framesMissing += Boolean.FALSE.equals(frames.get("present")) ? 1 : 0;
                    framesOff += Boolean.TRUE.equals(frames.get("present"))
                            && (!Boolean.TRUE.equals(frames.get("oneToOne")) || !Boolean.TRUE.equals(frames.get("allBlanked"))) ? 1 : 0;
                    Map<String, Object> variant = ResultData.object(checks.get("variantOpportunity"));
                    variantSame += Boolean.TRUE.equals(variant.get("sameAsBase")) ? 1 : 0;
                    variantShared += variant.get("sharedKeyWords") instanceof List<?> shared && !shared.isEmpty() ? 1 : 0;
                }
            }
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("scenes", scenes);
            totals.put("targetHeard", targetHeard);
            totals.put("opportunityConditionCopied", conditionCopied);
            totals.put("praiseWords", praise);
            totals.put("baseWithoutPartnerOpener", missingOpener);
            totals.put("descriptionMentionsOpportunityWords", cueInDescription);
            totals.put("scenesWithoutExpressionFrames", framesMissing);
            totals.put("expressionFramesNotOneToOneOrWithoutBlank", framesOff);
            totals.put("variantOpportunitySameAsBase", variantSame);
            totals.put("variantOpportunitySharesKeyWords", variantShared);
            return totals;
        }
    }

    // ------------------------------------------------------------------ VARIANT 진단

    /**
     * VARIANT가 발급됐는지, 아니면 왜 안 나왔는지. 생성기가 실제로 낸 사유 코드({@link ScenarioScriptGenerator.Note})를 {@code generatorPath}에 뜻과
     * 함께 싣고, 생성된 VARIANT가 어떻게 됐는지는 그 사유 코드로 정한다(source GENERATOR_NOTES). 사유 코드가 말하지 않는 앞 단계(그래프 맥락 · BASE 좌표
     * · 계획 요청 · 모델 출력)만 기록된 호출로 정한다(source RECORDED_CALLS). 지난 실측의 s8 진단은 마지막 추정이 실제 원인(보존 BASE 판정)과
     * 달랐다(docs/practice-dialogue-standard.md 12.2.1).
     */
    static final class VariantDiagnosis {

        private VariantDiagnosis() {
        }

        /** 사유 코드의 뜻(사람이 읽는 보고서용). */
        static String label(ScenarioScriptGenerator.Note note) {
            return switch (note) {
                case BASE_APPROVED_ALONE -> "BASE 단독 검토 승인";
                case BASE_REPLACED -> "BASE 바꿔 끼움(다음 생성이 고친 보존 BASE를 검토 전에 되돌림)";
                case REVIEW_REUSED -> "단독 검토 승인을 다시 씀(검토 호출 없음)";
                case VARIANT_INVALID -> "서버 검사가 변형을 뺌";
                case VARIANT_REJECTED -> "검토가 변형 사유로만 거절";
                case BASE_INVALID -> "서버 검사가 BASE를 거절";
                case REVIEW_NOT_APPROVED -> "검토가 승인하지 않음(변형 사유만은 아님)";
                case GRAPH_FALLBACK -> "그래프 맥락이 늦어 그래프 없이 다시 생성";
                case BASE_ONLY_EARLIER_REVIEW -> "BASE만 발급(추가 검토 없음)";
                case BASE_ONLY_REVIEWED -> "BASE만 발급(BASE만 다시 검토)";
            };
        }

        static Map<String, Object> of(Scenario scenario, String skillRef, KnowledgeSeed seed, List<Map<String, Object>> calls,
                List<ScenarioScriptGenerator.Note> notes) {
            Map<String, Object> result = new LinkedHashMap<>();
            boolean issued = scenario != null && scenario.scenes() != null
                    && scenario.scenes().stream().anyMatch(s -> "VARIANT".equals(s.sceneKind()));
            result.put("issued", issued);
            result.put("generatorPath", notes.stream().map(VariantDiagnosis::label).toList());
            if (scenario != null && scenario.generation() != null && scenario.generation().basePreservation() != null) {
                result.put("basePreservation", scenario.generation().basePreservation());
            }
            if (issued) {
                result.put("diagnosis", "VARIANT_ISSUED");
                result.put("source", "ISSUED_SCRIPT");
                return result;
            }
            result.put("source", "RECORDED_CALLS");
            if (scenario == null) {
                result.put("scenario", "NOT_ISSUED");
            }
            if ("NONE".equals(skillRef)) {
                result.put("diagnosis", "SKILL_REF_NONE");
                return result;
            }
            boolean offered = calls.stream().anyMatch(c -> ResultData.object(c.get("input")).containsKey("structureContext"));
            if (seed == null || !offered) {
                result.put("diagnosis", "GRAPH_CONTEXT_UNAVAILABLE");
                return result;
            }
            if (notes.contains(ScenarioScriptGenerator.Note.GRAPH_FALLBACK)) {
                result.put("diagnosis", "GRAPH_FALLBACK");
                result.put("source", "GENERATOR_NOTES");
                return result;
            }
            var firstBase = calls.stream()
                    .filter(c -> LunaPurpose.SCENARIO_GENERATION.name().equals(c.get("purpose")) && "OK".equals(c.get("outcome")))
                    .map(c -> ResultData.objects(ResultData.object(c.get("output")).get("scenes")))
                    .filter(scenes -> !scenes.isEmpty()).map(List::getFirst).findFirst();
            if (firstBase.isEmpty()) {
                result.put("diagnosis", "NO_GENERATION_OUTPUT");
                return result;
            }
            var base = firstBase.get();
            var preservation = ResultData.object(base.get("preservation"));
            List<String> mismatched = new ArrayList<>();
            if (preservation.isEmpty()) {
                mismatched.add("preservation");
            } else {
                check(mismatched, "skillRef", preservation.get("skillRef"), seed.seedId());
                check(mismatched, "invariantSentence", preservation.get("invariantSentence"), seed.invariantSentence());
                check(mismatched, "preservationRule", preservation.get("preservationRule"), seed.preservationRule());
            }
            if (!mismatched.isEmpty()) {
                result.put("diagnosis", "BASE_PRESERVATION_MISMATCH");
                result.put("fields", mismatched);
                return result;
            }
            Map<String, Object> outside = new LinkedHashMap<>();
            vocabulary(outside, "topic", base.get("topic"), VariantPlanner.TOPICS);
            vocabulary(outside, "place", base.get("place"), VariantPlanner.PLACES);
            vocabulary(outside, "preservation.participants", preservation.get("participants"), VariantPlanner.PARTICIPANTS);
            vocabulary(outside, "preservation.relationship", preservation.get("relationship"), KnowledgeSeed.RELATIONSHIPS);
            vocabulary(outside, "preservation.situation", preservation.get("situation"), KnowledgeSeed.SITUATIONS);
            if (!outside.isEmpty()) {
                result.put("diagnosis", "BASE_OUTSIDE_PLANNER_VOCABULARY");
                result.put("fields", outside);
                return result;
            }
            boolean planned = calls.stream().anyMatch(c -> LunaPurpose.SCENARIO_GENERATION.name().equals(c.get("purpose"))
                    && ResultData.object(c.get("input")).containsKey("variantPlan"));
            if (!planned) {
                // BASE 좌표가 어휘 안인데도 계획을 받은 생성 호출이 없다: 첫 BASE 검토 거절 · 서버 검사 실패로 계획 단계에 가지 못한 경우다
                result.put("diagnosis", "NO_VARIANT_PLAN_REQUEST");
                result.put("baseReviewReasons", rejectedReasons(calls, false));
                return result;
            }
            boolean generated = calls.stream().anyMatch(c -> LunaPurpose.SCENARIO_GENERATION.name().equals(c.get("purpose"))
                    && ResultData.object(c.get("input")).containsKey("variantPlan") && hasVariant(c.get("output")));
            if (!generated) {
                result.put("diagnosis", "VARIANT_NOT_GENERATED_BY_MODEL");
                return result;
            }
            // 여기부터는 생성된 VARIANT가 어떻게 됐는지다: 생성기가 낸 사유 코드로 정한다
            List<Object> reasons = rejectedReasons(calls, true);
            if (notes.contains(ScenarioScriptGenerator.Note.VARIANT_REJECTED)) {
                result.put("diagnosis", "VARIANT_REJECTED_BY_REVIEW");
                result.put("source", "GENERATOR_NOTES");
                result.put("reasons", reasons);
                return result;
            }
            if (notes.contains(ScenarioScriptGenerator.Note.VARIANT_INVALID)) {
                // 서버 결정 검사(계획 좌표 · 거리 · 변경 축 · 보존 값 · 금지 표현 · BASE와 같은 기회 문장)나 계획 불일치로 변형을 뺐다
                result.put("diagnosis", "VARIANT_DROPPED_BY_SERVER_CHECK");
                result.put("source", "GENERATOR_NOTES");
                return result;
            }
            if (notes.contains(ScenarioScriptGenerator.Note.BASE_INVALID) || notes.contains(ScenarioScriptGenerator.Note.REVIEW_NOT_APPROVED)) {
                result.put("diagnosis", "SCRIPT_REJECTED_WITH_VARIANT");
                result.put("source", "GENERATOR_NOTES");
                result.put("reasons", reasons);
                return result;
            }
            result.put("diagnosis", "UNEXPLAINED");
            result.put("source", "NONE");
            result.put("reasons", reasons);
            return result;
        }

        /** 거절 · 보류된 검토의 사유 코드. variantScripts면 VARIANT가 든 대본의 검토만, 아니면 BASE만 든 대본의 검토만 본다. */
        private static List<Object> rejectedReasons(List<Map<String, Object>> calls, boolean variantScripts) {
            List<Object> reasons = new ArrayList<>();
            for (var call : calls) {
                if (!LunaPurpose.SCENARIO_REVIEW.name().equals(call.get("purpose"))
                        || hasVariant(ResultData.object(call.get("input")).get("script")) != variantScripts) {
                    continue;
                }
                var output = ResultData.object(call.get("output"));
                if (output.get("verdict") != null && !"APPROVE".equals(output.get("verdict"))) {
                    reasons.add(output.get("verdict"));
                    if (output.get("reasons") instanceof List<?> list) {
                        list.stream().filter(reason -> !reasons.contains(reason)).forEach(reasons::add);
                    }
                }
            }
            return reasons;
        }

        private static boolean hasVariant(Object script) {
            return ResultData.objects(ResultData.object(script).get("scenes")).stream().anyMatch(s -> "VARIANT".equals(s.get("sceneKind")));
        }

        private static void check(List<String> mismatched, String field, Object actual, String expected) {
            if (!expected.equals(actual)) {
                mismatched.add(field);
            }
        }

        private static void vocabulary(Map<String, Object> outside, String field, Object value, Set<String> allowed) {
            if (!(value instanceof String text) || !allowed.contains(text)) {
                outside.put(field, value);
            }
        }
    }

    // ------------------------------------------------------------------ 운영 값 · 공용 도구

    /** application.yml의 운영 시한 · 재시도 · 목적 설정(바꾸지 않고 읽기만 한다). */
    record ProductionBudgets(Duration scenarioRequest, Duration historyTimeout, Duration textAiRequest, Duration resultRequest,
            Duration resultAttempt, Duration graphBudget, TextAiProperties textAi, LunaProperties.Purposes purposes, int maxResponseBytes) {

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
                    duration(binder, "mujoong.text-ai.result.request-timeout"),
                    duration(binder, "mujoong.timing.result.attempt-timeout"),
                    duration(binder, "mujoong.timing.knowledge.graph-budget"),
                    bound(binder, "mujoong.text-ai", Bindable.of(TextAiProperties.class)),
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

    /** SessionResultHandler.verifiedExperience와 같은 걸러내기: 아이 회상 출처는 인용이 전사와 글자 그대로 맞는 칸만 남긴다. */
    static Map<String, Object> verifiedExperience(Map<String, Object> output, Map<Long, String> quotes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("characters", ResultData.objects(output.get("characters")).stream()
                .filter(c -> !"CHILD_RECALL".equals(c.get("source")) || quotes.containsKey(ResultData.number(c, "utteranceSeq"))).toList());
        for (String key : List.of("situation", "partnerUtterance", "childResponse", "explanation")) {
            var field = ResultData.object(output.get(key));
            boolean valid = !field.isEmpty() && (!"CHILD_RECALL".equals(field.get("source"))
                    || (quotes.containsKey(ResultData.number(field, "utteranceSeq"))
                    && QuoteMatcher.allVerified(ResultData.text(field, "text"), List.of(quotes.get(ResultData.number(field, "utteranceSeq"))))));
            result.put(key, valid ? field : null);
        }
        return result;
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
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
