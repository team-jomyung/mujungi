package kr.mujoong.core.identity.google;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.identity.SocialAccountRepository;
import kr.mujoong.core.identity.google.GoogleIdTokenReader.GoogleIdentity;
import kr.mujoong.core.identity.google.GoogleTokenClient.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구글 로그인 (U2 GuardianIdentity · FR1.1 · AC1.1.1 ~ AC1.1.4 · AC1.6.4 · AC1.6.5 · R1 · R2 · R12 · U2-J1 · U2-J2).
 *
 * <ol>
 *   <li>구글 설정(client id · secret · redirect uri)이 없거나 요청 redirectUri가 설정과 다르면 실패.
 *   <li>구글 JWKS 열쇠를 먼저 확보하고(캐시, 못 얻으면 코드를 쓰지 않고 실패 — 2차 리뷰 4), 토큰 엔드포인트와 code를 교환해 id_token을 받아
 *       열쇠로 서명을 확인한 뒤 iss · aud · exp · sub를 확인한다(리뷰 1). 캐시에 없는 kid면 열쇠를 한 번 다시 받는다.
 *   <li>sub의 보호자가 있으면 그 보호자, 없으면 한 트랜잭션에 보호자(ACTIVE) + 연결 행을 만든다(동시 첫 로그인은 유일 제약이 하나만 남기고
 *       진 쪽은 이긴 쪽 보호자를 다시 읽는다). 삭제가 끝나 연결 행이 지워진 구글 계정은 새 보호자가 된다(AC1.6.5).
 * </ol>
 *
 * <p>결과는 보호자 id와 삭제 접수 여부다. 세션은 호출자(컨트롤러)가 삭제 접수가 아닐 때만 연다(U2-J5).
 */
@Component
public class GoogleLoginService {

    private final CoreProperties.Google google;
    private final GoogleTokenClient tokenClient;
    private final GoogleKeyCache keyCache;
    private final GoogleIdTokenReader idTokens;
    private final SocialAccountRepository socialAccounts;
    private final GuardianRepository guardians;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    @Autowired
    public GoogleLoginService(
            CoreProperties properties,
            JsonCodec json,
            SocialAccountRepository socialAccounts,
            GuardianRepository guardians,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this(properties.guardian().google(),
                new GoogleTokenClient(properties.guardian().google().tokenUri(),
                        properties.guardian().google().exchangeTimeout(), json),
                new GoogleKeyCache(new GoogleKeysClient(properties.guardian().google().jwksUri(),
                        properties.guardian().google().exchangeTimeout(), json),
                        properties.timing().account().googleKeysMaxAge()),
                new GoogleIdTokenReader(json), socialAccounts, guardians, transactionManager, clock);
    }

    GoogleLoginService(
            CoreProperties.Google google,
            GoogleTokenClient tokenClient,
            GoogleKeyCache keyCache,
            GoogleIdTokenReader idTokens,
            SocialAccountRepository socialAccounts,
            GuardianRepository guardians,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.google = Objects.requireNonNull(google, "google");
        this.tokenClient = Objects.requireNonNull(tokenClient, "tokenClient");
        this.keyCache = Objects.requireNonNull(keyCache, "keyCache");
        this.idTokens = Objects.requireNonNull(idTokens, "idTokens");
        this.socialAccounts = Objects.requireNonNull(socialAccounts, "socialAccounts");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 로그인된 보호자. */
    public record LoggedIn(UUID guardianId, boolean deletionInProgress) {
    }

    /** 실패 까닭(로그 kind용, 응답은 모두 401 GOOGLE_AUTH_FAILED). */
    public enum Failure {
        NOT_CONFIGURED,
        BAD_REQUEST,
        REDIRECT_MISMATCH,
        EXCHANGE_FAILED,
        /** 서명 열쇠(JWKS)를 읽지 못했다. */
        KEYS_UNAVAILABLE,
        /** 서명 · iss · aud · exp · sub 중 하나가 틀렸다. */
        TOKEN_INVALID
    }

    /** 결과: 로그인 또는 실패 까닭. */
    public record Outcome(Optional<LoggedIn> loggedIn, Failure failure) {

        static Outcome success(LoggedIn loggedIn) {
            return new Outcome(Optional.of(loggedIn), null);
        }

        static Outcome failed(Failure failure) {
            return new Outcome(Optional.empty(), failure);
        }
    }

    /** 로그인한다. */
    public Outcome login(String code, String redirectUri) {
        if (!google.configured()) {
            return Outcome.failed(Failure.NOT_CONFIGURED);
        }
        if (code == null || code.isBlank() || code.length() > 2048 || redirectUri == null) {
            return Outcome.failed(Failure.BAD_REQUEST);
        }
        if (!google.redirectUri().equals(redirectUri)) {
            return Outcome.failed(Failure.REDIRECT_MISMATCH);
        }
        // 열쇠를 먼저 확보한다: 구글 열쇠 주소가 잠깐 불안정해도 인가 코드를 쓰기 전에 실패해 코드가 남는다(2차 리뷰 4).
        // 캐시가 살아 있으면 받지 않는다
        if (keyCache.keysFor(null, clock.now()).isEmpty()) {
            return Outcome.failed(Failure.KEYS_UNAVAILABLE);
        }
        Optional<String> idToken = tokenClient.exchange(
                new Exchange(code, google.clientId(), google.clientSecret(), google.redirectUri()));
        if (idToken.isEmpty()) {
            return Outcome.failed(Failure.EXCHANGE_FAILED);
        }
        // 캐시에 없는 kid(구글이 열쇠를 바꾼 직후)일 때만 다시 받는다
        Optional<Map<String, PublicKey>> keys = keyCache.keysFor(idTokens.keyId(idToken.get()).orElse(null), clock.now());
        if (keys.isEmpty()) {
            return Outcome.failed(Failure.KEYS_UNAVAILABLE);
        }
        Optional<GoogleIdentity> identity = idTokens.read(idToken.get(), keys.get(), google.clientId(), clock.now());
        if (identity.isEmpty()) {
            return Outcome.failed(Failure.TOKEN_INVALID);
        }
        UUID guardianId = findOrCreate(identity.get().subject());
        boolean deleting = guardians.findById(guardianId).map(Guardian::isDeletionRequested).orElse(false);
        return Outcome.success(new LoggedIn(guardianId, deleting));
    }

    private UUID findOrCreate(String subject) {
        Optional<UUID> existing = socialAccounts.findGuardianByGoogleSubject(subject);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID created = transactions.execute(status -> {
            Instant now = clock.now();
            UUID guardianId = UUID.randomUUID();
            guardians.saveAndFlush(new Guardian(guardianId, now));
            if (!socialAccounts.insertGoogle(guardianId, subject, now)) {
                // 같은 구글 계정의 동시 첫 로그인이 먼저 이겼다: 만든 보호자를 버리고 이긴 쪽을 쓴다
                status.setRollbackOnly();
                return null;
            }
            return guardianId;
        });
        if (created != null) {
            return created;
        }
        return socialAccounts.findGuardianByGoogleSubject(subject)
                .orElseThrow(() -> new IllegalStateException("구글 계정 연결을 다시 읽지 못했다"));
    }
}
