package kr.mujoong.core.child;

import java.time.LocalDate;
import java.time.ZoneId;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;

/** 생년월일은 날짜만 저장한다. 2/29 생일은 평년 3/1에 나이가 바뀐다. */
public final class BirthDateRules {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private BirthDateRules() {}
    public static LocalDate parse(String value) {
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalid();
            return LocalDate.parse(value);
        } catch (java.time.DateTimeException e) { throw invalid(); }
    }
    public static int ageOn(LocalDate birthDate, LocalDate date) {
        if (birthDate == null || date == null || birthDate.isAfter(date)) throw invalid();
        int years = date.getYear() - birthDate.getYear();
        LocalDate birthday = birthDate.getMonthValue() == 2 && birthDate.getDayOfMonth() == 29 && !date.isLeapYear()
                ? LocalDate.of(date.getYear(), 3, 1) : birthDate.withYear(date.getYear());
        return years - (date.isBefore(birthday) ? 1 : 0);
    }
    public static void requireEligible(LocalDate birthDate, LocalDate registeredOn) {
        int age = ageOn(birthDate, registeredOn);
        if (age < 6 || age >= 13) throw invalid();
    }
    private static CoreApiException invalid() { return CoreApiException.of(ErrorCode.VALIDATION_FAILED); }
}
