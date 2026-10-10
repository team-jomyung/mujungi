package kr.mujoong.contract.error;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * REST 오류 code 목록(BR8.6 · 계약 2절 + 편차 (a)(b)(c)(d)(e)(n)(o)(y) · J124). 정본 표는 {@code contracts/errors.md}이고
 * {@code ContractFilesTest}가 code · HTTP · message · 노출을 이 enum과 대조한다.
 *
 * <p>message는 code마다 고정 문장 하나다. 전사 · 개인정보 · 입력값 · 예외 메시지를 넣지 않는다(BR8.6 · SD-11). 화면 문구는
 * code로 고른다. 문장은 화면 문구표에 없는 임시 문구다(J118).
 */
public enum ErrorCode {
    UNAUTHENTICATED("로그인이 필요해요.", EnumSet.of(Audience.GUARDIAN, Audience.INTERNAL), null, 401),
    NOT_FOUND("찾을 수 없어요.", EnumSet.of(Audience.GUARDIAN, Audience.INTERNAL), null, 404),
    VALIDATION_FAILED("요청 형식이 올바르지 않아요.", EnumSet.of(Audience.GUARDIAN, Audience.DEVICE, Audience.INTERNAL), "(d)", 400),
    CONSENT_MISSING("동의가 필요해요.", EnumSet.of(Audience.GUARDIAN), null, 403),
    ACCOUNT_DELETION_IN_PROGRESS("계정 삭제가 진행 중이에요.", EnumSet.of(Audience.GUARDIAN), null, 403),
    CSRF_TOKEN_INVALID("보안 확인이 만료됐어요. 다시 시도해 주세요.", EnumSet.of(Audience.GUARDIAN), "(e)", 403),
    SESSION_IN_PROGRESS("이미 진행 중인 활동이 있어요.", EnumSet.of(Audience.GUARDIAN, Audience.INTERNAL), null, 409),
    IDEMPOTENCY_CONFLICT("같은 요청 번호로 다른 내용이 왔어요.", EnumSet.of(Audience.GUARDIAN), null, 409),
    SESSION_NOT_ACTIVE("지금은 끝낼 수 있는 상태가 아니에요.", EnumSet.of(Audience.GUARDIAN, Audience.INTERNAL), "(w)", 409),
    DEVICE_NOT_STABLE("기기 연결이 안정되지 않았어요.", EnumSet.of(Audience.GUARDIAN), null, 423),
    REALTIME_UNAVAILABLE("지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.", EnumSet.of(Audience.GUARDIAN), null, 503),
    CODE_NOT_FOUND("없는 코드예요.", EnumSet.of(Audience.GUARDIAN), "(d)", 404),
    CODE_ALREADY_REGISTERED("이미 등록된 코드예요.", EnumSet.of(Audience.GUARDIAN), null, 409),
    DEVICE_ALREADY_ACTIVE("이미 연결된 기기가 있어요.", EnumSet.of(Audience.GUARDIAN), null, 409),
    TOO_MANY_ATTEMPTS("잠시 뒤 다시 시도해 주세요.", EnumSet.of(Audience.GUARDIAN), null, 429),
    RATE_LIMITED("잠시 뒤 다시 시도해 주세요.", EnumSet.of(Audience.DEVICE, Audience.NGINX), "(k)", 429),
    CODE_ISSUE_FAILED("잠시 뒤 다시 시도해 주세요.", EnumSet.of(Audience.DEVICE), "(c)", 503),
    BOOTSTRAP_NOT_FOUND("등록 정보를 찾을 수 없어요.", EnumSet.of(Audience.DEVICE), "(b)", 401),
    NOT_REGISTERED_YET("아직 등록되지 않았어요.", EnumSet.of(Audience.DEVICE), null, 409),
    BOOTSTRAP_CLOSED("이 등록은 이미 끝났어요.", EnumSet.of(Audience.DEVICE), null, 410),
    DEVICE_NOT_REGISTERED("등록되지 않은 기기예요.", EnumSet.of(Audience.DEVICE), null, 401),
    ALREADY_REGISTERED("이미 등록된 기기예요.", EnumSet.of(Audience.DEVICE), "(a)", 409),
    INVALID_ENVELOPE("요청 본문 형식이 올바르지 않아요.", EnumSet.of(Audience.INTERNAL), "(i)", 400),
    EVENT_CONFLICT("같은 이벤트에 다른 내용이 왔어요.", EnumSet.of(Audience.INTERNAL), null, 409),
    INVALID_DEVICE_TOKEN("기기 인증에 실패했어요.", EnumSet.of(Audience.INTERNAL), null, 401),
    DEVICE_NOT_CONNECTED("기기가 연결되어 있지 않아요.", EnumSet.of(Audience.INTERNAL), null, 423),
    SERVICE_NOT_READY("잠시 뒤 다시 시도해 주세요.", EnumSet.of(Audience.GUARDIAN, Audience.DEVICE, Audience.INTERNAL), "(n)", 503),
    INTERNAL_ERROR("잠시 문제가 생겼어요. 다시 시도해 주세요.", EnumSet.of(Audience.GUARDIAN, Audience.DEVICE, Audience.INTERNAL), "(o)", 500),
    UPSTREAM_UNAVAILABLE("지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.", EnumSet.of(Audience.NGINX), "(y)", 502, 504);

