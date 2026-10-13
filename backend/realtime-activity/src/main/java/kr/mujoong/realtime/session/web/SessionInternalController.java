package kr.mujoong.realtime.session.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.session.ActivitySessionRow;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.EndRequestHandler;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.session.SessionViewQuery;
import kr.mujoong.realtime.session.StartSessionHandler;
import kr.mujoong.realtime.web.ApiExceptionHandler;
import kr.mujoong.realtime.web.InternalBody;
import kr.mujoong.realtime.web.TraceIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Realtime 세션 내부 API (U1 3.5 Step 33 · 계약 5절 C8 · BR6.5 ~ BR6.7 · BR6.9 · BR6.15 · J54 · J111 · 편차 (f)(v)(w)).
 *
 * <p>C-INT 체인(내부 토큰 · 준비 상태는 필터 몫) 뒤에서만 닿는다. 본문은 2MB 이하 JSON이고 계약 스키마의 필수 · 범위 규칙을 여기서 검사한다
 * (모르는 필드는 무시, 계약 10절). 실패는 400 VALIDATION_FAILED, 응답 본문은 계약 JSON이다.
 *
 * <ul>
 *   <li>{@code POST /internal/sessions}: 202 {status} · 409 SESSION_IN_PROGRESS · 409 SESSION_NOT_ACTIVE(묘비 (v)) · 423
 *       DEVICE_NOT_CONNECTED(BR6.6). U1 자유대화만 받는다(activityType PRACTICE는 U5가 더한다, 400).
 *   <li>{@code POST /internal/sessions/{id}/end}: J54 · U4b 응답표(BR1.5 · BR1.6 · BR1.9, 편차 (U4b-b)) — 202 {status: CLOSING} · 202 {} ·
 *       409 SESSION_NOT_ACTIVE((w) 포함). 사유는 GUARDIAN_STOP · CONSENT_WITHDRAWN · CORE_ABANDONED이고 그 밖은 400. 실행기가 제한 시간 안에
 *       답하지 않으면 503(오류 처리기).
 *   <li>{@code GET /internal/sessions/{id}/view?sinceSeq}: LiveView(내부) · 모르는 세션 404 NOT_FOUND(BR6.15).
 * </ul>
 */
@RestController
@RequestMapping("/internal/sessions")
public class SessionInternalController {

    private static final Pattern CONTENT_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final Pattern AUDIO_URL = Pattern.compile("^/media/approved/[A-Za-z0-9._-]+\\.wav$");
    private static final Pattern SINCE_SEQ = Pattern.compile("^[0-9]{1,9}$");

    /** 시작 명령 본문(internal-session-start-request.schema.json). */
    record StartBody(UUID sessionId, UUID idempotencyKey, UUID guardianId, UUID childId, UUID deviceId,
            ActivityType activityType, ConsentBody consentSnapshot, ChildBody childContext,
            List<StopBody> stopExpressions, Map<String, ApprovedBody> approvedContent) {
    }

    record ConsentBody(String policyVersion, Boolean voiceAndText, Boolean guardianView, Boolean reportSharing,
            Boolean rawAudio) {
    }

    record ChildBody(String displayName, Integer age, List<String> interests) {
    }

    record StopBody(String text, Boolean isDefault) {
    }

    record ApprovedBody(UUID contentVersionId, String text, String audioUrl) {
    }

    /** 끝내기 명령 본문(internal-session-end-request.schema.json). */
    record EndBody(UUID requestId, EndRequestReason reason) {
    }

    private final StartSessionHandler starts;
    private final EndRequestHandler ends;
    private final SessionViewQuery views;
    private final JsonCodec json;
    private final long bodyMax;

    public SessionInternalController(StartSessionHandler starts, EndRequestHandler ends, SessionViewQuery views,
            JsonCodec json, RealtimeProperties properties) {
        this.starts = Objects.requireNonNull(starts, "starts");
        this.ends = Objects.requireNonNull(ends, "ends");
        this.views = Objects.requireNonNull(views, "views");
        this.json = Objects.requireNonNull(json, "json");
        this.bodyMax = properties.limits().internalEventBodyMax().toBytes();
    }

