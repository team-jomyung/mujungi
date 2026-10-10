package kr.mujoong.core.activity.web;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.StartActivityService;
import kr.mujoong.core.activity.StartActivityService.StartRequest;
import kr.mujoong.core.activity.StartActivityService.Started;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 08 자유대화 시작 {@code POST /api/activities/free} (U1 3.5 Step 32 · BR6.1 ~ BR6.7 · BR8.8 · 편차 (d)(e) · AC3.1.1 · AC3.1.3 · AC3.1.4 ·
 * AC3.1.6 · AC3.1.8).
 *
 * <p>체인 C-GRD(세션 + CSRF). 본문 {@code {idempotencyKey, context?}}을 읽어 서비스에 넘긴다. 같은 키 확인 → 동의 → 진행 중 → 기기 안정 순의
 * 판정과 200자 검증은 서비스가 한다. context는 검증만 하고 저장 · 전송하지 않는다(NFR3.1). 202 {@code {sessionId, status: REQUESTED}}. 거절은
 * 400 · 401 · 403 · 409 · 423 · 503 오류 봉투이고 Realtime 내부 코드는 번역돼 나간다(BR8.6).
 */
@RestController
public class ActivityController {

    private final StartActivityService startService;
    private final JsonCodec json;

    public ActivityController(StartActivityService startService, JsonCodec json) {
        this.startService = Objects.requireNonNull(startService, "startService");
        this.json = Objects.requireNonNull(json, "json");
    }

    @PostMapping("/api/activities/free")
    ResponseEntity<StartedBody> startFreeTalk(
            @AuthenticationPrincipal GuardianPrincipal guardian, @RequestBody(required = false) String body) {
        UUID guardianId = GuardianPrincipal.require(guardian);
        StartRequest request = ApiResponses.readJson(json, body, StartRequest.class, ErrorCode.VALIDATION_FAILED);
        Started started = startService.start(guardianId, request);
        return ApiResponses.json(202, new StartedBody(started.sessionId(), started.status()));
    }

    /** 202 본문(activity-free-response.schema.json, status는 늘 REQUESTED). */
    public record StartedBody(UUID sessionId, SessionStatus status) {
    }
}
