package kr.mujoong.core.admin;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.admin.AdminActivityRepository.ActivityRow;
import kr.mujoong.core.admin.AdminGuardianRepository.ChildRow;
import kr.mujoong.core.admin.AdminGuardianRepository.DeviceRow;
import kr.mujoong.core.admin.AdminSceneRepository.SceneRow;
import kr.mujoong.core.admin.AdminViews.Acceptance;
import kr.mujoong.core.admin.AdminViews.ActivityDetail;
import kr.mujoong.core.admin.AdminViews.ActivitySummary;
import kr.mujoong.core.admin.AdminViews.Averages;
import kr.mujoong.core.admin.AdminViews.ChildEntry;
import kr.mujoong.core.admin.AdminViews.DailyCount;
import kr.mujoong.core.admin.AdminViews.DeviceEntry;
import kr.mujoong.core.admin.AdminViews.EndReasonCount;
import kr.mujoong.core.admin.AdminViews.GuardianSummary;
import kr.mujoong.core.admin.AdminViews.HelpRecord;
import kr.mujoong.core.admin.AdminViews.Identity;
import kr.mujoong.core.admin.AdminViews.KindCount;
import kr.mujoong.core.admin.AdminViews.Overview;
import kr.mujoong.core.admin.AdminViews.Page;
import kr.mujoong.core.admin.AdminViews.Quality;
import kr.mujoong.core.admin.AdminViews.QualityRecord;
import kr.mujoong.core.admin.AdminViews.SceneResult;
import kr.mujoong.core.admin.AdminViews.TranscriptLine;
import kr.mujoong.core.admin.RealtimeAdminClient.SceneRunFacts;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.record.RecordCalendar;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.stereotype.Service;

/**
 * 관리자 대시보드 · 활동 분석 · 연습 품질 조회 (JIRA-280). 저장된 기록만 읽어 보여 주고 아무것도 바꾸지 않는다.
 *
 * <p>시작한 활동(끝 분류가 DECLINED · NOT_STARTED가 아님)만 활동 수 · 평균 · 일별 수에 넣고, 시작하지 못한 활동은 끝 분류 집계에만 따로 센다.
 * 연습 장면의 횟수 · 도움 기록은 Realtime 내부 조회로 받는다(실패하면 503).
 */
@Service
public class AdminQueryService {

    /** 연습 품질 목록에 싣는 최대 활동 수(최근 순). 전체 수는 recordsTotal로 따로 준다. */
    public static final int QUALITY_RECORDS_LIMIT = 100;
    /** 대시보드 최근 활동 수. */
    static final int RECENT = 5;

    private static final Set<String> NOT_STARTED = Set.of("DECLINED", "NOT_STARTED");
    private static final List<String> HELP_KINDS = List.of("FOCUS_HELP", "SITUATION_EXPLAIN", "EXPRESSION_EXAMPLE");

    private final AdminActivityRepository activities;
    private final AdminGuardianRepository guardians;
    private final AdminSceneRepository scenes;
    private final RealtimeAdminClient realtime;
    private final AdminAudit audit;
    private final JsonCodec json;

    public AdminQueryService(AdminActivityRepository activities, AdminGuardianRepository guardians, AdminSceneRepository scenes,
            RealtimeAdminClient realtime, AdminAudit audit, JsonCodec json) {
        this.activities = Objects.requireNonNull(activities, "activities");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.realtime = Objects.requireNonNull(realtime, "realtime");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.json = Objects.requireNonNull(json, "json");
    }

    public Identity me(UUID adminId) {
        return new Identity("관리자 " + AdminViews.shortId(adminId), "ADMIN");
    }

    public Overview overview(AdminFilter filter) {
        List<ActivityRow> rows = activities.all(filter);
        List<ActivityRow> started = rows.stream().filter(AdminQueryService::started).toList();
        Map<LocalDate, int[]> daily = new LinkedHashMap<>();
        filter.fromDate().datesUntil(filter.toDate().plus(Period.ofDays(1))).forEach(day -> daily.put(day, new int[2]));
        for (ActivityRow row : started) {
            int[] counts = daily.get(row.activityAt().atZone(RecordCalendar.ZONE).toLocalDate());
            if (counts != null) {
                counts[practice(row) ? 1 : 0]++;
            }
        }
        Map<String, Integer> categories = new HashMap<>();
        rows.forEach(row -> categories.merge(row.endCategory(), 1, Integer::sum));
        return new Overview(started.size(),
                (int) started.stream().filter(row -> !practice(row)).count(),
                (int) started.stream().filter(AdminQueryService::practice).count(),
                (int) started.stream().map(ActivityRow::childId).distinct().count(),
                sumKnown(rows, ActivityRow::childTurns), sumKnown(rows, ActivityRow::aiTurns),
                new Averages(average(started, row -> true), average(started, row -> !practice(row)),
                        average(started, AdminQueryService::practice)),
                daily.entrySet().stream()
                        .map(entry -> new DailyCount(entry.getKey().toString(), entry.getValue()[0], entry.getValue()[1]))
                        .toList(),
                AdminViews.END_CATEGORY_ORDER.stream()
                        .map(code -> new EndReasonCount(code, AdminViews.END_CATEGORY_LABEL.get(code),
                                categories.getOrDefault(code, 0)))
                        .toList(),
                rows.stream().limit(RECENT).map(AdminQueryService::summary).toList());
    }

