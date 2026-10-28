package kr.mujoong.core.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.admin.AdminActivityRepository.ActivityRow;
import kr.mujoong.core.admin.AdminPricingProperties.TokenRates;
import kr.mujoong.core.admin.AdminUsageRepository.Aggregate;
import kr.mujoong.core.admin.AdminViews.CostDetail;
import kr.mujoong.core.admin.AdminViews.CostPhase;
import kr.mujoong.core.admin.AdminViews.CostSummary;
import kr.mujoong.core.admin.AdminViews.CostTotals;
import kr.mujoong.core.admin.AdminViews.Costs;
import kr.mujoong.core.admin.AdminViews.Page;
import kr.mujoong.core.admin.RealtimeAdminClient.UsageAggregate;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.stereotype.Service;

/**
 * 관리자 API · 비용 조회 (JIRA-280). 저장된 토큰 · 음성 초에 현재 설정 단가(USD)를 곱한 예상 비용이다. 실제 청구액이 아니다.
 *
 * <ul>
 *   <li>목록 · 합계는 호출 완료(관측) 시각이 기간 안인 호출만 센다. 상세는 그 활동의 전체 기간이라 목록 소계와 다를 수 있다.
 *   <li>분류: ACTIVITY(끝난 활동에 연결) · FAILED_TO_START(활동 행 없이 연결된 연습 준비 호출) · UNLINKED(보호자 연결이 없는 Core 호출).
 *       지금 수집 구조에는 활동과 무관한 호출이 없어 NON_ACTIVITY 항목은 나오지 않는다(합계는 수집 범위 밖이라 null).
 *   <li>단계: 장면 생성 · 검수 → PREPARATION, 결과 문장 · 목표 추천 · 재판정 · 시드 생성 → POST_PROCESSING(Core Responses 단가), Realtime 음성
 *       → VOICE(분당 단가), Realtime 진행 중 판정 → JUDGMENT(Decisions 단가, 출력 과금 없음).
 *   <li>측정된 호출(토큰 · 초가 있는 행)만 금액을 계산한다. 측정되지 않은 호출 수는 unknownCount로 따로 주고 0원으로 더하지 않는다. 수집 중
 *       빠져 저장되지 않은 호출은 알 수 없다.
 *   <li>캐시 읽기 토큰을 모르는 측정 호출은 캐시 0으로 계산하되(실제보다 크게 잡힐 수 있다) 상태를 COMPLETE가 아니라 PARTIAL로 둔다(JIRA-287).
 *   <li>기간 안 활동(활동 시각 기준) 가운데 저장된 사용량이 하나도 없는 활동은 NOT_COLLECTED 항목으로 목록에 넣는다. 금액 · 토큰은 null이라
 *       합계는 바뀌지 않는다(JIRA-287).
 * </ul>
 */
@Service
public class AdminCostService {

    static final String LIST_SCOPE = "수집된 사용량 가운데 금액을 계산할 수 있는 호출 기준의 예상 비용(USD, 현재 설정 단가)입니다. 실제 청구액이"
            + " 아닙니다. 호출 완료 시각이 기간 안인 사용량만 세고, 수집하지 못해 저장되지 않은 호출 · 일부만 수집된 기간의 빠진 대화는 들어 있지"
            + " 않습니다. 저장된 사용량이 하나도 없는 활동은 활동 시각 기준으로 '미수집'(NOT_COLLECTED)으로 보이고, 캐시 읽기 토큰을 모르는"
            + " 호출은 캐시 0으로 계산해 '일부 수집'(PARTIAL)으로 보입니다. 저장된 사용량이 없는 합계는 0이 아니라 확인 불가(null)입니다."
            + " 단가를 바꾸면 지난 비용도 새 단가로 다시 계산되고, 요청당 입력 272K 토큰 초과 할증과 Realtime 판정의 캐시 쓰기 요금은 반영하지 않습니다.";
    static final String DETAIL_SCOPE = "이 항목의 전체 기간 사용량 기준입니다(목록의 기간 소계와 다를 수 있습니다). 현재 설정 단가(USD)로 계산한"
            + " 예상 비용이며 실제 청구액이 아닙니다.";

