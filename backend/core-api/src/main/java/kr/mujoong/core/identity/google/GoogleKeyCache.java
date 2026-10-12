package kr.mujoong.core.identity.google;

import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kr.mujoong.core.identity.google.GoogleKeysClient.KeySet;

/**
 * 구글 서명 열쇠(JWKS) 캐시 (U2 GuardianIdentity · 2차 리뷰 4 · U2-J2).
 *
 * <p>응답의 {@code Cache-Control: max-age}만큼, 단 설정 상한 {@code mujoong.timing.account.google-keys-max-age}를 넘지 않게 열쇠를 둔다.
 * 캐시가 살아 있으면 다시 받지 않고, 토큰의 {@code kid}가 캐시에 없을 때만(구글이 열쇠를 바꾼 직후) 다시 받는다. 받기에 실패하면 빈 값이다
 * (지난 열쇠로 넘어가지 않는다). 시각은 주입한 Clock에서 온다. 여러 로그인이 함께 불러도 한 번에 하나만 받는다.
 */
public final class GoogleKeyCache {

    private final GoogleKeysClient client;
    private final Duration maxAgeCap;
    private Map<String, PublicKey> keys = Map.of();
    private Instant expiresAt = Instant.MIN;

    public GoogleKeyCache(GoogleKeysClient client, Duration maxAgeCap) {
        this.client = Objects.requireNonNull(client, "client");
        this.maxAgeCap = Objects.requireNonNull(maxAgeCap, "maxAgeCap");
    }

    /**
     * 서명 확인에 쓸 열쇠.
     *
     * @param kid 토큰 머리의 kid(모르면 null — 캐시가 살아 있으면 그대로 준다)
     * @param now 지금(Clock)
     * @return 열쇠(받기에 실패하면 빈 값)
     */
    public synchronized Optional<Map<String, PublicKey>> keysFor(String kid, Instant now) {
        Objects.requireNonNull(now, "now");
        boolean fresh = !keys.isEmpty() && now.isBefore(expiresAt);
        if (fresh && (kid == null || keys.containsKey(kid))) {
            return Optional.of(keys);
        }
        Optional<KeySet> fetched = client.fetch();
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        keys = fetched.get().keys();
        Duration maxAge = fetched.get().maxAge();
        expiresAt = now.plus(maxAge.compareTo(maxAgeCap) > 0 ? maxAgeCap : maxAge);
        return Optional.of(keys);
    }
}
