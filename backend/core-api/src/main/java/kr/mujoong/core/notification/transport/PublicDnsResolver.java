package kr.mujoong.core.notification.transport;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/** HTTP 연결은 여기서 검사한 주소를 그대로 사용하며 DNS를 별도로 다시 조회하지 않는다. */
public final class PublicDnsResolver implements DnsResolver {
    @FunctionalInterface
    public interface Lookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final Lookup lookup;

    public PublicDnsResolver() {
        this(InetAddress::getAllByName);
    }

    public PublicDnsResolver(Lookup lookup) {
        this.lookup = Objects.requireNonNull(lookup);
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        try {
            InetAddress[] addresses = lookup.lookup(host);
            if (Thread.currentThread().isInterrupted()
                    || addresses == null
                    || addresses.length == 0) throw rejected();
            for (InetAddress address : addresses) {
                if (address == null || !PublicPushEndpointPolicy.isPublic(address))
                    throw rejected();
            }
            return addresses.clone();
        } catch (RuntimeException | UnknownHostException failure) {
            throw rejected();
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    private static UnknownHostException rejected() {
        return new UnknownHostException("푸시 주소 DNS 거부");
    }
}
