package kr.mujoong.realtime.observability;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.realtime.config.RealtimeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * ownerHash 비밀 설정 (U1 3.5 Step 26 · OD-4 · J124 · SD-12).
 *
 * <p>비밀은 환경 변수 {@code MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET}({@code mujoong.observability.owner-hash-secret})로만 받는다.
 * demo · prod 프로필에서 비었으면 기동을 거부하고(S1b), 그 밖(dev · test)에서 비었으면 프로세스마다 임시 무작위 비밀을 쓴다. 값이 있는데
 * 32바이트보다 짧으면 어느 프로필이든 거부한다. 두 앱이 같은 비밀을 쓰므로 같은 소유자는 두 앱에서 같은 해시다. 오류 메시지에는 키 이름만
 * 쓴다.
 */
@Configuration(proxyBeanMethods = false)
public class OwnerHashConfig {

    /** 비밀이 반드시 있어야 하는 공개 환경 프로필. */
    static final Set<String> PUBLIC_PROFILES = Set.of("demo", "prod");

    @Bean
    OwnerHasher ownerHasher(RealtimeProperties properties, Environment environment) {
        return create(properties.observability().ownerHashSecret(), environment.getActiveProfiles());
    }

    /** 비밀과 활성 프로필로 해시기를 만든다(시험이 직접 부른다). */
    public static OwnerHasher create(String secret, String... activeProfiles) {
        Objects.requireNonNull(activeProfiles, "activeProfiles");
        boolean publicEnvironment = Arrays.stream(activeProfiles).anyMatch(PUBLIC_PROFILES::contains);
        if (secret == null || secret.isBlank()) {
            if (publicEnvironment) {
                throw new IllegalStateException(
                        "필수 비밀이 비어 있다: MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET (demo · prod, OD-4)");
            }
            return OwnerHasher.ephemeral();
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < OwnerHasher.MIN_SECRET_BYTES) {
            throw new IllegalStateException("비밀이 너무 짧다: MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET (32바이트 이상, OD-4)");
        }
        return new OwnerHasher(secret);
    }
}
