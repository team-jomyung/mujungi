package kr.mujoong.core.result;

/** BR4.5: 보호자에게 내부 판정 대신 고정 칩만 내보낸다. */
public final class SceneChipMapper {
    private SceneChipMapper() {}

    public static String chip(String value, String timing) {
        if (value == null) {
            return "NOT_DONE";
        }
        return switch (value) {
            case "functional" -> "AFTER_HELP".equals(timing) ? "DONE_AFTER_HELP" : "DONE_IMMEDIATE";
            case "partial", "none" -> "NEXT_TIME";
            case "uncertain" -> "ON_HOLD";
            default -> throw new IllegalArgumentException("SCENE_VALUE_INVALID");
        };
    }

    public static String text(String chip, String reason) {
        return switch (chip) {
            case "DONE_IMMEDIATE" -> "바로 전달";
            case "DONE_AFTER_HELP" -> "도움 뒤 전달";
            case "NEXT_TIME" -> "다음 기회로";
            case "ON_HOLD" -> "보류";
            case "NOT_DONE" -> "미실시 · " + notDoneText(reason);
            default -> throw new IllegalArgumentException("SCENE_CHIP_INVALID");
        };
    }

    /** 미실시 사유 문구. 리포트 10항목의 미실시 장면 집계도 같은 문구를 쓴다. */
    public static String notDoneText(String reason) {
        return switch (reason == null ? "" : reason) {
            case "TIME_OVER" -> "시간 종료";
            case "SCENARIO_FAILED" -> "상황 준비 실패";
            case "NO_RESPONSE" -> "반응 없음";
            default -> "활동 끝";
        };
    }
}
