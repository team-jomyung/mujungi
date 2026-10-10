package kr.mujoong.core.device.web;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.device.ConnectionCopyWriter;
import kr.mujoong.core.device.DeviceJdbcRepository.ConnectionReport;
import kr.mujoong.core.device.DeviceTokenVerifier;
import kr.mujoong.core.device.DeviceTokenVerifier.VerifiedDevice;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Realtime → Core 내부 기기 API (U1 3.5 Step 32 · 계약 5절 C9 · internal-api.yaml · BR3.2 · BR3.4 · BR4.1 · BR4.2 · 편차 (i)(j)).
 *
 * <p>체인 C-INT(내부 토큰 필수, 사용자 권한 없음). 본문의 id는 이 앱의 DB로 다시 대조한다.
 *
 * <ul>
 *   <li>{@code POST /internal/devices/verify-token} {@code {deviceToken}} → 200
 *       {@code {deviceId, guardianId, childId, registrationStatus: REGISTERED}}. 첫 확인이면 claim을 닫는다(BR3.2). 결과는 캐시하지 않고
 *       연결마다 다시 확인한다(j). 미등록 · RELEASED · 모르는 토큰은 401 INVALID_DEVICE_TOKEN. 토큰 원문은 로그 · 오류 message에 없다.
 *   <li>{@code PUT /internal/devices/{deviceId}/connection} → 204. state · reportedAt은 필수이고 없거나 형식이 틀리면 400
 *       INVALID_ENVELOPE(i). 늦은 보고 · REGISTERED가 아닌 기기의 보고는 버리고 같은 204다(BR4.1 · BR4.2).
 * </ul>
 */
@RestController
@RequestMapping("/internal/devices")
public class DeviceInternalController {

    /** verify-token 본문의 토큰 최대 길이(internal-verify-token-request.schema.json). */
    static final int MAX_TOKEN_LENGTH = 256;

    private final DeviceTokenVerifier tokenVerifier;
    private final ConnectionCopyWriter connectionCopyWriter;
    private final JsonCodec json;

    public DeviceInternalController(
            DeviceTokenVerifier tokenVerifier, ConnectionCopyWriter connectionCopyWriter, JsonCodec json) {
        this.tokenVerifier = Objects.requireNonNull(tokenVerifier, "tokenVerifier");
        this.connectionCopyWriter = Objects.requireNonNull(connectionCopyWriter, "connectionCopyWriter");
        this.json = Objects.requireNonNull(json, "json");
    }

    @PostMapping("/verify-token")
    ResponseEntity<VerifiedBody> verifyToken(@RequestBody(required = false) String body) {
        VerifyTokenRequest request = ApiResponses.readJson(json, body, VerifyTokenRequest.class, ErrorCode.VALIDATION_FAILED);
        String token = request.deviceToken();
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        VerifiedDevice verified = tokenVerifier.verify(token);
        return ApiResponses.json(200, new VerifiedBody(verified.deviceId(), verified.guardianId(), verified.childId(),
                verified.registrationStatus()));
    }

    @PutMapping("/{deviceId}/connection")
    ResponseEntity<Void> reportConnection(
            @PathVariable("deviceId") String deviceId, @RequestBody(required = false) String body) {
        UUID id = ApiResponses.uuid(deviceId).orElseThrow(() -> CoreApiException.of(ErrorCode.INVALID_ENVELOPE));
        ConnectionReportBody report = ApiResponses.readJson(json, body, ConnectionReportBody.class, ErrorCode.INVALID_ENVELOPE);
        connectionCopyWriter.apply(id, report.toReport());
        return ResponseEntity.noContent().build();
    }

    /** verify-token 요청 본문. 토큰은 문자열 표현에 내지 않는다. */
    public record VerifyTokenRequest(String deviceToken) {

        @Override
        public String toString() {
            return "VerifyTokenRequest[deviceToken=***]";
        }
    }

    /** verify-token 200 본문(internal-verify-token-response.schema.json, childId는 아이가 없으면 null). */
    public record VerifiedBody(UUID deviceId, UUID guardianId, UUID childId, String registrationStatus) {
    }

    /** 연결 보고 본문(internal-device-connection-report.schema.json). */
    public record ConnectionReportBody(
            ConnectionState state,
            Instant reportedAt,
            Integer rttMs,
            Integer recentDisconnects,
            Instant since,
            Instant lastSeenAt) {

        /** 사본 쓰기 입력. state 누락 · 모르는 값 · reportedAt 누락 · 음수는 400 INVALID_ENVELOPE(편차 (i)). */
        ConnectionReport toReport() {
            if (state == null || state == ConnectionState.UNRECOGNIZED || reportedAt == null) {
                throw CoreApiException.of(ErrorCode.INVALID_ENVELOPE);
            }
            try {
                return new ConnectionReport(state, reportedAt, rttMs, recentDisconnects, since, lastSeenAt);
            } catch (IllegalArgumentException e) {
                throw CoreApiException.of(ErrorCode.INVALID_ENVELOPE);
            }
        }
    }
}
