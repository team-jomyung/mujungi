package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.judgment.JudgeOutcome;
import kr.mujoong.realtime.judgment.JudgeOutcomeValidator;
import kr.mujoong.realtime.judgment.JudgeRequest;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentPrompt;
import kr.mujoong.realtime.judgment.LunaJudgeClient;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 참여 확인 J1 합성 판정 탐침 (참여 관문 안정화 (5), {@code [실호출-합성]}). 운영 지시문 {@link JudgmentPrompt#load}(prompt-v2)와 운영 판정기
 * {@link LunaJudgeClient} · {@link RealtimeLunaClient}로 합성 참여 대답 7개를 J1으로 판정하고 결과 코드 · 1 · 2순위 코드 · 지연만 보고서에 남긴다.
 * 직전 대화에는 승인 시작 안내 v2 글(시드)을 COACH 줄로 넣는다.
 *
 * <p>사람이 R-F15 확인 기록을 남긴 뒤 그 PC의 셸 env {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew :realtime-activity:lunaReachTest}로만 돈다.
 * 기본 test · check · CI · verify.sh에서는 빠진다(태그 {@code luna-reach} 고정 제외 + 루트 {@code verifyLunaReachIsolation}). 키가 없으면 부르지
 * 않고 건너뜀으로 끝난다(통과 아님). 시도 시한은 {@link LunaReachProbe#TIMEOUT_ENV}(기본 60초)이고 운영 시한(4초) 안 건수를 함께 적는다.
 * 합성 문장 · 요청 · 응답 원문 · 키 · 주소 · 확률은 보고서에 쓰지 않는다(R-F3 · R-F14).
 */
@Tag("luna-reach")
class JoinJudgmentReachTest {

    /** 합성 참여 대답과 기대 1순위(지시서 (5)의 시험 문장, 아이 음성 · 실제 아동 데이터 아님). */
    record Case(String utterance, String expected) {
        @Override
        public String toString() {
            return "Case[expected=" + expected + "]";
        }
    }

    static final List<Case> CASES = List.of(
            new Case("응", "PARTICIPATE"),
            new Case("응 들려", "PARTICIPATE"),
            new Case("음 그래 볼까", "PARTICIPATE"),
            new Case("어 할래", "PARTICIPATE"),
            new Case("안녕", "UNCLEAR"),
            new Case("내 말이 들리니?", "UNCLEAR"),
            new Case("싫어", "DECLINE"));
    private static final Duration LIVE_BUDGET = Duration.ofSeconds(4);
    private static final Pattern INTRO = Pattern.compile("'INTRO_FREE_TALK',\\s*2,\\s*'([^']+)'", Pattern.DOTALL);

    @Test
    void theOperationalJ1PromptAndClientClassifyTheSyntheticJoinReplies() throws Exception {
        String key = System.getenv(LunaTransport.API_KEY_ENV);
        assumeTrue(key != null && !key.isBlank(), "SKIPPED_NO_KEY: env OPENAI_API_KEY 없음(실호출 안 함, 통과 아님)");
        LunaReachProbe probe = LunaReachProbe.live();
        int timeout = LunaReachProbe.timeoutSeconds(System.getenv(LunaReachProbe.TIMEOUT_ENV));
        LunaProperties properties = probe.properties();
        assertThat(LunaHosts.check(properties.baseUrl(), LunaHosts.modeFor())).as("주소 규칙(STRICT)").isEmpty();

        JsonCodec json = JsonCodec.shared();
        MujoongClock clock = new SystemMujoongClock();
        LunaTransport transport = new LunaTransport(properties.baseUrl(), LunaHosts.modeFor(),
                () -> System.getenv(LunaTransport.API_KEY_ENV), properties.maxResponseBytes());
        RealtimeLunaClient luna = new RealtimeLunaClient(properties, Duration.ofSeconds(timeout), LunaSchemas.loadDefault(json),
                new LunaInputSanitizer(json), transport, TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory()),
                clock, json);
        LiveContentResources.JudgmentOptionSet set = LiveContentResources.loadDefault().judgment();
        JudgmentPrompt prompt = JudgmentPrompt.load(set);
        LunaJudgeClient judge = new LunaJudgeClient(luna::call, prompt,
                new JudgeOutcomeValidator(threshold("mujoong.judgment.min-confidence"), threshold("mujoong.judgment.min-margin"), json),
                clock);
        List<String> options = set.points().get(LiveContentResources.JudgmentPoint.J1).optionCodes();
        String intro = introText();

        List<String> rows = new ArrayList<>();
        int confirmed = 0;
        int matched = 0;
        int withinBudget = 0;
        for (int i = 0; i < CASES.size(); i++) {
            Case item = CASES.get(i);
            JudgeRequest request = new JudgeRequest(JudgmentPoint.J1, set.version(), options, item.utterance(),
                    List.of(new JudgeRequest.RecentTurn(JudgeRequest.Speaker.COACH, intro)), null, Map.of(), false,
                    clock.now().plusSeconds(timeout));
            JudgeOutcome outcome = judge.judge(request, UUID.randomUUID()).toCompletableFuture()
                    .get(timeout + LunaReachProbe.OUTER_MARGIN.toSeconds(), TimeUnit.SECONDS);
            boolean ok = outcome.kind() == JudgmentOutcomeKind.CONFIRMED;
            confirmed += ok ? 1 : 0;
            matched += ok && item.expected().equals(outcome.firstOption()) ? 1 : 0;
            withinBudget += outcome.latency().compareTo(LIVE_BUDGET) < 0 ? 1 : 0;
            rows.add("| " + (i + 1) + " | " + item.expected() + " | " + outcome.kind() + " | "
                    + (outcome.abstainReason() == null ? "-" : outcome.abstainReason()) + " | "
                    + (ok ? LunaReachProbe.safe(outcome.firstOption()) : "-") + " | "
                    + (ok ? LunaReachProbe.safe(outcome.secondOption()) : "-") + " | " + outcome.latency().toMillis() + " |");
        }
        Path report = writeReport(prompt.version(), timeout, rows, confirmed, matched, withinBudget);
        System.out.println("J1 합성 판정: 확정 " + confirmed + "/" + CASES.size() + " · 기대 일치 " + matched + " · 4초 안 " + withinBudget
                + " (보고서 " + report + ")");

        assertThat(confirmed).as("Luna가 J1에 결과를 냈다(품질은 보고서의 관측값이다, 보고서 " + report + ")").isPositive();
    }

    /** 시드의 승인 시작 안내 v2 글(COACH 줄). 승인 문구를 코드에 다시 적지 않으려고 시드 파일에서 읽는다. */
    private static String introText() throws IOException {
        Path root = Path.of(System.getProperty("mujoong.repo.root", "../.."));
        String seed = Files.readString(root.resolve("infra/seed/003-u4a-reference.sql"), StandardCharsets.UTF_8);
        Matcher matcher = INTRO.matcher(seed);
        if (!matcher.find()) {
            throw new IOException("시드에 INTRO_FREE_TALK v2 글이 없다");
        }
        return matcher.group(1);
    }

    private static double threshold(String key) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return Double.parseDouble(value.toString());
            }
        }
        throw new IOException("application.yml에 " + key + "가 없다");
    }

    private static Path writeReport(String promptVersion, int timeout, List<String> rows, int confirmed, int matched, int withinBudget)
            throws IOException {
        String configured = System.getProperty(LunaReachProbe.REPORT_PROPERTY);
        Path base = Path.of(configured != null ? configured : "build/luna-reach/report.md").toAbsolutePath().getParent();
        Path report = base.resolve("j1-report.md");
        StringBuilder text = new StringBuilder();
        text.append("# J1 합성 판정 탐침 (`[실호출-합성]`)\n\n");
        text.append("- 지시문 판: `").append(LunaReachProbe.safe(promptVersion)).append("`, 시도 시한 ").append(timeout).append("초\n");
        text.append("- 확정 ").append(confirmed).append('/').append(rows.size()).append(" · 기대 1순위 일치 ").append(matched)
                .append(" · 운영 시한(4초) 안 ").append(withinBudget).append("\n");
        text.append("- 합성 문장 · 요청 · 응답 원문 · 확률 · 키 · 주소는 적지 않는다(R-F3 · R-F14). 번호는 `JoinJudgmentReachTest.CASES` 순서다.\n\n");
        text.append("| 번호 | 기대 | 결과 | 보류 사유 | 1순위 | 2순위 | 지연(ms) |\n|---|---|---|---|---|---|---|\n");
        rows.forEach(row -> text.append(row).append('\n'));
        Files.createDirectories(report.getParent());
        Files.writeString(report, text.toString(), StandardCharsets.UTF_8);
        return report;
    }
}
