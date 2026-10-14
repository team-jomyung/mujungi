package kr.mujoong.core.notification.transport;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

@Tag("u7")
class PublicPushEndpointPolicyTest {
    @Test
    void rejectsUnsafeUriShapesWithoutEchoingCapability() {
        for (String endpoint :
                List.of(
                        "http://push.invalid/secret",
                        "https://user@push.invalid/secret",
                        "https://push.invalid:8443/secret",
                        "https://push.invalid/secret#fragment",
                        "https://127.0.0.1/secret",
                        "https://[::1]/secret",
                        "https://localhost/secret")) {
            assertThatThrownBy(() -> PublicPushEndpointPolicy.validateUri(endpoint))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("잘못된 푸시 주소")
                    .hasNoCause();
        }
        assertThat(
                        PublicPushEndpointPolicy.validateUri(
                                        "https://push.example.invalid/capability")
                                .getPort())
                .isEqualTo(-1);
    }

    @Test
    void rejectsNonPublicAndTransitionAddresses() throws Exception {
        for (String address :
                List.of(
                        "0.0.0.0",
                        "127.0.0.1",
                        "10.0.0.1",
                        "172.16.0.1",
                        "192.168.0.1",
                        "169.254.169.254",
                        "100.64.0.1",
                        "192.0.2.1",
                        "198.18.0.1",
                        "224.0.0.1",
                        "255.255.255.255",
                        "::1",
                        "fc00::1",
                        "fe80::1",
                        "ff02::1",
                        "2001:db8::1",
                        "2002:7f00:1::1",
                        "64:ff9b::7f00:1")) {
            assertThat(PublicPushEndpointPolicy.isPublic(InetAddress.getByName(address)))
                    .as(address)
                    .isFalse();
        }
        assertThat(
                        PublicPushEndpointPolicy.isPublic(
                                InetAddress.getByAddress(new byte[] {8, 8, 8, 8})))
                .isTrue();
    }

    @Test
    void mixedDnsAnswersFailClosed() throws Exception {
        InetAddress external = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        var resolver =
                new PublicDnsResolver(
                        host -> new InetAddress[] {external, InetAddress.getLoopbackAddress()});
        assertThatThrownBy(() -> resolver.resolve("push.example.invalid"))
                .isInstanceOf(java.net.UnknownHostException.class)
                .hasMessage("푸시 주소 DNS 거부");
        assertThat(
                        new PublicDnsResolver(host -> new InetAddress[] {external})
                                .resolve("push.example.invalid"))
                .containsExactly(external);
    }
}
