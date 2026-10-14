package kr.mujoong.core.notification.transport;

/** 구독별 영속 진행 상태. 완료된 수신처는 다음 라운드에서 재시도하지 않는다. */
public record RecipientProgress(Status status, int attempt, String lastError) {
    public enum Status {
        PENDING,
        SENT,
        EXPIRED,
        FAILED,
        SKIPPED
    }

    public RecipientProgress {
        if (status == null
                || attempt < 0
                || (lastError != null && !lastError.matches("[A-Z0-9_]{1,80}"))) {
            throw new IllegalArgumentException("잘못된 푸시 수신처 진행 상태");
        }
    }

    public static RecipientProgress pending() {
        return new RecipientProgress(Status.PENDING, 0, null);
    }

    public boolean pendingDelivery() {
        return status == Status.PENDING;
    }

    public RecipientProgress skip(String reason) {
        return pendingDelivery() ? new RecipientProgress(Status.SKIPPED, attempt, reason) : this;
    }

    public RecipientProgress after(DeliveryOutcome outcome, int maximumAttempts) {
        if (!pendingDelivery()) return this;
        if (maximumAttempts < 1) throw new IllegalArgumentException("잘못된 푸시 재시도 횟수");
        return switch (outcome.status()) {
            case ACCEPTED -> new RecipientProgress(Status.SENT, attempt + 1, null);
            case EXPIRED -> new RecipientProgress(Status.EXPIRED, attempt + 1, outcome.errorCode());
            case DISABLED -> skip("PUSH_DISABLED");
            case RETRYABLE_FAILURE ->
                    new RecipientProgress(
                            attempt + 1 >= maximumAttempts ? Status.FAILED : Status.PENDING,
                            attempt + 1,
                            outcome.errorCode());
        };
    }
}
