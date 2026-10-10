package kr.mujoong.core.device;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.config.CoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 등록 코드 추첨 (U1 3.5 Step 19 · BR1.1 · BR1.2 · 편차 (c) · J45).
 *
 * <p>SecureRandom으로 31글자 알파벳에서 8글자를 뽑는다. 뽑은 코드가 발급 중(UNREGISTERED) 코드와 겹치면(부분 유일 색인) 새로 뽑고, 첫 추첨을
 * 포함해 {@code mujoong.bootstrap.code-draw-attempts}(5)번까지 시도한다. 모두 겹치면 503 CODE_ISSUE_FAILED다(기기 웹은 5초 뒤 다시 요청).
 */
@Component
public class CodeGenerator {

    private final RandomGenerator random;
    private final int attempts;

    /** 운영: SecureRandom, 시도 수는 설정 키. */
    @Autowired
    public CodeGenerator(CoreProperties properties) {
        this(new SecureRandom(), properties.bootstrap().codeDrawAttempts());
    }

    /**
     * @param random 난수원(시험은 고정 난수원)
     * @param attempts 첫 추첨을 포함한 시도 수(1 이상)
     */
    public CodeGenerator(RandomGenerator random, int attempts) {
        this.random = Objects.requireNonNull(random, "random");
        if (attempts < 1) {
            throw new IllegalArgumentException("추첨 시도 수는 1 이상이어야 한다");
        }
        this.attempts = attempts;
    }

    /** 코드 하나를 뽑는다. */
    public RegistrationCode draw() {
        char[] code = new char[RegistrationCode.LENGTH];
        for (int i = 0; i < code.length; i++) {
            code[i] = RegistrationCode.ALPHABET.charAt(random.nextInt(RegistrationCode.ALPHABET.length()));
        }
        return RegistrationCode.issued(new String(code));
    }

    /**
     * 뽑은 코드로 {@code tryStore}를 부르고, 빈 값(유일 충돌)이면 새로 뽑아 다시 부른다.
     *
     * @param tryStore 코드로 행 저장을 시도한다. 저장했으면 결과, 겹쳤으면 빈 값
     * @throws CoreApiException CODE_ISSUE_FAILED(503) — 시도를 다 썼을 때
     */
    public <T> T issue(Function<RegistrationCode, Optional<T>> tryStore) {
        Objects.requireNonNull(tryStore, "tryStore");
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Optional<T> stored = tryStore.apply(draw());
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        throw CoreApiException.of(ErrorCode.CODE_ISSUE_FAILED);
    }

    /** 첫 추첨을 포함한 시도 수. */
    public int attempts() {
        return attempts;
    }
}
