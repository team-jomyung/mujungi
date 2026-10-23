package kr.mujoong.realtime.speech;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.TimerDriver;
import kr.mujoong.realtime.luna.LunaHosts;

/**
 * OpenAI Audio API로 대본 줄 음성을 만든다(3회차 V1, contracts/ai-io.md 10.16). {@code POST {baseUrl}/audio/speech}에 {@code model · voice · input ·
 * response_format: "pcm" · instructions(말투)}를 보내고 24kHz s16 모노 PCM 본문을 받는다 — GPT-Live 출력과 같은 형식이라 바꾸지 않고 기기 0x02 프레임으로
 * 나눈다. Luna 호출부({@code luna.LunaTransport})와 같은 규칙을 따른다.
 *
 * <ul>
 *   <li>앱마다 하나인 {@link HttpClient}: 리다이렉트를 따르지 않고 프록시를 쓰지 않는다.
 *   <li>보내기 직전 대상 주소를 기동 때와 같은 {@link LunaHosts#check}로 다시 확인한다(demo · prod는 https + {@code api.openai.com}만, test는 루프백만).
 *   <li>{@code Authorization} 머리는 env 키({@value #API_KEY_ENV}, 새 비밀 없음)로 그 자리에서 붙이고 키를 필드 · 로그에 두지 않는다.
 *   <li>응답 본문은 바이트를 세며 상한(출력 구간 20초 분량)까지만 읽는다.
 *   <li>시한({@code timing.speech-clip.synthesis-timeout})은 본문 끝까지 덮는다: 안전 시계로 걸어 두었다가 넘으면 진행 중인 요청을 취소한다.
 * </ul>
 */
public final class OpenAiSpeechClipSynthesizer implements SpeechClipSynthesizer {

    /** 공급자 키 env 이름(Luna · GPT-Live와 같다, contracts/ai-io.md 10.7). */
    public static final String API_KEY_ENV = "OPENAI_API_KEY";
    private static final byte[] NO_BODY = new byte[0];

    private final HttpClient client;
    private final URI endpoint;
    private final LunaHosts.Mode mode;
    private final Supplier<String> apiKey;
    private final String model;
    private final String voice;
    private final String narratorStyle;
    private final String peerStyle;
    private final Duration timeout;
    private final int maxBytes;
    private final TimerDriver timers;
    private final JsonCodec json;

    /**
     * @param baseUrl Audio API 기준 주소({@code {baseUrl}/audio/speech}로 보낸다)
     * @param mode 주소 규칙(기동 때와 같은 모드)
     * @param apiKey 보낼 때마다 env에서 읽는 키 공급자(값을 보관하지 않음)
     * @param timeout 요청 하나의 상한(보내기 → 본문 끝)
     * @param maxBytes 응답 본문 바이트 상한
     * @param timers 시한 시계(운영은 안전 시계, 작업은 요청 취소 하나라 가볍다)
     */
    public OpenAiSpeechClipSynthesizer(URI baseUrl, LunaHosts.Mode mode, Supplier<String> apiKey, String model, String voice,
            String narratorStyle, String peerStyle, Duration timeout, int maxBytes, TimerDriver timers, JsonCodec json) {
        this.endpoint = endpoint(baseUrl);
        this.mode = Objects.requireNonNull(mode, "mode");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = Objects.requireNonNull(model, "model");
        this.voice = Objects.requireNonNull(voice, "voice");
        this.narratorStyle = narratorStyle == null ? "" : narratorStyle;
        this.peerStyle = peerStyle == null ? "" : peerStyle;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || maxBytes <= 0) {
            throw new IllegalArgumentException("시한과 본문 상한은 양수여야 한다");
        }
        this.maxBytes = maxBytes;
        this.timers = Objects.requireNonNull(timers, "timers");
        this.json = Objects.requireNonNull(json, "json");
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(timeout)
                .build();
    }

    /** 기준 주소의 합성 끝점({@code {baseUrl}/audio/speech}). */
    public static URI endpoint(URI baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/audio/speech");
    }

    /** 보내는 주소. */
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(SpeechClipVoice which, String text) {
        Objects.requireNonNull(which, "which");
        if (text == null || text.isBlank()) {
            return CompletableFuture.failedFuture(new SpeechClipException(SpeechClipFailure.INVALID_INPUT));
        }
        if (LunaHosts.check(endpoint, mode).isPresent()) {
            return CompletableFuture.failedFuture(new SpeechClipException(SpeechClipFailure.REJECTED_URL));
        }
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            return CompletableFuture.failedFuture(new SpeechClipException(SpeechClipFailure.NO_KEY));
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(json.write(body(which, text)), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<byte[]>> http = client.sendAsync(request,
                info -> info.statusCode() == 200 ? new BoundedBodySubscriber(maxBytes) : HttpResponse.BodySubscribers.replacing(NO_BODY));
        // 시한은 본문 끝까지 덮는다(HttpRequest.timeout은 응답 머리까지만이다). 넘으면 진행 중인 요청을 끊는다
        TimerDriver.TimerHandle deadline = timers.schedule(timeout, () -> http.cancel(true));
        return http.handle((response, failure) -> {
            deadline.cancel();
            if (failure != null) {
                throw new SpeechClipException(translate(failure));
            }
            int status = response.statusCode();
            if (status != 200) {
                throw new SpeechClipException(statusFailure(status));
            }
            return response.body();
        });
    }

    /** 요청 본문(원문은 이 본문에만 있고 로그 · 예외에 넣지 않는다). 말투 지시는 비어 있지 않을 때만 싣는다. */
    Map<String, Object> body(SpeechClipVoice which, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("voice", voice);
        body.put("input", text);
        body.put("response_format", "pcm");
        String style = which == SpeechClipVoice.NARRATOR ? narratorStyle : peerStyle;
        if (!style.isBlank()) {
            body.put("instructions", style);
        }
        return body;
    }

    @Override
    public void close() {
        client.close();
    }

    static SpeechClipFailure statusFailure(int status) {
        if (status == 401 || status == 403) {
            return SpeechClipFailure.AUTH_FAILED;
        }
        if (status == 429) {
            return SpeechClipFailure.RATE_LIMITED;
        }
        return status >= 500 ? SpeechClipFailure.PROVIDER_ERROR : SpeechClipFailure.REQUEST_REJECTED;
    }

    /** 실패 원인을 사유 코드로 바꾼다. 원인 메시지는 버린다. */
    static SpeechClipFailure translate(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof SpeechClipException clip) {
            return clip.failure();
        }
        if (cause instanceof HttpTimeoutException || cause instanceof CancellationException) {
            return SpeechClipFailure.TIMEOUT;
        }
        if (cause instanceof BodyTooLargeException) {
            return SpeechClipFailure.AUDIO_TOO_LONG;
        }
        return SpeechClipFailure.TRANSPORT_ERROR;
    }

    @Override
    public String toString() {
        return "OpenAiSpeechClipSynthesizer[model=" + model + ", voice=" + voice + "]";
    }

    /** 응답 본문이 상한을 넘었다. */
    static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("AUDIO_TOO_LONG");
        }
    }

    /** 바이트를 세며 상한까지만 모으는 본문 수신기. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;

        BoundedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            this.subscription = value;
            value.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                received += item.remaining();
                if (received > limit) {
                    subscription.cancel();
                    body.completeExceptionally(new BodyTooLargeException());
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(buffer.toByteArray());
        }
    }
}
