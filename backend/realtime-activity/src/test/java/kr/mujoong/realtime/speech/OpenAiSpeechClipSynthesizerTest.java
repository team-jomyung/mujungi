package kr.mujoong.realtime.speech;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 3회차 V1: OpenAI Audio API 대본 줄 합성기(contracts/ai-io.md 10.16). 공급자는 루프백 가짜 서버, 키는 가짜 문자열, 대본 줄은 합성 문장이다(R-F1 ·
 * R-F3). 요청 모양({@code POST /v1/audio/speech} · Bearer · model · voice · input · {@code response_format: pcm} · 말투별 instructions), 상태 코드별
 * 사유 코드, 보내기 전 거절(주소 · 키), 본문 상한 · 시한을 본다. 이 파일의 성공은 테스트 대역 증거이고 실제 합성 · 음질의 증거가 아니다(R-M11).
 */
@Tag("practice-flow")
class OpenAiSpeechClipSynthesizerTest {

    private static final String FAKE_KEY = "fake-openai-key-not-real-v1";
    private static final String NARRATOR_STYLE = "다정하고 차분한 어른 목소리";
    private static final String PEER_STYLE = "또래 친구처럼 밝게";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String LINE = "자, 여기 초록색!";
    private static final String ERROR_MARKER = "ERR-BODY-MARKER-v1-should-never-surface";

    private final MutableClock clock = new MutableClock();
    private final ManualTimerDriver timers = new ManualTimerDriver(clock);
    private final AtomicReference<String> key = new AtomicReference<>(FAKE_KEY);
    private FakeAudioApi api;
    private LogCapture logs;

    @BeforeEach
    void setUp() {
        api = FakeAudioApi.start();
        logs = LogCapture.attach();
    }

    @AfterEach
    void tearDown() {
        logs.close();
        api.close();
    }

    private OpenAiSpeechClipSynthesizer synthesizer(URI baseUrl, LunaHosts.Mode mode, int maxBytes) {
        return new OpenAiSpeechClipSynthesizer(baseUrl, mode, key::get, "gpt-4o-mini-tts", "nova", NARRATOR_STYLE, PEER_STYLE,
                TIMEOUT, maxBytes, timers, JsonCodec.shared());
    }

    private OpenAiSpeechClipSynthesizer synthesizer() {
        return synthesizer(api.baseUrl(), LunaHosts.Mode.TEST, 960_000);
    }

