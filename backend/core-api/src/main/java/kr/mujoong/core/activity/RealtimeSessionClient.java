package kr.mujoong.core.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.SessionPackageBuilder.SessionPackage;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.internalhttp.InternalHttpClient;
import kr.mujoong.core.internalhttp.InternalHttpClient.Call;
import kr.mujoong.core.internalhttp.InternalHttpClient.CallException;
import kr.mujoong.core.internalhttp.InternalHttpClient.Outcome;
import kr.mujoong.core.internalhttp.InternalHttpClient.Response;
import kr.mujoong.core.internalhttp.InternalHttpClient.Target;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Core → Realtime 세션 호출 (U1 3.5 Step 22 · 계약 5절 C8 · RD-14 · NFR4.12 · BR6.6 · BR6.7 · BR6.9 · BR6.15 · J54 · J111 · 편차 (f)(v)(w)).
 *
 * <table>
 *   <caption>호출별 규칙(RD-14)</caption>
 *   <tr><th>호출</th><th>응답 제한</th><th>재시도</th><th>차단기</th></tr>
 *   <tr><td>시작 {@code POST /internal/sessions}</td><td>3초</td><td>시간 초과만 같은 sessionId로 1회</td><td>있음</td></tr>
 *   <tr><td>끝내기 · 취소 {@code POST /internal/sessions/{id}/end}</td><td>2초</td><td>없음</td><td>없음</td></tr>
 *   <tr><td>조회 {@code GET /internal/sessions/{id}/view}</td><td>1초</td><td>없음</td><td>없음</td></tr>
 * </table>
 *
 * <p>응답 해석은 이 클래스가 하고 번역(423 → DEVICE_NOT_STABLE 등)은 호출하는 서비스가 한다.
 */
@Component
public class RealtimeSessionClient {

    /** 시작 결과 종류. */
    public enum StartKind {
        /** 202: 새 PREPARING 또는 같은 sessionId의 현재 상태. */
        ACCEPTED,
        /** 423 DEVICE_NOT_CONNECTED. */
        DEVICE_NOT_CONNECTED,
        /** 409 SESSION_IN_PROGRESS(2차 방어) · SESSION_NOT_ACTIVE(묘비). */
        CONFLICT,
        /** 확정 실패 · 재시도 뒤 시간 초과 · 차단기 열림. */
        UNAVAILABLE
    }

    /**
     * 시작 결과. {@code afterTimeoutRetry}는 이 결과가 시간 초과 뒤 같은 sessionId 재시도의 응답인지다 — 그때의 409는 첫 시작이 Realtime에서
     * 아직 INIT(곧 커밋돼 실제로 진행)일 수 있어 호출 쪽이 취소를 보낸다(BR6.6 끝 문장 · J320).
     */
    public record StartResult(StartKind kind, SessionStatus status, boolean breakerOpen, boolean afterTimeoutRetry) {

        /** 재시도 없이 첫 시도로 끝난 결과. */
        public StartResult(StartKind kind, SessionStatus status, boolean breakerOpen) {
            this(kind, status, breakerOpen, false);
        }
    }

    /** 끝내기 결과 종류. */
    public enum EndKind {
        /** 202. */
        ACCEPTED,
        /** 409 SESSION_NOT_ACTIVE. */
        NOT_ACTIVE,
        /** Realtime에 닿지 않음(시간 초과 · 연결 실패 · 401 · 5xx 등). */
        UNAVAILABLE
    }

    /** 끝내기 결과(GUARDIAN_STOP 수락이면 status CLOSING). */
    public record EndResult(EndKind kind, SessionStatus status) {
    }

    /** 조회 결과 종류. */
    public enum ViewKind {
        OK,
        /** 404: Realtime이 모르는 세션. */
        NOT_FOUND,
        UNAVAILABLE
    }

    /** 조회 결과(OK일 때만 view). */
    public record ViewResult(ViewKind kind, InternalSessionView view) {
    }

