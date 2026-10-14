package kr.mujoong.core.notification;

/** C6 구독 등록 본문. 검증 오류와 로그에 주소·키 원문을 넣지 않는다. */
public record PushSubscriptionRequest(String endpoint, Keys keys, String platform) {
    @Override
    public String toString() {
        return "PushSubscriptionRequest[REDACTED]";
    }

    public record Keys(String p256dh, String auth) {
        @Override
        public String toString() {
            return "Keys[REDACTED]";
        }
    }
}
