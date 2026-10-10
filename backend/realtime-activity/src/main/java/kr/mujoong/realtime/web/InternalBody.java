package kr.mujoong.realtime.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 내부 API 요청 본문 읽기와 경계 검사 도구 (U1 3.5 Step 33 · BR5.4 · BR3.9 · security-design 2.7 '본문 상한' · SD-11).
 *
 * <p>모든 {@code /internal} 본문은 {@code mujoong.limits.internal-event-body-max}(2MB) 이하다. 선언 길이가 넘으면 읽지 않고, 선언이 없으면
 * 상한 + 1바이트까지만 읽어 넘으면 거절한다. 실패는 {@link BadRequest}(400, code는 호출 경로가 정한다)이고 메시지에 본문 · 입력값을 넣지 않는다.
 */
public final class InternalBody {

    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private InternalBody() {
    }

    /** 400으로 답할 요청 오류(code는 경로별: 이벤트 수신은 INVALID_ENVELOPE, 그 밖은 VALIDATION_FAILED). */
    public static final class BadRequest extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient ErrorCode code;

        public BadRequest(ErrorCode code, String reason) {
            super(reason, null, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        public ErrorCode code() {
            return code;
        }
    }

    /**
     * 본문을 UTF-8 문자열로 읽는다.
     *
     * @param maxBytes 허용 최대 바이트 수
     * @param code 거절할 때의 오류 code
     */
    public static String read(HttpServletRequest request, long maxBytes, ErrorCode code) {
        Objects.requireNonNull(request, "request");
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            throw new BadRequest(code, "본문이 상한을 넘었다");
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, maxBytes + 1));
            if (bytes.length > maxBytes) {
                throw new BadRequest(code, "본문이 상한을 넘었다");
            }
            if (bytes.length == 0) {
                throw new BadRequest(code, "본문이 없다");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BadRequest(code, "본문을 읽지 못했다");
        }
    }

    /** JSON을 타입으로 읽는다. 형식 오류는 {@link BadRequest}. */
    public static <T> T parse(JsonCodec json, String body, Class<T> type, ErrorCode code) {
        try {
            T value = json.read(body, type);
            if (value == null) {
                throw new BadRequest(code, "본문이 비었다");
            }
            return value;
        } catch (JsonCodec.JsonCodecException e) {
            throw new BadRequest(code, "본문 형식이 틀렸다");
        }
    }

    /** 경로 변수의 UUID(36자 하이픈 형식만). */
    public static UUID uuid(String value, ErrorCode code) {
        if (value == null || !UUID_FORMAT.matcher(value).matches()) {
            throw new BadRequest(code, "UUID 형식이 아니다");
        }
        return UUID.fromString(value);
    }

    /** 값이 있어야 한다. */
    public static <T> T required(T value, String field) {
        if (value == null) {
            throw new BadRequest(ErrorCode.VALIDATION_FAILED, "필수 값이 없다: " + field);
        }
        return value;
    }

    /** 문자열 길이(코드 포인트, JSON Schema maxLength와 같은 셈)가 범위 안이어야 한다. */
    public static String text(String value, String field, int min, int max) {
        required(value, field);
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            throw new BadRequest(ErrorCode.VALIDATION_FAILED, "길이가 범위 밖이다: " + field);
        }
        return value;
    }

    /** 정수가 범위 안이어야 한다. */
    public static int range(Integer value, String field, int min, int max) {
        required(value, field);
        if (value < min || value > max) {
            throw new BadRequest(ErrorCode.VALIDATION_FAILED, "값이 범위 밖이다: " + field);
        }
        return value;
    }

    /** 조건이 참이어야 한다. */
    public static void check(boolean condition, String field) {
        if (!condition) {
            throw new BadRequest(ErrorCode.VALIDATION_FAILED, "값이 규칙에 맞지 않는다: " + field);
        }
    }
}
