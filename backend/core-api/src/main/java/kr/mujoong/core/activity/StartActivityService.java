package kr.mujoong.core.activity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.ActivityCopyRepository.NewActivity;
import kr.mujoong.core.activity.ActivityCopyRepository.StateChange;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.activity.RealtimeSessionClient.InternalSessionView;
import kr.mujoong.core.activity.RealtimeSessionClient.StartResult;
import kr.mujoong.core.activity.RealtimeSessionClient.ViewResult;
import kr.mujoong.core.activity.SessionPackageBuilder.SessionPackage;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.device.DeviceViewAssembler;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 자유대화 시작 (U1 3.5 Step 22 · BR6.1 ~ BR6.7 · BR6.17 · AC3.1.1 · AC3.1.3 · AC3.1.4 · AC3.1.6 · AC3.1.8 · NFR3.1 · OD-9).
 *
 * <ol>
 *   <li>입력 검증(BR6.4): context 서술 칸 knownContent · question은 200자 이하, 고르기 칸은 계약 값만. 위반은 400. context는 검증만 하고
 *       저장하지도 Realtime에 보내지도 않는다(요청 해시의 입력일 뿐).
 *   <li>⓪ 보호자가 없으면 401, 삭제 접수 상태면 403 ACCOUNT_DELETION_IN_PROGRESS.
 *   <li>⓪′ (보호자, 멱등 키) 사본이 있으면 ① ~ ③을 건너뛰고 BR6.2 재생: 요청 해시가 다르면 409 IDEMPOTENCY_CONFLICT, 로컬 FAILED면 저장된
 *       start_result_code(503 · 423 · 409 · 202) 재생, 그 밖은 첫 응답 202 {sessionId, REQUESTED}.
 *   <li>① 현재 정책 버전 4항목 동의(403 CONSENT_MISSING). ② 진행 중 사본(409 SESSION_IN_PROGRESS): REQUESTED가 아니면 Realtime view로
 *       한 번 대조해 낡은 사본을 고친 뒤 다시 판정(계정 잠김 방지). ③ 기기 판정이 안정이 아니면 423 DEVICE_NOT_STABLE.
 *   <li>REQUESTED 사본(status_version 0)을 넣고(ON CONFLICT DO NOTHING — 같은 키 동시 요청은 승자 행을 재생, 진행 중 하나 위반은 409)
 *       세션 패키지로 Realtime을 부른다. 202면 202 {sessionId, REQUESTED}. 423 → 로컬 FAILED(423) · DEVICE_NOT_STABLE, 409 → 로컬
 *       FAILED(409) · SESSION_IN_PROGRESS, 확정 실패 · 재시도 뒤 시간 초과 · 차단기 열림 → 로컬 FAILED(503) · REALTIME_UNAVAILABLE + 취소
 *       (CORE_ABANDONED, 응답 뒤 별도 가상 스레드). 시간 초과 재시도 뒤의 409도 로컬 FAILED(409)에 취소를 더한다 — 첫 시작이 Realtime에서
 *       아직 INIT이면 곧 커밋돼 진행되는데 사본이 종료 상태라 보호자 끝내기가 Realtime에 가지 않기 때문이다(BR6.6 끝 문장 · J320). 로컬
 *       FAILED 조건부 UPDATE가 0행이면(Realtime 이벤트가 사본을 앞섬) 취소 없이 202다.
 * </ol>
 */
@Service
public class StartActivityService implements DisposableBean {

    /** 서술 칸 최대 길이(BR6.4 · 편차 (d)). */
    public static final int NARRATIVE_MAX = 200;

    private static final Set<String> SITUATION_TYPES = Set.of("FRIEND", "SCHOOL", "FAMILY", "ACADEMY", "OTHER");
    private static final Set<String> SOURCE_TYPES = Set.of("SEEN", "HEARD_FROM_CHILD");

    private final GuardianRepository guardians;
    private final ChildRepository children;
    private final ActivityCopyRepository activities;
    private final ConsentGate consentGate;
    private final DeviceViewAssembler deviceView;
    private final ApprovedContentCatalog catalog;
    private final SessionPackageBuilder packageBuilder;
    private final RealtimeSessionClient realtime;
    private final JsonCodec json;
    private final EventLogger events;
    private final CoreMetrics metrics;
    private final MujoongClock clock;
    private final Executor cancelExecutor;
    private final ExecutorService ownedExecutor;

