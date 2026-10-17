package kr.mujoong.realtime.guardrail;

import java.util.Locale;
import java.util.Optional;

/**
 * 제브 단계의 동작 방식 (U13 BR1.3 · BR1.4 · BR1.5 · BR9.1). 설정 키 {@value #KEY}의 값이며 앱 기동 때 정해지고 세션 안에서 바뀌지 않는다.
 *
 * <ul>
 *   <li>{@link #REAL}: 실제 어댑터가 제브를 부른다. 공개 프로필(demo · prod)은 이것만 허용한다.
 *   <li>{@link #STUB}: 제브 단계를 모두 지나되 1차 사전 통과를 {@code ALLOW}(대역 표시)로 돌려주는 대역. 공개 프로필 밖에서만 허용한다.
 *   <li>{@link #OFF}: 제브 단계를 건너뛴다(제브 도입 전 동작). 공개 프로필 밖에서만 허용한다.
 * </ul>
 */
public enum GuardrailMode {
    REAL,
    STUB,
    OFF;

    /** 모드 설정 키(contracts/ai-io.md 10절). */
    public static final String KEY = "mujoong.guardrail.jev.mode";

    /** 문자열을 모드로 읽는다(앞뒤 공백 · 대소문자 무시). 비었거나 모르는 값이면 빈 값. */
    public static Optional<GuardrailMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (GuardrailMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** 기본 모드: 공개 프로필(강제)은 {@link #REAL}, 그 밖은 {@link #STUB}이다(BR1.3). */
    public static GuardrailMode defaultFor(boolean publicProfile) {
        return publicProfile ? REAL : STUB;
    }
}
