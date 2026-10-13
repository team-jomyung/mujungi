package kr.mujoong.core.identity.google;

import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.core.identity.google.GoogleKeysClient.KeySet;

/**
 * 구글 서명 열쇠(JWKS) 캐시 (U2 GuardianIdentity · 2차 리뷰 4 · 3차 리뷰 3 · 4 · U2-J2).
 *
 * <p>응답의 {@code Cache-Control: max-age}만큼 열쇠를 두되 설정 바닥 {@code mujoong.timing.account.google-keys-min-age}보다 짧게 두지 않고
 * 상한 {@code mujoong.timing.account.google-keys-max-age}를 넘지 않는다 — max-age가 0(없음 · no-store · no-cache)이어도 로그인 한 번에
 * 두 번 받지 않는다(3차 리뷰 3). 캐시가 살아 있으면 다시 받지 않고, 토큰의 {@code kid}가 캐시에 없을 때만(구글이 열쇠를 바꾼 직후) 다시 받는다.
 * 받기에 실패하면 빈 값이다(지난 열쇠로 넘어가지 않는다). 시각은 주입한 Clock에서 온다.
 *
 * <p>받는 동안에는 락을 쥐지 않는다(3차 리뷰 4): 받기가 필요한 호출들은 진행 중인 요청 하나를 함께 기다리고, 살아 있는 열쇠로 답할 수 있는
 * 호출은 받기를 기다리지 않는다. 받기 실패도 함께 기다린 호출 모두가 나눠 받는다(줄 서서 한 번씩 다시 받지 않는다).
 */
public final class GoogleKeyCache {

    private final GoogleKeysClient client;
    private final Duration minAge;
    private final Duration maxAgeCap;
    private final Object lock = new Object();
    private Cached cached = new Cached(Map.of(), Instant.MIN);
    private CompletableFuture<Optional<KeySet>> inFlight;

    public GoogleKeyCache(GoogleKeysClient client, Duration minAge, Duration maxAgeCap) {
        this.client = Objects.requireNonNull(client, "client");
        this.minAge = Objects.requireNonNull(minAge, "minAge");
        this.maxAgeCap = Objects.requireNonNull(maxAgeCap, "maxAgeCap");
        if (minAge.compareTo(maxAgeCap) > 0) {
            throw new IllegalArgumentException("google-keys-min-age가 google-keys-max-age보다 길다");
        }
    }

    /**
     * 서명 확인에 쓸 열쇠.
     *
     * @param kid 토큰 머리의 kid(모르면 null — 캐시가 살아 있으면 그대로 준다)
     * @param now 지금(Clock)
     * @return 열쇠(받기에 실패하면 빈 값)
     */
    public Optional<Map<String, PublicKey>> keysFor(String kid, Instant now) {
        Objects.requireNonNull(now, "now");
        CompletableFuture<Optional<KeySet>> pending;
        boolean fetching = false;
        synchronized (lock) {
            if (cached.answers(kid, now)) {
                return Optional.of(cached.keys());
            }
            if (inFlight == null) {
                inFlight = new CompletableFuture<>();
                fetching = true;
            }
            pending = inFlight;
        }
        if (fetching) {
            fetchInto(pending, now);
        }
        return pending.join().map(KeySet::keys);
    }

    /** 받아서 캐시에 두고 기다리는 호출 모두에게 결과를 준다(예외도 빈 값으로 — 기다리는 호출이 영영 멈추지 않게). */
    private void fetchInto(CompletableFuture<Optional<KeySet>> pending, Instant now) {
        Optional<KeySet> fetched = Optional.empty();
        try {
            fetched = client.fetch();
        } finally {
            synchronized (lock) {
                fetched.ifPresent(set -> cached = new Cached(set.keys(), now.plus(clamp(set.maxAge()))));
                inFlight = null;
            }
            pending.complete(fetched);
        }
    }

    private Duration clamp(Duration maxAge) {
        if (maxAge.compareTo(minAge) < 0) {
            return minAge;
        }
        return maxAge.compareTo(maxAgeCap) > 0 ? maxAgeCap : maxAge;
    }

    /** 캐시한 열쇠와 만료 시각(바꾸지 않는 값). */
    private record Cached(Map<String, PublicKey> keys, Instant expiresAt) {

        boolean answers(String kid, Instant now) {
            return !keys.isEmpty() && now.isBefore(expiresAt) && (kid == null || keys.containsKey(kid));
        }
    }
}
