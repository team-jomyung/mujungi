package kr.mujoong.core.notification.web;

import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.notification.PushSubscriptionRequest;
import kr.mujoong.core.notification.PushSubscriptionService;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** C6 푸시 구독. 기존 보호자 세션·CSRF 체인과 고정 오류 봉투를 사용한다. */
@RestController
public class PushSubscriptionController {
    private final PushSubscriptionService service;
    private final JsonCodec json;

    public PushSubscriptionController(PushSubscriptionService service, JsonCodec json) {
        this.service = service;
        this.json = json;
    }

    @PutMapping("/api/push-subscription")
    public ResponseEntity<Void> put(@AuthenticationPrincipal GuardianPrincipal guardian,
            @RequestBody(required = false) String body) {
        UUID guardianId = GuardianPrincipal.require(guardian);
        var request = ApiResponses.readJson(json, body, PushSubscriptionRequest.class, ErrorCode.VALIDATION_FAILED);
        service.put(guardianId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/push-subscription")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal GuardianPrincipal guardian,
            @RequestBody(required = false) String body) {
        UUID guardianId = GuardianPrincipal.require(guardian);
        var request = ApiResponses.readJson(json, body, RevokeRequest.class, ErrorCode.VALIDATION_FAILED);
        service.revoke(guardianId, request.endpoint());
        return ResponseEntity.noContent().build();
    }

    public record RevokeRequest(String endpoint) {
        @Override
        public String toString() {
            return "RevokeRequest[REDACTED]";
        }
    }
}
