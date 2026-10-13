package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.ActivityCopyRepository.NewActivity;
import kr.mujoong.core.activity.ActivityCopyRepository.StateChange;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.activity.RealtimeSessionClient.InternalSessionView;
import kr.mujoong.core.activity.RealtimeSessionClient.StartKind;
import kr.mujoong.core.activity.RealtimeSessionClient.StartResult;
import kr.mujoong.core.activity.RealtimeSessionClient.ViewKind;
import kr.mujoong.core.activity.RealtimeSessionClient.ViewResult;
import kr.mujoong.core.activity.StartActivityService.Context;
import kr.mujoong.core.activity.StartActivityService.StartRequest;
import kr.mujoong.core.activity.StartActivityService.Started;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.device.DeviceVerdict.ReasonCode;
import kr.mujoong.core.device.DeviceViewAssembler;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.child.ChildProfileService.ChildContext;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.consent.ConsentPolicyService.GateResult;
import kr.mujoong.core.consent.ConsentPolicyService.Snapshot;
import kr.mujoong.core.consent.ConsentStatusRule.Status;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.internalhttp.InternalHttpClient;
import kr.mujoong.core.internalhttp.InternalHttpClient.CallException;
import kr.mujoong.core.internalhttp.InternalHttpClient.Outcome;
import kr.mujoong.core.internalhttp.InternalHttpClient.Response;
import kr.mujoong.core.internalhttp.InternalHttpClient.Target;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 시작 정책 (BR6.1 ⓪ ~ ③ · BR6.2 재생 · BR6.4 · BR6.6 번역 · BR6.7 · AC3.1.1 · AC3.1.3 · AC3.1.6). BR6.1 ② 계정 잠김 방지: REQUESTED가 아닌
 * 막는 사본은 view로 한 번 대조한다.
 */
@Tag("u1")
class StartActivityPolicyTest {

    private static final Instant NOW = Instant.parse("2001-03-01T05:00:00Z");

    private final UUID guardianId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final MutableClock clock = new MutableClock(NOW);
    private final TestEvents events = new TestEvents(clock);
    private final JsonCodec json = JsonCodec.shared();
    private final GuardianRepository guardians = mock(GuardianRepository.class);
    private final ChildProfileService children = mock(ChildProfileService.class);
    private final ActivityCopyRepository activities = mock(ActivityCopyRepository.class);
    private final ActivityContextRepository contexts = mock(ActivityContextRepository.class);
    private final ConsentPolicyService consentGate = mock(ConsentPolicyService.class);
    private final DeviceViewAssembler deviceView = mock(DeviceViewAssembler.class);
    private final ApprovedContentCatalog catalog = mock(ApprovedContentCatalog.class);
    private final RealtimeSessionClient realtime = mock(RealtimeSessionClient.class);
    private StartActivityService service;
    private ChildContext child;

    @BeforeEach
    void setUp() {
        // U4a: 아이 · 동의는 계약 8절 포트(ChildContext · ConsentGate)로 읽는다. 단언은 U1 그대로다
        service = new StartActivityService(
                new StartActivityService.Ports(guardians, children, activities, contexts, consentGate, deviceView, catalog),
                new SessionPackageBuilder(), realtime, mock(PlatformTransactionManager.class), json, events.logger(),
                events.metrics(), clock, Runnable::run);
        child = new ChildContext(UUID.randomUUID(), "하늘", 8, List.of("공룡"),
                List.of(new ChildProfileService.StopExpression(SessionPackageBuilder.DEFAULT_STOP_EXPRESSION, true)));
        when(guardians.findById(guardianId)).thenReturn(Optional.of(new Guardian(guardianId, NOW)));
        when(children.childContext(guardianId)).thenReturn(Optional.of(child));
        when(consentGate.check(guardianId)).thenReturn(gate(true));
        when(activities.findByIdempotencyKey(eq(guardianId), any())).thenReturn(Optional.empty());
        when(activities.findInProgressByGuardian(guardianId)).thenReturn(Optional.empty());
        when(deviceView.readiness(guardianId)).thenReturn(new DeviceViewAssembler.Readiness(deviceId, true, null));
        when(catalog.closing()).thenReturn(Optional.of(new ApprovedItem(UUID.randomUUID(), "끝 인사",
                "/media/approved/closing-v1.wav")));
        when(catalog.waitingFiller()).thenReturn(Optional.of(new ApprovedItem(UUID.randomUUID(), "잠깐",
                "/media/approved/waiting-filler-v1.wav")));
        when(activities.insertRequested(any())).thenReturn(true);
    }