    private static final Map<String, String> PURPOSE_PHASE = Map.of(
            "SCENARIO_GENERATION", "PREPARATION",
            "SCENARIO_REVIEW", "PREPARATION",
            "RESULT_TEXT", "POST_PROCESSING",
            "GOAL_CANDIDATES", "POST_PROCESSING",
            "REJUDGMENT", "POST_PROCESSING",
            "SEED_GENERATION", "POST_PROCESSING");
    private static final Map<String, String> PURPOSE_LABEL = Map.of(
            "SCENARIO_GENERATION", "연습 장면 생성",
            "SCENARIO_REVIEW", "연습 장면 검수",
            "RESULT_TEXT", "결과 문장",
            "GOAL_CANDIDATES", "목표 추천",
            "REJUDGMENT", "장면 재판정",
            "SEED_GENERATION", "목표 시드 생성");
    private static final List<String> PHASE_ORDER = List.of("PREPARATION", "VOICE", "JUDGMENT", "POST_PROCESSING", "OTHER");

    private final AdminActivityRepository activities;
    private final AdminUsageRepository usage;
    private final RealtimeAdminClient realtime;
    private final AdminPricingProperties pricing;

    public AdminCostService(AdminActivityRepository activities, AdminUsageRepository usage, RealtimeAdminClient realtime,
            AdminPricingProperties pricing) {
        this.activities = Objects.requireNonNull(activities, "activities");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.realtime = Objects.requireNonNull(realtime, "realtime");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
    }

    /**
     * 사용량 출처 하나(Core 묶음 또는 Realtime 묶음)를 단계 계산 입력으로 바꾼 값. 표시용 합(voiceSeconds · inputTokens · …)은 아는 값을
     * 필드마다 더한 것이고, 금액은 측정된 호출(입력 · 출력이 모두 있음, 음성은 초 있음)의 합({@code measured*})으로만 계산한다.
     * {@code cacheUnknownCalls}는 측정된 호출 가운데 캐시 읽기 토큰을 모르는 호출 수다.
     */
    record Usage(String phase, String model, String rate, int calls, int measuredCalls, int knownCalls, int completeCalls,
            int cacheUnknownCalls, Double voiceSeconds, Long inputTokens, Long outputTokens, Long cachedInputTokens,
            Long cacheWriteTokens, Long reasoningTokens, Long measuredInputTokens, Long measuredOutputTokens,
            Long measuredCachedInputTokens, Long measuredCacheWriteTokens, Instant lastObservedAt) {
    }

    /** 계산된 항목(요약과 단계, 측정 안 된 호출 수). */
    record Item(CostSummary summary, List<CostPhase> phases, int unmeasuredCalls, BigDecimal usd, Instant observedAt) {
    }

