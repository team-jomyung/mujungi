package kr.mujoong.realtime.luna;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.observability.EventLogger;

/**
 * U9 realtime 시험 공용 준비물(시험 소스 전용). 공급자는 언제나 {@code LunaResponsesStub} 루프백 대역이고 키는 가짜 문자열이다(R-F1 · R-F3).
 * 판정 요청 입력은 합성 값이다(실제 아동 데이터 아님).
 */
final class RealtimeLunaTestFixtures {

    static final String FAKE_KEY = "test-key-not-real-u9-rt";
    static final JsonCodec JSON = JsonCodec.shared();
    static final Path REPO_ROOT = Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();

    private RealtimeLunaTestFixtures() {
    }

    static LunaProperties properties(URI baseUrl) {
        return new LunaProperties("gpt-6-luna", baseUrl, 1_000_000,
                new LunaProperties.Purposes(new LunaProperties.PurposeOptions(4000, "medium")));
    }

    static LunaTransport transport(URI baseUrl, LunaHosts.Mode mode) {
        return new LunaTransport(baseUrl, mode, () -> FAKE_KEY, 1_000_000);
    }

    static RealtimeLunaClient client(URI baseUrl, Duration liveBudget, EventLogger events,
            kr.mujoong.contract.time.MujoongClock clock) {
        return new RealtimeLunaClient(properties(baseUrl), liveBudget, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                transport(baseUrl, LunaHosts.Mode.TEST), events, clock, JSON);
    }

    /** ai-judgment-request 모양의 합성 입력. */
    static Map<String, Object> judgmentInput(String childUtterance) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("judgmentPoint", "J2");
        input.put("optionSetVersion", "judgment-v1");
        input.put("options", List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE"));
        input.put("childUtterance", childUtterance);
        input.put("recentTurns", List.of(Map.of("speaker", "PARTNER", "text", "이것 봐, 높지?")));
        input.put("criteria", Map.of("coreFunction", "필요한 것과 이유를 함께 말해 부탁한다",
                "judgmentCriteria", "무엇이 필요한지와 왜 필요한지가 드러나면 충분히 전달"));
        return input;
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

    static String contractFile(String name) {
        try {
            Path file = REPO_ROOT.resolve("contracts/schemas/" + name);
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
