package kr.mujoong.core.activity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.RealtimeSessionClient.InternalSessionView;
import kr.mujoong.core.activity.RealtimeSessionClient.ViewResult;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.common.scaling.SingleInstanceAssumption;
import kr.mujoong.core.observability.CoreMetrics;
import org.springframework.stereotype.Service;

/**
 * 12 진행 조회 (U1 3.5 Step 22 · BR6.15 · RD-6 · NFR4.3 · AC3.3.x · OD-5 · J120).
 *
 * <p>남의 세션이면 404. Realtime {@code GET /internal/sessions/{id}/view?sinceSeq}(제한 1초, 재시도 · 차단기 없음)를 중계한다. 실패하면 Core
 * 사본으로 답하고 items는 비우고 nextSeq는 요청 값으로 두며 {@code mujoong_session_view_fallback_total}을 올린다. observedAt은 Core가
 * Realtime에서 마지막으로 받은 시각(조회 성공 수신 시각과 사본 갱신 시각 중 늦은 값, 메모리 허용)이고, 종료 아닌 세션에서
 * now − observedAt이 {@code timing.core.device-status-stale}(30초)을 넘으면 stale이다(조회 한 번 실패만으로는 stale 아님, 한 번도 받지
 * 못했으면 requested_at 기준). gaps · technicalPause · silenceWarning · practice는 Realtime 값을 계약 모양으로 걸러 싣는다(U4a, 값의 생산은
 * U3 · U4b · U5). 판정값 · 08 입력 원문은 응답에 없다.
 */
@Service
@SingleInstanceAssumption(si = "SI-8", reason = "조회 성공 시각(observedAt)이 인스턴스마다 달라 같은 세션의 stale 판정이 흔들린다")
public class SessionViewService {

    /** 기억하는 세션 수 상한(오래된 것부터 버린다). */
    static final int MAX_TRACKED_SESSIONS = 10_000;

