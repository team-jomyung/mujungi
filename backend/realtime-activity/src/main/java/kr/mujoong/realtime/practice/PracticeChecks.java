package kr.mujoong.realtime.practice;

final class PracticeChecks {
    private PracticeChecks() {}
    static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "가 비어 있다");
        return value;
    }
    static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
