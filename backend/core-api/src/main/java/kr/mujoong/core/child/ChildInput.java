package kr.mujoong.core.child;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;

/**
 * 03 아이 정보 입력 검증 (U2 ChildProfile · AC1.3.5 · AC1.3.8 · R8 · child-request.schema.json).
 *
 * <p>글자는 NFC로 맞추고 앞뒤 공백을 뺀 뒤 센다(코드 포인트 수). 이름 1 ~ 10자, 나이 6 ~ 12 정수, 관심사 0 ~ 5개 · 각 1 ~ 10자 · 중복 없음.
 * 어긋나면 400 VALIDATION_FAILED이고 예외에 입력값을 담지 않는다(BR8.7).
 */
public final class ChildInput {

    /** 이름 최대 글자 수. */
    public static final int NAME_MAX = 10;
    /** 나이 범위. */
    public static final int AGE_MIN = 6;
    public static final int AGE_MAX = 12;
    /** 관심사 최대 개수 · 글자 수. */
    public static final int INTERESTS_MAX = 5;
    public static final int INTEREST_MAX_LENGTH = 10;

    private ChildInput() {
    }

    /** 검증을 마친 아이 정보. */
    public record Valid(String displayName, LocalDate birthDate, List<String> interests) {
        public Valid { interests = List.copyOf(interests); }
        @Override public String toString() { return "Valid[redacted]"; }
    }

    public static Valid forCreate(String displayName, String birthDate, List<String> interests, LocalDate registeredOn) {
        LocalDate birth = BirthDateRules.parse(birthDate);
        BirthDateRules.requireEligible(birth, registeredOn);
        return new Valid(name(displayName), birth, interests == null ? List.of() : interests(interests));
    }

    public static Valid forUpdate(Valid current, String displayName, String birthDate, List<String> interests, LocalDate registeredOn) {
        if (displayName == null && birthDate == null && interests == null) throw invalid();
        LocalDate birth = birthDate == null ? current.birthDate() : BirthDateRules.parse(birthDate);
        if (birthDate != null) BirthDateRules.requireEligible(birth, registeredOn);
        return new Valid(displayName == null ? current.displayName() : name(displayName), birth,
                interests == null ? current.interests() : interests(interests));
    }

    static String name(String value) {
        String name = clean(value);
        if (name.isEmpty() || length(name) > NAME_MAX) {
            throw invalid();
        }
        return name;
    }

    static List<String> interests(List<String> values) {
        if (values.size() > INTERESTS_MAX) {
            throw invalid();
        }
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String interest = clean(value);
            if (interest.isEmpty() || length(interest) > INTEREST_MAX_LENGTH || !seen.add(interest)) {
                throw invalid();
            }
            cleaned.add(interest);
        }
        return cleaned;
    }

    /** NFC + 앞뒤 공백 제거(null은 빈 글). */
    static String clean(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    }

    /** 코드 포인트 수. */
    static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static CoreApiException invalid() {
        return CoreApiException.of(ErrorCode.VALIDATION_FAILED);
    }
}
