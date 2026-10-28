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
 * GPT-Live 웹소켓({@code voice.ProviderClient})과는 다른 HTTP 경로다(NFR3.8). 끝점은 Decisions API {@code {baseUrl}/decisions} 하나다
 * (진행 중 판정, FT-1023-d).
 *
 * <ul>
 *   <li>앱마다 하나인 {@link HttpClient}: 리다이렉트를 따르지 않고 프록시를 쓰지 않는다.
 *   <li>보내기 직전 대상 주소를 기동 때와 같은 {@link LunaHosts#check(URI, LunaHosts.Mode)}로 다시 확인한다.
 *   <li>{@code Authorization} 머리는 env 키로 그 자리에서 붙이고 키를 필드 · 레코드 · 로그에 두지 않는다.
 *   <li>응답 본문은 바이트를 세며 상한까지만 읽고, 넘으면 LENGTH_EXCEEDED다.
 *   <li>시도 시한은 본문 수신 끝까지 덮는다({@code orTimeout}). 넘거나 호출자가 Future를 취소하면 진행 중인 요청을 취소한다.
 * </ul>
 */
public final class LunaTransport implements AutoCloseable {

    /** 공급자 키 env 이름(contracts/ai-io.md 10.7). */
    public static final String API_KEY_ENV = "OPENAI_API_KEY";

    /** 기준 주소 뒤에 붙는 Decisions API 경로(FT-1023-d). */
    static final String DECISIONS_PATH = "decisions";

    /** 연결 예열 GET 경로. 키 없이 보내므로 판정 · 과금이 없다. */
    static final String WARM_UP_PATH = "models";

    private final HttpClient client;
    private final URI endpoint;
    private final URI warmUpTarget;
    private final LunaHosts.Mode mode;
    private final Supplier<String> apiKey;
    private final int maxResponseBytes;

    /**
     * @param baseUrl OpenAI API 기준 주소({@code {baseUrl}/decisions}로 보낸다)
     * @param mode 주소 규칙(기동 때와 같은 모드)
     * @param apiKey 보낼 때마다 env에서 읽는 키 공급자(값을 보관하지 않음)
     * @param maxResponseBytes 응답 본문 바이트 상한
     */
    public LunaTransport(URI baseUrl, LunaHosts.Mode mode, Supplier<String> apiKey, int maxResponseBytes) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        String base = baseUrl.toString();
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.endpoint = URI.create(trimmed + "/" + DECISIONS_PATH);
        this.warmUpTarget = URI.create(trimmed + "/" + WARM_UP_PATH);
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

    /** 보내는 주소({@code {baseUrl}/decisions}). */
    public URI endpoint() {
        return endpoint;
    }

    /**
     * Decisions API로 요청 본문 하나를 보낸다. 반환 Future는 시한 {@code limit} 안에 끝나며, 실패는 {@link LunaException}을 원인으로 갖는다.
     */
    public CompletableFuture<RawResponse> send(String body, Duration limit) {
        Objects.requireNonNull(body, "body");
        Optional<LunaFailureReason> rejected = rejectBeforeSend(limit);
        if (rejected.isPresent()) {
            return CompletableFuture.failedFuture(new LunaException(rejected.get(), 0));
        }
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            // rejectBeforeSend 뒤 키가 사라진 드문 경우도 보내지 않는다
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

    /**
     * 판정 전에 공급자 연결을 미리 맺는다. 세션 첫 판정이 연결 생성 · TLS 협상 비용으로 시한(live-budget)을 넘지 않게 하려는 것이다. 같은 호스트의
     * {@code {baseUrl}/models}에 키 없이 GET 한 번을 보내고(판정 · 과금 없음, 데이터 없음) 응답 상태와 관계없이 연결만 남긴다. 반환 Future는
     * 실패하지 않고 시한 안에 끝난다. 주소 규칙을 어기면 보내지 않는다.
     */
    public CompletableFuture<Void> warmUp(Duration limit) {
        if (limit == null || limit.isNegative() || limit.isZero() || LunaHosts.check(warmUpTarget, mode).isPresent()) {
            return CompletableFuture.completedFuture(null);
        }
        HttpRequest request = HttpRequest.newBuilder(warmUpTarget).timeout(limit).GET().build();
        CompletableFuture<HttpResponse<Void>> http = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        return http.orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS).handle((response, failure) -> {
            if (failure != null) {
                // 예열 실패는 판정에 영향을 주지 않는다: 첫 판정이 연결을 새로 맺을 뿐이다
                http.cancel(true);
            }
            return null;
        });
    }

    /**
     * 보내기 전에 거절할 사유(검토 R-04). 시한 0 이하 → TIMEOUT, 허용 밖 주소 → REQUEST_REJECTED, 빈 키 → AUTH_FAILED. 호출부는 이 사유로 끝난
     * 경우를 공급자 시도로 세지 않는다({@code LUNA_CALL attempt=0}). {@link #send}도 같은 확인을 한다.
     */
    Optional<LunaFailureReason> rejectBeforeSend(Duration limit) {
        if (limit == null || limit.isNegative() || limit.isZero()) {
            return Optional.of(LunaFailureReason.TIMEOUT);
        }
        if (LunaHosts.check(endpoint, mode).isPresent()) {
            return Optional.of(LunaFailureReason.REQUEST_REJECTED);
        }
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            return Optional.of(LunaFailureReason.AUTH_FAILED);
        }
        return Optional.empty();
    }

    /** 앱 컨텍스트가 닫힐 때 {@link HttpClient}를 닫는다(검토 R-06). 진행 중인 요청이 끝나기를 기다린다. */
    @Override
    public void close() {
        client.close();
    }

    /** 공유 {@link HttpClient}가 닫혔는가(시험 확인용). */
    boolean isClosed() {
        return client.isTerminated();
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