    /** 누가 받는 응답인가(errors.md '노출' 열). */
    public enum Audience {
        /** 보호자 웹 응답. */
        GUARDIAN,
        /** 아이 기기 웹 응답. */
        DEVICE,
        /** 앱 사이 내부 응답. 보호자 · 기기 응답에 그대로 내보내지 않는다. */
        INTERNAL,
        /** Nginx가 만든 봉투. */
        NGINX
    }

    private final String message;
    private final Set<Audience> audiences;
    private final String deviation;
    private final List<Integer> httpStatuses;

    ErrorCode(String message, Set<Audience> audiences, String deviation, int... httpStatuses) {
        this.message = message;
        this.audiences = Set.copyOf(audiences);
        this.deviation = deviation;
        this.httpStatuses = Arrays.stream(httpStatuses).boxed().toList();
    }

    /** 고정 message(errors.md). */
    public String message() {
        return message;
    }

    /** 대표 HTTP 상태 코드(목록의 첫 값). */
    public int httpStatus() {
        return httpStatuses.getFirst();
    }

    /** 이 code를 쓰는 HTTP 상태 코드 전부(UPSTREAM_UNAVAILABLE은 502 · 504). */
    public List<Integer> httpStatuses() {
        return httpStatuses;
    }

    /** 노출 대상. */
    public Set<Audience> audiences() {
        return audiences;
    }

    /** 앱 사이 응답 전용 code인가(보호자 · 기기 응답에 내보내지 않는다, BR8.6). */
    public boolean isInternalOnly() {
        return audiences.equals(Set.of(Audience.INTERNAL));
    }

    /** 승인 계약에 더한 편차 기호(없으면 빈 값). */
    public Optional<String> deviation() {
        return Optional.ofNullable(deviation);
    }

    /**
     * 보호자 응답으로 내보낼 code. 내부 전용 code는 번역한다: DEVICE_NOT_CONNECTED → DEVICE_NOT_STABLE(BR6.6 · BR8.6),
     * 그 밖의 내부 전용 code는 INTERNAL_ERROR.
     */
    public ErrorCode forGuardian() {
        if (this == DEVICE_NOT_CONNECTED) {
            return DEVICE_NOT_STABLE;
        }
        return isInternalOnly() ? INTERNAL_ERROR : this;
    }

    /** 선 형식 code 문자열을 읽는다. 모르는 code는 빈 값(받는 쪽은 일반 오류로 처리). */
    public static Optional<ErrorCode> fromWire(String code) {
        return Arrays.stream(values()).filter(value -> value.name().equals(code)).findFirst();
    }
}
