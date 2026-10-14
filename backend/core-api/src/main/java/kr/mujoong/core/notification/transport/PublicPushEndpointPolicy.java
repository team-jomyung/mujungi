package kr.mujoong.core.notification.transport;

import java.net.InetAddress;
import java.net.URI;

/** 푸시 주소 형식 검사. DNS 결과는 실제 연결에 쓰는 resolver가 다시 검사한다. */
public final class PublicPushEndpointPolicy {
    private PublicPushEndpointPolicy() {}

    public static URI validateUri(String endpoint) {
        try {
            if (endpoint == null
                    || endpoint.length() > 2048
                    || !endpoint.chars().allMatch(c -> c > 32 && c < 127)) {
                throw invalid();
            }
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || host == null
                    || host.length() > 253
                    || !host.contains(".")
                    || !host.matches(
                            "(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")
                    || host.matches("[0-9.]+")) {
                throw invalid();
            }
            return uri;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    /** 공인 유니캐스트만 허용하며 특수 용도와 IP 전환 대역도 거부한다. */
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int a = Byte.toUnsignedInt(b[0]), c = Byte.toUnsignedInt(b[1]);
        if (b.length == 4) {
            int d = Byte.toUnsignedInt(b[2]);
            return !(a == 0
                    || a == 10
                    || a == 127
                    || a >= 224
                    || (a == 100 && c >= 64 && c <= 127)
                    || (a == 169 && c == 254)
                    || (a == 172 && c >= 16 && c <= 31)
                    || (a == 192
                            && (c == 168 || (c == 0 && (d == 0 || d == 2)) || (c == 88 && d == 99)))
                    || (a == 198 && (c == 18 || c == 19 || (c == 51 && d == 100)))
                    || (a == 203 && c == 0 && d == 113));
        }
        if (b.length != 16 || (a & 0xe0) != 0x20) return false;
        int d = Byte.toUnsignedInt(b[2]), e = Byte.toUnsignedInt(b[3]);
        return !(a == 0x20 && c == 0x02) // 6to4에 내부 IPv4 주소가 들어가는 우회 차단.
                && !(a == 0x20 && c == 0x01 && (d < 2 || (d == 0x0d && e == 0xb8)))
                && !(a == 0x3f && c == 0xff && (d & 0xf0) == 0); // 문서용 주소 대역
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("잘못된 푸시 주소");
    }
}