    @PostMapping
    ResponseEntity<String> start(HttpServletRequest request) {
        String body = InternalBody.read(request, bodyMax, ErrorCode.VALIDATION_FAILED);
        StartSessionHandler.StartCommand command =
                toCommand(InternalBody.parse(json, body, StartBody.class, ErrorCode.VALIDATION_FAILED));
        StartSessionHandler.StartResult result = starts.start(command, TraceIdFilter.traceId(request));
        return switch (result) {
            case StartSessionHandler.Accepted accepted -> json(HttpStatus.ACCEPTED, Map.of("status", accepted.status()));
            case StartSessionHandler.Rejected rejected -> error(switch (rejected.reason()) {
                case SESSION_NOT_ACTIVE -> ErrorCode.SESSION_NOT_ACTIVE;
                case DEVICE_NOT_CONNECTED -> ErrorCode.DEVICE_NOT_CONNECTED;
                case SESSION_IN_PROGRESS -> ErrorCode.SESSION_IN_PROGRESS;
            }, request);
        };
    }

    @PostMapping("/{sessionId}/end")
    ResponseEntity<String> end(@PathVariable("sessionId") String sessionId, HttpServletRequest request) {
        UUID id = InternalBody.uuid(sessionId, ErrorCode.VALIDATION_FAILED);
        String body = InternalBody.read(request, bodyMax, ErrorCode.VALIDATION_FAILED);
        EndBody end = InternalBody.parse(json, body, EndBody.class, ErrorCode.VALIDATION_FAILED);
        InternalBody.required(end.requestId(), "requestId");
        InternalBody.check(end.reason() != null && end.reason() != EndRequestReason.UNRECOGNIZED, "reason");
        EndOutcome outcome = ends.end(id, end.requestId(), end.reason(), TraceIdFilter.traceId(request));
        return switch (outcome) {
            case CLOSING -> json(HttpStatus.ACCEPTED, Map.of("status", "CLOSING"));
            case ACCEPTED_EMPTY -> json(HttpStatus.ACCEPTED, Map.of());
            case NOT_ACTIVE -> error(ErrorCode.SESSION_NOT_ACTIVE, request);
            case UNSUPPORTED -> error(ErrorCode.VALIDATION_FAILED, request);
        };
    }

    @GetMapping("/{sessionId}/view")
    ResponseEntity<String> view(@PathVariable("sessionId") String sessionId,
            @RequestParam(name = "sinceSeq", required = false) String sinceSeq, HttpServletRequest request) {
        UUID id = InternalBody.uuid(sessionId, ErrorCode.VALIDATION_FAILED);
        int since = 0;
        if (sinceSeq != null) {
            InternalBody.check(SINCE_SEQ.matcher(sinceSeq).matches(), "sinceSeq");
            since = Integer.parseInt(sinceSeq);
        }
        return views.view(id, since).map(view -> json(HttpStatus.OK, wire(view)))
                .orElseGet(() -> error(ErrorCode.NOT_FOUND, request));
    }

    /** 본문을 시작 명령으로 바꾼다(스키마의 필수 · 범위 규칙). */
    static StartSessionHandler.StartCommand toCommand(StartBody body) {
        InternalBody.required(body.sessionId(), "sessionId");
        InternalBody.required(body.idempotencyKey(), "idempotencyKey");
        InternalBody.required(body.guardianId(), "guardianId");
        InternalBody.required(body.childId(), "childId");
        InternalBody.required(body.deviceId(), "deviceId");
        // U1 Realtime은 자유대화만 실행한다(연습 프로토콜은 U5)
        InternalBody.check(body.activityType() == ActivityType.FREE_TALK, "activityType");
        return new StartSessionHandler.StartCommand(body.sessionId(), body.idempotencyKey(), body.guardianId(),
                body.childId(), body.deviceId(), body.activityType(), consent(body.consentSnapshot()),
                child(body.childContext()), stops(body.stopExpressions()), approved(body.approvedContent()));
    }

    private static ConsentSnapshot consent(ConsentBody body) {
        InternalBody.required(body, "consentSnapshot");
        return new ConsentSnapshot(InternalBody.text(body.policyVersion(), "consentSnapshot.policyVersion", 1, 20),
                InternalBody.required(body.voiceAndText(), "consentSnapshot.voiceAndText"),
                InternalBody.required(body.guardianView(), "consentSnapshot.guardianView"),
                InternalBody.required(body.reportSharing(), "consentSnapshot.reportSharing"),
                InternalBody.required(body.rawAudio(), "consentSnapshot.rawAudio"));
    }

