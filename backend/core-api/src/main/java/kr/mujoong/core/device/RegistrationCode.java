package kr.mujoong.core.device;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 등록 코드 (U1 3.5 Step 19 · BR1.1 · BR2.1 · 편차 (d) · J22 · AC6.1.1 · AC6.1.5).
 *
 * <p>8글자이고 알파벳은 2 ~ 9와 A ~ Z 중 I · L · O를 뺀 31글자다. 입력은 앞뒤 · 가운데 공백, 하이픈, 가운뎃점을 지우고 대문자로 바꾼 뒤
 * 비교한다. 정규화 뒤 영숫자 8자가 아니면 형식 오류(400 VALIDATION_FAILED), 영숫자 8자인데 발급 알파벳 밖 글자(0 · 1 · I · L · O)가 있으면
 * 어떤 행과도 맞지 않으므로 조회 없이 404 CODE_NOT_FOUND다. 화면 표시는 앞 4 + 뒤 4({@code 7F3K · 92QD})다.
 *
 * <p>코드는 로그 · 문자열 표현에 내지 않는다(BR8.7보다 엄격: 앞 4자도 쓰지 않는다, 3.3 추가 동작). 앞 4자는 응답 · 05 화면에만 쓴다(BR2.7).
 */
public final class RegistrationCode {

    /** 발급 알파벳 31글자(J22). */
    public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    /** 코드 길이. */
    public static final int LENGTH = 8;
    /** 화면 표시의 가운데 구분자. */
    public static final String DISPLAY_SEPARATOR = " · ";

    /** 정규화가 지우는 가운뎃점류(가운뎃점 U+00B7 · 하이픈 점 U+2027 · 가타카나 가운뎃점 U+30FB · 글머리 U+2022). */
    private static final String MIDDLE_DOTS = "·‧・•";

    /** 정규화 결과의 문제. */
    public enum Problem {
        /** 정규화 뒤 영숫자 8자가 아니다(400 VALIDATION_FAILED). */
        INVALID_FORMAT,
        /** 영숫자 8자이지만 발급 알파벳 밖 글자가 있다(404 CODE_NOT_FOUND, 조회하지 않는다). */
        OUTSIDE_ALPHABET
    }

    private final String value;

    private RegistrationCode(String value) {
        this.value = value;
    }

    /**
     * 입력을 정규화한다.
     *
     * @return 코드 또는 문제 하나
     */
    public static Parsed parse(String raw) {
        if (raw == null) {
            return Parsed.problem(Problem.INVALID_FORMAT);
        }
        String normalized = normalize(raw);
        if (normalized.length() != LENGTH || !isAsciiAlphanumeric(normalized)) {
            return Parsed.problem(Problem.INVALID_FORMAT);
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (ALPHABET.indexOf(normalized.charAt(i)) < 0) {
                return Parsed.problem(Problem.OUTSIDE_ALPHABET);
            }
        }
        return Parsed.code(new RegistrationCode(normalized));
    }

    /** 발급기가 만든 값(알파벳 · 길이를 다시 확인한다). */
    public static RegistrationCode issued(String value) {
        Parsed parsed = parse(value);
        if (parsed.code().isEmpty() || !parsed.code().get().value.equals(value)) {
            throw new IllegalArgumentException("발급 코드 형식이 아니다");
        }
        return parsed.code().get();
    }

    /** 공백 · 하이픈 · 가운뎃점을 지우고 대문자로 바꾼다. */
    static String normalize(String raw) {
        StringBuilder normalized = new StringBuilder(raw.length());
        raw.codePoints().forEach(codePoint -> {
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                    || Character.getType(codePoint) == Character.DASH_PUNCTUATION
                    || MIDDLE_DOTS.indexOf(codePoint) >= 0) {
                return;
            }
            normalized.appendCodePoint(codePoint);
        });
        return normalized.toString().toUpperCase(Locale.ROOT);
    }

    private static boolean isAsciiAlphanumeric(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /** 정규화된 8자(저장 · 조회 값). 로그에 쓰지 않는다. */
    public String value() {
        return value;
    }

    /** 앞 4글자(BR2.7 codePrefix). */
    public String prefix() {
        return value.substring(0, LENGTH / 2);
    }

    /** 기기 화면 표시({@code 7F3K · 92QD}, C.D-00.30.3). */
    public String display() {
        return value.substring(0, LENGTH / 2) + DISPLAY_SEPARATOR + value.substring(LENGTH / 2);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof RegistrationCode that && value.equals(that.value));
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** 코드는 문자열 표현에 내지 않는다. */
    @Override
    public String toString() {
        return "RegistrationCode[***]";
    }

    /** 정규화 결과: 코드 또는 문제 하나. */
    public record Parsed(Optional<RegistrationCode> code, Optional<Problem> problem) {

        public Parsed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(problem, "problem");
            if (code.isPresent() == problem.isPresent()) {
                throw new IllegalArgumentException("코드와 문제 중 하나만 있어야 한다");
            }
        }

        static Parsed code(RegistrationCode code) {
            return new Parsed(Optional.of(code), Optional.empty());
        }

        static Parsed problem(Problem problem) {
            return new Parsed(Optional.empty(), Optional.of(problem));
        }
    }
}