    @Test
    void newKeyPassesAllChecksAndReturnsRequested() {
        when(realtime.start(any())).thenReturn(new StartResult(StartKind.ACCEPTED, SessionStatus.PREPARING, false));
        StartRequest request = new StartRequest(UUID.randomUUID(), new Context("놀이터 이야기", "FRIEND", "SEEN", "왜?"));

        Started started = service.start(guardianId, request);

        assertThat(started.status()).isEqualTo(SessionStatus.REQUESTED);
        verify(activities).insertRequested(org.mockito.ArgumentMatchers.argThat(row -> row.sessionId()
                .equals(started.sessionId()) && row.deviceId().equals(deviceId)
                && row.idempotencyKey().equals(request.idempotencyKey().toString())));
        verify(realtime).start(org.mockito.ArgumentMatchers.argThat(p -> !json.write(p).contains("놀이터")
                && !json.write(p).contains("왜?") && p.sessionId().equals(started.sessionId())));
        assertThat(events.capture().anyLineContains("놀이터")).isFalse();
    }

    @Test
    void rejectionOrderIsDeletionThenConsentThenInProgressThenDevice() {
        UUID leaving = UUID.randomUUID();
        Guardian deleting = mock(Guardian.class);
        when(deleting.isDeletionRequested()).thenReturn(true);
        when(guardians.findById(leaving)).thenReturn(Optional.of(deleting));
        assertThatThrownBy(() -> service.start(leaving, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(CoreApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS));

        when(consentGate.check(guardianId)).thenReturn(gate(false));
        when(deviceView.readiness(guardianId)).thenReturn(new DeviceViewAssembler.Readiness(null, false,
                ReasonCode.NO_DEVICE));
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(CoreApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONSENT_MISSING));
        verify(deviceView, never()).readiness(any());

        when(consentGate.check(guardianId)).thenReturn(gate(true));
        when(activities.findInProgressByGuardian(guardianId)).thenReturn(Optional.of(
                ActivityCopies.copy(UUID.randomUUID(), guardianId, SessionStatus.REQUESTED, 0, NOW)));
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(CoreApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_IN_PROGRESS));
        verify(realtime, never()).view(any(), anyLong());

        when(activities.findInProgressByGuardian(guardianId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(CoreApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.DEVICE_NOT_STABLE));
        verify(activities, never()).insertRequested(any());
        verifyNoInteractions(catalog);
    }

