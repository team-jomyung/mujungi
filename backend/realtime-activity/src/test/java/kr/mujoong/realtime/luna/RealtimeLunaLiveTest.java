package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Luna 실호출 시험 — realtime (U9 FR12.1 · BR9.2 · NFR5.2 · 편차 U9-c). 사람이 env {@code OPENAI_API_KEY}를 넣고
 * {@code ./gradlew lunaLiveTest}로만 돈다. 키가 없으면 {@code SKIPPED_NO_KEY}. 키가 있으면 프로필 없이 STRICT 주소 규칙으로
 * {@code JUDGMENT_LIVE} 합성 판정 1건을 실제로 불러 요청용 스키마 사본 수락 · 스키마 검증 · 응답 모델 · 지연을 보고서에 남긴다(원문 없음).
 * 시도 시한은 운영 값 {@code mujoong.timing.judgment.live-budget}(2초)과 같다.
 */
@Tag("luna-live")
class RealtimeLunaLiveTest {

    private static Path report() {
        String configured = System.getProperty("mujoong.luna.live.report");
        return Path.of(configured != null ? configured : "build/luna-live/report.txt");
    }

    private static void write(String line) throws IOException {
        Path report = report();
        Files.createDirectories(report.getParent());
        Files.writeString(report, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @Test
    void theJudgmentPurposeAnswersARealSyntheticCall() throws IOException, InterruptedException {
        String key = System.getenv(LunaTransport.API_KEY_ENV);
        if (key == null || key.isBlank()) {
            write("app=realtime outcome=SKIPPED_NO_KEY evidence=NONE");
        }
        assumeTrue(key != null && !key.isBlank(), "SKIPPED_NO_KEY: env OPENAI_API_KEY 없음(실호출 안 함)");

        MujoongClock clock = new SystemMujoongClock();
        JsonCodec json = JsonCodec.shared();
        LunaProperties properties = new LunaProperties(System.getenv("OPENAI_JUDGE_MODEL"), URI.create("https://api.openai.com/v1"),
                1_000_000, new LunaProperties.Purposes(new LunaProperties.PurposeOptions(4000, "medium")), true);
        RealtimeLunaClient client = new RealtimeLunaClient(properties, Duration.ofSeconds(2), LunaSchemas.loadDefault(json),
                new LunaInputSanitizer(json), new LunaTransport(properties.baseUrl(), LunaHosts.modeFor(),
                        () -> System.getenv(LunaTransport.API_KEY_ENV), properties.maxResponseBytes()),
                TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory()), clock, json);
        LunaRequest request = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "u9-live.v1",
                "합성 시험 자료다. 아이 발화가 판정 기준에 얼마나 맞는지 options 중 1 · 2순위와 확률을 고른다.",
                RealtimeLunaTestFixtures.judgmentInput("그 블록 줄래? 탑 쌓고 싶어"), Map.of(), clock.now().plusSeconds(30));

        String outcome;
        try {
            LunaResult result = client.call(request).toCompletableFuture().get();
            outcome = "outcome=OK model=" + result.responseModel() + " latencyMs=" + result.latency().toMillis();
        } catch (ExecutionException failure) {
            outcome = "outcome=" + (failure.getCause() instanceof LunaException luna ? luna.reason().name() : "UNKNOWN");
        }
        write("app=realtime purpose=JUDGMENT_LIVE " + outcome + " evidence=REAL_API_SYNTHETIC");

        assertThat(outcome).as("실호출 결과(보고서 " + report() + ")").startsWith("outcome=OK");
    }
}
