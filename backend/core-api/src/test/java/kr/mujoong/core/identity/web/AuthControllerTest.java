package kr.mujoong.core.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.config.CoreSecurityProperties;
import kr.mujoong.core.identity.GuardianSessions;
import kr.mujoong.core.identity.google.GoogleLoginService;
import kr.mujoong.core.identity.google.GoogleLoginService.Failure;
import kr.mujoong.core.identity.google.GoogleLoginService.LoggedIn;
import kr.mujoong.core.identity.google.GoogleLoginService.Outcome;
import kr.mujoong.core.identity.web.MeAssembler.MeBody;
import kr.mujoong.core.observability.EventLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 구글 로그인 컨트롤러의 경합 · 실패 까닭(U2 · 리뷰 2 · 리뷰 4 · R1 · R10 · R12 · U2-J5).
 *
 * <p>경합: 로그인이 '삭제 접수 아님'을 읽은 뒤 세션을 열기 전에 다른 기기의 계정 삭제가 커밋되면, 세션을 연 뒤 다시 읽은 Me가 '삭제 처리 중'이다.
 * 이때 연 세션을 곧바로 닫고 '삭제 처리 중'만 답해야 한다(접수의 closeAll보다 늦게 생긴 세션이 살아남지 않게).
 */
@Tag("parent-account")
class AuthControllerTest {

    private static final String ORIGIN = "https://mujoong.invalid";
    private static final String BODY = "{\"code\":\"c\",\"redirectUri\":\"https://mujoong.invalid/parent/login/callback\"}";

    private final GoogleLoginService login = mock(GoogleLoginService.class);
    private final GuardianSessions sessions = mock(GuardianSessions.class);
    private final MeAssembler me = mock(MeAssembler.class);
    private final AuthController controller = new AuthController(login, sessions, me, mock(EventLogger.class),
            JsonCodec.shared(), properties());

    @Test
    void aDeletionAcceptedWhileTheSessionWasOpeningClosesThatSessionAndAnswersInProgress() {
        UUID guardianId = UUID.randomUUID();
        when(login.login(any(), any())).thenReturn(new Outcome(Optional.of(new LoggedIn(guardianId, false)), null));
        when(me.me(guardianId)).thenReturn(MeAssembler.deletionInProgress(guardianId));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/google");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<MeBody> answer = controller.google(request, response, BODY);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).isEqualTo(MeAssembler.deletionInProgress(guardianId));
        InOrder order = inOrder(sessions, me);
        order.verify(sessions).open(request, response, guardianId);
        order.verify(me).me(guardianId);
        order.verify(sessions).close(request);
    }

    @Test
    void anActiveGuardianKeepsTheNewSession() {
        UUID guardianId = UUID.randomUUID();
        MeBody active = new MeBody(guardianId, "GIVEN", true, "NONE");
        when(login.login(any(), any())).thenReturn(new Outcome(Optional.of(new LoggedIn(guardianId, false)), null));
        when(me.me(guardianId)).thenReturn(active);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/google");
        request.addHeader("Origin", ORIGIN);

        ResponseEntity<MeBody> answer = controller.google(request, new MockHttpServletResponse(), BODY);

        assertThat(answer.getBody()).isEqualTo(active);
        verify(sessions, never()).close(any());
    }

    @Test
    void everyLoginFailureReasonHasItsOwnLogKind() {
        assertThat(AuthController.failureKind(Failure.NOT_CONFIGURED)).isEqualTo("GOOGLE_NOT_CONFIGURED");
        assertThat(AuthController.failureKind(Failure.BAD_REQUEST)).isEqualTo("GOOGLE_BAD_REQUEST");
        assertThat(AuthController.failureKind(Failure.REDIRECT_MISMATCH)).isEqualTo("GOOGLE_REDIRECT_MISMATCH");
        assertThat(AuthController.failureKind(Failure.EXCHANGE_FAILED)).isEqualTo("GOOGLE_EXCHANGE_FAILED");
        assertThat(AuthController.failureKind(Failure.KEYS_UNAVAILABLE)).isEqualTo("GOOGLE_KEYS_UNAVAILABLE");
        assertThat(AuthController.failureKind(Failure.TOKEN_INVALID)).isEqualTo("GOOGLE_TOKEN_INVALID");
        for (Failure failure : Failure.values()) {
            assertThat(kr.mujoong.contract.log.AttrKey.KIND.enumValues()).as(failure.name())
                    .contains(AuthController.failureKind(failure));
        }
    }

    private static CoreProperties properties() {
        CoreProperties properties = mock(CoreProperties.class);
        when(properties.security()).thenReturn(new CoreSecurityProperties(Duration.ofDays(30), ORIGIN, 60));
        return properties;
    }
}
