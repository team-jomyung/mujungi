package kr.mujoong.realtime.luna;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Luna 공급자로 나가는 유일한 길 — realtime (U9 SD3 · NFR3.4 · NFR3.6 · NFR3.9 · NFR3.10). core에 같은 모양이 따로 있다(R-F5).
 * GPT-Live 웹소켓({@code voice.ProviderClient})과는 다른 HTTP 경로다(NFR3.8).
 *
 * <ul>
 *   <li>앱마다 하나인 {@link HttpClient}: 리다이렉트를 따르지 않고 프록시를 쓰지 않는다.
 *   <li>보내기 직전 대상 주소를 기동 때와 같은 {@link LunaHosts#check(URI, LunaHosts.Mode)}로 다시 확인한다.
 *   <li>{@code Authorization} 머리는 env 키로 그 자리에서 붙이고 키를 필드 · 레코드 · 로그에 두지 않는다.
 *   <li>응답 본문은 바이트를 세며 상한까지만 읽고, 넘으면 LENGTH_EXCEEDED다.
 *   <li>시도 시한은 본문 수신 끝까지 덮는다({@code orTimeout}). 넘거나 호출자가 Future를 취소하면 진행 중인 요청을 취소한다.
 * </ul>
 */
public final class LunaTransport {

    /** 공급자 키 env 이름(contracts/ai-io.md 10.7). */
    public static final String API_KEY_ENV = "OPENAI_API_KEY";

    private final HttpClient client;
    private final URI endpoint;
    private final LunaHosts.Mode mode;
    private final Supplier<String> apiKey;
    private final int maxResponseBytes;

    /**
     * @param baseUrl Responses API 기준 주소({@code {baseUrl}/responses}로 보낸다)
     * @param mode 주소 규칙(기동 때와 같은 모드)
     * @param apiKey 보낼 때마다 env에서 읽는 키 공급자(값을 보관하지 않음)
     * @param maxResponseBytes 응답 본문 바이트 상한
     */
    public LunaTransport(URI baseUrl, LunaHosts.Mode mode, Supplier<String> apiKey, int maxResponseBytes) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        String base = baseUrl.toString();
        this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/responses");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes는 양수여야 한다");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    /** 보내는 주소({@code {baseUrl}/responses}). */
    public URI endpoint() {
        return endpoint;
    }

    /**
     * 요청 본문 하나를 보낸다. 반환 Future는 시한 {@code limit} 안에 끝나며, 실패는 {@link LunaException}을 원인으로 갖는다.
     */
    public CompletableFuture<RawResponse> send(String body, Duration limit) {
        Objects.requireNonNull(body, "body");
        if (limit == null || limit.isNegative() || limit.isZero()) {
            return CompletableFuture.failedFuture(new LunaException(LunaFailureReason.TIMEOUT, 0));
        }
        if (LunaHosts.check(endpoint, mode).isPresent()) {
            return CompletableFuture.failedFuture(new LunaException(LunaFailureReason.REQUEST_REJECTED, 0));
        }
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            return CompletableFuture.failedFuture(new LunaException(LunaFailureReason.AUTH_FAILED, 0));
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(limit)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<byte[]>> http =
                client.sendAsync(request, info -> new BoundedBodySubscriber(maxResponseBytes));
        CompletableFuture<RawResponse> result = http
                .thenApply(response -> new RawResponse(response.statusCode(),
                        new String(response.body(), StandardCharsets.UTF_8), retryAfter(response.headers())))
                .orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS);
        CompletableFuture<RawResponse> returned = result.handle((response, failure) -> {
            if (failure == null) {
                return response;
            }
            // 시한 초과 · 상한 초과면 진행 중인 요청을 끊는다(SD3, 공급자 과금 중지는 보장하지 않음)
            http.cancel(true);
            throw new CompletionException(translate(failure));
        });
        // 호출자가 돌려받은 Future를 취소해도(판정 예산 초과 등) 진행 중인 요청을 끊는다
        returned.whenComplete((response, failure) -> {
            if (returned.isCancelled()) {
                http.cancel(true);
            }
        });
        return returned;
    }

    /** 실패 원인을 사유 코드로 바꾼다. 원인 메시지는 버린다(BR6.2). */
    static LunaException translate(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof LunaException luna) {
            return luna;
        }
        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException || cause instanceof CancellationException) {
            return new LunaException(LunaFailureReason.TIMEOUT, 0);
        }
        if (cause instanceof BodyTooLargeException) {
            return new LunaException(LunaFailureReason.LENGTH_EXCEEDED, 0);
        }
        return new LunaException(LunaFailureReason.TRANSPORT_ERROR, 0);
    }

    /** {@code Retry-After}(초 단위 정수만). 판정은 재시도하지 않지만 기록 · U11 판단을 위해 읽어 둔다. */
    static Optional<Duration> retryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After").flatMap(value -> {
            String trimmed = value.strip();
            if (!trimmed.matches("[0-9]{1,6}")) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofSeconds(Long.parseLong(trimmed)));
        });
    }

    /** 받은 응답 하나. 문자열 표현은 본문을 숨긴다(BR6.2). */
    public record RawResponse(int status, String body, Optional<Duration> retryAfter) {

        public RawResponse {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(retryAfter, "retryAfter");
        }

        @Override
        public String toString() {
            return "RawResponse[status=" + status + ", body=redacted]";
        }
    }

    /** 응답 본문이 상한을 넘었다. */
    static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("LENGTH_EXCEEDED");
        }
    }

    /** 바이트를 세며 상한까지만 모으는 본문 수신기(NFR3.10). */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;

        BoundedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
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
                if (received > maxBytes) {
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
