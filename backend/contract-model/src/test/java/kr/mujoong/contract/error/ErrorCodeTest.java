package kr.mujoong.contract.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.mujoong.contract.trace.TraceIds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 오류 code 표(BR8.6 · 계약 2절 · 편차 (a)(b)(c)(e)(n)(o) · SD-11). */
@Tag("u1")
class ErrorCodeTest {

    /** BR8.6 code → HTTP 표(contracts/errors.md 코드 표와 같다). */
    private static final Map<String, List<Integer>> HTTP_TABLE = table();

    private static Map<String, List<Integer>> table() {
        Map<String, List<Integer>> table = new LinkedHashMap<>();
        table.put("UNAUTHENTICATED", List.of(401));
        table.put("NOT_FOUND", List.of(404));
        table.put("VALIDATION_FAILED", List.of(400));
        table.put("CONSENT_MISSING", List.of(403));
        table.put("ACCOUNT_DELETION_IN_PROGRESS", List.of(403));
        table.put("CSRF_TOKEN_INVALID", List.of(403));
        table.put("SESSION_IN_PROGRESS", List.of(409));
        table.put("IDEMPOTENCY_CONFLICT", List.of(409));
        table.put("SESSION_NOT_ACTIVE", List.of(409));
        table.put("DEVICE_NOT_STABLE", List.of(423));
        table.put("REALTIME_UNAVAILABLE", List.of(503));
        table.put("CODE_NOT_FOUND", List.of(404));
        table.put("CODE_ALREADY_REGISTERED", List.of(409));
        table.put("DEVICE_ALREADY_ACTIVE", List.of(409));
        table.put("TOO_MANY_ATTEMPTS", List.of(429));
        table.put("RATE_LIMITED", List.of(429));
        table.put("CODE_ISSUE_FAILED", List.of(503));
        table.put("BOOTSTRAP_NOT_FOUND", List.of(401));
        table.put("NOT_REGISTERED_YET", List.of(409));
        table.put("BOOTSTRAP_CLOSED", List.of(410));
        table.put("DEVICE_NOT_REGISTERED", List.of(401));
        table.put("ALREADY_REGISTERED", List.of(409));
        table.put("INVALID_ENVELOPE", List.of(400));
        table.put("EVENT_CONFLICT", List.of(409));
        table.put("INVALID_DEVICE_TOKEN", List.of(401));
        table.put("DEVICE_NOT_CONNECTED", List.of(423));
        table.put("SERVICE_NOT_READY", List.of(503));
        table.put("INTERNAL_ERROR", List.of(500));
        table.put("UPSTREAM_UNAVAILABLE", List.of(502, 504));
        // U2가 더한 code(계약 3절 /child · 편차 (U2-a))
        table.put("GOOGLE_AUTH_FAILED", List.of(401));
        table.put("CHILD_NOT_REGISTERED", List.of(404));
        table.put("CHILD_EXISTS", List.of(409));
        return table;
    }

    @Test
    void codeSetAndHttpStatusesMatchTheBr86Table() {
        Map<String, List<Integer>> actual = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(Enum::name, ErrorCode::httpStatuses, (a, b) -> a, LinkedHashMap::new));

