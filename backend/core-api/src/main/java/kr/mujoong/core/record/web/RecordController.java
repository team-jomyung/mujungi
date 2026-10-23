package kr.mujoong.core.record.web;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.LiveExtras;
import kr.mujoong.core.activity.SessionViewService;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.record.ActivityRecordRepository.StoredRecord;
import kr.mujoong.core.record.RecordCalendar;
import kr.mujoong.core.record.RecordQueryService;
import kr.mujoong.core.record.RecordQueryService.Detail;
import kr.mujoong.core.record.RecordQueryService.ListItem;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 26 · 27 대화 기록 API (U4a ActivityRecord · 계약 3절 {@code /records} · AC5.1.1 · AC5.1.2 · AC5.1.8 · AC5.2.1 · AC5.2.2 · 편차 (U4a-b)).
 *
 * <ul>
 *   <li>{@code GET /api/records?month=yyyy-MM} → {@code {days[{date, count}]}}.
 *   <li>{@code GET /api/records?date=yyyy-MM-dd} → {@code {items[{sessionId, activityType, startedAt, endedAt, finalStatus, endReason,
 *       linkable, goalCount, interrupted}]}}. month · date는 둘 중 하나만(아니면 400).
 *   <li>{@code GET /api/records/{sessionId}} → {@code {session, goals, utterances, gaps, recordScope, endConfirmed}}. 남의 · 없는 ·
 *       아직 기록 없음 · FAILED는 404.
 * </ul>
 *
 * <p>계정 삭제가 접수됐으면 403(U1 GuardianPrincipal). 판정값 · 구조화 결과 · 08 원문 · 원음 경로는 응답 모양에 없다(FR4.3 · A10).
 */
@RestController
public class RecordController {

    private final RecordQueryService records;
    private final GuardianRepository guardians;

    public RecordController(RecordQueryService records, GuardianRepository guardians) {
        this.records = Objects.requireNonNull(records, "records");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
    }

    @GetMapping("/api/records")
    ResponseEntity<?> list(
            @AuthenticationPrincipal GuardianPrincipal guardian,
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "date", required = false) String date) {
        UUID guardianId = GuardianPrincipal.requireActive(guardian, guardians);
        if ((month == null) == (date == null)) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (month != null) {
            YearMonth parsed = RecordCalendar.month(month).orElseThrow(() -> CoreApiException.of(ErrorCode.VALIDATION_FAILED));
            List<DayBody> days = records.days(guardianId, parsed).stream()
                    .map(day -> new DayBody(day.day().toString(), day.count()))
                    .toList();
            return ApiResponses.json(200, new MonthBody(days));
        }
        LocalDate parsed = RecordCalendar.date(date).orElseThrow(() -> CoreApiException.of(ErrorCode.VALIDATION_FAILED));
        return ApiResponses.json(200, new DateBody(records.onDate(guardianId, parsed).stream().map(ItemBody::of).toList()));
    }

    @GetMapping("/api/records/{sessionId}")
    ResponseEntity<DetailBody> detail(
            @AuthenticationPrincipal GuardianPrincipal guardian, @PathVariable("sessionId") String sessionId) {
        UUID guardianId = GuardianPrincipal.requireActive(guardian, guardians);
        UUID id = ApiResponses.uuid(sessionId).orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        return ApiResponses.json(200, DetailBody.of(records.detail(guardianId, id)));
    }

    /** 달 응답. */
    public record MonthBody(List<DayBody> days) {
    }

    /** 날짜 하나(yyyy-MM-dd)와 기록 수. */
    public record DayBody(String date, int count) {
    }

    /** 날 응답. */
    public record DateBody(List<ItemBody> items) {
    }

    /** 26 그날 목록 한 줄. */
    public record ItemBody(
            UUID sessionId,
            ActivityType activityType,
            String startedAt,
            String endedAt,
            SessionStatus finalStatus,
            EndReason endReason,
            boolean linkable,
            int goalCount,
            boolean interrupted) {

        static ItemBody of(ListItem item) {
            StoredRecord record = item.record();
            return new ItemBody(record.sessionId(), ActivityType.fromWire(record.activityType()),
                    ApiResponses.time(record.startedAt()), ApiResponses.time(record.endedAt()),
                    SessionStatus.fromWire(record.finalStatus()), SessionViewService.parse(EndReason.class, record.endReason()),
                    item.linkable(), item.goalCount(), item.interrupted());
        }
    }

    /** 27 머리(SessionSummary 모양 — 상태는 기록의 최종 상태). */
    public record SummaryBody(
            UUID sessionId,
            ActivityType activityType,
            SessionStatus status,
            EndReason endReason,
            FailureCode failureCode,
            boolean confirmed,
            String requestedAt,
            String startedAt,
            String endedAt, String endDetail) {
    }

    /** 27 연습 목표(이름 · 순서 · 상태). */
    public record GoalBody(String displayName, int order, String status) {
    }

    /** 끊김 · 미확인 구간. */
    public record GapBody(String from, String to, String kind) {
    }

    /** 기록 범위. */
    public record ScopeBody(String from, String to) {
    }

    /** 27 응답. utterances는 live-view Bubble 모양({@code final}이 자바 예약어라 지도로 싣는다). */
    public record DetailBody(
            SummaryBody session,
            List<GoalBody> goals,
            List<Map<String, Object>> utterances,
            List<GapBody> gaps,
            ScopeBody recordScope,
            boolean endConfirmed) {

        static DetailBody of(Detail detail) {
            StoredRecord record = detail.record();
            SummaryBody session = new SummaryBody(record.sessionId(), ActivityType.fromWire(record.activityType()),
                    SessionStatus.fromWire(record.finalStatus()), SessionViewService.parse(EndReason.class, record.endReason()),
                    SessionViewService.parse(FailureCode.class, detail.copy().failureCode()), record.confirmed(),
                    ApiResponses.time(detail.copy().requestedAt()), ApiResponses.time(record.startedAt()),
                    ApiResponses.time(record.endedAt()), record.endDetail());
            List<Map<String, Object>> utterances = detail.utterances().stream().map(bubble -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("seq", bubble.seq());
                body.put("speaker", bubble.speaker());
                body.put("text", bubble.text());
                body.put("final", true);
                body.put("uncertain", bubble.uncertain());
                body.put("startedAt", ApiResponses.time(bubble.startedAt()));
                body.put("endedAt", ApiResponses.time(bubble.endedAt()));
                return body;
            }).toList();
            return new DetailBody(session,
                    detail.goals().stream().map(goal -> new GoalBody(goal.displayName(), goal.order(), goal.status())).toList(),
                    utterances,
                    detail.gaps().stream().map(RecordController::gap).toList(),
                    Optional.ofNullable(detail.recordScope())
                            .map(scope -> new ScopeBody(ApiResponses.time(scope.from()), ApiResponses.time(scope.to())))
                            .orElse(null),
                    detail.endConfirmed());
        }

        @Override
        public String toString() {
            return "DetailBody[sessionId=" + session.sessionId() + ", utterances=" + utterances.size() + "]";
        }
    }

    private static GapBody gap(LiveExtras.Gap gap) {
        return new GapBody(ApiResponses.time(gap.from()), ApiResponses.time(gap.to()), gap.kind());
    }
}
