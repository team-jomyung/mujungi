package kr.mujoong.realtime.session;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
@Tag("db")
class NoticeDeliveryServiceGuardrailIT {
    @Test
    void authenticatedDeviceActualCompletedThenCurrentReplyCommitsParticipation() {
        try (var env = RealtimeItEnvironment.startWith(Map.of(), context -> context.addBeanFactoryPostProcessor(factory ->
                factory.registerSingleton("noticeAssetCatalog", ServiceGuardrailFixtures.catalog())))) {
            assertThat(env.app.bean(SessionExecutor.Services.class).notices())
                    .isSameAs(env.app.bean(NoticeAssetCatalog.class));
            assertThat(env.app.bean(NoticeAssetCatalog.class).verified(ServiceGuardrailFixtures.item(),
                    ServiceGuardrailFixtures.audio(), "service-0.1",
                    env.app.bean(SessionExecutor.Services.class).settings().playbackExpiry())).isPresent();
            var device = env.connectDevice();
            UUID id = UUID.randomUUID();
            var base = env.startCommand(id, new ChildContext("별", 8, java.util.List.of()));
            var approved = new LinkedHashMap<>(base.approvedContent());
            approved.put(ServiceGuardrailFixtures.CODE, new StartSessionHandler.ApprovedContentItem(
                    ServiceGuardrailFixtures.INTRO, ServiceGuardrailFixtures.TEXT, ServiceGuardrailFixtures.URL));
            var command = new StartSessionHandler.StartCommand(id, base.idempotencyKey(), base.guardianId(), base.childId(),
                    base.deviceId(), base.activityType(), base.consentSnapshot(), base.childContext(), base.stopExpressions(), approved);
            env.app.bean(StartSessionHandler.class).start(command, null);
            var start = device.await(WsMessageType.START_SESSION, WAIT);
            device.ack(start.opId(), id);
            device.deviceReady(id, true, true);
            var play = (PlayApprovedContent) device.await(WsMessageType.PLAY_APPROVED_CONTENT, WAIT).payload();
            assertThat(env.sessionStatus(id)).isEqualTo(SessionStatus.WAITING_CHILD);
            device.playbackMark(id, play.playbackId(), PlaybackMark.Status.COMPLETED);
            awaitTrue(() -> env.app.bean(SessionGuardrailFactsRepository.class).latest(id)
                    .map(facts -> facts.notice() == SessionGuardrailFacts.Notice.COMPLETED).orElse(false), "notice committed");
            env.join(id);
            assertThat(env.app.bean(SessionGuardrailFactsRepository.class).latest(id).orElseThrow().participation())
                    .isEqualTo(SessionGuardrailFacts.Participation.ACCEPTED);
        }
    }
}