    /**
     * 내부 진행 조회 응답(internal-session-view.schema.json). items는 Core가 해석하지 않고 그대로 중계한다. gaps · technicalPause ·
     * silenceWarning · practice는 U4a가 받아 형식을 걸러 보호자 LiveView에 싣는다(AC3.2.17 · {@link LiveExtras}).
     */
    public record InternalSessionView(
            UUID sessionId,
            ActivityType activityType,
            SessionStatus status,
            EndReason endReason,
            FailureCode failureCode,
            Boolean confirmed,
            Instant requestedAt,
            Instant startedAt,
            Instant endedAt,
            Long statusVersion,
            Instant serverTime,
            List<Map<String, Object>> items,
            Long nextSeq,
            List<Map<String, Object>> gaps,
            Map<String, Object> technicalPause,
            Map<String, Object> silenceWarning,
            Map<String, Object> practice,
            Map<String, Object> guardrail,
            String endDetail) {

        public InternalSessionView(UUID sessionId, ActivityType activityType, SessionStatus status, EndReason endReason,
                FailureCode failureCode, Boolean confirmed, Instant requestedAt, Instant startedAt, Instant endedAt,
                Long statusVersion, Instant serverTime, List<Map<String, Object>> items, Long nextSeq,
                List<Map<String, Object>> gaps, Map<String, Object> technicalPause, Map<String, Object> silenceWarning,
                Map<String, Object> practice) {
            this(sessionId, activityType, status, endReason, failureCode, confirmed, requestedAt, startedAt, endedAt,
                    statusVersion, serverTime, items, nextSeq, gaps, technicalPause, silenceWarning, practice, null, null);
        }

        /** U1 모양(진행 보기 덧붙임 없음). */
        public InternalSessionView(
                UUID sessionId,
                ActivityType activityType,
                SessionStatus status,
                EndReason endReason,
                FailureCode failureCode,
                Boolean confirmed,
                Instant requestedAt,
                Instant startedAt,
                Instant endedAt,
                Long statusVersion,
                Instant serverTime,
                List<Map<String, Object>> items,
                Long nextSeq) {
            this(sessionId, activityType, status, endReason, failureCode, confirmed, requestedAt, startedAt, endedAt,
                    statusVersion, serverTime, items, nextSeq, null, null, null, null);
        }

        /** 전사가 담길 수 있어 문자열 표현에 items를 내지 않는다. */
        @Override
        public String toString() {
            return "InternalSessionView[sessionId=" + sessionId + ", status=" + status + ", statusVersion=" + statusVersion
                    + "]";
        }
    }