    public Page<ActivitySummary> activities(AdminFilter filter, int page, int pageSize) {
        int total = activities.count(filter);
        List<ActivitySummary> items = activities.page(filter, page * pageSize, pageSize).stream()
                .map(AdminQueryService::summary).toList();
        return new Page<>(items, page, pageSize, total);
    }

    /** 활동 상세. 부를 때마다 열람 기록을 남긴다. */
    public ActivityDetail activity(UUID adminId, UUID sessionId) {
        ActivityRow row = activities.find(sessionId).orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        boolean available = row.hasRecord() && !"DECLINED".equals(row.endCategory());
        List<TranscriptLine> transcript = available
                ? activities.transcript(sessionId).map(this::transcriptLines).orElse(List.of())
                : List.of();
        List<SceneResult> sceneResults = List.of();
        if (practice(row) && started(row)) {
            Map<UUID, List<SceneRunFacts>> facts = realtime.facts(List.of(sessionId));
            sceneResults = sceneResults(scenes.scenes(List.of(sessionId)), facts.getOrDefault(sessionId, List.of()));
        }
        audit.recordViewed(adminId, sessionId);
        ActivitySummary s = summary(row);
        return new ActivityDetail(s.id(), s.guardianId(), s.guardianName(), s.deviceId(), s.childId(), s.childName(), s.type(),
                s.status(), s.endCategory(), s.startedAt(), s.endedAt(), s.durationSeconds(), s.childTurns(), s.aiTurns(),
                s.endReason(), available, transcript, sceneResults);
    }

