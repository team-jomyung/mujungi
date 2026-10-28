package kr.mujoong.core.luna;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import kr.mujoong.core.practicegoal.GoalCandidateValidator;
import kr.mujoong.core.practicegoal.RepeatedAiSpan;
import kr.mujoong.core.textai.TextAiProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 목표 후보 진단 측정(수동, lunaLiveTest 전용). 결과 작업과 같은 모양의 입력 파일(env {@code GOAL_PROBE_INPUT}: transcript · structured · endReason ·
 * scenes)을 현재 지시문(goal-candidates.v3)과 운영과 같은 전처리(불확실 행 · AI 되풀이 구간 제외)로 Luna에 보내, 모델이 낸 후보와 운영 서버 검사(GoalCandidateValidator)의 통과 여부 · 탈락 사유 코드를
 * 보고서(env {@code GOAL_PROBE_REPORT})에 쓴다. 입력 파일과 보고서는 저장소 밖에 둔다. 입력에 seedSummaries가 없으면 비워 보낸다.
 */
@Tag("luna-live")
class GoalCandidateProbeLiveTest {
    private static final JsonCodec JSON = JsonCodec.shared();

    @Test
    @SuppressWarnings("unchecked")
    void reportsWhatTheModelProposedAndWhichServerRuleKeptOrDroppedEachCandidate() throws Exception {
        String key = System.getenv(LunaTransport.API_KEY_ENV);
        String inputPath = System.getenv("GOAL_PROBE_INPUT");
        assumeTrue(key != null && inputPath != null, "SKIPPED: env OPENAI_API_KEY · GOAL_PROBE_INPUT 필요");
        Path report = Path.of(System.getenv().getOrDefault("GOAL_PROBE_REPORT", "build/goal-probe-report.json"));
        var clock = new SystemMujoongClock();
        var ordinary = new LunaProperties.PurposeOptions(4000, "medium");
        var properties = new LunaProperties(null, URI.create("https://api.openai.com/v1"), 1_000_000,
                new LunaProperties.Purposes(ordinary, ordinary, ordinary, ordinary,
                        // 운영 goal-candidates 값(low · 4000). env GOAL_PROBE_EFFORT · GOAL_PROBE_MAX_TOKENS로 바꿔 비교 측정한다
                        new LunaProperties.PurposeOptions(Integer.parseInt(System.getenv().getOrDefault("GOAL_PROBE_MAX_TOKENS", "4000")),
                                System.getenv().getOrDefault("GOAL_PROBE_EFFORT", "low")), ordinary));
        // 호출 한 번의 시한(기본 30s, 운영 mujoong.text-ai.result.request-timeout은 40s). env GOAL_PROBE_TIMEOUT_SECONDS로 바꾼다
        Duration callTimeout = Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("GOAL_PROBE_TIMEOUT_SECONDS", "30")));
        var timing = new TextAiProperties(callTimeout, new TextAiProperties.Retry(1, Duration.ofMillis(250)),
                new TextAiProperties.Result(callTimeout, 0, 0.2, "v1"));
        // 시도마다 결과 코드 · 토큰 수만 모은다(본문 없음)
        List<Map<String, Object>> attempts = new ArrayList<>();
        kr.mujoong.core.usage.AiUsageCollector usage = record -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("outcome", record.outcome());
            row.put("inputTokens", record.inputTokens());
            row.put("outputTokens", record.outputTokens());
            row.put("reasoningTokens", record.reasoningTokens());
            attempts.add(row);
            return true;
        };
        var client = new CoreLunaClient(properties, timing, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                new LunaTransport(properties.baseUrl(), LunaHosts.modeFor(), () -> System.getenv(LunaTransport.API_KEY_ENV),
                        properties.maxResponseBytes()),
                new kr.mujoong.core.support.TestEvents(clock).logger(), clock, JSON, CoreLunaClient.SYSTEM_SLEEPER, usage);

        Map<String, Object> input = new LinkedHashMap<>(JSON.read(Files.readString(Path.of(inputPath)), Map.class));
        input.putIfAbsent("seedSummaries", List.of());
        java.util.Set<Object> catalog = new java.util.HashSet<>();
        for (var seed : (List<Map<String, Object>>) input.get("seedSummaries")) catalog.add(seed.get("seedId"));
        List<Map<String, Object>> transcript = (List<Map<String, Object>>) input.get("transcript");
        transcript = RepeatedAiSpan.without(transcript.stream().filter(row -> !Boolean.TRUE.equals(row.get("uncertain"))).toList());
        input.put("transcript", transcript);
        Map<Long, String> quotes = new LinkedHashMap<>();
        for (var row : transcript) if ("CHILD".equals(row.get("speaker"))) quotes.put(((Number) row.get("seq")).longValue(), (String) row.get("text"));
        // env GOAL_PROBE_PROMPT가 있으면 그 파일의 지시문으로 비교 측정한다(저장소의 지시문은 바꾸지 않는다)
        String override = System.getenv("GOAL_PROBE_PROMPT");
        String prompt = override != null ? Files.readString(Path.of(override))
                : new ClassPathResource("prompts/goal/goal-candidates.v3.txt").getContentAsString(StandardCharsets.UTF_8);

        Files.createDirectories(report.toAbsolutePath().getParent());
        long started = System.nanoTime();
        LunaResult result;
        try {
            // 마감은 재시도 1회까지 들어가게 시한의 두 배로 둔다
            result = client.call(LunaRequest.of(LunaPurpose.GOAL_CANDIDATES, "goal-candidates.v3", prompt, input, Map.of(),
                    clock.now().plus(callTimeout.multipliedBy(2))));
        } catch (LunaException failed) {
            Files.writeString(report, JSON.write(Map.of("failure", failed.reason().name(), "elapsedMs", (System.nanoTime() - started) / 1_000_000,
                    "attempts", attempts)));
            System.out.println("goal-probe failure=" + failed.reason() + " report=" + report.toAbsolutePath());
            return;
        }
        Map<String, Object> output = JSON.read(result.outputJson(), Map.class);
        List<Map<String, Object>> proposed = (List<Map<String, Object>>) output.getOrDefault("candidates", List.of());
        // 운영과 같은 검사(GoalCandidateValidator)의 탈락 사유 코드를 그대로 쓴다. 2차 분류(OutputChecker)는 부르지 않는다
        Set<String> skills = new HashSet<>();
        for (Object id : catalog) skills.add(String.valueOf(id));
        var checked = new GoalCandidateValidator(new ForbiddenDictionary()).check(proposed, skills, quotes);
        List<Map<String, Object>> verdicts = new ArrayList<>();
        for (int i = 0; i < proposed.size(); i++) {
            int index = i;
            Object verdict = checked.indexes().contains(index) ? List.of("KEPT")
                    : checked.dropped().stream().filter(d -> d.index() == index).map(d -> d.reason() + "(" + d.field() + ")").toList();
            verdicts.add(Map.of("candidate", proposed.get(i), "serverValidation", verdict));
        }
        Files.writeString(report, JSON.write(Map.of("model", result.responseModel(), "latencyMs", result.latency().toMillis(),
                "attemptsUsed", result.attemptsUsed(), "attempts", attempts, "candidateCount", verdicts.size(), "candidates", verdicts)));
        System.out.println("goal-probe candidates=" + verdicts.size() + " report=" + report.toAbsolutePath());
    }
}
