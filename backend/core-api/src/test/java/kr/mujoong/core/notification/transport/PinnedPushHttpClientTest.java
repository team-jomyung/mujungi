package kr.mujoong.core.notification.transport;

import static org.assertj.core.api.Assertions.*;

import com.the13haven.push2u.PushDeliveryException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Tag("web-push")
class PinnedPushHttpClientTest {
    @Test
    void unsafeUriNeverResolvesAndNonPublicDnsNeverConnects() {
        AtomicInteger resolutions = new AtomicInteger();
        var dns =
                new PublicDnsResolver(
                        host -> {
                            resolutions.incrementAndGet();
                            return new InetAddress[] {InetAddress.getLoopbackAddress()};
                        });
        try (var client = new PinnedPushHttpClient(Duration.ofSeconds(2), dns)) {
            assertThatThrownBy(
                            () ->
                                    client.post(
                                            URI.create("http://push.example.invalid/capability"),
                                            Map.of(),
                                            new byte[0]))
                    .isInstanceOf(PushDeliveryException.class)
                    .hasMessage("푸시 HTTP 전송 실패")
                    .hasNoCause();
            assertThat(resolutions).hasValue(0);
            assertThatThrownBy(
                            () ->
                                    client.post(
                                            URI.create("https://push.example.invalid/capability"),
                                            Map.of(),
                                            new byte[0]))
                    .isInstanceOf(PushDeliveryException.class)
                    .hasMessage("푸시 HTTP 전송 실패")
                    .hasNoCause();
            assertThat(resolutions).hasValue(1);
        }
    }

    @Test
    void deadlineCoversStalledDnsAndCancelsTheConnectingTask() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        var dns =
                new PublicDnsResolver(
                        host -> {
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                stopped.countDown();
                            }
                            return new InetAddress[] {InetAddress.getLoopbackAddress()};
                        });
        try (var client = new PinnedPushHttpClient(Duration.ofSeconds(1), dns)) {
            assertThatThrownBy(
                            () ->
                                    client.post(
                                            URI.create("https://push.example.invalid/capability"),
                                            Map.of(),
                                            new byte[0]))
                    .isInstanceOf(PushDeliveryException.class)
                    .hasNoCause();
            assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