    /** Realtime에 닿지 못한 취소(BR6.14 재취소 처리기가 예외로 BR5.8 재호출을 탄다). */
    public static final class RealtimeCallFailedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public RealtimeCallFailedException(String message) {
            super(message);
        }
    }

    record StartResponse(SessionStatus status) {
    }

    record EndRequest(UUID requestId, EndRequestReason reason) {
    }

    record EndResponse(SessionStatus status) {
    }

    private final InternalHttpClient http;
    private final SessionStartBreaker breaker;
    private final JsonCodec json;
    private final Duration startTimeout;
    private final int startRetries;
    private final Duration endTimeout;
    private final Duration viewTimeout;

    @Autowired
    public RealtimeSessionClient(
            InternalHttpClient http, SessionStartBreaker breaker, JsonCodec json, CoreProperties properties) {
        this(http, breaker, json, properties.internalHttp().sessionStartTimeout(),
                properties.internalHttp().sessionStartRetries(), properties.internalHttp().sessionEndTimeout(),
                properties.internalHttp().sessionViewTimeout());
    }

    public RealtimeSessionClient(
            InternalHttpClient http,
            SessionStartBreaker breaker,
            JsonCodec json,
            Duration startTimeout,
            int startRetries,
            Duration endTimeout,
            Duration viewTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.json = Objects.requireNonNull(json, "json");
        this.startTimeout = Objects.requireNonNull(startTimeout, "startTimeout");
        if (startRetries < 0) {
            throw new IllegalArgumentException("startRetries는 0 이상이어야 한다");
        }
        this.startRetries = startRetries;
        this.endTimeout = Objects.requireNonNull(endTimeout, "endTimeout");
        this.viewTimeout = Objects.requireNonNull(viewTimeout, "viewTimeout");
    }

    /**
     * 세션 패키지로 시작을 명령한다(BR6.6 · BR6.7). 차단기가 열려 있으면 호출하지 않는다. 시간 초과만 같은 본문(같은 sessionId)으로
     * {@code session-start-retries}번 더 시도한다 — Realtime의 같은 sessionId 202(BR6.6 ①)가 멱등을 보장한다. 재시도의 응답이면
     * {@link StartResult#afterTimeoutRetry()}가 참이다(J320).
     */
    public StartResult start(SessionPackage sessionPackage) {
        Objects.requireNonNull(sessionPackage, "sessionPackage");
        return start(sessionPackage.sessionId(),json.write(sessionPackage));
    }

    /** U5b 고정 목표는 공통 패키지의 개인정보 허용 목록을 바꾸지 않고 C8에 더한다. */
    public StartResult startPractice(SessionPackage sessionPackage, Map<String,Object> practice) {
        Objects.requireNonNull(sessionPackage, "sessionPackage");
        if(sessionPackage.activityType()!=ActivityType.PRACTICE) throw new IllegalArgumentException("practice activity required");
        var body=new java.util.LinkedHashMap<String,Object>();
        Map<?,?> common=json.read(json.write(sessionPackage),Map.class);
        common.forEach((key,value)->body.put(key.toString(),value));
        body.put("practice",Map.copyOf(practice));
        return start(sessionPackage.sessionId(),json.write(body));
    }

    /**
     * 음성 파일 없는 승인 발화를 함께 보낸다(U8 · BR1.2 · 편차 U8-b). 본문은 공통 패키지 뒤에 선택 속성 {@code approvedSpeech}를 더한 것이고,
     * 비어 있으면 {@link #start(SessionPackage)}와 같은 본문이다(더하기만, NFR6.1).
     */
    public StartResult start(SessionPackage sessionPackage, Map<String, ApprovedContentCatalog.SpeechItem> approvedSpeech) {
        Objects.requireNonNull(sessionPackage, "sessionPackage");
        Objects.requireNonNull(approvedSpeech, "approvedSpeech");
        if (approvedSpeech.isEmpty()) {
            return start(sessionPackage);
        }
        return start(sessionPackage.sessionId(), json.write(withApprovedSpeech(sessionPackage, approvedSpeech)));
    }

    /** 연습 시작 + 승인 발화(U8). {@code approvedSpeech}가 비면 {@link #startPractice(SessionPackage, Map)}와 같은 본문이다. */
    public StartResult startPractice(SessionPackage sessionPackage, Map<String, Object> practice,
            Map<String, ApprovedContentCatalog.SpeechItem> approvedSpeech) {
        Objects.requireNonNull(sessionPackage, "sessionPackage");
        Objects.requireNonNull(approvedSpeech, "approvedSpeech");
        if (approvedSpeech.isEmpty()) {
            return startPractice(sessionPackage, practice);
        }
        if (sessionPackage.activityType() != ActivityType.PRACTICE) {
            throw new IllegalArgumentException("practice activity required");
        }
        Map<String, Object> body = withApprovedSpeech(sessionPackage, approvedSpeech);
        body.put("practice", Map.copyOf(practice));
        return start(sessionPackage.sessionId(), json.write(body));
    }

    /** 공통 패키지 필드(입력 순서) 뒤에 approvedSpeech(입력 순서)를 붙인 본문. */
    private Map<String, Object> withApprovedSpeech(SessionPackage sessionPackage,
            Map<String, ApprovedContentCatalog.SpeechItem> approvedSpeech) {
        var body = new java.util.LinkedHashMap<String, Object>();
        Map<?, ?> common = json.read(json.write(sessionPackage), Map.class);
        common.forEach((key, value) -> body.put(key.toString(), value));
        body.put("approvedSpeech", new java.util.LinkedHashMap<>(approvedSpeech));
        return body;
    }

    private StartResult start(UUID sessionId, String body) {
        SessionStartBreaker.Permit permit = breaker.acquire();
        if (permit == SessionStartBreaker.Permit.REJECTED) {
            http.recordBreakerOpen(sessionId);
            return new StartResult(StartKind.UNAVAILABLE, null, true);
        }
        int attempts = 1 + startRetries;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Response response;
            try {
                response = http.send(Target.SESSION_START,
                        new Call("POST", "/internal/sessions", body, startTimeout, attempt, sessionId));
            } catch (CallException e) {
                if (e.outcome() == Outcome.TIMEOUT && attempt < attempts) {
                    continue;
                }
                breaker.onFailure(permit);
                return new StartResult(StartKind.UNAVAILABLE, null, false, attempt > 1);
            }
            // 재시도는 시간 초과 뒤에만 하므로 두 번째 이후 시도의 응답은 '시간 초과 재시도 뒤'의 응답이다
            return interpretStart(response, permit, attempt > 1);
        }
        breaker.onFailure(permit);
        return new StartResult(StartKind.UNAVAILABLE, null, false, attempts > 1);
    }

    private StartResult interpretStart(Response response, SessionStartBreaker.Permit permit, boolean afterTimeoutRetry) {
        int status = response.status();
        if (status >= 500) {
            breaker.onFailure(permit);
            return new StartResult(StartKind.UNAVAILABLE, null, false, afterTimeoutRetry);
        }
        // 업무 응답(2xx · 409 · 423)과 그 밖 4xx는 Realtime이 살아 답한 것이라 차단기 실패로 세지 않는다(RD-14)
        breaker.onSuccess(permit);
        if (response.isSuccess()) {
            return new StartResult(StartKind.ACCEPTED, readStatus(response.body()), false, afterTimeoutRetry);
        }
        return switch (status) {
            case 423 -> new StartResult(StartKind.DEVICE_NOT_CONNECTED, null, false, afterTimeoutRetry);
            case 409 -> new StartResult(StartKind.CONFLICT, null, false, afterTimeoutRetry);
            default -> new StartResult(StartKind.UNAVAILABLE, null, false, afterTimeoutRetry);
        };
    }

    private SessionStatus readStatus(String body) {
        try {
            StartResponse parsed = json.read(body, StartResponse.class);
            return parsed == null ? null : parsed.status();
        } catch (JsonCodec.JsonCodecException e) {
            return null;
        }
    }

    /** 보호자 끝내기(GUARDIAN_STOP, BR6.9 · J54). 제한 2초 · 재시도 없음 · 차단기 없음. */
    public EndResult end(UUID sessionId, UUID requestId) {
        return callEnd(sessionId, requestId, EndRequestReason.GUARDIAN_STOP);
    }

    /**
     * 계정 삭제 접수의 진행 세션 끝내기(CONSENT_WITHDRAWN, 계약 5절 · AC1.6.3, U2 삭제 지휘가 부른다). 제한 · 재시도는 보호자 끝내기와 같다.
     */
    public EndResult endForConsentWithdrawal(UUID sessionId, UUID requestId) {
        return callEnd(sessionId, requestId, EndRequestReason.CONSENT_WITHDRAWN);
    }

    /**
     * Core 포기 취소(CORE_ABANDONED, requestId = sessionId, BR6.7 · 편차 (f)). 실패해도 예외를 던지지 않는다(BR6.14 재취소가 보완).
     *
     * @return Realtime이 202로 답했으면 true
     */
    public boolean cancel(UUID sessionId) {
        return callEnd(sessionId, sessionId, EndRequestReason.CORE_ABANDONED).kind() == EndKind.ACCEPTED;
    }

    /** Inbox 처리기 안의 재취소(BR6.14): 실패하면 예외 → BR5.8 재호출. */
    public void cancelOrThrow(UUID sessionId) {
        if (!cancel(sessionId)) {
            throw new RealtimeCallFailedException("CORE_ABANDONED 취소가 Realtime에 닿지 않았다");
        }
    }

    private EndResult callEnd(UUID sessionId, UUID requestId, EndRequestReason reason) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(requestId, "requestId");
        String body = json.write(new EndRequest(requestId, reason));
        Response response;
        try {
            response = http.send(Target.SESSION_END,
                    new Call("POST", "/internal/sessions/" + sessionId + "/end", body, endTimeout, 1, sessionId));
        } catch (CallException e) {
            return new EndResult(EndKind.UNAVAILABLE, null);
        }
        if (response.isSuccess()) {
            SessionStatus status = null;
            try {
                EndResponse parsed = json.read(response.body(), EndResponse.class);
                status = parsed == null ? null : parsed.status();
            } catch (JsonCodec.JsonCodecException e) {
                // CORE_ABANDONED의 202 본문은 {}이고, 본문이 틀려도 수락은 수락이다
                status = null;
            }
            return new EndResult(EndKind.ACCEPTED, status);
        }
        if (response.status() == 409) {
            return new EndResult(EndKind.NOT_ACTIVE, null);
        }
        return new EndResult(EndKind.UNAVAILABLE, null);
    }

    /** 진행 조회(BR6.15). 제한 1초 · 재시도 없음 · 차단기 없음. */
    public ViewResult view(UUID sessionId, long sinceSeq) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sinceSeq < 0) {
            throw new IllegalArgumentException("sinceSeq는 0 이상이어야 한다");
        }
        Response response;
        try {
            response = http.send(Target.SESSION_VIEW, new Call("GET",
                    "/internal/sessions/" + sessionId + "/view?sinceSeq=" + sinceSeq, null, viewTimeout, 1, sessionId));
        } catch (CallException e) {
            return new ViewResult(ViewKind.UNAVAILABLE, null);
        }
        if (response.status() == 404) {
            return new ViewResult(ViewKind.NOT_FOUND, null);
        }
        if (!response.isSuccess()) {
            return new ViewResult(ViewKind.UNAVAILABLE, null);
        }
        try {
            InternalSessionView view = json.read(response.body(), InternalSessionView.class);
            if (view == null || view.status() == null || view.statusVersion() == null || view.statusVersion() < 0
                    || !sessionId.equals(view.sessionId())) {
                return new ViewResult(ViewKind.UNAVAILABLE, null);
            }
            return new ViewResult(ViewKind.OK, view);
        } catch (JsonCodec.JsonCodecException e) {
            return new ViewResult(ViewKind.UNAVAILABLE, null);
        }
    }
}