    public Costs costs(AdminFilter filter, int page, int pageSize) {
        Map<UUID, List<Usage>> bySession = new LinkedHashMap<>();
        for (Aggregate row : usage.linkedBetween(filter.from(), filter.to())) {
            bySession.computeIfAbsent(row.key(), key -> new ArrayList<>()).add(core(row));
        }
        for (UsageAggregate row : realtime.usageBetween(filter.from(), filter.to())) {
            bySession.computeIfAbsent(row.sessionId(), key -> new ArrayList<>()).add(realtime(row));
        }
        Map<UUID, ActivityRow> activityRows = new HashMap<>();
        activities.findAll(bySession.keySet()).forEach(row -> activityRows.put(row.sessionId(), row));
        Set<UUID> known = usage.activitySessions(bySession.keySet());
        boolean unfiltered = filter.type() == null && filter.guardianId() == null && filter.childId() == null
                && filter.deviceId() == null && filter.status() == null && filter.query() == null;

        List<Item> activityItems = new ArrayList<>();
        List<Item> otherItems = new ArrayList<>();
        for (Map.Entry<UUID, List<Usage>> entry : bySession.entrySet()) {
            UUID sessionId = entry.getKey();
            ActivityRow row = activityRows.get(sessionId);
            if (row != null) {
                if (matches(row, filter)) {
                    activityItems.add(item(sessionId.toString(), row, "ACTIVITY", activityPurpose(row), entry.getValue(),
                            row.activityAt()));
                }
            } else if (!known.contains(sessionId) && unfiltered) {
                otherItems.add(item(sessionId.toString(), null, "FAILED_TO_START", "연습 준비(시작 실패)", entry.getValue(),
                        null));
            }
        }
        notCollected(filter, bySession.keySet()).forEach(row -> activityItems.add(item(row.sessionId().toString(), row,
                "ACTIVITY", activityPurpose(row), List.of(), row.activityAt())));
        if (unfiltered) {
            Map<UUID, List<Usage>> byCall = new LinkedHashMap<>();
            Map<UUID, String> callPurpose = new HashMap<>();
            for (Aggregate row : usage.unlinkedBetween(filter.from(), filter.to())) {
                byCall.computeIfAbsent(row.key(), key -> new ArrayList<>()).add(core(row));
                callPurpose.putIfAbsent(row.key(), row.purpose());
            }
            byCall.forEach((callId, rows) -> otherItems.add(item(callId.toString(), null, "UNLINKED",
                    "연결 미확인 · " + PURPOSE_LABEL.getOrDefault(callPurpose.get(callId), "기타 호출"), rows, null)));
        }
        Comparator<Item> recent = Comparator.comparing(Item::observedAt).reversed()
                .thenComparing(item -> item.summary().id(), Comparator.reverseOrder());
        activityItems.sort(recent);
        otherItems.sort(recent);

        List<Item> all = new ArrayList<>(activityItems);
        all.addAll(otherItems);
        CostTotals totals = new CostTotals(total(all, item -> true), total(activityItems, item -> true),
                total(otherItems, item -> "FAILED_TO_START".equals(item.summary().category())), null,
                total(otherItems, item -> "UNLINKED".equals(item.summary().category())),
                all.stream().mapToInt(Item::unmeasuredCalls).sum());
        List<CostSummary> pageItems = activityItems.stream().skip((long) page * pageSize).limit(pageSize)
                .map(Item::summary).toList();
        return new Costs(totals, new Page<>(pageItems, page, pageSize, activityItems.size()),
                otherItems.stream().map(Item::summary).toList(), LIST_SCOPE);
    }

    /**
     * 기간 안(활동 시각 기준) 필터에 맞는 활동 가운데 Core · Realtime 어디에도 저장된 사용량이 없는 활동. 기간 밖에서 관측된 사용량만 있는 활동은
     * 미수집이 아니라 이 기간 목록에 나오지 않는다.
     */
    private List<ActivityRow> notCollected(AdminFilter filter, Set<UUID> listed) {
        List<ActivityRow> candidates = activities.all(filter).stream().filter(row -> !listed.contains(row.sessionId())).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = candidates.stream().map(ActivityRow::sessionId).toList();
        Set<UUID> withUsage = new HashSet<>(usage.sessionsWithUsage(ids));
        realtime.usageOf(ids).forEach(aggregate -> withUsage.add(aggregate.sessionId()));
        return candidates.stream().filter(row -> !withUsage.contains(row.sessionId())).toList();
    }

