package kr.mujoong.core.luna;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.testsupport.time.MutableClock;

/**
 * U9 core 시험 공용 준비물(시험 소스 전용). 공급자는 언제나 {@code LunaResponsesStub} 루프백 대역이고 키는 가짜 문자열이다(R-F1 · R-F3).
 * 시간 값은 시험 속성처럼 여기서만 정한다(운영 숫자 시간 값 아님).
 */
final class LunaTestFixtures {

    /** 가짜 키(실제 키 모양이 아님). */
    static final String FAKE_KEY = "test-key-not-real-u9";
    static final JsonCodec JSON = JsonCodec.shared();
    static final Path REPO_ROOT = Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();

    private LunaTestFixtures() {
    }

    static LunaProperties properties(URI baseUrl) {
        LunaProperties.PurposeOptions options = new LunaProperties.PurposeOptions(4000, "medium");
        return new LunaProperties("gpt-6-luna", baseUrl, 1_000_000,
                new LunaProperties.Purposes(options, options, options, options, options, options));
    }

    /** text-ai 시간 설정: 생성류 시도 2초, 결과류 시도 3초, 재시도 대기 20ms(시험 값). */
    static TextAiProperties timing() {
        return timing(Duration.ofSeconds(2));
    }

    static TextAiProperties timing(Duration requestTimeout) {
        return new TextAiProperties(requestTimeout, new TextAiProperties.Retry(1, Duration.ofMillis(20)),
                new TextAiProperties.Result(requestTimeout.plusSeconds(1), 0, 0.2, "v1"));
    }

    /** 시계를 앞당기는 대기 대역. */
    static final class ClockSleeper implements CoreLunaClient.Sleeper {

        final List<Duration> waits = new ArrayList<>();
        private final MutableClock clock;

        ClockSleeper(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(Duration duration) {
            waits.add(duration);
            clock.advance(duration);
        }
    }

    static LunaTransport transport(URI baseUrl, LunaHosts.Mode mode) {
        return new LunaTransport(baseUrl, mode, () -> FAKE_KEY, 1_000_000);
    }

    static CoreLunaClient client(URI baseUrl, MutableClock clock, TestEvents events, CoreLunaClient.Sleeper sleeper,
            TextAiProperties timing) {
        return new CoreLunaClient(properties(baseUrl), timing, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                transport(baseUrl, LunaHosts.Mode.TEST), events.logger(), clock, JSON, sleeper);
    }

    /** 계약 스키마 파일의 첫 정상 예시(JSON 텍스트). */
    @SuppressWarnings("unchecked")
    static String example(String schemaFile) {
        try {
            Map<String, Object> document = JSON.read(Files.readString(REPO_ROOT.resolve("contracts/schemas/" + schemaFile)),
                    Map.class);
            return JSON.write(((List<Object>) document.get("examples")).getFirst());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
