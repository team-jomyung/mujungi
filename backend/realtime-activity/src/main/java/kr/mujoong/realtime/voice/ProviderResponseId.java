package kr.mujoong.realtime.voice;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 공급자가 준 응답 식별자(U3 L3 · {@code session.response.cancel}의 대상, U3-J11).
 *
 * <p>공급자 이벤트의 responseId를 그대로 되돌려 보낼 때만 쓴다. 형식(영숫자 · {@code . _ : -}, 128자 이하)을 지키는 값만 받아 자유 문자열이 공급자
 * 요청에 섞이지 않게 한다(SD-20). 세션 패키지의 값(보호자 · 아이 · 기기 id 등)은 이 타입으로 만들 일이 없다.
 */
public record ProviderResponseId(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public ProviderResponseId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("공급자 응답 식별자 형식이 아니다");
        }
    }

    /** 형식에 맞으면 값, 아니면 빈 값. */
    public static Optional<ProviderResponseId> of(String raw) {
        return raw != null && FORMAT.matcher(raw).matches() ? Optional.of(new ProviderResponseId(raw)) : Optional.empty();
    }
}