    /** 항목 상세(전체 기간). id는 활동 · 준비 id 또는 연결 미확인 호출 id다. */
    public CostDetail detail(UUID id) {
        Optional<ActivityRow> row = activities.find(id);
        Item item;
        if (row.isPresent()) {
            List<Usage> rows = new ArrayList<>(usage.linkedOf(id).stream().map(this::core).toList());
            realtime.usageOf(List.of(id)).stream().filter(aggregate -> id.equals(aggregate.sessionId()))
                    .forEach(aggregate -> rows.add(realtime(aggregate)));
            item = item(id.toString(), row.get(), "ACTIVITY", activityPurpose(row.get()), rows, row.get().activityAt());
        } else {
            List<Aggregate> linked = usage.linkedOf(id);
            if (!linked.isEmpty() && usage.activitySessions(List.of(id)).isEmpty()) {
                item = item(id.toString(), null, "FAILED_TO_START", "연습 준비(시작 실패)",
                        linked.stream().map(this::core).toList(), null);
            } else {
                List<Aggregate> unlinked = usage.unlinkedCall(id);
                if (unlinked.isEmpty()) {
                    throw CoreApiException.of(ErrorCode.NOT_FOUND);
                }
                item = item(id.toString(), null, "UNLINKED",
                        "연결 미확인 · " + PURPOSE_LABEL.getOrDefault(unlinked.getFirst().purpose(), "기타 호출"),
                        unlinked.stream().map(this::core).toList(), null);
            }
        }
        CostSummary s = item.summary();
        return new CostDetail(s.id(), s.activityId(), s.childName(), s.type(), s.category(), s.purpose(), s.observedAt(),
                s.status(), s.voiceSeconds(), s.inputTokens(), s.outputTokens(), s.estimatedUsd(), item.phases(), DETAIL_SCOPE);
    }

