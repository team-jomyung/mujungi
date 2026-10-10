package kr.mujoong.contract.trace;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * traceId 규칙(OD-7 · NFR5.7). 형식은 32자 소문자 16진수(W3C trace-id · Nginx {@code $request_id}와 같은 길이)이다.
 *
 * <p>바깥에서 온 값은 형식이 맞을 때만 이어 쓰고 틀리면 새로 만든다(로그 주입 방지, SD-4 F1). 새 값은 UUID v4에서
 * 하이픈을 뺀 값이다.
 */
public final class TraceIds {

    /** 내부 호출 · 보호자 REST 응답 헤더 이름. */
    public static final String HEADER = "X-Trace-Id";
    /** 로그 MDC 키(OD-1 traceId). */
    public static final String MDC_KEY = "traceId";
    /** 형식 길이. */
    public static final int LENGTH = 32;

    private static final Pattern FORMAT = Pattern.compile("^[0-9a-f]{32}$");

    private TraceIds() {
    }

    /** 새 traceId(UUID v4 → 하이픈 제거, 소문자). */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 형식이 맞는가. null은 거짓. */
    public static boolean isValid(String candidate) {
        return candidate != null && FORMAT.matcher(candidate).matches();
    }

    /** 형식이 맞으면 그 값, 아니면 빈 값. */
    public static Optional<String> parse(String candidate) {
        return isValid(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /** 형식이 맞으면 그 값, 아니면 새 값(OD-7 '검증 → 틀리면 새로'). */
    public static String orNew(String candidate) {
        return isValid(candidate) ? candidate : newTraceId();
    }
}
