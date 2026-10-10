package kr.mujoong.core.web;

import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.health.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 헬스 {@code GET /api/health} (U1 3.5 Step 31 · 편차 (t)(n) · OD-11 · RD-16 · health-response.schema.json).
 *
 * <p>외부 프로브가 쓰는 유일한 헬스 경로다. 준비 상태 값 하나만 읽고 요청마다 DB를 부르지 않는다. READY면 200 {@code {"status":"UP"}}뿐이고
 * 상세 · 버전 · 연결 수를 내지 않는다. 준비가 아니면 F1b가 먼저 503을 낸다(이 컨트롤러의 확인은 이중 방어). 인증 · CSRF 면제(체인 C-PUB).
 */
@RestController
public class HealthController {

    private final ReadinessState readiness;

    public HealthController(ReadinessState readiness) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    @GetMapping("/api/health")
    ResponseEntity<HealthBody> health() {
        if (!readiness.isReady()) {
            throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
        }
        return ApiResponses.json(200, new HealthBody("UP"));
    }

    /** {@code {"status":"UP"}}(다른 필드 없음). */
    public record HealthBody(String status) {
    }
}
