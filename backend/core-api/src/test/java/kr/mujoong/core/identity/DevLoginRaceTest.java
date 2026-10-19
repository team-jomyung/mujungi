package kr.mujoong.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.profile.ProfileGuardRules;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.observability.EventLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 시험 로그인과 삭제 접수의 경합(U2 3차 리뷰 2 · 편차 (U2-k)).
 *
 * <p>시험 로그인이 '삭제 접수 아님'을 읽은 뒤 · 세션을 열기 전에 삭제 접수가 커밋되면 예전에는 세션이 만들어졌다. 세션을 연 뒤 다시 읽어 그 사이
 * 접수됐으면 연 세션을 닫고 403 ACCOUNT_DELETION_IN_PROGRESS여야 한다.
 */
@Tag("parent-account")
class DevLoginRaceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    private final GuardianRepository guardians = mock(GuardianRepository.class);
    private final DevLoginController controller = new DevLoginController(guardians,
            new HttpSessionSecurityContextRepository(), mock(EventLogger.class), devEnvironment());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aDeletionAcceptedWhileTheSessionWasOpeningClosesThatSessionAndAnswersForbidden() {
        when(guardians.findById(SeedIds.GUARDIAN)).thenReturn(Optional.of(guardian(Guardian.STATUS_ACTIVE)))
                .thenReturn(Optional.of(guardian(Guardian.STATUS_DELETION_REQUESTED)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/dev/login");

        assertThatThrownBy(() -> controller.login(request, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(CoreApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS));

        MockHttpSession opened = (MockHttpSession) request.getSession(false);
        assertThat(opened == null || opened.isInvalid()).as("연 세션은 닫혔다").isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void anActiveSeedGuardianKeepsTheNewSession() {
        when(guardians.findById(SeedIds.GUARDIAN)).thenReturn(Optional.of(guardian(Guardian.STATUS_ACTIVE)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/dev/login");

        assertThat(controller.login(request, new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(200);

        MockHttpSession opened = (MockHttpSession) request.getSession(false);
        assertThat(opened).isNotNull();
        assertThat(opened.isInvalid()).isFalse();
    }

    private static Guardian guardian(String status) {
        Guardian guardian = new Guardian(SeedIds.GUARDIAN, NOW);
        ReflectionTestUtils.setField(guardian, "status", status);
        return guardian;
    }

    private static MockEnvironment devEnvironment() {
        MockEnvironment environment = new MockEnvironment().withProperty(ProfileGuardRules.DEV_LOGIN_ENABLED_KEY, "true");
        environment.setActiveProfiles(ProfileGuardRules.DEV);
        return environment;
    }
}
