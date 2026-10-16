package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.ApprovedContentCatalog.SpeechItem;
import kr.mujoong.core.activity.RealtimeSessionClient.StartKind;
import kr.mujoong.core.activity.RealtimeSessionClient.StartResult;
import kr.mujoong.core.activity.StartActivityService.Context;
import kr.mujoong.core.activity.StartActivityService.StartRequest;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import kr.mujoong.core.catalog.ApprovedContentVersion;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.child.ChildProfileService.ChildContext;
import kr.mujoong.core.child.ChildProfileService.StopExpression;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.consent.ConsentPolicyService.GateResult;
import kr.mujoong.core.consent.ConsentPolicyService.Snapshot;
import kr.mujoong.core.consent.ConsentStatusRule.Status;
import kr.mujoong.core.device.DeviceViewAssembler;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.internalhttp.InternalHttpClient;
import kr.mujoong.core.internalhttp.InternalHttpClient.CallException;
import kr.mujoong.core.internalhttp.InternalHttpClient.Outcome;
import kr.mujoong.core.internalhttp.InternalHttpClient.Response;
import kr.mujoong.core.internalhttp.InternalHttpClient.Target;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 승인 발화가 실린 시작의 결과 처리(U8 NFR6.1 · 편차 U8-b). approvedSpeech가 있는 새 호출 경로도 옛 경로와 같은 규칙을 따르는지 본다:
 * Realtime의 423 · 409 · 503이 같은 로컬 FAILED와 오류 code로 끝나고(503은 CORE_ABANDONED 취소까지), 같은 멱등 키 재생은 발화를 다시 고르지
 * 않으며, 입력 검증 실패는 발화를 고르기 전에 끝난다. Core → Realtime 호출부는 발화 본문에도 같은 시간 초과 재시도 · 차단기를 쓴다.
 */
