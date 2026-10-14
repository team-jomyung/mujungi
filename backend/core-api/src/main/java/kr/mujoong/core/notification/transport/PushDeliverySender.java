package kr.mujoong.core.notification.transport;

public interface PushDeliverySender {
    boolean enabled();

    DeliveryOutcome send(PushTarget target, PushPayload payload, String topic);
}