        assertThat(actual).containsExactlyEntriesOf(HTTP_TABLE);
        assertThat(ErrorCode.UPSTREAM_UNAVAILABLE.httpStatus()).isEqualTo(502);
        assertThat(ErrorCode.UNAUTHENTICATED.httpStatus()).isEqualTo(401);
    }

    @Test
    void deviationCodesAddedBy33AreMarkedWithTheirSymbol() {
        assertThat(ErrorCode.ALREADY_REGISTERED.deviation()).contains("(a)");
        assertThat(ErrorCode.BOOTSTRAP_NOT_FOUND.deviation()).contains("(b)");
        assertThat(ErrorCode.CODE_ISSUE_FAILED.deviation()).contains("(c)");
        assertThat(ErrorCode.CSRF_TOKEN_INVALID.deviation()).contains("(e)");
        assertThat(ErrorCode.SERVICE_NOT_READY.deviation()).contains("(n)");
        assertThat(ErrorCode.INTERNAL_ERROR.deviation()).contains("(o)");
        assertThat(ErrorCode.CONSENT_MISSING.deviation()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void messageIsOneFixedSentenceWithoutPersonalDataOrPlaceholders(ErrorCode code) {
        String message = code.message();

        assertThat(message).isNotBlank().hasSizeLessThanOrEqualTo(200).endsWith(".");
        // 자리표시자 · 이메일 · 숫자(코드 · 전화 · 시각) · 코드 이름 · 줄바꿈이 없다(BR8.6 · SD-11)
        assertThat(message).doesNotContain("%", "{", "}", "$", "@", "\n", code.name()).doesNotContainPattern("[0-9]");
        assertThat(message).containsPattern("[가-힣]");
    }

    @Test
    void internalOnlyCodesAreExactlyTheFourAppToAppCodes() {
        Set<ErrorCode> internalOnly = Arrays.stream(ErrorCode.values())
                .filter(ErrorCode::isInternalOnly)
                .collect(Collectors.toSet());

        assertThat(internalOnly).containsExactlyInAnyOrder(
                ErrorCode.INVALID_ENVELOPE, ErrorCode.EVENT_CONFLICT, ErrorCode.INVALID_DEVICE_TOKEN,
                ErrorCode.DEVICE_NOT_CONNECTED);
        assertThat(ErrorCode.SERVICE_NOT_READY.audiences()).contains(
                ErrorCode.Audience.GUARDIAN, ErrorCode.Audience.DEVICE, ErrorCode.Audience.INTERNAL);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void forGuardianNeverLeaksInternalOnlyCodes(ErrorCode code) {
        ErrorCode translated = code.forGuardian();

        assertThat(translated.isInternalOnly()).isFalse();
        if (!code.isInternalOnly()) {
            assertThat(translated).isSameAs(code);
        }
    }

    @Test
    void deviceNotConnectedIsTranslatedToDeviceNotStableAndOtherInternalCodesToInternalError() {
        assertThat(ErrorCode.DEVICE_NOT_CONNECTED.forGuardian()).isEqualTo(ErrorCode.DEVICE_NOT_STABLE);
        assertThat(ErrorCode.INVALID_ENVELOPE.forGuardian()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.EVENT_CONFLICT.forGuardian()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void fromWireReadsKnownCodesAndReturnsEmptyForUnknownOrNull() {
        assertThat(ErrorCode.fromWire("SESSION_IN_PROGRESS")).contains(ErrorCode.SESSION_IN_PROGRESS);
        assertThat(ErrorCode.fromWire("session_in_progress")).isEmpty();
        assertThat(ErrorCode.fromWire("FUTURE_CODE")).isEmpty();
        assertThat(ErrorCode.fromWire(null)).isEmpty();
    }

    @Test
    void envelopeCarriesFixedMessageAndRequestTraceId() {
        String traceId = TraceIds.newTraceId();

        ErrorEnvelope envelope = ErrorEnvelope.of(ErrorCode.DEVICE_NOT_STABLE, traceId);

        assertThat(envelope.code()).isEqualTo("DEVICE_NOT_STABLE");
        assertThat(envelope.message()).isEqualTo(ErrorCode.DEVICE_NOT_STABLE.message());
        assertThat(envelope.traceId()).isEqualTo(traceId);
        assertThat(envelope.errorCode()).isEqualTo(ErrorCode.DEVICE_NOT_STABLE);
    }

    @Test
    void envelopeRejectsMissingCodeBadTraceIdAndReadsUnknownCodeAsInternalError() {
        assertThatThrownBy(() -> ErrorEnvelope.of(null, TraceIds.newTraceId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ErrorEnvelope.of(ErrorCode.NOT_FOUND, "not-a-trace-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not-a-trace-id");
        assertThatThrownBy(() -> new ErrorEnvelope(" ", "m", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorEnvelope("NOT_FOUND", "", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorEnvelope("NOT_FOUND", "m", "ABC")).isInstanceOf(IllegalArgumentException.class);

        assertThat(new ErrorEnvelope("FUTURE_CODE", "m", null).errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