    Item item(String id, ActivityRow activity, String category, String purpose, List<Usage> rows, Instant fallbackAt) {
        Map<String, List<Usage>> grouped = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing((Usage usage) -> PHASE_ORDER.indexOf(usage.phase()))
                        .thenComparing(usage -> usage.model() == null ? "" : usage.model()))
                .forEach(usage -> grouped.computeIfAbsent(usage.phase() + "\u0000" + usage.model() + "\u0000" + usage.rate(),
                        key -> new ArrayList<>()).add(usage));
        List<CostPhase> phases = new ArrayList<>();
        BigDecimal usd = null;
        int unmeasured = 0;
        Double voice = null;
        Long input = null;
        Long output = null;
        for (List<Usage> group : grouped.values()) {
            Usage merged = merge(group);
            BigDecimal phaseUsd = cost(merged);
            phases.add(new CostPhase(merged.phase(), merged.model() == null ? "UNKNOWN" : merged.model(), merged.calls(),
                    merged.voiceSeconds(), merged.inputTokens(), merged.outputTokens(), merged.cachedInputTokens(),
                    merged.cacheWriteTokens(), merged.reasoningTokens(), UsageCost.money(phaseUsd), status(merged),
                    calculation(merged)));
            usd = add(usd, phaseUsd);
            unmeasured += merged.calls() - merged.measuredCalls();
            if (merged.voiceSeconds() != null) {
                voice = (voice == null ? 0 : voice) + merged.voiceSeconds();
            }
            if (merged.inputTokens() != null) {
                input = (input == null ? 0 : input) + merged.inputTokens();
            }
            if (merged.outputTokens() != null) {
                output = (output == null ? 0 : output) + merged.outputTokens();
            }
        }
        // 목록 시각은 마지막 호출 완료 시각이다. 사용량이 하나도 없는 활동 상세만 활동 시각을 쓴다
        Instant observed = rows.stream().map(Usage::lastObservedAt).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .orElse(fallbackAt);
        String itemStatus = phases.isEmpty() ? "NOT_COLLECTED"
                : phases.stream().allMatch(phase -> "COMPLETE".equals(phase.status())) ? "COMPLETE"
                : phases.stream().allMatch(phase -> "UNKNOWN".equals(phase.status())) ? "UNKNOWN" : "PARTIAL";
        CostSummary summary = new CostSummary(id, activity == null ? null : activity.sessionId().toString(),
                activity == null ? null : activity.childName(), activity == null ? null : activity.activityType(), category,
                purpose, ApiResponses.time(observed), itemStatus, voice, input, output, UsageCost.money(usd));
        return new Item(summary, phases, unmeasured, usd, observed);
    }

    private Usage core(Aggregate row) {
        return new Usage(PURPOSE_PHASE.getOrDefault(row.purpose(), "OTHER"), row.model(), "RESPONSES", row.calls(),
                row.measuredCalls(), row.knownCalls(), row.completeCalls(), row.cacheUnknownCalls(), null, row.inputTokens(),
                row.outputTokens(), row.cachedInputTokens(), row.cacheWriteTokens(), row.reasoningTokens(),
                row.measuredInputTokens(), row.measuredOutputTokens(), row.measuredCachedInputTokens(),
                row.measuredCacheWriteTokens(), row.lastObservedAt());
    }

    private Usage realtime(UsageAggregate row) {
        if ("VOICE".equals(row.source())) {
            return new Usage("VOICE", row.model(), "VOICE", row.calls(), row.measuredCalls(), row.knownCalls(),
                    row.completeCalls(), 0, row.voiceSeconds(), null, null, null, null, null, null, null, null, null,
                    row.lastObservedAt());
        }
        // Realtime 판정 표에는 캐시 쓰기 열이 없다
        return new Usage("JUDGMENT", row.model(), "DECISIONS", row.calls(), row.measuredCalls(), row.knownCalls(),
                row.completeCalls(), row.cacheUnknownCalls(), null, row.inputTokens(), row.outputTokens(),
                row.cachedInputTokens(), null, row.reasoningTokens(), row.measuredInputTokens(), row.measuredOutputTokens(),
                row.measuredCachedInputTokens(), null, row.lastObservedAt());
    }

    private static Usage merge(List<Usage> group) {
        Usage first = group.getFirst();
        Instant last = group.stream().map(Usage::lastObservedAt).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .orElse(null);
        return new Usage(first.phase(), first.model(), first.rate(), group.stream().mapToInt(Usage::calls).sum(),
                group.stream().mapToInt(Usage::measuredCalls).sum(), group.stream().mapToInt(Usage::knownCalls).sum(),
                group.stream().mapToInt(Usage::completeCalls).sum(), group.stream().mapToInt(Usage::cacheUnknownCalls).sum(),
                sumDouble(group, Usage::voiceSeconds), sumLong(group, Usage::inputTokens), sumLong(group, Usage::outputTokens),
                sumLong(group, Usage::cachedInputTokens),
                sumLong(group, Usage::cacheWriteTokens), sumLong(group, Usage::reasoningTokens),
                sumLong(group, Usage::measuredInputTokens), sumLong(group, Usage::measuredOutputTokens),
                sumLong(group, Usage::measuredCachedInputTokens), sumLong(group, Usage::measuredCacheWriteTokens), last);
    }

    /** 측정된 호출이 없으면 null(0원이 아니다). 입력만 아는 호출 같은 부분 수집 호출은 금액에 넣지 않는다. */
    BigDecimal cost(Usage usage) {
        if (usage.measuredCalls() == 0) {
            return null;
        }
        if ("VOICE".equals(usage.rate())) {
            return usage.voiceSeconds() == null ? null : UsageCost.voice(usage.voiceSeconds(), pricing.voiceUsdPerMinute());
        }
        if (usage.measuredInputTokens() == null || usage.measuredOutputTokens() == null) {
            return null;
        }
        return UsageCost.tokens(usage.measuredInputTokens(), zero(usage.measuredCachedInputTokens()),
                zero(usage.measuredCacheWriteTokens()), usage.measuredOutputTokens(), rates(usage));
    }

    private String calculation(Usage usage) {
        if (cost(usage) == null) {
            return null;
        }
        String measured = usage.measuredCalls() < usage.calls()
                ? " (입력 · 출력을 모두 아는 호출 " + usage.measuredCalls() + " / " + usage.calls() + "건만 계산)" : "";
        if ("VOICE".equals(usage.rate())) {
            return "음성 " + UsageCost.number(usage.voiceSeconds()) + "초 ÷ 60 × " + UsageCost.rate(pricing.voiceUsdPerMinute())
                    + "/분" + measured;
        }
        TokenRates rates = rates(usage);
        long cached = zero(usage.measuredCachedInputTokens());
        long cacheWrite = zero(usage.measuredCacheWriteTokens());
        long uncached = Math.max(0, usage.measuredInputTokens() - cached - cacheWrite);
        String text = "(일반 입력 " + UsageCost.number(uncached) + " × " + UsageCost.rate(rates.inputUsdPerMillion())
                + " + 캐시 읽기 " + UsageCost.number(cached) + " × " + UsageCost.rate(rates.cachedInputUsdPerMillion())
                + " + 캐시 쓰기 " + UsageCost.number(cacheWrite) + " × " + UsageCost.rate(rates.cacheWriteUsdPerMillion())
                + " + 출력 " + UsageCost.number(usage.measuredOutputTokens()) + " × " + UsageCost.rate(rates.outputUsdPerMillion())
                + ") ÷ 1,000,000";
        if ("DECISIONS".equals(usage.rate())) {
            text += " · Decisions 출력은 과금하지 않음, 캐시 쓰기 미수집";
        }
        if (usage.cacheUnknownCalls() > 0) {
            text += " · 캐시 읽기를 모르는 호출 " + usage.cacheUnknownCalls() + "건은 캐시 0으로 계산(실제보다 크게 잡힐 수 있음)";
        }
        return text + measured;
    }

    private TokenRates rates(Usage usage) {
        return "DECISIONS".equals(usage.rate()) ? pricing.decisions() : pricing.responses();
    }

    /**
     * 아무 값도 모르면 UNKNOWN, 모든 호출이 측정 · 완결이고 캐시 읽기도 알면 COMPLETE, 그 밖(일부만 앎 · 캐시 읽기 모름)은 PARTIAL. 캐시 쓰기는
     * Realtime 판정 표에 열이 없어 기준에 넣지 않는다.
     */
    private static String status(Usage usage) {
        if (usage.knownCalls() == 0) {
            return "UNKNOWN";
        }
        return usage.measuredCalls() == usage.calls() && usage.completeCalls() == usage.calls() && usage.cacheUnknownCalls() == 0
                ? "COMPLETE" : "PARTIAL";
    }

    private static boolean matches(ActivityRow row, AdminFilter filter) {
        if (filter.type() != null && !filter.type().equals(row.activityType())
                || filter.guardianId() != null && !filter.guardianId().equals(row.guardianId())
                || filter.childId() != null && !filter.childId().equals(row.childId())
                || filter.deviceId() != null && !filter.deviceId().equals(row.deviceId())
                || filter.status() != null && !filter.status().equals(row.uiStatus())) {
            return false;
        }
        if (filter.query() == null) {
            return true;
        }
        String query = filter.query().toLowerCase(Locale.ROOT);
        return row.childName() != null && row.childName().toLowerCase(Locale.ROOT).contains(query)
                || row.sessionId().toString().startsWith(query) || row.guardianId().toString().startsWith(query)
                || row.deviceId().toString().startsWith(query);
    }

    private static String activityPurpose(ActivityRow row) {
        return AdminFilter.PRACTICE.equals(row.activityType()) ? "연습" : "자유대화";
    }

    /** 분류 합계. 저장된 사용량이 없으면 "0"이 아니라 확인 불가(null)다. */
    private static String total(List<Item> items, java.util.function.Predicate<Item> include) {
        List<Item> selected = items.stream().filter(include).toList();
        if (selected.isEmpty()) {
            return null;
        }
        BigDecimal sum = null;
        for (Item item : selected) {
            sum = add(sum, item.usd());
        }
        return UsageCost.money(sum);
    }

    private static BigDecimal add(BigDecimal sum, BigDecimal value) {
        if (value == null) {
            return sum;
        }
        return sum == null ? value : sum.add(value);
    }

    private static long zero(Long value) {
        return value == null ? 0 : value;
    }

    private static Long sumLong(List<Usage> group, Function<Usage, Long> value) {
        Long sum = null;
        for (Usage usage : group) {
            Long v = value.apply(usage);
            if (v != null) {
                sum = (sum == null ? 0 : sum) + v;
            }
        }
        return sum;
    }

    private static Double sumDouble(List<Usage> group, Function<Usage, Double> value) {
        Double sum = null;
        for (Usage usage : group) {
            Double v = value.apply(usage);
            if (v != null) {
                sum = (sum == null ? 0 : sum) + v;
            }
        }
        return sum;
    }
}
