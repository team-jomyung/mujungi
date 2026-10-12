package kr.mujoong.core.identity.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.identity.SocialAccountRepository;
import kr.mujoong.core.identity.google.GoogleLoginService.Failure;
import kr.mujoong.core.identity.google.GoogleLoginService.Outcome;
import kr.mujoong.core.support.U2Fixtures;
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
                tokens, new GoogleKeyCache(keys, Duration.ofHours(1)), new GoogleIdTokenReader(JsonCodec.shared()), social,
                guardians,
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
    void whenTheGoogleKeysCannotBeReadTheAuthorizationCodeIsNotSpent() {
        // 2차 리뷰 4: 열쇠를 못 읽으면 코드를 교환하기 전에 실패한다(이미 쓴 코드로 로그인이 실패하지 않게)
        GoogleLoginService service = service("client", "secret", REDIRECT);
        when(keys.fetch()).thenReturn(Optional.empty());

        assertThat(service.login("code", REDIRECT).failure()).isEqualTo(Failure.KEYS_UNAVAILABLE);
        verify(tokens, never()).exchange(any());
    }

    @Test
    void exchangeOrTokenFailuresAreReportedAndAnExistingGuardianLogsIn() {
        GoogleLoginService service = service("client", "secret", REDIRECT);
        when(keys.fetch()).thenReturn(Optional.of(keySet(GoogleIdTokenReaderTest.KEYS, Duration.ZERO)));
        when(tokens.exchange(any())).thenReturn(Optional.empty());
        assertThat(service.login("code", REDIRECT).failure()).isEqualTo(Failure.EXCHANGE_FAILED);

        when(tokens.exchange(any())).thenReturn(Optional.of("not-a-token"));
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

    @Test
    void keysAreCachedForTheirMaxAgeAndFetchedAgainOnlyWhenStaleOrForAnUnknownKid() {
        // 2차 리뷰 4: 로그인마다 받지 않는다. max-age 안에서는 캐시, 모르는 kid면 다시 받는다
        GoogleLoginService service = service("client", "secret", REDIRECT);
        UUID guardianId = UUID.randomUUID();
        when(social.findGuardianByGoogleSubject("sub-1")).thenReturn(Optional.of(guardianId));
        when(guardians.findById(guardianId)).thenReturn(Optional.of(new Guardian(guardianId, NOW)));
        when(keys.fetch()).thenReturn(Optional.of(keySet(GoogleIdTokenReaderTest.KEYS, Duration.ofMinutes(10))));
        when(tokens.exchange(any())).thenAnswer(call -> Optional.of(GoogleIdTokenReaderTest.token(
                GoogleIdTokenReaderTest.claims("accounts.google.com", "client", clock.now().plusSeconds(60), "sub-1"))));

        assertThat(service.login("code", REDIRECT).failure()).isNull();
        assertThat(service.login("code", REDIRECT).failure()).isNull();
        verify(keys, times(1)).fetch();

        clock.advance(Duration.ofMinutes(10));
        assertThat(service.login("code", REDIRECT).failure()).as("max-age가 지나면 다시 받는다").isNull();
        verify(keys, times(2)).fetch();

        KeyPair rotated = U2Fixtures.newKeyPair();
        when(tokens.exchange(any())).thenAnswer(call -> Optional.of(U2Fixtures.sign("{\"alg\":\"RS256\",\"kid\":\"k2\"}",
                GoogleIdTokenReaderTest.claims("accounts.google.com", "client", clock.now().plusSeconds(60), "sub-1"),
                rotated.getPrivate())));
        when(keys.fetch()).thenReturn(Optional.of(keySet(Map.of("k2", rotated.getPublic()), Duration.ofMinutes(10))));
        assertThat(service.login("code", REDIRECT).failure()).as("구글이 열쇠를 바꾼 직후 — 모르는 kid").isNull();
        verify(keys, times(3)).fetch();
    }

    @Test
    void theCacheIsCappedByTheTimingKeyAndANoCacheResponseIsNotKept() {
        GoogleKeyCache cache = new GoogleKeyCache(keys, Duration.ofMinutes(5));
        when(keys.fetch()).thenReturn(Optional.of(keySet(GoogleIdTokenReaderTest.KEYS, Duration.ofHours(6))));
        cache.keysFor(null, NOW);
        cache.keysFor(null, NOW.plus(Duration.ofMinutes(4)));
        verify(keys, times(1)).fetch();
        cache.keysFor(null, NOW.plus(Duration.ofMinutes(5)));
        verify(keys, times(2)).fetch();

        when(keys.fetch()).thenReturn(Optional.of(keySet(GoogleIdTokenReaderTest.KEYS, Duration.ZERO)));
        cache.keysFor(null, NOW.plus(Duration.ofMinutes(10)));
        cache.keysFor(null, NOW.plus(Duration.ofMinutes(10)));
        verify(keys, times(4)).fetch();
        when(keys.fetch()).thenReturn(Optional.empty());
        assertThat(cache.keysFor(null, NOW.plus(Duration.ofMinutes(10)))).as("받기 실패는 지난 열쇠로 넘어가지 않는다").isEmpty();
    }

    private static GoogleKeysClient.KeySet keySet(Map<String, PublicKey> keys, Duration maxAge) {
        return new GoogleKeysClient.KeySet(keys, maxAge);
    }
}