    @Test
    void sameKeyReplaysFirstResponseAndDifferentBodyConflicts() {
        UUID key = UUID.randomUUID();
        StartRequest request = new StartRequest(key, null);
        String hash = json.sha256OfCanonical(json.write(request));
        UUID sessionId = UUID.randomUUID();
        when(activities.findByIdempotencyKey(guardianId, key.toString())).thenReturn(Optional.of(
                ActivityCopies.keyed(sessionId, guardianId, key.toString(), hash, false, null, NOW)));
        when(consentGate.check(any())).thenReturn(gate(false));

        assertThat(service.start(guardianId, request).sessionId()).isEqualTo(sessionId);
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(key, new Context("다른 내용", null, null, null))))
                .isInstanceOfSatisfying(CoreApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        verify(realtime, never()).start(any());
        verify(activities, never()).insertRequested(any());
    }

    @ParameterizedTest(name = "로컬 FAILED {0} → {1}")
    @CsvSource({"503, REALTIME_UNAVAILABLE", "423, DEVICE_NOT_STABLE", "409, SESSION_IN_PROGRESS", "202, "})
    void localFailedKeyReplaysStoredStartResultCode(int code, ErrorCode expected) {
        UUID key = UUID.randomUUID();
        StartRequest request = new StartRequest(key, null);
        UUID sessionId = UUID.randomUUID();
        when(activities.findByIdempotencyKey(guardianId, key.toString())).thenReturn(Optional.of(ActivityCopies.keyed(
                sessionId, guardianId, key.toString(), json.sha256OfCanonical(json.write(request)), true, code, NOW)));

        if (expected == null) {
            assertThat(service.start(guardianId, request)).isEqualTo(new Started(sessionId, SessionStatus.REQUESTED));
        } else {
            assertThatThrownBy(() -> service.start(guardianId, request)).isInstanceOfSatisfying(CoreApiException.class,
                    e -> assertThat(e.code()).isEqualTo(expected));
        }
        verify(realtime, never()).start(any());
    }

    @Test
    void narrativeOver200CharactersIs400AndExactly200Passes() {
        when(realtime.start(any())).thenReturn(new StartResult(StartKind.ACCEPTED, SessionStatus.PREPARING, false));
        String twoHundred = "가".repeat(200);

        assertThat(service.start(guardianId, new StartRequest(UUID.randomUUID(), new Context(twoHundred, null, null,
                twoHundred))).status()).isEqualTo(SessionStatus.REQUESTED);
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(),
                new Context(null, null, null, twoHundred + "가")))).isInstanceOfSatisfying(CoreApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(),
                new Context(null, "PARK", null, null)))).isInstanceOf(CoreApiException.class);
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(null, null))).isInstanceOf(CoreApiException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleActiveCopyIsReconciledWithViewBeforeDeciding() {
        UUID oldSession = UUID.randomUUID();
        ActivityCopy stale = ActivityCopies.copy(oldSession, guardianId, SessionStatus.ACTIVE, 3, NOW.minusSeconds(600));
        when(activities.findInProgressByGuardian(guardianId)).thenReturn(Optional.of(stale), Optional.empty());
        when(realtime.view(oldSession, 0)).thenReturn(new ViewResult(ViewKind.OK, new InternalSessionView(oldSession, null,
                SessionStatus.ENDED, kr.mujoong.contract.status.EndReason.GUARDIAN_STOP, null, true, NOW, NOW, NOW.minusSeconds(5),
                5L, NOW, java.util.List.of(), 0L)));
        when(realtime.start(any())).thenReturn(new StartResult(StartKind.ACCEPTED, SessionStatus.PREPARING, false));

        assertThat(service.start(guardianId, new StartRequest(UUID.randomUUID(), null)).status())
                .isEqualTo(SessionStatus.REQUESTED);
        verify(activities).applyStateChange(new StateChange(oldSession, SessionStatus.ENDED, 5L,
                kr.mujoong.contract.status.EndReason.GUARDIAN_STOP, null, true, NOW.minusSeconds(5)), NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownToRealtimeClosesCopyLocallyButFailedViewKeeps409() {
        UUID ghost = UUID.randomUUID();
        ActivityCopy copy = ActivityCopies.copy(ghost, guardianId, SessionStatus.ACTIVE, 4, NOW.minusSeconds(900));
        when(activities.findInProgressByGuardian(guardianId)).thenReturn(Optional.of(copy), Optional.empty());
        when(realtime.view(ghost, 0)).thenReturn(new ViewResult(ViewKind.NOT_FOUND, null));
        when(activities.closeLocalFailed(ghost, SessionStatus.ACTIVE, 4, 202, NOW)).thenReturn(1);
        when(realtime.start(any())).thenReturn(new StartResult(StartKind.ACCEPTED, SessionStatus.PREPARING, false));

        service.start(guardianId, new StartRequest(UUID.randomUUID(), null));

        verify(activities).closeLocalFailed(ghost, SessionStatus.ACTIVE, 4, 202, NOW);
        assertThat(events.metrics().count("mujoong.session.ended", "origin", "core_local", "phase", "running"))
                .isEqualTo(1.0);

        when(activities.findInProgressByGuardian(guardianId)).thenReturn(Optional.of(copy));
        when(realtime.view(ghost, 0)).thenReturn(new ViewResult(ViewKind.UNAVAILABLE, null));
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(CoreApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_IN_PROGRESS));
    }

    @ParameterizedTest(name = "Realtime {0} → 로컬 FAILED {1}")
    @CsvSource({"DEVICE_NOT_CONNECTED, 423, DEVICE_NOT_STABLE", "CONFLICT, 409, SESSION_IN_PROGRESS",
        "UNAVAILABLE, 503, REALTIME_UNAVAILABLE"})
    void realtimeRefusalClosesCopyLocallyAndOnlyUnavailableCancels(StartKind kind, int code, ErrorCode expected) {
        when(realtime.start(any())).thenReturn(new StartResult(kind, null, false));
        when(activities.closeLocalFailed(any(), eq(SessionStatus.REQUESTED), eq(0L), eq(code), eq(NOW))).thenReturn(1);

        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(CoreApiException.class, e -> assertThat(e.code()).isEqualTo(expected));
        if (code == 503) {
            verify(realtime).cancel(any());
        } else {
            verify(realtime, never()).cancel(any());
        }
        assertThat(events.capture().events(EventCode.SESSION_TRANSITION)).hasSize(2);
    }

    /**
     * F3 재현(BR6.6 violation 끝 문장 · BR6.7 · functional-spec W5 6단계): 첫 시작이 시간 초과로 끝나고 같은 sessionId 재시도가 409를
     * 받으면(첫 시작이 Realtime에서 아직 INIT이라 곧 커밋돼 실제로 진행된다) 사본은 로컬 FAILED(409)로 닫되 CORE_ABANDONED 취소를 보낸다.
     * 취소가 없으면 사본이 종료 상태라 보호자 끝내기(BR6.9)도 Realtime에 가지 않아, 돌고 있는 세션을 아무도 끝낼 수 없다.
     */
    @Test
    void conflictAfterTimeoutRetryClosesTheCopyLocallyAndCancelsTheSession() throws Exception {
        InternalHttpClient http = mock(InternalHttpClient.class);
        when(http.send(eq(Target.SESSION_START), any()))
                .thenThrow(new CallException(Target.SESSION_START, Outcome.TIMEOUT, "HttpTimeoutException"))
                .thenReturn(new Response(409, "{\"code\":\"SESSION_IN_PROGRESS\",\"message\":\"x\"}"));
        when(http.send(eq(Target.SESSION_END), any())).thenReturn(new Response(202, "{}"));
        StartActivityService throughClient = serviceCalling(http);
        when(activities.closeLocalFailed(any(), eq(SessionStatus.REQUESTED), eq(0L), eq(409), eq(NOW))).thenReturn(1);

        assertThatThrownBy(() -> throughClient.start(guardianId, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(CoreApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_IN_PROGRESS));

        UUID sessionId = insertedSessionId();
        verify(http).send(eq(Target.SESSION_START), org.mockito.ArgumentMatchers.argThat(call -> call.attempt() == 2));
        verify(http).send(eq(Target.SESSION_END), org.mockito.ArgumentMatchers.argThat(call -> call.path()
                .equals("/internal/sessions/" + sessionId + "/end") && call.body().contains("\"reason\":\"CORE_ABANDONED\"")
                && call.body().contains("\"requestId\":\"" + sessionId + "\"")));
        verify(activities).closeLocalFailed(sessionId, SessionStatus.REQUESTED, 0, 409, NOW);
    }

    /** 재시도 없이 바로 온 409는 Realtime에 이 세션이 없다는 뜻이라 취소하지 않는다(BR6.6). 재시도 뒤 409라도 이벤트가 앞섰으면 202 · 취소 없음. */
    @Test
    @SuppressWarnings("unchecked")
    void conflictWithoutRetryAndRetriedConflictThatLostTheRaceDoNotCancel() throws Exception {
        InternalHttpClient http = mock(InternalHttpClient.class);
        when(http.send(eq(Target.SESSION_START), any()))
                .thenReturn(new Response(409, "{\"code\":\"SESSION_IN_PROGRESS\",\"message\":\"x\"}"))
                .thenThrow(new CallException(Target.SESSION_START, Outcome.TIMEOUT, "HttpTimeoutException"))
                .thenReturn(new Response(409, "{\"code\":\"SESSION_IN_PROGRESS\",\"message\":\"x\"}"));
        StartActivityService throughClient = serviceCalling(http);
        when(activities.closeLocalFailed(any(), eq(SessionStatus.REQUESTED), eq(0L), eq(409), eq(NOW))).thenReturn(1, 0);
        when(activities.findById(any())).thenAnswer(call -> Optional.of(ActivityCopies.copy(call.getArgument(0), guardianId,
                SessionStatus.PREPARING, 1, NOW)));

        assertThatThrownBy(() -> throughClient.start(guardianId, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(CoreApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_IN_PROGRESS));
        assertThat(throughClient.start(guardianId, new StartRequest(UUID.randomUUID(), null)).status())
                .as("재시도 뒤 409지만 PREPARING 이벤트가 사본을 앞섰다(0행) → 202").isEqualTo(SessionStatus.REQUESTED);

        verify(http, never()).send(eq(Target.SESSION_END), any());
    }

    private StartActivityService serviceCalling(InternalHttpClient http) {
        RealtimeSessionClient client = new RealtimeSessionClient(http,
                new SessionStartBreaker(clock, events.metrics(), 3, Duration.ofSeconds(5)), json, Duration.ofSeconds(3), 1,
                Duration.ofSeconds(2), Duration.ofSeconds(1));
        return new StartActivityService(
                new StartActivityService.Ports(guardians, children, activities, contexts, consentGate, deviceView, catalog),
                new SessionPackageBuilder(), client, mock(PlatformTransactionManager.class), json, events.logger(),
                events.metrics(), clock, Runnable::run);
    }

    /** ConsentGate 포트 응답(통과면 4항목 모두 GIVEN). */
    private static GateResult gate(boolean ok) {
        return new GateResult(ok, ok ? Status.GIVEN : Status.NOT_GIVEN, new Snapshot("1.0", ok, ok, ok, ok));
    }

    private UUID insertedSessionId() {
        ArgumentCaptor<NewActivity> inserted = ArgumentCaptor.forClass(NewActivity.class);
        verify(activities).insertRequested(inserted.capture());
        return inserted.getValue().sessionId();
    }

    @Test
    @SuppressWarnings("unchecked")
    void lostLocalFailRaceReturns202WithoutCancelAndInsertConflictReplaysWinner() {
        when(realtime.start(any())).thenReturn(new StartResult(StartKind.UNAVAILABLE, null, false));
        when(activities.closeLocalFailed(any(), any(), anyLong(), anyInt(), any())).thenReturn(0);
        when(activities.findById(any())).thenAnswer(call -> Optional.of(ActivityCopies.copy(call.getArgument(0), guardianId,
                SessionStatus.PREPARING, 1, NOW)));

        assertThat(service.start(guardianId, new StartRequest(UUID.randomUUID(), null)).status())
                .isEqualTo(SessionStatus.REQUESTED);
        verify(realtime, never()).cancel(any());

        UUID key = UUID.randomUUID();
        StartRequest request = new StartRequest(key, null);
        UUID winner = UUID.randomUUID();
        when(activities.insertRequested(any())).thenReturn(false);
        when(activities.findByIdempotencyKey(guardianId, key.toString())).thenReturn(Optional.empty(), Optional.of(
                ActivityCopies.keyed(winner, guardianId, key.toString(), json.sha256OfCanonical(json.write(request)), false,
                        null, NOW)));
        assertThat(service.start(guardianId, request).sessionId()).isEqualTo(winner);

        when(activities.findByIdempotencyKey(eq(guardianId), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.start(guardianId, new StartRequest(UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(CoreApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_IN_PROGRESS));
    }
}
