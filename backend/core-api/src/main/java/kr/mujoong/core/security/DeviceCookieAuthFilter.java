package kr.mujoong.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 체인 C-DEV 인증 (U1 3.5 Step 31 · SD-4 · SD-7 · BR1.5 · BR1.7 · BR3.1 · BR3.5 · J75(m) · 편차 (b)).
 *
 * <p>기기 4경로의 인증 수단은 경로마다 정해진 쿠키 하나뿐이다. 보호자 세션 쿠키는 이 체인에서 아무 권한도 주지 않는다(세션을 읽지 않는다).
 *
 * <table>
 *   <caption>경로별 쿠키</caption>
 *   <tr><th>경로</th><th>쿠키</th><th>없을 때</th></tr>
 *   <tr><td>{@code POST /api/device/bootstrap}</td><td>{@value DeviceCookies#BOOTSTRAP}(선택)</td><td>새 발급(서비스 판정)</td></tr>
 *   <tr><td>{@code GET /api/device/bootstrap/status} · {@code POST /api/device/bootstrap/claim}</td>
 *       <td>{@value DeviceCookies#BOOTSTRAP}</td><td>401 BOOTSTRAP_NOT_FOUND</td></tr>
 *   <tr><td>{@code GET /api/device/me}</td><td>{@value DeviceCookies#DEVICE}</td>
 *       <td>401 DEVICE_NOT_REGISTERED + SECURITY_AUTH_FAILED(DEVICE_TOKEN) INFO</td></tr>
 * </table>
 *
 * <p>쿠키가 있으면 값을 요청 속성에 두고 지나간다. 해시 조회 · 상태 판정은 서비스가 한다(비교는 해시 일치, 원문은 저장 · 로그하지 않는다).
 * 쿠키 없는 /device/me는 기기 웹 첫 로드의 정상 경로이므로 사건은 INFO로 남기고 카운터만 올린다(J75(m)와 같은 판단). 무효 · 해제 · 회전된
 * 토큰의 401은 서비스가 WARN으로 남긴다.
 */
public final class DeviceCookieAuthFilter extends OncePerRequestFilter {

    /** bootstrap 비밀 요청 속성. */
    public static final String BOOTSTRAP_SECRET_ATTRIBUTE = DeviceCookieAuthFilter.class.getName() + ".bootstrapSecret";
    /** 기기 토큰 요청 속성. */
    public static final String DEVICE_TOKEN_ATTRIBUTE = DeviceCookieAuthFilter.class.getName() + ".deviceToken";

    /** 기기 REST 경로(체인 C-DEV 일치 조건과 같다). */
    public static final String BOOTSTRAP_PATH = "/api/device/bootstrap";
    public static final String STATUS_PATH = "/api/device/bootstrap/status";
    public static final String CLAIM_PATH = "/api/device/bootstrap/claim";
    public static final String ME_PATH = "/api/device/me";

    private final EventLogger events;

    public DeviceCookieAuthFilter(EventLogger events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = ApiResponses.path(request);
        switch (path) {
            case BOOTSTRAP_PATH -> DeviceCookies.single(request, DeviceCookies.BOOTSTRAP)
                    .ifPresent(secret -> request.setAttribute(BOOTSTRAP_SECRET_ATTRIBUTE, secret));
            case STATUS_PATH, CLAIM_PATH -> {
                Optional<String> secret = DeviceCookies.single(request, DeviceCookies.BOOTSTRAP);
                if (secret.isEmpty()) {
                    ApiResponses.writeError(request, response, ErrorCode.BOOTSTRAP_NOT_FOUND);
                    return;
                }
                request.setAttribute(BOOTSTRAP_SECRET_ATTRIBUTE, secret.get());
            }
            case ME_PATH -> {
                Optional<String> token = DeviceCookies.single(request, DeviceCookies.DEVICE);
                if (token.isEmpty()) {
                    events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.DEVICE_REGISTRY, null, entry -> entry
                            .level(EventCode.Level.INFO)
                            .attr(AttrKey.KIND, "DEVICE_TOKEN")
                            .attr(AttrKey.STATUS_CODE, ErrorCode.DEVICE_NOT_REGISTERED.httpStatus()));
                    ApiResponses.writeError(request, response, ErrorCode.DEVICE_NOT_REGISTERED);
                    return;
                }
                request.setAttribute(DEVICE_TOKEN_ATTRIBUTE, token.get());
            }
            default -> {
                // 체인 일치 조건 밖의 경로는 이 체인에 오지 않는다(SecurityConfig). 오면 막는다.
                ApiResponses.writeError(request, response, ErrorCode.NOT_FOUND);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** 컨트롤러가 bootstrap 비밀을 읽는다(없으면 null). */
    public static String bootstrapSecret(HttpServletRequest request) {
        return request.getAttribute(BOOTSTRAP_SECRET_ATTRIBUTE) instanceof String value ? value : null;
    }

    /** 컨트롤러가 기기 토큰을 읽는다(없으면 null). */
    public static String deviceToken(HttpServletRequest request) {
        return request.getAttribute(DEVICE_TOKEN_ATTRIBUTE) instanceof String value ? value : null;
    }
}