    private final ActivityCopyRepository activities;
    private final RealtimeSessionClient realtime;
    private final StaleEvaluator staleEvaluator;
    private final CoreMetrics metrics;
    private final MujoongClock clock;
    private final Map<UUID, Instant> lastObserved = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Instant> eldest) {
            return size() > MAX_TRACKED_SESSIONS;
        }
    };

    public SessionViewService(
            ActivityCopyRepository activities,
            RealtimeSessionClient realtime,
            StaleEvaluator staleEvaluator,
            CoreMetrics metrics,
            MujoongClock clock) {
        this.activities = Objects.requireNonNull(activities, "activities");
        this.realtime = Objects.requireNonNull(realtime, "realtime");
        this.staleEvaluator = Objects.requireNonNull(staleEvaluator, "staleEvaluator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 진행 상태를 읽는다.
     *
     * @param sinceSeq 0 이상(12가 받은 마지막 nextSeq)
     * @throws CoreApiException 404(남의 세션 · 없는 세션) · 400(sinceSeq 음수)
     */
    public LiveView view(UUID guardianId, UUID sessionId, long sinceSeq) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(sessionId, "sessionId");
        if (sinceSeq < 0) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        ActivityCopy copy = activities.findOwned(sessionId, guardianId)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        ViewResult result = realtime.view(sessionId, sinceSeq);
        Instant now = clock.now();
        if (result.kind() == RealtimeSessionClient.ViewKind.OK) {
            remember(sessionId, now);
            return fromRealtime(copy, result.view(), now);
        }
        metrics.sessionViewFallback();
        return fromCopy(copy, sinceSeq, now);
    }

    private LiveView fromRealtime(ActivityCopy copy, InternalSessionView view, Instant now) {
        Instant observedAt = later(now, copy.observedAt());
        SessionStatus status = view.status();
        List<Map<String, Object>> items = view.items() == null ? List.of() : view.items();
        long nextSeq = view.nextSeq() == null ? 0L : view.nextSeq();
        return new LiveView(copy.sessionId(),
                view.activityType() == null ? copy.activityType() : view.activityType(),
                status,
                known(view.endReason()),
                known(view.failureCode()),
                Boolean.TRUE.equals(view.confirmed()),
                view.requestedAt() == null ? copy.requestedAt() : view.requestedAt(),
                view.startedAt(),
                view.endedAt(),
                view.statusVersion(),
                now,
                observedAt,
                staleEvaluator.isSessionStale(observedAt, copy.requestedAt(), status.isTerminal(), now),
                LiveExtras.gaps(view.gaps()),
                LiveExtras.technicalPause(view.technicalPause()),
                LiveExtras.silenceWarning(view.silenceWarning()),
                LiveExtras.practice(view.practice()),
                items,
                nextSeq,
                LiveExtras.guardrail(view.guardrail()));
    }

    private LiveView fromCopy(ActivityCopy copy, long sinceSeq, Instant now) {
        Instant observedAt = later(recalled(copy.sessionId()), copy.observedAt());
        return new LiveView(copy.sessionId(),
                copy.activityType(),
                copy.status(),
                parse(EndReason.class, copy.endReason()),
                parse(FailureCode.class, copy.failureCode()),
                copy.confirmed(),
                copy.requestedAt(),
                copy.startedAt(),
                copy.endedAt(),
                copy.statusVersion(),
                now,
                observedAt,
                staleEvaluator.isSessionStale(observedAt, copy.requestedAt(), copy.status().isTerminal(), now),
                List.of(),
                null,
                null,
                null,
                List.of(),
                sinceSeq);
    }

    private synchronized void remember(UUID sessionId, Instant at) {
        lastObserved.put(sessionId, at);
    }

    private synchronized Instant recalled(UUID sessionId) {
        return lastObserved.get(sessionId);
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    /** 사본 열 문자열을 enum으로 읽는다. 없거나 모르는 값은 null('알 수 없음'은 응답에 싣지 않는다). */
    public static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            E parsed = Enum.valueOf(type, value);
            return "UNRECOGNIZED".equals(parsed.name()) ? null : parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static EndReason known(EndReason value) {
        return value == EndReason.UNRECOGNIZED ? null : value;
    }

    private static FailureCode known(FailureCode value) {
        return value == FailureCode.UNRECOGNIZED ? null : value;
    }

    /**
     * 보호자 LiveView(live-view.schema.json). items는 Realtime 응답을 그대로 싣는다. gaps · technicalPause · silenceWarning · practice는
     * Realtime 값을 {@link LiveExtras}로 거른 것이고, Realtime에 닿지 못하면 빈 목록 · null이다(U4a · AC3.2.17).
     *
     * @param observedAt 한 번도 받지 못했으면 null
     */
    public record LiveView(
            UUID sessionId,
            ActivityType activityType,
            SessionStatus status,
            EndReason endReason,
            FailureCode failureCode,
            boolean confirmed,
            Instant requestedAt,
            Instant startedAt,
            Instant endedAt,
            long statusVersion,
            Instant serverTime,
            Instant observedAt,
            boolean stale,
            List<LiveExtras.Gap> gaps,
            LiveExtras.TechnicalPause technicalPause,
            LiveExtras.SilenceWarning silenceWarning,
            LiveExtras.Practice practice,
            List<Map<String, Object>> items,
            long nextSeq,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            kr.mujoong.contract.guardrail.GuardrailPresentation guardrail) {

        public LiveView(UUID sessionId, ActivityType activityType, SessionStatus status, EndReason endReason,
                FailureCode failureCode, boolean confirmed, Instant requestedAt, Instant startedAt, Instant endedAt,
                long statusVersion, Instant serverTime, Instant observedAt, boolean stale, List<LiveExtras.Gap> gaps,
                LiveExtras.TechnicalPause technicalPause, LiveExtras.SilenceWarning silenceWarning,
                LiveExtras.Practice practice, List<Map<String, Object>> items, long nextSeq) {
            this(sessionId, activityType, status, endReason, failureCode, confirmed, requestedAt, startedAt, endedAt,
                    statusVersion, serverTime, observedAt, stale, gaps, technicalPause, silenceWarning, practice, items, nextSeq, null);
        }

        public LiveView {
            gaps = List.copyOf(gaps);
            items = List.copyOf(items);
        }

        /** 전사가 담길 수 있어 items는 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "LiveView[sessionId=" + sessionId + ", status=" + status + ", statusVersion=" + statusVersion + ", stale="
                    + stale + ", items=" + items.size() + "]";
        }
    }
}