@Tag("u8")
class U8ApprovedSpeechStartOutcomesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

    private final UUID guardianId = UUID.randomUUID();
    private final ApprovedContentRepository repository = mock(ApprovedContentRepository.class);
    private final ActivityCopyRepository activities = mock(ActivityCopyRepository.class);
    private final RealtimeSessionClient realtime = mock(RealtimeSessionClient.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final TestEvents events = new TestEvents(clock);
    private final StartActivityService service = service();

    @Test
    void realtimeRejectionsOnTheSpeechPathEndLikeTheOldPath() {
        when(activities.closeLocalFailed(any(), eq(SessionStatus.REQUESTED), anyLong(), anyInt(), any())).thenReturn(1);
        when(realtime.start(any(), anyMap()))
                .thenReturn(new StartResult(StartKind.DEVICE_NOT_CONNECTED, null, false))
                .thenReturn(new StartResult(StartKind.CONFLICT, null, false))
                .thenReturn(new StartResult(StartKind.UNAVAILABLE, null, true));

        assertRejected(ErrorCode.DEVICE_NOT_STABLE);
        assertRejected(ErrorCode.SESSION_IN_PROGRESS);
        assertRejected(ErrorCode.REALTIME_UNAVAILABLE);

        verify(realtime, times(3)).start(any(), anyMap());
        verify(realtime, never()).start(any());
        verify(realtime, times(1)).cancel(any());
        assertThat(events.capture().lines()).noneMatch(line -> line.contains("쉬고"));
    }

    @Test
    void whenARealtimeEventWonTheRaceTheStartIsReplayedFromTheCurrentRow() {
        when(activities.closeLocalFailed(any(), eq(SessionStatus.REQUESTED), anyLong(), anyInt(), any())).thenReturn(0);
        ActivityCopy current = mock(ActivityCopy.class);
        when(current.localFailed()).thenReturn(false);
        when(activities.findById(any())).thenReturn(Optional.of(current));
        when(realtime.start(any(), anyMap())).thenReturn(new StartResult(StartKind.CONFLICT, null, false));

        StartActivityService.Started started = service.start(guardianId, new StartRequest(UUID.randomUUID(), null));

        assertThat(started.status()).isEqualTo(SessionStatus.REQUESTED);
        verify(realtime, never()).cancel(any());
    }

    @Test
    void theSameIdempotencyKeyReplaysWithoutAssemblingSpeechAgain() {
        UUID key = UUID.randomUUID();
        ActivityCopy copy = mock(ActivityCopy.class);
        when(copy.requestHash()).thenReturn(JsonCodec.shared().sha256OfCanonical(JsonCodec.shared().write(new StartRequest(key, null))));
        when(copy.sessionId()).thenReturn(UUID.randomUUID());
        when(activities.findByIdempotencyKey(guardianId, key.toString())).thenReturn(Optional.of(copy));

        StartActivityService.Started replayed = service.start(guardianId, new StartRequest(key, null));

        assertThat(replayed.sessionId()).isEqualTo(copy.sessionId());
        verify(repository, never()).findByContentCode(any());
        verify(realtime, never()).start(any(), anyMap());
        when(copy.requestHash()).thenReturn("sha256:다른");
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(key, null)))
                .isInstanceOf(CoreApiException.class).satisfies(e -> assertThat(((CoreApiException) e).code())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void invalidInputIsRefusedBeforeAnySpeechIsPicked() {
        String tooLong = "가".repeat(StartActivityService.NARRATIVE_MAX + 1);

        for (Context context : List.of(new Context(tooLong, null, null, null), new Context(null, "PLANET", null, null),
                new Context(null, null, "DREAM", null), new Context(null, null, null, tooLong))) {
            assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(), context)))
                    .isInstanceOf(CoreApiException.class)
                    .satisfies(e -> assertThat(((CoreApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(null, null))).isInstanceOf(CoreApiException.class);
        verify(repository, never()).findByContentCode(any());
    }

    @Test
    void theRealtimeClientAppliesTheSameRetryAndBreakerToTheSpeechBody() throws Exception {
        InternalHttpClient http = mock(InternalHttpClient.class);
        SessionStartBreaker breaker = new SessionStartBreaker(clock, CoreMetrics.inMemory(), 1, Duration.ofSeconds(5));
        RealtimeSessionClient client = new RealtimeSessionClient(http, breaker, JsonCodec.shared(), Duration.ofSeconds(3), 1,
                Duration.ofSeconds(2), Duration.ofSeconds(1));
        Map<String, SpeechItem> speech = Map.of("D_REST_ACK", new SpeechItem(UUID.randomUUID(), "잠깐 쉬고 싶구나."));
        when(http.send(eq(Target.SESSION_START), any()))
                .thenThrow(new CallException(Target.SESSION_START, Outcome.TIMEOUT, "HttpTimeoutException"))
                .thenReturn(new Response(202, "{\"status\":\"PREPARING\"}"))
                .thenThrow(new CallException(Target.SESSION_START, Outcome.CONN_ERROR, "ConnectException"));

        StartResult retried = client.start(U8RealtimeStartBodyTestPackages.free(), speech);
        StartResult refused = client.start(U8RealtimeStartBodyTestPackages.free(), speech);
        StartResult open = client.start(U8RealtimeStartBodyTestPackages.free(), speech);

        assertThat(retried.kind()).isEqualTo(StartKind.ACCEPTED);
        assertThat(refused.kind()).isEqualTo(StartKind.UNAVAILABLE);
        assertThat(open.kind()).as("차단기가 열리면 부르지 않는다").isEqualTo(StartKind.UNAVAILABLE);
        verify(http, times(3)).send(eq(Target.SESSION_START), any());
        verify(http).recordBreakerOpen(any());
    }

    @Test
    void theCancelAfterASpeechPath503IsACoreAbandonedEndWithTheSessionIdAsRequestId() throws Exception {
        InternalHttpClient http = mock(InternalHttpClient.class);
        RealtimeSessionClient client = new RealtimeSessionClient(http,
                new SessionStartBreaker(clock, CoreMetrics.inMemory(), 3, Duration.ofSeconds(5)), JsonCodec.shared(),
                Duration.ofSeconds(3), 1, Duration.ofSeconds(2), Duration.ofSeconds(1));
        UUID sessionId = UUID.randomUUID();
        when(http.send(eq(Target.SESSION_END), any())).thenReturn(new Response(202, "{}"))
                .thenReturn(new Response(409, "{\"code\":\"SESSION_NOT_ACTIVE\",\"message\":\"x\"}"));

        assertThat(client.cancel(sessionId)).isTrue();
        assertThat(client.cancel(sessionId)).isFalse();

        org.mockito.ArgumentCaptor<InternalHttpClient.Call> call = org.mockito.ArgumentCaptor.forClass(InternalHttpClient.Call.class);
        verify(http, times(2)).send(eq(Target.SESSION_END), call.capture());
        assertThat(call.getAllValues().get(0).path()).isEqualTo("/internal/sessions/" + sessionId + "/end");
        assertThat(call.getAllValues().get(0).body()).contains("\"reason\":\"CORE_ABANDONED\"")
                .contains("\"requestId\":\"" + sessionId + "\"");
    }

    @Test
    void theCatalogsExistingPinAndItemRulesStayAsBefore() {
        when(repository.findCurrentPolicyVersion()).thenReturn(Optional.of("1.0"));
        ApprovedContentCatalog catalog = new ApprovedContentCatalog(repository);

        assertThat(catalog.currentPolicyVersion()).contains("1.0");
        assertThat(catalog.pin(List.of(ApprovedContent.CODE_CLOSING, ApprovedContent.CODE_D_REST_ACK, "NO_SUCH")))
                .containsOnlyKeys(ApprovedContent.CODE_CLOSING, ApprovedContent.CODE_D_REST_ACK);
        assertThat(catalog.sessionContent(List.of(ApprovedContent.CODE_CLOSING, ApprovedContent.CODE_D_REST_ACK)))
                .as("음성 없는 승인 발화는 approvedContent 후보가 아니다").containsOnlyKeys(ApprovedContent.CODE_CLOSING);
        assertThat(catalog.latestApproved(ApprovedContent.CODE_D_REST_ACK)).get().hasToString(
                "PinnedContent[contentVersionId=" + catalog.latestApproved(ApprovedContent.CODE_D_REST_ACK).get().contentVersionId() + "]");
        assertThatThrownBy(() -> new ApprovedContentCatalog.ApprovedItem(UUID.randomUUID(), " ", "/media/approved/a.wav"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovedContentCatalog.ApprovedItem(UUID.randomUUID(), "글", "https://x/a.wav"))
                .isInstanceOf(IllegalArgumentException.class);
        ApprovedContent speech = new ApprovedContent(UUID.randomUUID(), ApprovedContent.CODE_PEER_RETURN,
                ApprovedContent.TYPE_APPROVED_SPEECH, NOW);
        assertThat(speech.getContentType()).isEqualTo("APPROVED_SPEECH");
        assertThat(speech.getContentCode()).isEqualTo("PEER_RETURN");
        assertThat(speech.getCreatedAt()).isEqualTo(NOW);
        assertThat(speech).isEqualTo(speech).isNotEqualTo(new ApprovedContent(UUID.randomUUID(), "PEER_RETURN",
                ApprovedContent.TYPE_APPROVED_SPEECH, NOW)).hasSameHashCodeAs(new ApprovedContent(UUID.randomUUID(), "X", "Y", NOW));
        assertThat(speech.toString()).contains("PEER_RETURN").contains(speech.getId().toString());
    }

    // ------------------------------------------------------------------ 도움

    private void assertRejected(ErrorCode code) {
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOf(CoreApiException.class)
                .satisfies(e -> assertThat(((CoreApiException) e).code()).isEqualTo(code));
    }

    private StartActivityService service() {
        GuardianRepository guardians = mock(GuardianRepository.class);
        ChildProfileService children = mock(ChildProfileService.class);
        ConsentPolicyService consent = mock(ConsentPolicyService.class);
        DeviceViewAssembler deviceView = mock(DeviceViewAssembler.class);
        when(guardians.findById(guardianId)).thenReturn(Optional.of(new Guardian(guardianId, NOW)));
        when(children.childContext(guardianId)).thenReturn(Optional.of(new ChildContext(UUID.randomUUID(), "하늘", 8, List.of("공룡"),
                List.of(new StopExpression(SessionPackageBuilder.DEFAULT_STOP_EXPRESSION, true)))));
        when(consent.check(guardianId)).thenReturn(new GateResult(true, Status.GIVEN, new Snapshot("1.0", true, true, true, true)));
        when(activities.findByIdempotencyKey(eq(guardianId), any())).thenReturn(Optional.empty());
        when(activities.findInProgressByGuardian(guardianId)).thenReturn(Optional.empty());
        when(activities.insertRequested(any())).thenReturn(true);
        when(deviceView.readiness(guardianId)).thenReturn(new DeviceViewAssembler.Readiness(UUID.randomUUID(), true, null));
        content(ApprovedContent.CODE_CLOSING, ApprovedContent.TYPE_APPROVED_AUDIO, "끝 인사", "closing-v1.wav");
        content(ApprovedContent.CODE_WAITING_FILLER, ApprovedContent.TYPE_APPROVED_AUDIO, "잠깐", "waiting-filler-v1.wav");
        content(ApprovedContent.CODE_D_REST_ACK, ApprovedContent.TYPE_APPROVED_SPEECH, "잠깐 쉬고 싶구나.", null);
        return new StartActivityService(
                new StartActivityService.Ports(guardians, children, activities, mock(ActivityContextRepository.class), consent,
                        deviceView, new ApprovedContentCatalog(repository)),
                new SessionPackageBuilder(), realtime, mock(PlatformTransactionManager.class), JsonCodec.shared(), events.logger(),
                events.metrics(), clock, Runnable::run);
    }

    private void content(String code, String type, String text, String audio) {
        UUID contentId = UUID.randomUUID();
        when(repository.findByContentCode(code)).thenReturn(Optional.of(new ApprovedContent(contentId, code, type, NOW)));
        when(repository.findLatestApproved(code)).thenReturn(Optional.of(new ApprovedContentVersion(UUID.randomUUID(), contentId, 1, text,
                audio, ApprovedContentVersion.STATUS_APPROVED, NOW)));
    }

    /** 시험용 자유대화 패키지(U8RealtimeStartBodyTest와 같은 모양). */
    static final class U8RealtimeStartBodyTestPackages {
        static SessionPackageBuilder.SessionPackage free() {
            Map<String, ApprovedContentCatalog.ApprovedItem> approved = new java.util.LinkedHashMap<>();
            approved.put("CLOSING", new ApprovedContentCatalog.ApprovedItem(UUID.randomUUID(), "안녕!", "/media/approved/closing-v1.wav"));
            approved.put("WAITING_FILLER", new ApprovedContentCatalog.ApprovedItem(UUID.randomUUID(), "음.",
                    "/media/approved/waiting-filler-v1.wav"));
            return new SessionPackageBuilder().build(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    new ChildContext(UUID.randomUUID(), "하늘", 8, List.of("공룡"),
                            List.of(new StopExpression(SessionPackageBuilder.DEFAULT_STOP_EXPRESSION, true))),
                    new Snapshot("1.0", true, true, true, true), approved);
        }
    }
}