    public Page<GuardianSummary> guardians(AdminFilter filter, int page, int pageSize) {
        int total = guardians.count(filter);
        List<UUID> ids = guardians.page(filter, page * pageSize, pageSize);
        Map<UUID, List<ChildRow>> children = guardians.children(ids).stream()
                .collect(Collectors.groupingBy(ChildRow::guardianId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<DeviceRow>> devices = guardians.registeredDevices(ids).stream()
                .collect(Collectors.groupingBy(DeviceRow::guardianId, LinkedHashMap::new, Collectors.toList()));
        List<AdminGuardianRepository.DeviceChildCount> counts = guardians.activityCounts(
                children.values().stream().flatMap(List::stream).map(ChildRow::id).toList(), filter);
        Map<UUID, Integer> byChild = new HashMap<>();
        Map<String, Integer> byDeviceChild = new HashMap<>();
        for (AdminGuardianRepository.DeviceChildCount count : counts) {
            byChild.merge(count.childId(), count.count(), Integer::sum);
            byDeviceChild.put(count.deviceId() + "/" + count.childId(), count.count());
        }
        List<GuardianSummary> items = new ArrayList<>();
        for (UUID id : ids) {
            List<ChildRow> kids = children.getOrDefault(id, List.of());
            // 기기 아래 아이의 활동 수는 그 기기로 한 활동만 센다. 보호자 활동 수는 해제된 과거 기기의 활동까지 센다
            List<DeviceEntry> deviceEntries = devices.getOrDefault(id, List.of()).stream()
                    .map(device -> new DeviceEntry(device.id().toString(), AdminViews.deviceName(device.id()), kids.stream()
                            .map(child -> new ChildEntry(child.id().toString(), child.displayName(),
                                    byDeviceChild.getOrDefault(device.id() + "/" + child.id(), 0)))
                            .toList()))
                    .toList();
            items.add(new GuardianSummary(id.toString(), AdminViews.guardianName(id),
                    kids.stream().mapToInt(child -> byChild.getOrDefault(child.id(), 0)).sum(), deviceEntries));
        }
        return new Page<>(items, page, pageSize, total);
    }

    public Quality quality(AdminFilter filter) {
        if (AdminFilter.FREE_TALK.equals(filter.type())) {
            return emptyQuality();
        }
        AdminFilter practiceOnly = filter.withType(AdminFilter.PRACTICE);
        List<ActivityRow> rows = activities.all(practiceOnly);
        List<ActivityRow> started = rows.stream().filter(AdminQueryService::started).toList();
        List<UUID> startedIds = started.stream().map(ActivityRow::sessionId).toList();
        Map<UUID, List<SceneRunFacts>> facts = startedIds.isEmpty() ? Map.of() : realtime.facts(startedIds);
        Map<UUID, List<SceneRow>> sceneRows = scenes.scenes(startedIds).stream()
                .collect(Collectors.groupingBy(SceneRow::sessionId));

        int helpExecutions = 0;
        int retries = 0;
        int focusExamples = 0;
        Map<String, Integer> kinds = new HashMap<>();
        Map<HelpProjection.Offer, Integer> offers = new EnumMap<>(HelpProjection.Offer.class);
        Map<String, Integer> answers = new HashMap<>();
        for (UUID sessionId : startedIds) {
            List<SceneRunFacts> runs = facts.getOrDefault(sessionId, List.of());
            Set<UUID> runIds = new HashSet<>();
            for (SceneRunFacts run : runs) {
                runIds.add(run.sceneRunId());
                retries += run.retryCount() == null ? 0 : run.retryCount();
                for (RealtimeAdminClient.HelpFact help : run.helps()) {
                    if (HelpProjection.EXECUTED_KINDS.contains(help.kind())) {
                        helpExecutions++;
                        kinds.merge(help.kind(), 1, Integer::sum);
                        if ("FOCUS_HELP".equals(help.kind()) && help.exampleGiven()) {
                            focusExamples++;
                        }
                    }
                }
                count(HelpProjection.of(run), offers, answers);
            }
            // 장면 실행 기록이 없는 판정 장면(레거시 흐름 · 기록 실패)은 확인 불가로 센다
            for (SceneRow scene : sceneRows.getOrDefault(sessionId, List.of())) {
                if (scene.sceneRunId() == null || !runIds.contains(scene.sceneRunId())) {
                    count(HelpProjection.of(null), offers, answers);
                }
            }
        }
        int accepted = answers.getOrDefault("ACCEPT", 0);
        int declined = answers.getOrDefault("DECLINE", 0);
        Acceptance acceptance = new Acceptance(accepted, declined, answers.getOrDefault("UNCLEAR", 0),
                answers.getOrDefault("REQUESTED", 0), null, offers.getOrDefault(HelpProjection.Offer.NOT_OFFERED, 0),
                offers.getOrDefault(HelpProjection.Offer.UNKNOWN, 0),
                accepted + declined == 0 ? null : (double) accepted / (accepted + declined));

        List<QualityRecord> records = rows.stream().limit(QUALITY_RECORDS_LIMIT)
                .map(row -> new QualityRecord(summary(row), started(row)
                        ? sceneResults(sceneRows.getOrDefault(row.sessionId(), List.of()),
                                facts.getOrDefault(row.sessionId(), List.of()))
                        : List.of()))
                .toList();
        return new Quality(started.size(), helpExecutions, retries, acceptance,
                HELP_KINDS.stream().map(kind -> new KindCount(kind, kinds.getOrDefault(kind, 0))).toList(), focusExamples,
                records, rows.size(), QUALITY_RECORDS_LIMIT);
    }

    /** 판정 장면에 Realtime 장면 실행을 붙인다. 판정이 아직 없으면(결과 정리 전) 장면 실행만으로 미판정 장면을 만든다. */
    List<SceneResult> sceneResults(List<SceneRow> rows, List<SceneRunFacts> runs) {
        Map<UUID, SceneRunFacts> byId = runs.stream().filter(run -> run.sceneRunId() != null)
                .collect(Collectors.toMap(SceneRunFacts::sceneRunId, run -> run, (a, b) -> a));
        if (rows.isEmpty()) {
            List<SceneResult> pending = new ArrayList<>();
            for (int i = 0; i < runs.size(); i++) {
                SceneRunFacts run = runs.get(i);
                pending.add(scene(run.sceneRunId().toString(), "장면 " + (i + 1) + " · 결과 정리 전",
                        "VARIANT".equals(run.sceneKind()) ? "VARIANT" : "BASE", "NOT_EVALUATED", null, run.liveTiming(),
                        run.variantSkipReason(), run));
            }
            return pending;
        }
        return rows.stream().map(row -> {
            SceneRunFacts run = row.sceneRunId() == null ? null : byId.get(row.sceneRunId());
            String title = (row.goalName() == null ? "목표 " + row.goalOrder() : row.goalName()) + " · 장면 " + row.sceneNo();
            String reason = row.rationale() != null ? row.rationale() : AdminViews.notDoneLabel(row.notDoneReason());
            String timing = row.timing() != null ? row.timing() : run == null ? null : run.liveTiming();
            String skip = row.variantSkipReason() != null ? row.variantSkipReason() : run == null ? null : run.variantSkipReason();
            return scene(row.id().toString(), title, "ORIGINAL".equals(row.exposureType()) ? "BASE" : "VARIANT",
                    judgment(row.value()), reason, timing, skip, run);
        }).toList();
    }

    private static SceneResult scene(String id, String title, String kind, String judgment, String reason, String timing,
            String skip, SceneRunFacts run) {
        HelpProjection.Result help = HelpProjection.of(run);
        List<HelpRecord> helps = run == null ? List.of() : run.helps().stream()
                .filter(record -> HelpProjection.EXECUTED_KINDS.contains(record.kind()))
                .map(record -> new HelpRecord(record.helpId().toString(), record.kind(), record.consentAnswer(),
                        record.exampleGiven()))
                .toList();
        return new SceneResult(id, title, kind, judgment, reason, timing, null, help.answer(), help.offer().name(), skip,
                run == null ? null : run.retryCount(), run == null ? null : run.reexplainCount(), helps);
    }

    static String judgment(String value) {
        if (value == null) {
            return "NOT_EVALUATED";
        }
        return switch (value) {
            case "functional" -> "SUCCESS";
            case "partial", "none" -> "FAILURE";
            default -> "ABSTAINED";
        };
    }

    static ActivitySummary summary(ActivityRow row) {
        Double duration = null;
        if (started(row) && row.startedAt() != null && row.endedAt() != null) {
            duration = Duration.between(row.startedAt(), row.endedAt()).toMillis() / 1000.0;
        }
        return new ActivitySummary(row.sessionId().toString(), row.guardianId().toString(),
                AdminViews.guardianName(row.guardianId()), row.deviceId().toString(), row.childId().toString(), row.childName(),
                row.activityType(), row.uiStatus(), row.endCategory(), ApiResponses.time(row.activityAt()),
                ApiResponses.time(row.endedAt()), duration, row.childTurns(), row.aiTurns(),
                AdminViews.endReasonLabel(row.endCategory(), row.endReason(), row.failureCode(), row.endDetail()));
    }

    private List<TranscriptLine> transcriptLines(String transcriptJson) {
        List<TranscriptLine> lines = new ArrayList<>();
        Object[] items = json.read(transcriptJson, Object[].class);
        for (Object item : items == null ? new Object[0] : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object seq = map.get("seq");
            Object speaker = map.get("speaker");
            Object text = map.get("text");
            Object startedAt = map.get("startedAt");
            if (!(seq instanceof Number number) || !("CHILD".equals(speaker) || "AI".equals(speaker))
                    || !(text instanceof String content) || !(startedAt instanceof String at)) {
                continue;
            }
            Instant parsed;
            try {
                parsed = Instant.parse(at);
            } catch (java.time.format.DateTimeParseException e) {
                continue;
            }
            lines.add(new TranscriptLine(number.longValue(), (String) speaker, content, ApiResponses.time(parsed)));
        }
        lines.sort(Comparator.comparing(TranscriptLine::at).thenComparingLong(TranscriptLine::sequence));
        return lines;
    }

    private static void count(HelpProjection.Result result, Map<HelpProjection.Offer, Integer> offers,
            Map<String, Integer> answers) {
        offers.merge(result.offer(), 1, Integer::sum);
        if (result.answer() != null) {
            answers.merge(result.answer(), 1, Integer::sum);
        }
    }

    private static Quality emptyQuality() {
        return new Quality(0, 0, 0, new Acceptance(0, 0, 0, 0, null, 0, 0, null),
                HELP_KINDS.stream().map(kind -> new KindCount(kind, 0)).toList(), 0, List.of(), 0, QUALITY_RECORDS_LIMIT);
    }

    private static boolean started(ActivityRow row) {
        return !NOT_STARTED.contains(row.endCategory());
    }

    private static boolean practice(ActivityRow row) {
        return AdminFilter.PRACTICE.equals(row.activityType());
    }

    private static Integer sumKnown(List<ActivityRow> rows, java.util.function.Function<ActivityRow, Integer> value) {
        List<Integer> known = rows.stream().map(value).filter(Objects::nonNull).toList();
        return known.isEmpty() ? null : known.stream().mapToInt(Integer::intValue).sum();
    }

    private static Double average(List<ActivityRow> rows, Predicate<ActivityRow> include) {
        List<Long> millis = rows.stream().filter(include)
                .filter(row -> row.startedAt() != null && row.endedAt() != null)
                .map(row -> Duration.between(row.startedAt(), row.endedAt()).toMillis())
                .toList();
        return millis.isEmpty() ? null : millis.stream().mapToLong(Long::longValue).average().orElseThrow() / 1000.0;
    }
}
