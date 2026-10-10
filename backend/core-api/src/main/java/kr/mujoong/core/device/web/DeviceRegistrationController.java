package kr.mujoong.core.device.web;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.device.RegistrationService;
import kr.mujoong.core.device.RegistrationService.RegistrationResult;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 05a 코드 등록 {@code POST /api/device/register} (U1 3.5 Step 32 · BR2.1 ~ BR2.7 · BR8.8 · 편차 (d)(m)(e) · AC2.2.1 ~ AC2.2.8).
 *
 * <p>체인 C-GRD: 보호자 세션 + CSRF 필수({@code /api/device/**} 통째 면제 없음). 보호자는 세션의 id뿐이고 본문에서 받지 않는다(BR2.2). 본문
 * {@code {code}}는 정규화 전 입력이며 서비스가 정규화 · 판정한다(영숫자 8자가 아니면 400, 알파벳 밖이면 404). 모르는 필드는 무시한다. 본문이
 * JSON 객체가 아니면 400 VALIDATION_FAILED다(실패 한도에는 서비스가 판정한 400 · 404 · 409만 센다).
 */
@RestController
public class DeviceRegistrationController {

    private final RegistrationService registrationService;
    private final JsonCodec json;

    public DeviceRegistrationController(RegistrationService registrationService, JsonCodec json) {
        this.registrationService = Objects.requireNonNull(registrationService, "registrationService");
        this.json = Objects.requireNonNull(json, "json");
    }

    @PostMapping("/api/device/register")
    ResponseEntity<RegisteredBody> register(
            @AuthenticationPrincipal GuardianPrincipal guardian, @RequestBody(required = false) String body) {
        UUID guardianId = GuardianPrincipal.require(guardian);
        RegisterRequest request = ApiResponses.readJson(json, body, RegisterRequest.class, ErrorCode.VALIDATION_FAILED);
        RegistrationResult result = registrationService.register(guardianId, request.code());
        return ApiResponses.json(201, new RegisteredBody(result.deviceId(), result.codePrefix(),
                ApiResponses.time(result.registeredAt())));
    }

    /** 요청 본문(device-register-request.schema.json). 입력 코드는 문자열 표현에 내지 않는다. */
    public record RegisterRequest(String code) {

        @Override
        public String toString() {
            return "RegisterRequest[code=***]";
        }
    }

    /** 201 본문(device-register-response.schema.json). 해시 · 토큰은 없다. */
    public record RegisteredBody(UUID deviceId, String codePrefix, String registeredAt) {
    }
}
