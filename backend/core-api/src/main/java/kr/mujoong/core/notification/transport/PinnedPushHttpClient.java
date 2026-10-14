package kr.mujoong.core.notification.transport;

import com.the13haven.push2u.PushDeliveryException;
import com.the13haven.push2u.PushHttpClient;
import com.the13haven.push2u.PushResponse;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 제한 시간 내 단일 POST. 연결 DNS 검사와 표준 TLS 호스트 검증을 사용하고 proxy·redirect·자동 재시도는 쓰지 않는다. */
public final class PinnedPushHttpClient implements PushHttpClient, AutoCloseable {
    private final Duration timeout;
    private final PublicDnsResolver resolver;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public PinnedPushHttpClient(Duration timeout) {
        this(timeout, new PublicDnsResolver());
    }

    public PinnedPushHttpClient(Duration timeout, PublicDnsResolver resolver) {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("잘못된 푸시 전송 제한 시간");
        this.timeout = timeout;
        this.resolver = resolver;
    }

    @Override
    public PushResponse post(URI endpoint, Map<String, String> headers, byte[] body) {
        try {
            PublicPushEndpointPolicy.validateUri(endpoint.toString());
        } catch (RuntimeException rejected) {
            throw failure();
        }
        HttpPost request = new HttpPost(endpoint);
        headers.forEach(request::setHeader);
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_OCTET_STREAM));
        Timeout limit = Timeout.of(timeout);
        request.setConfig(
                RequestConfig.custom()
                        .setConnectionRequestTimeout(limit)
                        .setResponseTimeout(limit)
                        .build());
        Future<PushResponse> attempt =
                executor.submit(
                        () -> {
                            var connections =
                                    PoolingHttpClientConnectionManagerBuilder.create()
                                            .setDnsResolver(resolver)
                                            .setDefaultConnectionConfig(
                                                    ConnectionConfig.custom()
                                                            .setConnectTimeout(limit)
                                                            .setSocketTimeout(limit)
                                                            .build())
                                            .build();
                            try (var http =
                                    HttpClients.custom()
                                            .setConnectionManager(connections)
                                            .disableAutomaticRetries()
                                            .disableRedirectHandling()
                                            .disableCookieManagement()
                                            .disableAuthCaching()
                                            .build()) {
                                return http.execute(
                                        request, response -> PushResponse.of(response.getCode()));
                            }
                        });
        try {
            return attempt.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            request.cancel();
            attempt.cancel(true);
            throw failure();
        } catch (Exception failed) {
            request.cancel();
            attempt.cancel(true);
            throw failure();
        }
    }

    private static PushDeliveryException failure() {
        return new PushDeliveryException("푸시 HTTP 전송 실패");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
