package kr.mujoong.core.identity.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.identity.SocialAccountRepository;
import kr.mujoong.core.identity.google.GoogleLoginService.Failure;
import kr.mujoong.core.identity.google.GoogleLoginService.Outcome;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** 구글 로그인 분기(U2 · R1 · R2 · R12 · U2-J1): 설정 없음 · 형식 · redirect · 교환 · 토큰 실패와 기존 · 삭제 중 보호자. */
@Tag("u2")
class GoogleLoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final String REDIRECT = "https://mujoong.invalid/parent/login/callback";
    private static final URI TOKEN_URI = URI.create("http://127.0.0.1:9/token");
    private static final URI JWKS_URI = URI.create("http://127.0.0.1:9/certs");

    private final GoogleTokenClient tokens = mock(GoogleTokenClient.class);
    private final GoogleKeysClient keys = mock(GoogleKeysClient.class);
    private final SocialAccountRepository social = mock(SocialAccountRepository.class);
    private final GuardianRepository guardians = mock(GuardianRepository.class);
    private final MutableClock clock = new MutableClock(NOW);

    private GoogleLoginService service(String clientId, String secret, String redirect) {
        return new GoogleLoginService(
                new CoreProperties.Google(clientId, secret, redirect, TOKEN_URI, JWKS_URI, Duration.ofSeconds(1)),
                tokens, keys, new GoogleIdTokenReader(JsonCodec.shared()), social, guardians,
                mock(PlatformTransactionManager.class), clock);
    }

    @Test
    void withoutGoogleSettingsEveryLoginFailsWithoutCallingGoogle() {
        Outcome outcome = service("", "", "").login("code", REDIRECT);

        assertThat(outcome.failure()).isEqualTo(Failure.NOT_CONFIGURED);
        assertThat(outcome.loggedIn()).isEmpty();
        verifyNoInteractions(tokens, keys);
        assertThat(new CoreProperties.Google(null, null, null, TOKEN_URI, JWKS_URI, Duration.ofSeconds(1)).toString())
                .contains("<empty>").doesNotContain("null");
        assertThat(new CoreProperties.Google("id", "secret-value", REDIRECT, TOKEN_URI, JWKS_URI, Duration.ofSeconds(1))
                .toString()).doesNotContain("secret-value").contains("<redacted>");
    }

    @Test
    void badInputOrAForeignRedirectFailsBeforeTheExchange() {
        GoogleLoginService service = service("client", "secret", REDIRECT);

        assertThat(service.login(null, REDIRECT).failure()).isEqualTo(Failure.BAD_REQUEST);
        assertThat(service.login(" ", REDIRECT).failure()).isEqualTo(Failure.BAD_REQUEST);
        assertThat(service.login("c".repeat(2049), REDIRECT).failure()).isEqualTo(Failure.BAD_REQUEST);
        assertThat(service.login("code", null).failure()).isEqualTo(Failure.BAD_REQUEST);
        assertThat(service.login("code", "https://evil.invalid/cb").failure()).isEqualTo(Failure.REDIRECT_MISMATCH);
        verifyNoInteractions(tokens, keys);
    }

    @Test
    void exchangeOrTokenFailuresAreReportedAndAnExistingGuardianLogsIn() {
        GoogleLoginService service = service("client", "secret", REDIRECT);
        when(tokens.exchange(any())).thenReturn(Optional.empty());
        assertThat(service.login("code", REDIRECT).failure()).isEqualTo(Failure.EXCHANGE_FAILED);

        when(tokens.exchange(any())).thenReturn(Optional.of("not-a-token"));
        when(keys.fetch()).thenReturn(Optional.empty());
        assertThat(service.login("code", REDIRECT).failure()).as("서명 열쇠를 못 읽음").isEqualTo(Failure.KEYS_UNAVAILABLE);

        when(keys.fetch()).thenReturn(Optional.of(GoogleIdTokenReaderTest.KEYS));
        assertThat(service.login("code", REDIRECT).failure()).isEqualTo(Failure.TOKEN_INVALID);

        UUID guardianId = UUID.randomUUID();
        when(tokens.exchange(any())).thenReturn(Optional.of(
                GoogleIdTokenReaderTest.token(GoogleIdTokenReaderTest.claims("accounts.google.com", "client",
                        NOW.plusSeconds(60), "sub-1"))));
        when(social.findGuardianByGoogleSubject("sub-1")).thenReturn(Optional.of(guardianId));
        when(guardians.findById(guardianId)).thenReturn(Optional.of(new Guardian(guardianId, NOW)));

        Outcome outcome = service.login("code", REDIRECT);

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.loggedIn()).hasValueSatisfying(loggedIn -> {
            assertThat(loggedIn.guardianId()).isEqualTo(guardianId);
            assertThat(loggedIn.deletionInProgress()).isFalse();
        });
        verify(social, never()).insertGoogle(any(), any(), any());
    }
}
