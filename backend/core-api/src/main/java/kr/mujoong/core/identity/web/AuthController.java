package kr.mujoong.core.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.GuardianSessions;
import kr.mujoong.core.identity.google.GoogleLoginService;
import kr.mujoong.core.identity.google.GoogleLoginService.LoggedIn;
import kr.mujoong.core.identity.google.GoogleLoginService.Outcome;
import kr.mujoong.core.identity.web.MeAssembler.MeBody;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.security.SecurityConfig;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 01 구글 로그인 {@code POST /api/auth/google} · 로그아웃 {@code POST /api/auth/logout} (U2 GuardianIdentity · 계약 3절 · J21 · R1 · R2 · R5 ·
 * AC1.1.1 ~ AC1.1.4 · AC1.5.2 · AC1.5.5 · 편차 (U2-a)(U2-d)(U2-e)).
 *
 * <p>구글 로그인은 체인 C-PUB(세션이 아직 없어 CSRF 토큰도 없다)이고 대신 Origin이 있으면 허용 origin 하나와 같아야 한다(로그인 CSRF 방어,
 * 화면 쪽은 OAuth state 쿠키 U2-J4). 실패는 모두 401 GOOGLE_AUTH_FAILED + SECURITY_AUTH_FAILED(GUARDIAN_SESSION, 401)이고 세션을 만들지
 * 않는다. 삭제 처리 중 계정이면 세션 없이 Me{deletionStatus: IN_PROGRESS}만 준다. 로그아웃은 체인 C-GRD(세션 + CSRF)이고 지금 세션만
 * 끝낸다(진행 중 활동은 그대로, B2).
 */
@RestController
public class AuthController {

    private final GoogleLoginService login;
    private final GuardianSessions sessions;
    private final MeAssembler me;
    private final EventLogger events;
    private final JsonCodec json;
    private final String allowedOrigin;

    public AuthController(
            GoogleLoginService login,
            GuardianSessions sessions,
            MeAssembler me,
            EventLogger events,
            JsonCodec json,
            CoreProperties properties) {
        this.login = Objects.requireNonNull(login, "login");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.me = Objects.requireNonNull(me, "me");
        this.events = Objects.requireNonNull(events, "events");
        this.json = Objects.requireNonNull(json, "json");
        this.allowedOrigin = properties.security().allowedOrigin();
    }

    @PostMapping(SecurityConfig.GOOGLE_LOGIN_PATH)
    ResponseEntity<MeBody> google(
            HttpServletRequest request, HttpServletResponse response, @RequestBody(required = false) String body) {
        if (!originAllowed(request)) {
            throw failed();
        }
        GoogleLoginRequest parsed;
        try {
            parsed = ApiResponses.readJson(json, body, GoogleLoginRequest.class, ErrorCode.GOOGLE_AUTH_FAILED);
        } catch (CoreApiException e) {
            throw failed();
        }
        Outcome outcome = login.login(parsed.code(), parsed.redirectUri());
        if (outcome.loggedIn().isEmpty()) {
            throw failed();
        }
        LoggedIn loggedIn = outcome.loggedIn().get();
        if (loggedIn.deletionInProgress()) {
            return ApiResponses.json(200, MeAssembler.deletionInProgress(loggedIn.guardianId()));
        }
        sessions.open(request, response, loggedIn.guardianId());
        return ApiResponses.json(200, me.me(loggedIn.guardianId()));
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal GuardianPrincipal guardian, HttpServletRequest request) {
        GuardianPrincipal.require(guardian);
        sessions.close(request);
        return ResponseEntity.noContent().build();
    }

    private boolean originAllowed(HttpServletRequest request) {
        List<String> origins = request.getHeaders(HttpHeaders.ORIGIN) == null
                ? List.of()
                : Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        return origins.isEmpty() || (origins.size() == 1 && allowedOrigin.equals(origins.get(0)));
    }

    private CoreApiException failed() {
        events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.SECURITY, null, entry -> entry
                .attr(AttrKey.KIND, "GUARDIAN_SESSION")
                .attr(AttrKey.STATUS_CODE, ErrorCode.GOOGLE_AUTH_FAILED.httpStatus()));
        return CoreApiException.of(ErrorCode.GOOGLE_AUTH_FAILED);
    }

    /** 요청 본문(auth-google-request.schema.json). 인가 코드는 문자열 표현에 내지 않는다. */
    public record GoogleLoginRequest(String code, String redirectUri) {

        @Override
        public String toString() {
            return "GoogleLoginRequest[***]";
        }
    }
}