    @Autowired
    public StartActivityService(
            GuardianRepository guardians,
            ChildRepository children,
            ActivityCopyRepository activities,
            ConsentGate consentGate,
            DeviceViewAssembler deviceView,
            ApprovedContentCatalog catalog,
            SessionPackageBuilder packageBuilder,
            RealtimeSessionClient realtime,
            JsonCodec json,
            EventLogger events,
            CoreMetrics metrics,
            MujoongClock clock) {
        this(guardians, children, activities, consentGate, deviceView, catalog, packageBuilder, realtime, json, events,
                metrics, clock, null);
    }

    /**
     * @param cancelExecutor 취소 호출을 돌릴 실행기(시험은 호출 스레드 실행기). null이면 가상 스레드 실행기를 만든다
     */
    public StartActivityService(
            GuardianRepository guardians,
            ChildRepository children,
            ActivityCopyRepository activities,
            ConsentGate consentGate,
            DeviceViewAssembler deviceView,
            ApprovedContentCatalog catalog,
            SessionPackageBuilder packageBuilder,
            RealtimeSessionClient realtime,
            JsonCodec json,
            EventLogger events,
            CoreMetrics metrics,
            MujoongClock clock,
            Executor cancelExecutor) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.children = Objects.requireNonNull(children, "children");
        this.activities = Objects.requireNonNull(activities, "activities");
        this.consentGate = Objects.requireNonNull(consentGate, "consentGate");
        this.deviceView = Objects.requireNonNull(deviceView, "deviceView");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.packageBuilder = Objects.requireNonNull(packageBuilder, "packageBuilder");
        this.realtime = Objects.requireNonNull(realtime, "realtime");
        this.json = Objects.requireNonNull(json, "json");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (cancelExecutor == null) {
            this.ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.cancelExecutor = ownedExecutor;
        } else {
            this.ownedExecutor = null;
            this.cancelExecutor = cancelExecutor;
        }
    }

    /**
     * 시작 요청 하나를 처리한다.
     *
     * @return 202 본문({@code status}는 늘 REQUESTED)
     * @throws CoreApiException 거절(400 · 401 · 403 · 409 · 423 · 503)
     */
    public Started start(UUID guardianId, StartRequest request) {
        validate(request);
        Guardian guardian = guardians.findById(Objects.requireNonNull(guardianId, "guardianId"))
                .orElseThrow(() -> CoreApiException.of(ErrorCode.UNAUTHENTICATED));
        if (guardian.isDeletionRequested()) {
            throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        }
        String key = request.idempotencyKey().toString();
        String requestHash = json.sha256OfCanonical(json.write(request));
        Optional<ActivityCopy> existing = activities.findByIdempotencyKey(guardianId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        Instant now = clock.now();
        Child child = children.findByGuardianId(guardianId)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.CONSENT_MISSING));
        String policyVersion = consentGate.check(child.getId(), now)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.CONSENT_MISSING));
        try {
            rejectIfInProgress(guardianId);
        } catch (CoreApiException inProgress) {
            // 같은 키 동시 요청: 막는 사본이 바로 이 키의 승자 행이면 409가 아니라 재생이다(BR6.2 · BR6.3)
            Optional<ActivityCopy> sameKey = activities.findByIdempotencyKey(guardianId, key);
            if (sameKey.isPresent()) {
                return replay(sameKey.get(), requestHash);
            }
            throw inProgress;
        }
        DeviceViewAssembler.Readiness readiness = deviceView.readiness(guardianId);
        if (!readiness.canStart()) {
            throw CoreApiException.of(ErrorCode.DEVICE_NOT_STABLE);
        }
        ApprovedItem closing = catalog.closing()
                .orElseThrow(() -> new IllegalStateException("승인 문구 CLOSING이 없다(시드 확인)"));
        ApprovedItem waitingFiller = catalog.waitingFiller()
                .orElseThrow(() -> new IllegalStateException("승인 문구 WAITING_FILLER가 없다(시드 확인)"));

        UUID sessionId = UUID.randomUUID();
        SessionPackage sessionPackage = packageBuilder.build(sessionId, request.idempotencyKey(), guardianId, child,
                readiness.deviceId(), policyVersion, closing, waitingFiller);
        boolean inserted = activities.insertRequested(new NewActivity(sessionId, guardianId, child.getId(),
                readiness.deviceId(), ActivityType.FREE_TALK, key, requestHash, now));
        if (!inserted) {
            // 같은 키 동시 요청의 패자는 승자 행을 재생하고, 진행 중 하나 위반이면 409(BR6.2 · BR6.3)
            return activities.findByIdempotencyKey(guardianId, key)
                    .map(winner -> replay(winner, requestHash))
                    .orElseThrow(() -> CoreApiException.of(ErrorCode.SESSION_IN_PROGRESS));
        }
        logTransition(sessionId, "NONE", SessionStatus.REQUESTED, 0, false);

        StartResult result = realtime.start(sessionPackage);
        return switch (result.kind()) {
            case ACCEPTED -> new Started(sessionId, SessionStatus.REQUESTED);
            case DEVICE_NOT_CONNECTED -> finishLocally(sessionId, 423, false);
            // 시간 초과 재시도 뒤의 409는 첫 시작이 Realtime에서 아직 INIT일 수 있다 — BR6.7처럼 취소한다(BR6.6 끝 문장, J320)
            case CONFLICT -> finishLocally(sessionId, 409, result.afterTimeoutRetry());
            case UNAVAILABLE -> finishLocally(sessionId, 503, true);
        };
    }

    /** BR6.2 재생. */
    private Started replay(ActivityCopy copy, String requestHash) {
        if (!copy.requestHash().equals(requestHash)) {
            throw CoreApiException.of(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (copy.localFailed() && copy.startResultCode() != null && copy.startResultCode() != 202) {
            throw CoreApiException.of(errorFor(copy.startResultCode()));
        }
        return new Started(copy.sessionId(), SessionStatus.REQUESTED);
    }

    /**
     * BR6.1 ②: 진행 중 사본이 있으면 409. REQUESTED가 아닌 사본은 Realtime view로 한 번 대조해 낡은 사본을 고친 뒤 다시 판정한다.
     */
    private void rejectIfInProgress(UUID guardianId) {
        Optional<ActivityCopy> blocking = activities.findInProgressByGuardian(guardianId);
        if (blocking.isEmpty()) {
            return;
        }
        ActivityCopy copy = blocking.get();
        if (copy.status() == SessionStatus.REQUESTED) {
            throw CoreApiException.of(ErrorCode.SESSION_IN_PROGRESS);
        }
        ViewResult view = realtime.view(copy.sessionId(), 0);
        Instant now = clock.now();
        switch (view.kind()) {
            case OK -> reconcile(copy, view.view(), now);
            case NOT_FOUND -> {
                if (activities.closeLocalFailed(copy.sessionId(), copy.status(), copy.statusVersion(), 202, now) == 1) {
                    recordLocalFailure(events, metrics, copy.sessionId(), copy.status(), copy.statusVersion());
                }
            }
            case UNAVAILABLE -> throw CoreApiException.of(ErrorCode.SESSION_IN_PROGRESS);
        }
        if (activities.findInProgressByGuardian(guardianId).isPresent()) {
            throw CoreApiException.of(ErrorCode.SESSION_IN_PROGRESS);
        }
    }

    /** view 결과를 BR5.10 조건부 UPDATE로 사본에 반영한다(BR6.17 예외: 대조 결과 반영). */
    private void reconcile(ActivityCopy copy, InternalSessionView view, Instant now) {
        SessionStatus status = view.status();
        if (status == SessionStatus.UNRECOGNIZED || status == SessionStatus.REQUESTED || view.statusVersion() < 1
                || view.statusVersion() <= copy.statusVersion()) {
            return;
        }
        Instant at = status.isTerminal() && view.endedAt() != null
                ? view.endedAt()
                : (status == SessionStatus.ACTIVE && view.startedAt() != null ? view.startedAt() : now);
        activities.applyStateChange(new StateChange(copy.sessionId(), status, view.statusVersion(),
                view.endReason() == kr.mujoong.contract.status.EndReason.UNRECOGNIZED ? null : view.endReason(),
                view.failureCode() == kr.mujoong.contract.status.FailureCode.UNRECOGNIZED ? null : view.failureCode(),
                Boolean.TRUE.equals(view.confirmed()), at), now);
    }

    /**
     * 로컬 FAILED로 닫고 코드대로 답한다. 0행이면(Realtime 이벤트가 앞섬) 취소 없이 지금 행을 재생한다(BR6.7).
     *
     * @param cancel 로컬 FAILED를 이겼을 때 CORE_ABANDONED 취소를 보내는가(503 · 시간 초과 재시도 뒤 409, BR6.6 · BR6.7)
     */
    private Started finishLocally(UUID sessionId, int startResultCode, boolean cancel) {
        Instant now = clock.now();
        if (activities.closeLocalFailed(sessionId, SessionStatus.REQUESTED, 0, startResultCode, now) == 1) {
            recordLocalFailure(events, metrics, sessionId, SessionStatus.REQUESTED, 0);
            if (cancel) {
                scheduleCancel(sessionId);
            }
            throw CoreApiException.of(errorFor(startResultCode));
        }
        ActivityCopy current = activities.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("방금 넣은 사본이 없다"));
        if (current.localFailed() && current.startResultCode() != null && current.startResultCode() != 202) {
            throw CoreApiException.of(errorFor(current.startResultCode()));
        }
        return new Started(sessionId, SessionStatus.REQUESTED);
    }

    private void scheduleCancel(UUID sessionId) {
        try {
            cancelExecutor.execute(() -> {
                try {
                    realtime.cancel(sessionId);
                } catch (RuntimeException e) {
                    events.appError(EventLog.Component.ACTIVITY_COORDINATOR, e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 종료 중: BR6.14 재취소가 보완한다
            events.appError(EventLog.Component.ACTIVITY_COORDINATOR, e);
        }
    }

    static ErrorCode errorFor(int startResultCode) {
        return switch (startResultCode) {
            case 423 -> ErrorCode.DEVICE_NOT_STABLE;
            case 409 -> ErrorCode.SESSION_IN_PROGRESS;
            case 503 -> ErrorCode.REALTIME_UNAVAILABLE;
            default -> throw new IllegalArgumentException("재생할 수 없는 start_result_code: " + startResultCode);
        };
    }

    /**
     * Core 로컬 FAILED의 SESSION_TRANSITION 한 줄과 {@code mujoong_session_ended_total{origin=core_local}}(OD-9). 전이를 이긴 쪽만 부른다.
     */
    static void recordLocalFailure(
            EventLogger events, CoreMetrics metrics, UUID sessionId, SessionStatus from, long statusVersion) {
        String phase = from == SessionStatus.ACTIVE || from == SessionStatus.CLOSING ? "RUNNING" : "START";
        metrics.sessionEnded(SessionStatus.FAILED.name(), "FAILED", null, phase, "CORE_LOCAL");
        events.log(EventCode.SESSION_TRANSITION, EventLog.Component.ACTIVITY_COORDINATOR, sessionId, entry -> entry
                .attr(AttrKey.FROM, from.name())
                .attr(AttrKey.TO, SessionStatus.FAILED.name())
                .attr(AttrKey.STATUS_VERSION, statusVersion)
                .attr(AttrKey.END_REASON, "FAILED")
                .attr(AttrKey.CONFIRMED, false)
                .attr(AttrKey.PHASE, phase)
                .attr(AttrKey.ORIGIN, "CORE_LOCAL"));
    }

    private void logTransition(UUID sessionId, String from, SessionStatus to, long statusVersion, boolean confirmed) {
        events.log(EventCode.SESSION_TRANSITION, EventLog.Component.ACTIVITY_COORDINATOR, sessionId, entry -> entry
                .attr(AttrKey.FROM, from)
                .attr(AttrKey.TO, to.name())
                .attr(AttrKey.STATUS_VERSION, statusVersion)
                .attr(AttrKey.CONFIRMED, confirmed)
                .attr(AttrKey.PHASE, "START")
                .attr(AttrKey.ORIGIN, "CORE_LOCAL"));
    }

    private static void validate(StartRequest request) {
        if (request == null || request.idempotencyKey() == null) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        Context context = request.context();
        if (context == null) {
            return;
        }
        if (tooLong(context.knownContent()) || tooLong(context.question())
                || (context.situationType() != null && !SITUATION_TYPES.contains(context.situationType()))
                || (context.sourceType() != null && !SOURCE_TYPES.contains(context.sourceType()))) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static boolean tooLong(String value) {
        return value != null && value.codePointCount(0, value.length()) > NARRATIVE_MAX;
    }

    @Override
    public void destroy() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    /** {@code POST /api/activities/free} 본문(activity-free-request.schema.json). */
    public record StartRequest(UUID idempotencyKey, Context context) {
    }

    /** 08 입력 네 칸(모두 선택). 검증만 하고 저장 · 전송하지 않는다(NFR3.1). */
    public record Context(String knownContent, String situationType, String sourceType, String question) {

        /** 원문은 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "Context[***]";
        }
    }

    /** 202 본문. */
    public record Started(UUID sessionId, SessionStatus status) {
    }
}
