package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.luna.LunaDecisionsStub;
import kr.mujoong.testsupport.luna.LunaDecisionsStub.Reply;
import kr.mujoong.testsupport.luna.LunaResponsesStub.RecordedRequest;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판정 호출 경로 예열 — 세션 첫 판정이 처음 도는 코드 · 새 연결 비용으로 live-budget(4초)을 넘어 보류되던 문제(2026-10-27 실키 세션). 예열은 키 없는
 * GET 한 번만 보내고(판정 · 과금 · 데이터 없음), 실패 · 무응답에도 시한 안에 정상 완료하며, 주소 규칙을 어기면 보내지 않는다. 공급자는
 * {@link LunaDecisionsStub} 루프백 대역이다. 증거 종류: 테스트 대역(R-M11).
 */
@Tag("luna")
class LunaWarmUpTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private LunaDecisionsStub stub;
    private MutableClock clock;
    private LogCapture capture;
    private RealtimeLunaClient client;

    @BeforeEach
    void setUp() {
        stub = LunaDecisionsStub.start();
        clock = new MutableClock();
        capture = LogCapture.detached();
        client = RealtimeLunaTestFixtures.client(stub.baseUrl(), Duration.ofSeconds(2),
                TestEventLoggers.strict(clock, capture, RealtimeMetrics.inMemory()), clock);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void warmUpSendsOneKeylessRequestToTheModelsPathAndNoJudgment() throws Exception {
        client.warmUp().toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(stub.requests()).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/v1/" + LunaTransport.WARM_UP_PATH);
            assertThat(request.headers()).as("키를 보내지 않는다").doesNotContainKey("authorization");
            assertThat(request.body()).as("데이터를 보내지 않는다").isEmpty();
        });
        assertThat(capture.events(EventCode.LUNA_CALL)).as("판정 시도가 아니다").isEmpty();
    }

    @Test
    void aJudgmentAfterWarmUpStillGoesToTheDecisionsEndpointWithTheKey() throws Exception {
        stub.respond(Reply.choice("gpt-6-luna", "judgment", "FULL", java.util.Map.of("FULL", 0.9, "PARTIAL", 0.1)));
        client.warmUp().toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        LunaRequest request = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "prompt-service-v3", "아이의 가장 마지막 말 하나만 판정한다.",
                RealtimeLunaTestFixtures.judgmentInput("그거 줘."), java.util.Map.of(), clock.now().plusSeconds(10));
        LunaDecisionQuestion question = new LunaDecisionQuestion("judgment",
                List.of(new LunaDecisionQuestion.Choice("FULL", null), new LunaDecisionQuestion.Choice("PARTIAL", null)));
        client.decide(request, question).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        List<RecordedRequest> requests = stub.requests();
        assertThat(requests).extracting(RecordedRequest::path).containsExactly("/v1/" + LunaTransport.WARM_UP_PATH, LunaDecisionsStub.PATH);
        assertThat(requests.get(1).headers()).containsKey("authorization");
        assertThat(capture.events(EventCode.LUNA_CALL)).hasSize(1);
    }

    @Test
    void warmUpCompletesNormallyWhenTheProviderIsUnreachable() {
        URI closed = stub.baseUrl();
        stub.close();
        LunaTransport transport = RealtimeLunaTestFixtures.transport(closed, LunaHosts.Mode.TEST);

        assertThatCode(() -> transport.warmUp(Duration.ofSeconds(2)).get(WAIT.toSeconds(), TimeUnit.SECONDS))
                .doesNotThrowAnyException();
    }

    @Test
    void warmUpGivesUpWithinItsLimitWhenTheProviderNeverAnswers() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            LunaTransport transport = RealtimeLunaTestFixtures.transport(
                    URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/v1"), LunaHosts.Mode.TEST);
            long started = System.nanoTime();

            assertThatCode(() -> transport.warmUp(Duration.ofMillis(300)).get(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .doesNotThrowAnyException();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void warmUpSendsNothingWhenTheAddressBreaksTheRule() throws Exception {
        LunaTransport strict = RealtimeLunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.STRICT);

        strict.warmUp(Duration.ofSeconds(2)).get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(stub.requestCount()).as("루프백은 STRICT 규칙 밖이다").isZero();
    }

    @Test
    void warmUpWithoutATimeLimitSendsNothing() throws Exception {
        LunaTransport transport = RealtimeLunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST);

        transport.warmUp(Duration.ZERO).get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(stub.requestCount()).isZero();
    }
}
