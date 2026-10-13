package kr.mujoong.core.record;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.LiveExtras;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.record.ActivityRecordRepository.DayCount;
import kr.mujoong.core.record.ActivityRecordRepository.StoredRecord;
import org.springframework.stereotype.Service;

/**
 * 26 · 27 대화 기록 조회 (U4a ActivityRecord · AC5.1.1 · AC5.1.2 · AC5.1.8 · AC5.2.1 · AC5.2.2 · FR4.3 · A10 · 설계 R7 · R8).
 *
 * <p>보호자 응답에서 뺄 것을 빼는 곳이 여기 하나다: 연습의 판정(label) · Cue · 상황 · 응답 묶음, 원음 경로, termination, 08 배경 원문,
 * 구조화 결과는 응답 모양에 자리가 없다(목표는 이름 · 순서 · 상태만). DECLINED는 머리 정보만, FAILED는 목록에 없고 상세는 404다.
 */
@Service
public class RecordQueryService {

    static final Set<String> SPEAKERS = Set.of("CHILD", "AI");
    static final Set<String> GOAL_STATUSES = Set.of("DONE", "SKIPPED", "NOT_REACHED");

    private final ActivityRecordRepository records;
    private final ActivityCopyRepository activities;
    private final JsonCodec json;

    public RecordQueryService(ActivityRecordRepository records, ActivityCopyRepository activities, JsonCodec json) {
        this.records = Objects.requireNonNull(records, "records");
        this.activities = Objects.requireNonNull(activities, "activities");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 26 달력: 그달 기록이 있는 날과 수(DECLINED 포함 · FAILED 빼고, B19). */
    public List<DayCount> days(UUID guardianId, YearMonth month) {
        RecordCalendar.Range range = RecordCalendar.of(month);
        return records.countByDay(guardianId, range.from(), range.to(), RecordCalendar.ZONE.getId());
    }

    /** 26 그날 목록. */
    public List<ListItem> onDate(UUID guardianId, LocalDate date) {
        RecordCalendar.Range range = RecordCalendar.of(date);
        return records.findListedBetween(guardianId, range.from(), range.to()).stream().map(this::listItem).toList();
    }

    /**
     * 27 대화 기록 칸.
     *
     * @throws CoreApiException 404(남의 · 없는 · 기록 아직 없음 · FAILED, U4a-J9)
     */
    public Detail detail(UUID guardianId, UUID sessionId) {
        StoredRecord record = records.findOwned(sessionId, guardianId)
                .filter(found -> !"FAILED".equals(found.finalStatus()))
                .orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        ActivityCopy copy = activities.findOwned(sessionId, guardianId)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        boolean declined = "DECLINED".equals(record.finalStatus());
        List<Bubble> utterances = declined ? List.of() : bubbles(record.transcriptJson());
        List<LiveExtras.Gap> gaps = declined ? List.of() : LiveExtras.gaps(maps(record.gapsJson()));
        List<Goal> goals = declined ? List.of() : goals(record.practiceJson());
        Scope scope = !declined && record.recordFrom() != null && record.recordTo() != null
                ? new Scope(record.recordFrom(), record.recordTo())
                : null;
        return new Detail(record, copy, goals, utterances, gaps, scope, record.confirmed());
    }

    private ListItem listItem(StoredRecord record) {
        boolean declined = "DECLINED".equals(record.finalStatus());
        int goalCount = declined ? 0 : goals(record.practiceJson()).size();
        boolean interrupted = !declined && LiveExtras.gaps(maps(record.gapsJson())).stream()
                .anyMatch(gap -> "DISCONNECT".equals(gap.kind()));
        return new ListItem(record, !declined, goalCount, interrupted);
    }

    /** 발화 → 말풍선(형식이 틀린 항목은 버린다). 모두 확정된 기록이라 final은 true다. */
    private List<Bubble> bubbles(String transcriptJson) {
        List<Bubble> bubbles = new ArrayList<>();
        for (Map<String, Object> item : maps(transcriptJson)) {
            Object seq = item.get("seq");
            Object speaker = item.get("speaker");
            Object text = item.get("text");
            Instant startedAt = instant(item.get("startedAt"));
            if (!(seq instanceof Number n) || n.longValue() < 1 || !(speaker instanceof String s) || !SPEAKERS.contains(s)
                    || !(text instanceof String t) || startedAt == null) {
                continue;
            }
            bubbles.add(new Bubble(n.longValue(), s, t, Boolean.TRUE.equals(item.get("uncertain")), startedAt,
                    instant(item.get("endedAt"))));
        }
        bubbles.sort(Comparator.comparingLong(Bubble::seq));
        return List.copyOf(bubbles);
    }

    /** 연습 목표: 이름 · 순서 · 상태만(판정 · Cue · 상황 · 응답은 읽지도 않는다, FR4.3). */
    private List<Goal> goals(String practiceJson) {
        if (practiceJson == null) {
            return List.of();
        }
        Object goals = json.read(practiceJson, Map.class).get("goals");
        if (!(goals instanceof List<?> items)) {
            return List.of();
        }
        List<Goal> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> goal && goal.get("displayName") instanceof String name
                    && goal.get("order") instanceof Number order) {
                Object status = goal.get("status");
                result.add(new Goal(name, order.intValue(),
                        status instanceof String s && GOAL_STATUSES.contains(s) ? s : null));
            }
        }
        result.sort(Comparator.comparingInt(Goal::order));
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(String arrayJson) {
        if (arrayJson == null) {
            return List.of();
        }
        List<Object> items = json.read(arrayJson, List.class);
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : items == null ? List.of() : items) {
            if (item instanceof Map<?, ?> map) {
                maps.add((Map<String, Object>) map);
            }
        }
        return maps;
    }

    private static Instant instant(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 26 그날 목록 한 줄. */
    public record ListItem(StoredRecord record, boolean linkable, int goalCount, boolean interrupted) {
    }

    /** 27 말풍선(아이 전사 · 승인된 AI 말). 글은 문자열 표현에 내지 않는다. */
    public record Bubble(long seq, String speaker, String text, boolean uncertain, Instant startedAt, Instant endedAt) {

        @Override
        public String toString() {
            return "Bubble[seq=" + seq + ", speaker=" + speaker + "]";
        }
    }

    /** 연습 목표 한 줄(고른 목표 이름 · 순서 · 상태). */
    public record Goal(String displayName, int order, String status) {
    }

    /** 기록 범위. */
    public record Scope(Instant from, Instant to) {
    }

    /** 27 대화 기록 칸. */
    public record Detail(
            StoredRecord record,
            ActivityCopy copy,
            List<Goal> goals,
            List<Bubble> utterances,
            List<LiveExtras.Gap> gaps,
            Scope recordScope,
            boolean endConfirmed) {

        @Override
        public String toString() {
            return "Detail[sessionId=" + record.sessionId() + ", utterances=" + utterances.size() + "]";
        }
    }
}
