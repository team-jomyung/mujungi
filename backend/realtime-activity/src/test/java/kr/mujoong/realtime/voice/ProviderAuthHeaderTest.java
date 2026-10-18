package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 키가 비면 연결 요청에 {@code Authorization} 헤더를 싣지 않는다(U11 BR12.2). 키 값은 실행 때 만든 가짜 문자열이고 가짜 공급자만 부른다
 * (R-F1 · R-F3).
 */
@Tag("judgment-turn")
class ProviderAuthHeaderTest {

    private static final JsonCodec JSON = JsonCodec.shared();

    private List<Boolean> headersFor(String key) throws Exception {
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            ProviderClient client = new ProviderClient(server.uri(), key, JSON, Duration.ofSeconds(2));
            ProviderPort.ProviderSession session = client.open(event -> { }, Duration.ofSeconds(2));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (server.bearerHeaders().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            List<Boolean> seen = server.bearerHeaders();
            session.close(new ProviderRequestFactory().sessionClose());
            client.destroy();
            return seen;
        }
    }

    @Test
    void aKeyIsSentOnlyAsABearerHeader() throws Exception {
        assertThat(headersFor(TestSecrets.base64Url(24))).containsExactly(true);
    }

    @Test
    void aBlankKeyOpensWithoutTheHeader() throws Exception {
        assertThat(headersFor(" ")).as("공백 키 = 헤더 없음").containsExactly(false);
        assertThat(headersFor(null)).as("키 없음 = 헤더 없음").containsExactly(false);
    }
}
