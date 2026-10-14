package kr.mujoong.core.notification.transport;

/** 응답 본문·주소·헤더·원인 예외를 밖으로 전달하지 않는 발송 결과. */
public record DeliveryOutcome(Status status, String errorCode) {
    public enum Status {
        ACCEPTED,
        EXPIRED,
        RETRYABLE_FAILURE,
        DISABLED
    }

    public DeliveryOutcome {
        if (status == null || (errorCode != null && !errorCode.matches("[A-Z0-9_]{1,80}"))) {
            throw new IllegalArgumentException("잘못된 푸시 발송 결과");
        }
    }

    public static DeliveryOutcome failed(String code) {
        return new DeliveryOutcome(Status.RETRYABLE_FAILURE, code);
    }
}
