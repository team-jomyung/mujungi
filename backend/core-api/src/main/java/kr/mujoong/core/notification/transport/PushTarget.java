package kr.mujoong.core.notification.transport;

import com.the13haven.push2u.Subscription;

/** 푸시 주소와 키는 로그·예외·문자열 표현에 싣지 않는다. */
public record PushTarget(String endpoint, String p256dh, String auth) {
    public PushTarget {
        try {
            PublicPushEndpointPolicy.validateUri(endpoint);
            Subscription.fromBase64(endpoint, p256dh, auth);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("잘못된 푸시 구독");
        }
    }

    public static PushTarget validated(String endpoint, String p256dh, String auth) {
        return new PushTarget(endpoint, p256dh, auth);
    }

    @Override
    public String toString() {
        return "PushTarget[REDACTED]";
    }
}
