package kr.mujoong.core.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.record.RecordCalendar;
import kr.mujoong.core.web.ApiResponses;

/**
 * 관리자 조회 공통 필터 (JIRA-280 · contracts/parent-api.yaml {@code /admin/**}).
 *
 * <p>기간은 Asia/Seoul 날짜 {@code from} ~ {@code to}(양 끝 포함, 최대 366일)이고 시각 범위 [from 0시, to 다음 날 0시)로 바꾼다. 형식이 틀리면
 * 400 VALIDATION_FAILED다. 검색어는 질의 인자로만 쓰고 로그 · 오류 메시지에 싣지 않는다(R-F14).
 */
public record AdminFilter(
        LocalDate fromDate,
        LocalDate toDate,
        Instant from,
        Instant to,
        String type,
        String query,
        UUID guardianId,
        UUID childId,
        UUID deviceId,
        String status) {

    /** 한 번에 고를 수 있는 최대 날짜 수(양 끝 포함). */
    public static final int MAX_RANGE_LENGTH = 366;
    /** 검색어 최대 길이. */
    public static final int MAX_QUERY = 50;
    /** 활동 유형 값. */
    public static final String FREE_TALK = "FREE_TALK";
    public static final String PRACTICE = "PRACTICE";

    /** 화면 상태 값(activity.status). */
    public enum Status { COMPLETED, INTERRUPTED, FAILED_TO_START }

    public static AdminFilter parse(String from, String to, String timezone, String type, String query, String guardianId,
            String childId, String deviceId, String status) {
        LocalDate fromDate = RecordCalendar.date(from).orElseThrow(AdminFilter::invalid);
        LocalDate toDate = RecordCalendar.date(to).orElseThrow(AdminFilter::invalid);
        long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        check(days >= 1 && days <= MAX_RANGE_LENGTH);
        check(timezone == null || RecordCalendar.ZONE.getId().equals(timezone));
        check(type == null || FREE_TALK.equals(type) || PRACTICE.equals(type));
        String trimmed = query == null ? null : query.strip();
        check(trimmed == null || trimmed.length() <= MAX_QUERY);
        Status parsedStatus = status == null ? null : parseStatus(status);
        return new AdminFilter(fromDate, toDate, RecordCalendar.of(fromDate).from(), RecordCalendar.of(toDate).to(), type,
                trimmed == null || trimmed.isEmpty() ? null : trimmed, optionalUuid(guardianId), optionalUuid(childId),
                optionalUuid(deviceId), parsedStatus == null ? null : parsedStatus.name());
    }

    /** 페이지 번호(0부터). */
    public static int page(String value) {
        return bounded(value, 0, 0, 100_000);
    }

    /** 페이지 크기(기본 8, 1 ~ 50). */
    public static int pageSize(String value) {
        return bounded(value, 8, 1, 50);
    }

    /** 검색어를 {@code ILIKE} 패턴으로(와일드카드 문자는 글자 그대로). 검색어가 없으면 null. */
    public String likePattern() {
        if (query == null) {
            return null;
        }
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** 검색어를 id(활동 · 보호자 · 기기) 앞부분 비교용 소문자로. */
    public String idPrefix() {
        return query == null ? null : query.toLowerCase(Locale.ROOT) + "%";
    }

    /** 같은 조건에서 활동 유형만 바꾼 필터. */
    public AdminFilter withType(String value) {
        return new AdminFilter(fromDate, toDate, from, to, value, query, guardianId, childId, deviceId, status);
    }

    /** 검색어(아이 이름일 수 있음)는 문자열 표현에 넣지 않는다(R-F14). */
    @Override
    public String toString() {
        return "AdminFilter[from=" + fromDate + ", to=" + toDate + ", type=" + type + ", status=" + status + "]";
    }

    private static Status parseStatus(String value) {
        try {
            return Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static UUID optionalUuid(String value) {
        if (value == null) {
            return null;
        }
        Optional<UUID> parsed = ApiResponses.uuid(value);
        return parsed.orElseThrow(AdminFilter::invalid);
    }

    private static int bounded(String value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        check(value.matches("\\d{1,6}"));
        int parsed = Integer.parseInt(value);
        check(parsed >= min && parsed <= max);
        return parsed;
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw invalid();
        }
    }

    private static CoreApiException invalid() {
        return CoreApiException.of(ErrorCode.VALIDATION_FAILED);
    }
}
