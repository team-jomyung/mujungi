package kr.mujoong.core.device.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.device.BootstrapService;
import kr.mujoong.core.device.BootstrapService.BootstrapResult;
import kr.mujoong.core.device.BootstrapService.BootstrapStatus;
import kr.mujoong.core.device.DeviceClaimService;
import kr.mujoong.core.device.DeviceClaimService.ClaimResult;
import kr.mujoong.core.device.DeviceTokenVerifier;
import kr.mujoong.core.device.DeviceTokenVerifier.MeResult;
import kr.mujoong.core.security.DeviceCookieAuthFilter;
import kr.mujoong.core.security.DeviceCookies;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아이 기기 REST (U1 3.5 Step 32 · 계약 4.1 · device-api.yaml · BR1.3 ~ BR1.7 · BR3.1 · BR3.2 · BR3.5 · 편차 (a)(b)(c)(k) · AC6.1.1 ~ AC6.1.5).
 *
 * <p>체인 C-DEV(쿠키는 {@link DeviceCookieAuthFilter}가 꺼내 둔다). 비밀 · 토큰은 본문에 없고 HttpOnly 쿠키로만 오간다(BR1.5).
 *
 * <ul>
 *   <li>{@code POST /api/device/bootstrap} → 201 {@code {code, expiresAt}}. 새 발급이면 Set-Cookie {@value DeviceCookies#BOOTSTRAP}
 *       (Max-Age = code-ttl), 같은 쿠키의 같은 코드면 다시 내리지 않는다. 409 ALREADY_REGISTERED · 429 RATE_LIMITED · 503 CODE_ISSUE_FAILED.
 *       클라이언트 IP는 {@code getRemoteAddr()}(신뢰 프록시를 거친 경우 Tomcat이 X-Forwarded-For로 바꾼 값)다.
 *   <li>{@code GET /api/device/bootstrap/status} → 200 {@code {status, expiresAt}}(코드 재전송 없음).
 *   <li>{@code POST /api/device/bootstrap/claim} → 200 {@code {deviceId}} + Set-Cookie {@value DeviceCookies#DEVICE}(400일) + bootstrap 쿠키
 *       삭제(같은 Path, Max-Age=0). 401 BOOTSTRAP_NOT_FOUND · 409 NOT_REGISTERED_YET · 410 BOOTSTRAP_CLOSED.
 *   <li>{@code GET /api/device/me} → 200 {@code {deviceId, registeredAt}} + 기기 쿠키 Max-Age 재설정(갱신형). 401 DEVICE_NOT_REGISTERED.
 * </ul>
 */
@RestController
@RequestMapping("/api/device")
public class DeviceBootstrapController {

    private final BootstrapService bootstrapService;
    private final DeviceClaimService claimService;
    private final DeviceTokenVerifier tokenVerifier;
    private final DeviceCookies cookies;

    public DeviceBootstrapController(
            BootstrapService bootstrapService,
            DeviceClaimService claimService,
            DeviceTokenVerifier tokenVerifier,
            DeviceCookies cookies) {
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService");
        this.claimService = Objects.requireNonNull(claimService, "claimService");
        this.tokenVerifier = Objects.requireNonNull(tokenVerifier, "tokenVerifier");
        this.cookies = Objects.requireNonNull(cookies, "cookies");
    }

    @PostMapping("/bootstrap")
    ResponseEntity<BootstrapBody> bootstrap(HttpServletRequest request) {
        BootstrapResult result = bootstrapService.bootstrap(
                DeviceCookieAuthFilter.bootstrapSecret(request), request.getRemoteAddr());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON);
        result.newCookieSecret()
                .ifPresent(secret -> response.header(HttpHeaders.SET_COOKIE, cookies.bootstrapCookie(secret.value())));
        return response.body(new BootstrapBody(result.code().value(), ApiResponses.time(result.expiresAt())));
    }

    @GetMapping("/bootstrap/status")
    ResponseEntity<StatusBody> status(HttpServletRequest request) {
        BootstrapStatus status = bootstrapService.status(DeviceCookieAuthFilter.bootstrapSecret(request));
        return ApiResponses.json(200, new StatusBody(status.status().name(), ApiResponses.time(status.expiresAt())));
    }

    @PostMapping("/bootstrap/claim")
    ResponseEntity<ClaimBody> claim(HttpServletRequest request) {
        ClaimResult result = claimService.claim(DeviceCookieAuthFilter.bootstrapSecret(request));
        return ResponseEntity.status(200)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.SET_COOKIE, cookies.deviceCookie(result.deviceToken().value()))
                .header(HttpHeaders.SET_COOKIE, cookies.clearBootstrapCookie())
                .body(new ClaimBody(result.deviceId()));
    }

    @GetMapping("/me")
    ResponseEntity<MeBody> me(HttpServletRequest request) {
        String token = DeviceCookieAuthFilter.deviceToken(request);
        MeResult result = tokenVerifier.me(token);
        return ResponseEntity.status(200)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.SET_COOKIE, cookies.deviceCookie(token))
                .body(new MeBody(result.deviceId(), ApiResponses.time(result.registeredAt())));
    }

    /** 201 본문(device-bootstrap-response.schema.json). 코드는 기기 화면에 보일 값이고 로그에는 쓰지 않는다. */
    public record BootstrapBody(String code, String expiresAt) {

        @Override
        public String toString() {
            return "BootstrapBody[code=***, expiresAt=" + expiresAt + "]";
        }
    }

    /** 200 본문(device-bootstrap-status-response.schema.json). */
    public record StatusBody(String status, String expiresAt) {
    }

    /** 200 본문(device-claim-response.schema.json). */
    public record ClaimBody(UUID deviceId) {
    }

    /** 200 본문(device-me-response.schema.json). 아이 정보는 없다. */
    public record MeBody(UUID deviceId, String registeredAt) {
    }
}
