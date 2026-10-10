package kr.mujoong.core.activity.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.EndSessionService;
import kr.mujoong.core.activity.EndSessionService.Ended;
import kr.mujoong.core.activity.SessionViewService;
import kr.mujoong.core.activity.SessionViewService.LiveView;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12 진행 조회 · P1 끝내기 (U1 3.5 Step 32 · BR6.9 · BR6.15 · BR6.16 · BR8.5 · SD-9 · J54 · J111 · 편차 (w) · AC3.3.1 · AC3.3.8 · AC1.1.8 ·
 * AC1.1.9).
 *
 * <ul>
 *   <li>{@code GET /api/sessions/{sessionId}?sinceSeq=n} → 200 LiveView(live-view.schema.json). technicalPause · silenceWarning · practice는
 *       null, gaps는 빈 목록(U3 · U4 · U5). items는 Realtime이 낸 아이 전사 · 승인된 AI 말을 그대로 싣는다. sinceSeq가 정수가 아니거나 음수면 400.
 *   <li>{@code POST /api/sessions/{sessionId}/end} {@code {requestId}} → 202 {@code {status: CLOSING}}. 409 SESSION_NOT_ACTIVE · 503
 *       REALTIME_UNAVAILABLE. 계정 삭제 접수면 403. 같은 requestId 재시도는 서비스 · Realtime이 멱등으로 받는다.
 * </ul>
 *
 * <p>세션은 늘 (id ∧ 세션 보호자)로 찾고 남의 세션 · 없는 세션 · id 형식이 아닌 경로는 같은 404 봉투다(존재를 드러내지 않는다, BR8.5 · SD-9).
 * 보호자 세션 만료 · 로그아웃은 진행 중 세션에 아무 호출도 만들지 않는다(BR6.16, 401은 체인이 낸다).
 */
@RestController
public class SessionController {

    private final SessionViewService viewService;
    private final EndSessionService endService;
    private final GuardianRepository guardians;
    private final JsonCodec json;

    public SessionController(
            SessionViewService viewService, EndSessionService endService, GuardianRepository guardians, JsonCodec json) {
        this.viewService = Objects.requireNonNull(viewService, "viewService");
        this.endService = Objects.requireNonNull(endService, "endService");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.json = Objects.requireNonNull(json, "json");
    }

    @GetMapping("/api/sessions/{sessionId}")
    ResponseEntity<LiveViewBody> view(
            @AuthenticationPrincipal GuardianPrincipal guardian,
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "sinceSeq", required = false) String sinceSeq) {
        UUID guardianId = GuardianPrincipal.require(guardian);
        UUID id = ApiResponses.uuid(sessionId).orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        LiveView view = viewService.view(guardianId, id, parseSinceSeq(sinceSeq));
        return ApiResponses.json(200, LiveViewBody.of(view));
    }

    @PostMapping("/api/sessions/{sessionId}/end")
    ResponseEntity<EndedBody> end(
            @AuthenticationPrincipal GuardianPrincipal guardian,
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) String body) {
        UUID guardianId = GuardianPrincipal.requireActive(guardian, guardians);
        UUID id = ApiResponses.uuid(sessionId).orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        EndRequest request = ApiResponses.readJson(json, body, EndRequest.class, ErrorCode.VALIDATION_FAILED);
        Ended ended = endService.end(guardianId, id, request.requestId());
        return ApiResponses.json(202, new EndedBody(ended.status()));
    }

    /** sinceSeq: 없으면 0, 0 이상의 정수만(BR6.15). */
    static long parseSinceSeq(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** 끝내기 요청 본문(session-end-request.schema.json). */
    public record EndRequest(UUID requestId) {
    }

    /** 202 본문(session-end-response.schema.json). */
    public record EndedBody(SessionStatus status) {
    }

    /**
     * LiveView 응답 모양(live-view.schema.json 중 U1이 채우는 값). 판정값 · 08 입력 원문 · 게이트가 폐기한 구간은 없다. 전사가 담길 수 있어
     * items는 문자열 표현에 내지 않는다.
     */
    public record LiveViewBody(
            UUID sessionId,
            ActivityType activityType,
            SessionStatus status,
            EndReason endReason,
            FailureCode failureCode,
            boolean confirmed,
            String requestedAt,
            String startedAt,
            String endedAt,
            long statusVersion,
            String serverTime,
            String observedAt,
            boolean stale,
            Object technicalPause,
            Object silenceWarning,
            Object practice,
            List<Map<String, Object>> gaps,
            List<Map<String, Object>> items,
            long nextSeq) {

        /** 서비스 값을 응답 모양으로. */
        public static LiveViewBody of(LiveView view) {
            Objects.requireNonNull(view, "view");
            return new LiveViewBody(view.sessionId(), view.activityType(), view.status(), view.endReason(),
                    view.failureCode(), view.confirmed(), ApiResponses.time(view.requestedAt()),
                    ApiResponses.time(view.startedAt()), ApiResponses.time(view.endedAt()), view.statusVersion(),
                    ApiResponses.time(view.serverTime()), ApiResponses.time(view.observedAt()), view.stale(),
                    null, null, null, view.gaps(), view.items(), view.nextSeq());
        }

        @Override
        public String toString() {
            return "LiveViewBody[sessionId=" + sessionId + ", status=" + status + ", items=" + items.size() + "]";
        }
    }
}
