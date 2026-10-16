package kr.mujoong.core.activity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import kr.mujoong.core.activity.ApprovedContentCatalog.SpeechItem;
import kr.mujoong.core.activity.RealtimeSessionClient.InternalSessionView;
import kr.mujoong.core.activity.RealtimeSessionClient.StartResult;
import kr.mujoong.core.activity.RealtimeSessionClient.ViewResult;
import kr.mujoong.core.activity.SessionPackageBuilder.SessionPackage;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.child.ChildProfileService.ChildContext;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.consent.ConsentPolicyService.GateResult;
import kr.mujoong.core.device.DeviceViewAssembler;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자유대화 시작 (U1 3.5 Step 22 · BR6.1 ~ BR6.7 · BR6.17 · AC3.1.1 · AC3.1.3 · AC3.1.4 · AC3.1.6 · AC3.1.8 · NFR3.1 · OD-9, U4a가 계약 8절
 * 포트 · 08 배경 저장을 붙임 · AC3.1.2 · AC3.1.7 · AC2.3.7).
 *
 * <ol>
 *   <li>입력 검증(BR6.4): context 서술 칸 knownContent · question은 200자 이하, 고르기 칸은 계약 값만. 위반은 400. context는 Core에만
 *       저장하고(08 배경, 결과 구조화 입력) 세션 패키지 · Realtime에는 보내지 않는다(빌더에 넘길 인자가 없다, NFR3 · AC3.1.7).
 *   <li>⓪ 보호자가 없으면 401, 삭제 접수 상태면 403 ACCOUNT_DELETION_IN_PROGRESS.
 *   <li>⓪′ (보호자, 멱등 키) 사본이 있으면 ① ~ ③을 건너뛰고 BR6.2 재생: 요청 해시가 다르면 409 IDEMPOTENCY_CONFLICT, 로컬 FAILED면 저장된
 *       start_result_code(503 · 423 · 409 · 202) 재생, 그 밖은 첫 응답 202 {sessionId, REQUESTED}.
 *   <li>① 아이(ChildContext 포트, 없으면 403 CONSENT_MISSING) · 현재 정책 버전 4항목 동의(ConsentGate 포트 {@code ConsentPolicyService.check},
 *       403 CONSENT_MISSING). ② 진행 중 사본(409 SESSION_IN_PROGRESS): REQUESTED가 아니면 Realtime view로
 *       한 번 대조해 낡은 사본을 고친 뒤 다시 판정(계정 잠김 방지). ③ 기기 판정이 안정이 아니면 423 DEVICE_NOT_STABLE.
 *   <li>REQUESTED 사본(status_version 0)과 08 배경을 한 트랜잭션에 넣고(ON CONFLICT DO NOTHING — 같은 키 동시 요청은 승자 행을 재생, 진행
 *       중 하나 위반은 409) 세션 패키지(아이 문맥 · 종료 표현 스냅숏 · 동의 스냅숏 · 고정한 승인 문구)로 Realtime을 부른다. 202면 202 {sessionId, REQUESTED}. 423 → 로컬 FAILED(423) · DEVICE_NOT_STABLE, 409 → 로컬
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

    /**
     * 자유대화 패키지에 싣는 승인 문구(CLOSING · WAITING_FILLER 밖, U4a-J2): 시작 안내 · 재안내 · 대안 제안 · 공백 경고 · 종료 확인. 승인 · 음성
     * 있는 것만 실린다(AC6.3.7 문구 버전 고정). 연습 문구(INTRO_PRACTICE · HELP_OFFER)는 U5가 연습 패키지에 싣는다.
     */
    static final List<String> FREE_TALK_SESSION_CONTENT =
            List.of("INTRO_FREE_TALK", "RE_PROMPT", "DECLINE_ALTERNATIVE", "SILENCE_WARNING", "STOP_CONFIRM");

    /** 연습 패키지에 더 싣는 음성 있는 승인 문구(U5). HELP_OFFER는 U8부터 음성 없는 승인 발화라 아래 목록으로 옮겼다. */
    static final List<String> PRACTICE_SESSION_CONTENT = List.of("INTRO_PRACTICE");

    /**
     * 활동 유형별 음성 없는 승인 발화 허용 목록(U8 functional-spec 8.1 표 · BR1.2). 이 목록에 있고 종류가 APPROVED_SPEECH이며 APPROVED 판이
     * 있는 code만 approvedSpeech에 싣는다. 목록 밖 code(CONSENT_POLICY · POLICY_TERMS_* · DRAFT만 있는 code)는 싣지 않는다.
     */
    static final List<String> FREE_TALK_APPROVED_SPEECH =
            List.of(ApprovedContent.CODE_D_REST_ACK_START, ApprovedContent.CODE_D_REST_ACK);
    static final List<String> PRACTICE_APPROVED_SPEECH = List.of(ApprovedContent.CODE_HELP_OFFER,
            ApprovedContent.CODE_D_REST_ACK_START, ApprovedContent.CODE_D_REST_ACK, ApprovedContent.CODE_COACH_SWITCH,
            ApprovedContent.CODE_PEER_RETURN);

    private final GuardianRepository guardians;
    private final ChildProfileService childProfiles;
    private final ActivityCopyRepository activities;
    private final ActivityContextRepository contexts;
    private final ConsentPolicyService consentPolicy;
    private final DeviceViewAssembler deviceView;
    private final ApprovedContentCatalog catalog;
    private final SessionPackageBuilder packageBuilder;
    private final RealtimeSessionClient realtime;
    private final TransactionTemplate transactions;
    private final JsonCodec json;
    private final EventLogger events;
    private final CoreMetrics metrics;
    private final MujoongClock clock;
    private final Executor cancelExecutor;
    private final ExecutorService ownedExecutor;
    private kr.mujoong.core.identity.GuardianWriteLock practiceGuardianLock;

    @Autowired(required=false)
    void practiceGuardianLock(kr.mujoong.core.identity.GuardianWriteLock value) {
        this.practiceGuardianLock=value;
    }

    /** 시작이 쓰는 저장소 · 포트 묶음(생성자 인자를 줄인다). */
    public record Ports(
            GuardianRepository guardians,
            ChildProfileService childProfiles,
            ActivityCopyRepository activities,
            ActivityContextRepository contexts,
            ConsentPolicyService consentPolicy,
            DeviceViewAssembler deviceView,
            ApprovedContentCatalog catalog) {

        public Ports {
            Objects.requireNonNull(guardians, "guardians");
            Objects.requireNonNull(childProfiles, "childProfiles");
            Objects.requireNonNull(activities, "activities");
            Objects.requireNonNull(contexts, "contexts");
            Objects.requireNonNull(consentPolicy, "consentPolicy");
            Objects.requireNonNull(deviceView, "deviceView");
            Objects.requireNonNull(catalog, "catalog");
        }
    }

    @Autowired
    public StartActivityService(
            GuardianRepository guardians,
            ChildProfileService childProfiles,
            ActivityCopyRepository activities,
            ActivityContextRepository contexts,
            ConsentPolicyService consentPolicy,
            DeviceViewAssembler deviceView,
            ApprovedContentCatalog catalog,
            SessionPackageBuilder packageBuilder,
            RealtimeSessionClient realtime,
            PlatformTransactionManager transactionManager,
            JsonCodec json,
            EventLogger events,
            CoreMetrics metrics,
            MujoongClock clock) {
        this(new Ports(guardians, childProfiles, activities, contexts, consentPolicy, deviceView, catalog), packageBuilder,
                realtime, transactionManager, json, events, metrics, clock, null);
    }

    /**
     * @param cancelExecutor 취소 호출을 돌릴 실행기(시험은 호출 스레드 실행기). null이면 가상 스레드 실행기를 만든다
     */
    public StartActivityService(
            Ports ports,
            SessionPackageBuilder packageBuilder,
            RealtimeSessionClient realtime,
            PlatformTransactionManager transactionManager,
            JsonCodec json,
            EventLogger events,
            CoreMetrics metrics,
            MujoongClock clock,
            Executor cancelExecutor) {
        Objects.requireNonNull(ports, "ports");
        this.guardians = ports.guardians();
        this.childProfiles = ports.childProfiles();
        this.activities = ports.activities();
        this.contexts = ports.contexts();
        this.consentPolicy = ports.consentPolicy();
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.deviceView = ports.deviceView();
        this.catalog = ports.catalog();
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
        return start(guardianId,request,UUID.randomUUID(),ActivityType.FREE_TALK,
                json.sha256OfCanonical(json.write(request)),null);
    }

    public Started startPractice(UUID guardianId, UUID key, UUID sessionId, String requestHash,
            Map<String,Object> practice) {
        return start(guardianId,new StartRequest(key,null),sessionId,ActivityType.PRACTICE,requestHash,practice);
    }

    /** 준비가 외부 호출을 하기 전에 적용하는 시작 문턱. 최종 시작에서도 같은 검사를 다시 한다. */
    public void checkPracticeReadiness(UUID guardianId) {
        Guardian guardian=guardians.findById(guardianId).orElseThrow(()->CoreApiException.of(ErrorCode.UNAUTHENTICATED));
        if(guardian.isDeletionRequested()) throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        if(childProfiles.childContext(guardianId).isEmpty() || !consentPolicy.check(guardianId).ok())
            throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
        rejectIfInProgress(guardianId);
        if(!deviceView.readiness(guardianId).canStart()) throw CoreApiException.of(ErrorCode.DEVICE_NOT_STABLE);
    }

    public Optional<Started> replayPractice(UUID guardianId, UUID key, String hash) {
        return activities.findByIdempotencyKey(guardianId,key.toString()).map(copy->replay(copy,hash));
    }

    public boolean isActivePractice(UUID guardianId, UUID childId, UUID sessionId) {
        return activities.findOwned(sessionId,guardianId).filter(c->c.childId().equals(childId)
                && c.activityType()==ActivityType.PRACTICE && c.status()==SessionStatus.ACTIVE).isPresent();
    }

    private Started start(UUID guardianId, StartRequest request, UUID sessionId, ActivityType activityType,
            String requestHash, Map<String,Object> practice) {
        Guardian guardian = guardians.findById(Objects.requireNonNull(guardianId, "guardianId"))
                .orElseThrow(() -> CoreApiException.of(ErrorCode.UNAUTHENTICATED));
        if (guardian.isDeletionRequested()) {
            throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        }
        String key = request.idempotencyKey().toString();
        Optional<ActivityCopy> existing = activities.findByIdempotencyKey(guardianId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        Instant now = clock.now();
        ChildContext child = childProfiles.childContext(guardianId)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.CONSENT_MISSING));
        GateResult consent = consentPolicy.check(guardianId);
        if (!consent.ok()) {
            throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
        }
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

        Map<String, ApprovedItem> approved = new LinkedHashMap<>();
        approved.put(ApprovedContent.CODE_CLOSING, closing);
        approved.put(ApprovedContent.CODE_WAITING_FILLER, waitingFiller);
        catalog.sessionContent(FREE_TALK_SESSION_CONTENT).forEach(approved::putIfAbsent);
        if(activityType==ActivityType.PRACTICE) {
            approved.remove("INTRO_FREE_TALK");
            catalog.sessionContent(PRACTICE_SESSION_CONTENT).forEach(approved::putIfAbsent);
        }
        Map<String, SpeechItem> speech = approvedSpeech(activityType, approved);

        SessionPackage sessionPackage = packageBuilder.build(sessionId, request.idempotencyKey(), guardianId,
                readiness.deviceId(), child, consent.snapshot(), approved);
        if(activityType==ActivityType.PRACTICE) sessionPackage=sessionPackage.forPractice();
        // 사본 행과 08 배경을 한 트랜잭션에 쓴다(배경은 이긴 요청만 쓴다, AC3.1.2 · AC3.1.8 · U4a-J1)
        Boolean insertedResult = transactions.execute(status -> {
            if(activityType==ActivityType.PRACTICE) {
                Objects.requireNonNull(practiceGuardianLock,"practice guardian lock").lockActive(guardianId);
                if(!consentPolicy.check(guardianId).ok()) throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
            }
            boolean wrote = activities.insertRequested(new NewActivity(sessionId, guardianId, child.childId(),
                    readiness.deviceId(), activityType, key, requestHash, now));
            if (wrote) {
                contexts.insert(sessionId, guardianId, child.childId(), request.context(), now);
            }
            return wrote;
        });
        boolean inserted = Boolean.TRUE.equals(insertedResult);
        if (!inserted) {
            // 같은 키 동시 요청의 패자는 승자 행을 재생하고, 진행 중 하나 위반이면 409(BR6.2 · BR6.3)
            return activities.findByIdempotencyKey(guardianId, key)
                    .map(winner -> replay(winner, requestHash))
                    .orElseThrow(() -> CoreApiException.of(ErrorCode.SESSION_IN_PROGRESS));
        }
        logTransition(sessionId, "NONE", SessionStatus.REQUESTED, 0, false);

        // 승인 발화가 없으면 옛 본문 그대로 보낸다(더하기만, NFR6.1 · 편차 U8-b)
        StartResult result;
        if (activityType == ActivityType.PRACTICE) {
            result = speech.isEmpty() ? realtime.startPractice(sessionPackage, practice)
                    : realtime.startPractice(sessionPackage, practice, speech);
        } else {
            result = speech.isEmpty() ? realtime.start(sessionPackage) : realtime.start(sessionPackage, speech);
        }
        return switch (result.kind()) {
            case ACCEPTED -> new Started(sessionId, SessionStatus.REQUESTED);
            case DEVICE_NOT_CONNECTED -> finishLocally(sessionId, 423, false);
            // 시간 초과 재시도 뒤의 409는 첫 시작이 Realtime에서 아직 INIT일 수 있다 — BR6.7처럼 취소한다(BR6.6 끝 문장, J320)
            case CONFLICT -> finishLocally(sessionId, 409, result.afterTimeoutRetry());
            case UNAVAILABLE -> finishLocally(sessionId, 503, true);
        };
    }

    /**
     * 활동 유형의 허용 목록으로 음성 없는 승인 발화를 고른다(U8 · BR1.2 · BR1.3). 한 code는 두 맵 중 하나에만 들어간다 — approvedContent에 이미
     * 있는 code는 approvedSpeech에서 뺀다(음성 있는 판이 먼저, 기존 기기 계약 그대로).
     */
    Map<String, SpeechItem> approvedSpeech(ActivityType activityType, Map<String, ApprovedItem> approved) {
        List<String> allowed = activityType == ActivityType.PRACTICE ? PRACTICE_APPROVED_SPEECH : FREE_TALK_APPROVED_SPEECH;
        Map<String, SpeechItem> speech = new LinkedHashMap<>(catalog.approvedSpeech(allowed));
        speech.keySet().removeIf(approved::containsKey);
        speech.keySet().retainAll(allowed);
        return speech;
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
