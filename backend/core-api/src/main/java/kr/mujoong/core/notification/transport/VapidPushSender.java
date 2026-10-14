package kr.mujoong.core.notification.transport;

import com.the13haven.push2u.EndpointAssessment;
import com.the13haven.push2u.PushHttpClient;
import com.the13haven.push2u.PushMessage;
import com.the13haven.push2u.PushOutcome;
import com.the13haven.push2u.PushSender;
import com.the13haven.push2u.Subscription;

import kr.mujoong.contract.json.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 프로토콜 암호는 라이브러리에 맡기고 영속 재시도와 업무 시각은 앱이 관리한다. */
public final class VapidPushSender implements PushDeliverySender {
    private final PushSender sender;
    private final JsonCodec json;

    public VapidPushSender(PushVapidSettings settings, PushHttpClient transport, JsonCodec json) {
        this.json = json;
        this.sender =
                settings.enabled()
                        ? PushSender.builder(
                                        settings.keys(),
                                        settings.subject(),
                                        endpoint -> {
                                            try {
                                                PublicPushEndpointPolicy.validateUri(
                                                        endpoint.toString());
                                                return new EndpointAssessment.Allowed();
                                            } catch (IllegalArgumentException rejected) {
                                                return new EndpointAssessment.Refused(
                                                        "PUSH_ENDPOINT_REJECTED");
                                            }
                                        })
                                .httpClient(transport)
                                .build()
                        : null;
    }

    @Override
    public boolean enabled() {
        return sender != null;
    }

    @Override
    public DeliveryOutcome send(PushTarget target, PushPayload payload, String topic) {
        if (!enabled())
            return new DeliveryOutcome(DeliveryOutcome.Status.DISABLED, "PUSH_DISABLED");
        try {
            var subscription =
                    Subscription.fromBase64(target.endpoint(), target.p256dh(), target.auth());
            // 서비스에 오래된 알림을 보관하지 않는다. U7은 완료 알림도 즉시 전달만 지원한다.
            var message =
                    PushMessage.builder(json.write(payload).getBytes(StandardCharsets.UTF_8))
                            .ttl(Duration.ZERO)
                            .topic(topic)
                            .build();
            PushOutcome result = sender.send(subscription, message);
            if (result instanceof PushOutcome.Accepted)
                return new DeliveryOutcome(DeliveryOutcome.Status.ACCEPTED, null);
            if (result instanceof PushOutcome.SubscriptionExpired)
                return new DeliveryOutcome(
                        DeliveryOutcome.Status.EXPIRED, "PUSH_SUBSCRIPTION_EXPIRED");
            if (result instanceof PushOutcome.EndpointRejected)
                return DeliveryOutcome.failed("PUSH_ENDPOINT_REJECTED");
            return DeliveryOutcome.failed("PUSH_DELIVERY_FAILED");
        } catch (RuntimeException failure) {
            return DeliveryOutcome.failed("PUSH_DELIVERY_FAILED");
        }
    }
}
