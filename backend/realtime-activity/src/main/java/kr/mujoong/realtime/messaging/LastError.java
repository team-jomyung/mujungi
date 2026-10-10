package kr.mujoong.realtime.messaging;

import java.util.regex.Pattern;

/**
 * Outbox/Inbox {@code last_error} 값 규칙 (U1 3.5 Step 16 · BR5.13 · BR5.3 · BR5.8 · A1).
 *
 * <p>last_error에는 상태 코드 · 오류 코드 · 예외 종류만 적는다(페이로드 · 전사 · 토큰 · 메시지 원문 없음). 그래서 저장소는 공백 없는
 * 짧은 토큰만 받는다 — 문장이나 JSON 조각은 형식에서 걸러진다.
 */
public final class LastError {

    /** 수신 쪽 409(즉시 FAILED, 재무장 제외 A1). */
    public static final String EVENT_CONFLICT = "EVENT_CONFLICT";
    /** 수신 쪽 400(즉시 FAILED, 재무장 제외 A1). */
    public static final String INVALID_ENVELOPE = "INVALID_ENVELOPE";
    /** 계정 삭제 참가자가 닫은 행(BR5.14 (c), 재무장 제외 A1). */
    public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";

    /** 최대 길이(코드 · 예외 클래스 이름). */
    public static final int MAX_LENGTH = 200;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:$-]{1," + MAX_LENGTH + "}");

    private LastError() {
    }

    /** HTTP 상태 코드 토큰(예: {@code HTTP_503}). */
    public static String http(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("HTTP 상태 코드가 아니다");
        }
        return "HTTP_" + status;
    }

    /** 예외 종류 토큰(클래스 이름만, 메시지 없음). */
    public static String exceptionKind(Throwable error) {
        if (error == null) {
            throw new IllegalArgumentException("예외가 없다");
        }
        return error.getClass().getName();
    }

    /** 형식에 맞으면 그대로 돌려주고 아니면 {@link IllegalArgumentException}(값은 메시지에 넣지 않는다). */
    public static String require(String value) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("last_error는 공백 없는 코드 · 예외 종류 토큰이어야 한다(BR5.13)");
        }
        return value;
    }
}