    @Test
    void aLineIsPostedAsPcmWithTheVoiceStyleAndTheBodyComesBackUnchanged() throws Exception {
        byte[] pcm = new byte[9_600];
        pcm[1] = 7;
        api.respond(200, pcm);
        try (OpenAiSpeechClipSynthesizer synth = synthesizer()) {
            assertThat(synth.endpoint()).isEqualTo(URI.create(api.baseUrl() + "/audio/speech"));

            assertThat(get(synth.synthesize(SpeechClipVoice.PEER, LINE))).isEqualTo(pcm);
            get(synth.synthesize(SpeechClipVoice.NARRATOR, LINE));
        }

        FakeAudioApi.Request peer = api.requests.getFirst();
        assertThat(peer.method()).isEqualTo("POST");
        assertThat(peer.path()).isEqualTo("/v1/audio/speech");
        assertThat(peer.authorization()).isEqualTo("Bearer " + FAKE_KEY);
        assertThat(peer.contentType()).startsWith("application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JsonCodec.shared().read(peer.body(), Map.class);
        assertThat(body).containsEntry("model", "gpt-4o-mini-tts").containsEntry("voice", "nova").containsEntry("input", LINE)
                .containsEntry("response_format", "pcm").containsEntry("instructions", PEER_STYLE);
        @SuppressWarnings("unchecked")
        Map<String, Object> narrator = JsonCodec.shared().read(api.requests.get(1).body(), Map.class);
        assertThat(narrator).as("무중의 줄은 무중 말투").containsEntry("instructions", NARRATOR_STYLE);
        assertThat(String.join("\n", logs.lines())).as("키 · 원문을 남기지 않는다").doesNotContain(FAKE_KEY, LINE);
    }

    @Test
    void anEmptyStyleIsNotSent() {
        OpenAiSpeechClipSynthesizer synth = new OpenAiSpeechClipSynthesizer(api.baseUrl(), LunaHosts.Mode.TEST, key::get,
                "gpt-4o-mini-tts", "nova", " ", null, TIMEOUT, 960_000, timers, JsonCodec.shared());

        assertThat(synth.body(SpeechClipVoice.NARRATOR, LINE)).doesNotContainKey("instructions");
        assertThat(synth.body(SpeechClipVoice.PEER, LINE)).doesNotContainKey("instructions");
        assertThat(synth.toString()).doesNotContain(LINE, FAKE_KEY);
        synth.close();
    }

    @Test
    void eachErrorStatusBecomesItsReasonCodeWithoutTheResponseBody() throws Exception {
        try (OpenAiSpeechClipSynthesizer synth = synthesizer()) {
            for (Map.Entry<Integer, SpeechClipFailure> status : Map.of(401, SpeechClipFailure.AUTH_FAILED, 403,
                    SpeechClipFailure.AUTH_FAILED, 429, SpeechClipFailure.RATE_LIMITED, 400, SpeechClipFailure.REQUEST_REJECTED, 500,
                    SpeechClipFailure.PROVIDER_ERROR, 503, SpeechClipFailure.PROVIDER_ERROR).entrySet()) {
                api.respond(status.getKey(), ERROR_MARKER.getBytes(StandardCharsets.UTF_8));
                Throwable failure = failureOf(synth.synthesize(SpeechClipVoice.PEER, LINE));
                assertThat(SpeechClipException.failureOf(failure)).as("상태 %d", status.getKey()).isEqualTo(status.getValue());
                assertThat(String.valueOf(failure.getMessage())).doesNotContain(ERROR_MARKER);
            }
        }
        assertThat(String.join("\n", logs.lines())).doesNotContain(ERROR_MARKER);
    }

    @Test
    void nothingIsSentWithoutAKeyOrToAnAddressOutsideTheProfileRule() throws Exception {
        key.set(" ");
        try (OpenAiSpeechClipSynthesizer synth = synthesizer()) {
            assertThat(SpeechClipException.failureOf(failureOf(synth.synthesize(SpeechClipVoice.PEER, LINE))))
                    .isEqualTo(SpeechClipFailure.NO_KEY);
        }
        key.set(FAKE_KEY);
        try (OpenAiSpeechClipSynthesizer strict = synthesizer(api.baseUrl(), LunaHosts.Mode.STRICT, 960_000)) {
            assertThat(SpeechClipException.failureOf(failureOf(strict.synthesize(SpeechClipVoice.PEER, LINE))))
                    .as("STRICT는 https + api.openai.com만").isEqualTo(SpeechClipFailure.REJECTED_URL);
            assertThat(SpeechClipException.failureOf(failureOf(strict.synthesize(SpeechClipVoice.PEER, " "))))
                    .isEqualTo(SpeechClipFailure.INVALID_INPUT);
        }
        assertThat(api.requests).as("어느 것도 보내지 않았다").isEmpty();
        assertThat(LunaHosts.check(OpenAiSpeechClipSynthesizer.endpoint(URI.create("https://api.openai.com/v1")),
                LunaHosts.Mode.STRICT)).as("운영 기본 주소는 STRICT를 지난다").isEmpty();
    }

    @Test
    void aBodyOverTheLimitIsAudioTooLong() throws Exception {
        api.respond(200, new byte[4_802]);
        try (OpenAiSpeechClipSynthesizer synth = synthesizer(api.baseUrl(), LunaHosts.Mode.TEST, 4_800)) {
            assertThat(SpeechClipException.failureOf(failureOf(synth.synthesize(SpeechClipVoice.PEER, LINE))))
                    .isEqualTo(SpeechClipFailure.AUDIO_TOO_LONG);
        }
    }

    @Test
    void theSynthesisDeadlineCoversTheWholeBodyAndCancelsTheRequestAsATimeout() throws Exception {
        CountDownLatch release = api.hold();
        try (OpenAiSpeechClipSynthesizer synth = synthesizer()) {
            CompletableFuture<byte[]> pending = synth.synthesize(SpeechClipVoice.PEER, LINE);
            api.awaitRequests(1);
            assertThat(pending).isNotDone();

            clock.advance(TIMEOUT);
            timers.runDue();

            assertThat(SpeechClipException.failureOf(failureOf(pending))).isEqualTo(SpeechClipFailure.TIMEOUT);
        } finally {
            release.countDown();
        }
    }

    @Test
    void aClosedServerIsATransportError() throws Exception {
        URI closed = api.baseUrl();
        api.close();
        try (OpenAiSpeechClipSynthesizer synth = synthesizer(closed, LunaHosts.Mode.TEST, 960_000)) {
            assertThat(SpeechClipException.failureOf(failureOf(synth.synthesize(SpeechClipVoice.PEER, LINE))))
                    .isEqualTo(SpeechClipFailure.TRANSPORT_ERROR);
        }
        assertThat(OpenAiSpeechClipSynthesizer.translate(new java.net.http.HttpTimeoutException("시험")))
                .isEqualTo(SpeechClipFailure.TIMEOUT);
    }

    private static byte[] get(CompletableFuture<byte[]> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<byte[]> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            throw new AssertionError("실패해야 한다");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (java.util.concurrent.CancellationException e) {
            return e;
        }
    }

    /** 루프백 가짜 Audio API(요청을 모으고 정해 준 상태 · 본문으로 답한다). */
    static final class FakeAudioApi implements AutoCloseable {

        record Request(String method, String path, String authorization, String contentType, String body) {
        }

        final List<Request> requests = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final AtomicInteger status = new AtomicInteger(200);
        private final AtomicReference<byte[]> body = new AtomicReference<>(new byte[2]);
        private final AtomicReference<CountDownLatch> hold = new AtomicReference<>();

        private FakeAudioApi(HttpServer server) {
            this.server = server;
        }

        static FakeAudioApi start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                FakeAudioApi api = new FakeAudioApi(server);
                server.createContext("/", api::handle);
                server.setExecutor(api.executor);
                server.start();
                return api;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        }

        void respond(int code, byte[] payload) {
            status.set(code);
            body.set(payload);
        }

        CountDownLatch hold() {
            CountDownLatch latch = new CountDownLatch(1);
            hold.set(latch);
            return latch;
        }

        void awaitRequests(int count) throws InterruptedException {
            for (int i = 0; i < 200 && requests.size() < count; i++) {
                Thread.sleep(10);
            }
            assertThat(requests).hasSizeGreaterThanOrEqualTo(count);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Content-Type"), text));
            CountDownLatch latch = hold.get();
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = body.get();
            try {
                exchange.sendResponseHeaders(status.get(), payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            } catch (IOException closedByClient) {
                // 시한으로 끊긴 요청이다(시험이 기대한 일)
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
