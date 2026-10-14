package kr.mujoong.core.notification.transport;

import static org.assertj.core.api.Assertions.*;

import kr.mujoong.contract.json.JsonCodec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Tag("u7")
class PushDeliveryLedgerTest {
    @Test
    void partialSuccessSurvivesSerializationAndOnlyPendingRecipientAdvances() {
        String phone = UUID.randomUUID().toString(), tablet = UUID.randomUUID().toString();
        var pending = RecipientProgress.pending();
        var accepted = pending.after(new DeliveryOutcome(DeliveryOutcome.Status.ACCEPTED, null), 4);
        var failing = pending.after(DeliveryOutcome.failed("PUSH_DELIVERY_FAILED"), 4);
        Map<String, RecipientProgress> progress =
                new LinkedHashMap<>(Map.of(phone, accepted, tablet, failing));
        assertThat(PushDeliveryLedger.status(progress)).isEqualTo("REQUESTED");
        for (int round = 2; round <= 4; round++) {
            progress =
                    PushDeliveryLedger.read(JsonCodec.shared(), JsonCodec.shared().write(progress));
            assertThat(progress.get(phone)).isEqualTo(accepted);
            assertThat(PushDeliveryLedger.retryIndex(progress, 3)).isEqualTo(round - 2);
            progress.put(
                    tablet,
                    progress.get(tablet).after(DeliveryOutcome.failed("PUSH_DELIVERY_FAILED"), 4));
        }
        assertThat(PushDeliveryLedger.status(progress)).isEqualTo("FAILED");
        assertThat(progress.get(tablet).attempt()).isEqualTo(4);
        assertThat(accepted.after(DeliveryOutcome.failed("PUSH_DELIVERY_FAILED"), 4))
                .isSameAs(accepted);
    }

    @Test
    void noTargetsExpiredAndRevokedAreSkippedButOneAcceptedIsSent() {
        var expired =
                RecipientProgress.pending()
                        .after(
                                new DeliveryOutcome(
                                        DeliveryOutcome.Status.EXPIRED,
                                        "PUSH_SUBSCRIPTION_EXPIRED"),
                                4);
        var skipped = RecipientProgress.pending().skip("PUSH_SUBSCRIPTION_INACTIVE");
        assertThat(PushDeliveryLedger.status(Map.of())).isEqualTo("SKIPPED");
        assertThat(PushDeliveryLedger.status(Map.of("a", expired, "b", skipped)))
                .isEqualTo("SKIPPED");
        var accepted =
                RecipientProgress.pending()
                        .after(new DeliveryOutcome(DeliveryOutcome.Status.ACCEPTED, null), 4);
        assertThat(PushDeliveryLedger.status(Map.of("a", expired, "b", accepted)))
                .isEqualTo("SENT");
        assertThat(expired.skip("PUSH_EVENT_OBSOLETE")).isSameAs(expired);
        assertThat(
                        RecipientProgress.pending()
                                .after(
                                        new DeliveryOutcome(
                                                DeliveryOutcome.Status.DISABLED, "PUSH_DISABLED"),
                                        4)
                                .status())
                .isEqualTo(RecipientProgress.Status.SKIPPED);
    }

    @Test
    void topicIsStableBoundedAndContainsNoEventIdentifier() {
        String eventKey = UUID.randomUUID() + ":VOICE_PROBLEM:DEVICE:2026-10-14T00:00:00Z";
        assertThat(PushDispatchRepository.topic(eventKey))
                .hasSize(32)
                .matches("[A-Za-z0-9_-]+")
                .isEqualTo(PushDispatchRepository.topic(eventKey))
                .isNotEqualTo(PushDispatchRepository.topic(eventKey + "1"));
    }
}