    private static ChildContext child(ChildBody body) {
        InternalBody.required(body, "childContext");
        String displayName = InternalBody.text(body.displayName(), "childContext.displayName", 1, 10);
        InternalBody.check(!displayName.isBlank(), "childContext.displayName");
        int age = InternalBody.range(body.age(), "childContext.age", 6, 12);
        List<String> interests = InternalBody.required(body.interests(), "childContext.interests");
        InternalBody.check(interests.size() <= 10, "childContext.interests");
        for (String interest : interests) {
            InternalBody.text(interest, "childContext.interests[]", 1, 20);
        }
        return new ChildContext(displayName, age, interests);
    }

    private static List<StartSessionHandler.StopExpressionItem> stops(List<StopBody> body) {
        InternalBody.required(body, "stopExpressions");
        InternalBody.check(!body.isEmpty(), "stopExpressions");
        List<StartSessionHandler.StopExpressionItem> items = new ArrayList<>();
        for (StopBody stop : body) {
            InternalBody.required(stop, "stopExpressions[]");
            items.add(new StartSessionHandler.StopExpressionItem(
                    InternalBody.text(stop.text(), "stopExpressions[].text", 1, 20),
                    InternalBody.required(stop.isDefault(), "stopExpressions[].isDefault")));
        }
        return items;
    }

    private static Map<String, StartSessionHandler.ApprovedContentItem> approved(Map<String, ApprovedBody> body) {
        InternalBody.required(body, "approvedContent");
        InternalBody.check(body.containsKey(PlayApprovedContent.CLOSING)
                && body.containsKey(PlayApprovedContent.WAITING_FILLER), "approvedContent");
        Map<String, StartSessionHandler.ApprovedContentItem> items = new LinkedHashMap<>();
        for (Map.Entry<String, ApprovedBody> entry : body.entrySet()) {
            InternalBody.check(CONTENT_CODE.matcher(entry.getKey()).matches(), "approvedContent.key");
            ApprovedBody value = InternalBody.required(entry.getValue(), "approvedContent.value");
            String audioUrl = InternalBody.required(value.audioUrl(), "approvedContent.audioUrl");
            InternalBody.check(AUDIO_URL.matcher(audioUrl).matches(), "approvedContent.audioUrl");
            items.put(entry.getKey(), new StartSessionHandler.ApprovedContentItem(
                    InternalBody.required(value.contentVersionId(), "approvedContent.contentVersionId"),
                    InternalBody.text(value.text(), "approvedContent.text", 1, 200), audioUrl));
        }
        return items;
    }

    /** 내부 LiveView 선 형식(internal-session-view.schema.json). U1이 채우지 않는 칸은 null · 빈 배열이고 gaps는 U3가 채운다. */
    static Map<String, Object> wire(SessionViewQuery.View view) {
        ActivitySessionRow session = view.session();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.id());
        out.put("activityType", session.activityType());
        out.put("status", session.status());
        out.put("endReason", session.endReason());
        out.put("failureCode", session.failureCode());
        out.put("confirmed", Boolean.TRUE.equals(session.confirmed()));
        out.put("requestedAt", session.createdAt());
        out.put("startedAt", session.startedAt());
        out.put("endedAt", session.endedAt());
        out.put("statusVersion", session.statusVersion());
        out.put("serverTime", view.serverTime());
        out.put("technicalPause", null);
        out.put("silenceWarning", null);
        out.put("practice", null);
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (kr.mujoong.realtime.log.SessionGapRepository.SessionGapRow gap : view.gaps()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", gap.startedAt());
            item.put("to", gap.endedAt());
            item.put("kind", gap.kind());
            gaps.add(item);
        }
        out.put("gaps", gaps);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SessionViewQuery.Item item : view.items()) {
            Map<String, Object> bubble = new LinkedHashMap<>();
            bubble.put("seq", item.seq());
            bubble.put("speaker", item.speaker());
            bubble.put("text", item.text());
            bubble.put("final", item.isFinal());
            bubble.put("uncertain", item.uncertain());
            bubble.put("startedAt", item.startedAt());
            bubble.put("endedAt", item.endedAt());
            items.add(bubble);
        }
        out.put("items", items);
        out.put("nextSeq", view.nextSeq());
        return out;
    }

    private ResponseEntity<String> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json.write(body));
    }

    private ResponseEntity<String> error(ErrorCode code, HttpServletRequest request) {
        return ApiExceptionHandler.errorEntity(json, code, request);
    }
}
