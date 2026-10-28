package kr.mujoong.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.core.admin.AdminActivityRepository.ActivityRow;
import kr.mujoong.core.admin.AdminPricingProperties.TokenRates;
import kr.mujoong.core.admin.AdminUsageRepository.Aggregate;
import kr.mujoong.core.admin.AdminViews.CostDetail;
import kr.mujoong.core.admin.AdminViews.CostPhase;
import kr.mujoong.core.admin.AdminViews.Costs;
import kr.mujoong.core.admin.RealtimeAdminClient.UsageAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** JIRA-280: 비용은 측정된 호출만 계산하고, 측정 안 된 호출은 0원이 아니라 미확인으로 드러낸다. */
@Tag("admin")
class AdminCostServiceTest {

    private static final Instant AT = Instant.parse("2026-10-27T01:00:00Z");
    private static final AdminFilter ALL = AdminFilter.parse("2026-10-27", "2026-10-27", null, null, null, null, null, null, null);

    private final AdminActivityRepository activities = mock(AdminActivityRepository.class);
    private final AdminUsageRepository usage = mock(AdminUsageRepository.class);
    private final RealtimeAdminClient realtime = mock(RealtimeAdminClient.class);
    private AdminCostService service;

    private final UUID activityId = UUID.randomUUID();
    private final UUID failedPrepId = UUID.randomUUID();
    private final UUID unlinkedCall = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AdminPricingProperties pricing = new AdminPricingProperties(new BigDecimal("0.05"),
                new TokenRates(new BigDecimal("0.10"), new BigDecimal("0.01"), new BigDecimal("0.125"), new BigDecimal("0.50")),
                new TokenRates(new BigDecimal("0.10"), new BigDecimal("0.01"), new BigDecimal("0.125"), BigDecimal.ZERO));
        service = new AdminCostService(activities, usage, realtime, pricing);
        when(usage.linkedBetween(any(), any())).thenReturn(List.of(
                // 장면 생성: 2호출 중 1호출만 측정
                new Aggregate(activityId, "SCENARIO_GENERATION", "gpt-6-luna", 2, 1, 2, 1, 0, 10_300L, 2_000L, 0L, 0L, 500L, 10_000L, 2_000L, 0L,
                        0L, AT),
                new Aggregate(failedPrepId, "SCENARIO_REVIEW", "gpt-6-luna", 1, 1, 1, 1, 0, 4_000L, 1_000L, 0L, 0L, 0L, 4_000L, 1_000L, 0L, 0L,
                        AT)));
        when(realtime.usageBetween(any(), any())).thenReturn(List.of(
                new UsageAggregate(activityId, "VOICE", "gpt-live-1", 1, 1, 1, 1, 0, 180.0, null, null, null, null, null, null, null,
                        AT.plusMillis(5)),
                new UsageAggregate(activityId, "LUNA", "gpt-6-luna", 3, 3, 3, 3, 0, null, 5_400L, 60L, 1_200L, 0L, 5_400L, 60L, 1_200L, AT)));
        when(usage.unlinkedBetween(any(), any())).thenReturn(List.of(
                new Aggregate(unlinkedCall, "RESULT_TEXT", null, 1, 0, 0, 0, 0, null, null, null, null, null, null, null, null, null, AT)));
        when(activities.findAll(any())).thenReturn(List.of(row(activityId, "PRACTICE")));
        when(usage.activitySessions(any())).thenReturn(Set.of(activityId));
    }

    @Test
    void activityCostSumsMeasuredPhasesAndMarksThePartialOnes() {
        Costs costs = service.costs(ALL, 0, 8);

        assertThat(costs.activities().total()).isEqualTo(1);
        var item = costs.activities().items().getFirst();
        // 준비 10,000 × 0.10 + 2,000 × 0.50 = 0.002 · 음성 3분 0.15 · 판정 (4,200 × 0.10 + 1,200 × 0.01) = 0.000432
        assertThat(item.estimatedUsd()).isEqualTo("0.152432");
        assertThat(item.status()).isEqualTo("PARTIAL");
        assertThat(item.category()).isEqualTo("ACTIVITY");
        assertThat(item.observedAt()).isEqualTo("2026-10-27T01:00:00.005Z");
        assertThat(costs.totals().unknownCount()).as("측정 안 된 준비 1호출 + 연결 미확인 1호출").isEqualTo(2);
    }

    @Test
    void failedPreparationAndUnlinkedCallsAreSeparateAndUnmeasuredIsNullNotZero() {
        Costs costs = service.costs(ALL, 0, 8);

        assertThat(costs.otherCosts()).extracting(c -> c.category()).containsExactlyInAnyOrder("FAILED_TO_START", "UNLINKED");
        var unlinked = costs.otherCosts().stream().filter(c -> c.category().equals("UNLINKED")).findFirst().orElseThrow();
        assertThat(unlinked.estimatedUsd()).isNull();
        assertThat(unlinked.status()).isEqualTo("UNKNOWN");
        assertThat(costs.totals().unlinkedUsd()).isNull();
        assertThat(costs.totals().failedStartUsd()).isEqualTo("0.0009");
        assertThat(costs.totals().nonActivityUsd()).as("현재 수집 범위 밖").isNull();
        assertThat(costs.totals().collectedUsd()).isEqualTo("0.153332");
    }

    @Test
    void anyFilterHidesItemsThatAreNotLinkedToAnActivity() {
        AdminFilter practice = AdminFilter.parse("2026-10-27", "2026-10-27", null, "PRACTICE", null, null, null, null, null);

        Costs costs = service.costs(practice, 0, 8);

        assertThat(costs.otherCosts()).isEmpty();
        assertThat(costs.activities().items()).hasSize(1);
        AdminFilter freeTalk = AdminFilter.parse("2026-10-27", "2026-10-27", null, "FREE_TALK", null, null, null, null, null);
        assertThat(service.costs(freeTalk, 0, 8).activities().total()).isZero();
    }

    @Test
    void decisionsPhaseStatesThatOutputIsFreeAndCacheWriteIsNotCollected() {
        when(activities.find(activityId)).thenReturn(Optional.of(row(activityId, "PRACTICE")));
        when(usage.linkedOf(activityId)).thenReturn(List.of());
        when(realtime.usageOf(List.of(activityId))).thenReturn(List.of(
                new UsageAggregate(activityId, "LUNA", "gpt-6-luna", 3, 3, 3, 3, 0, null, 5_400L, 60L, 1_200L, 0L, 5_400L, 60L, 1_200L,
                        AT)));

        CostDetail detail = service.detail(activityId);

        CostPhase judgment = detail.phases().getFirst();
        assertThat(judgment.phase()).isEqualTo("JUDGMENT");
        assertThat(judgment.cacheWriteTokens()).isNull();
        assertThat(judgment.estimatedUsd()).isEqualTo("0.000432");
        assertThat(judgment.calculation()).contains("Decisions 출력은 과금하지 않음").contains("$0.1").doesNotContain("E-");
    }

    @Test
    void activityWithoutAnyStoredUsageIsNotCollected() {
        when(activities.find(activityId)).thenReturn(Optional.of(row(activityId, "FREE_TALK")));
        when(usage.linkedOf(activityId)).thenReturn(List.of());
        when(realtime.usageOf(List.of(activityId))).thenReturn(List.of());

        CostDetail detail = service.detail(activityId);

        assertThat(detail.status()).isEqualTo("NOT_COLLECTED");
        assertThat(detail.phases()).isEmpty();
        assertThat(detail.estimatedUsd()).isNull();
        assertThat(detail.observedAt()).isEqualTo("2026-10-27T00:30:00Z");
    }

    @Test
    void partiallyCollectedCallsKeepTheirKnownTokensButOnlyMeasuredCallsArePriced() {
        // 장면 생성 2호출: 하나는 입력 10,000 · 출력 2,000, 다른 하나는 입력 300만 앎
        Costs costs = service.costs(ALL, 0, 8);

        var item = costs.activities().items().getFirst();
        assertThat(item.inputTokens()).as("표시용: 입력만 아는 호출의 300도 들어간다").isEqualTo(10_300L + 5_400L);
        assertThat(item.estimatedUsd()).as("금액: 입력 · 출력을 모두 아는 호출만").isEqualTo("0.152432");
        assertThat(item.status()).isEqualTo("PARTIAL");
    }

    @Test
    void aPeriodWithoutStoredUsageHasUnknownTotalsNotZero() {
        when(usage.linkedBetween(any(), any())).thenReturn(List.of());
        when(realtime.usageBetween(any(), any())).thenReturn(List.of());
        when(usage.unlinkedBetween(any(), any())).thenReturn(List.of());

        Costs costs = service.costs(ALL, 0, 8);

        assertThat(costs.totals().collectedUsd()).isNull();
        assertThat(costs.totals().activityUsd()).isNull();
        assertThat(costs.totals().failedStartUsd()).isNull();
        assertThat(costs.totals().unlinkedUsd()).isNull();
        assertThat(costs.totals().unknownCount()).isZero();
        assertThat(costs.activities().total()).isZero();
    }

    @Test
    void deviceFilterAndDeviceIdSearchKeepOnlyThatDevicesActivities() {
        ActivityRow row = row(activityId, "PRACTICE");
        when(activities.findAll(any())).thenReturn(List.of(row));

        AdminFilter other = AdminFilter.parse("2026-10-27", "2026-10-27", null, null, null, null, null,
                UUID.randomUUID().toString(), null);
        AdminFilter same = AdminFilter.parse("2026-10-27", "2026-10-27", null, null, null, null, null,
                row.deviceId().toString(), null);
        AdminFilter search = AdminFilter.parse("2026-10-27", "2026-10-27", null, null,
                row.deviceId().toString().substring(0, 8), null, null, null, null);

        assertThat(service.costs(other, 0, 8).activities().total()).isZero();
        assertThat(service.costs(same, 0, 8).activities().total()).isEqualTo(1);
        assertThat(service.costs(search, 0, 8).activities().total()).isEqualTo(1);
    }

    @Test
    void measuredCallsWithUnknownCacheReadArePricedWithZeroCacheButMarkedPartial() {
        when(activities.find(activityId)).thenReturn(Optional.of(row(activityId, "PRACTICE")));
        when(usage.linkedOf(activityId)).thenReturn(List.of(
                // 결과 문장 2호출 모두 입력 · 출력을 알지만 1호출은 캐시 읽기를 모름
                new Aggregate(activityId, "RESULT_TEXT", "gpt-6-luna", 2, 2, 2, 2, 1, 3_000L, 400L, 1_000L, 0L, 0L, 3_000L, 400L,
                        1_000L, 0L, AT),
                new Aggregate(activityId, "SCENARIO_GENERATION", "gpt-6-luna", 1, 1, 1, 1, 0, 1_000L, 100L, 0L, 0L, 0L, 1_000L,
                        100L, 0L, 0L, AT)));
        when(realtime.usageOf(List.of(activityId))).thenReturn(List.of());

        CostDetail detail = service.detail(activityId);

        CostPhase preparation = detail.phases().get(0);
        CostPhase post = detail.phases().get(1);
        assertThat(preparation.status()).as("캐시 읽기를 알면 그대로 완결").isEqualTo("COMPLETE");
        assertThat(post.phase()).isEqualTo("POST_PROCESSING");
        assertThat(post.status()).isEqualTo("PARTIAL");
        // (일반 입력 2,000 × 0.10 + 캐시 읽기 1,000 × 0.01 + 출력 400 × 0.50) ÷ 1,000,000: 모르는 캐시는 0으로 계산한 금액을 그대로 둔다
        assertThat(post.estimatedUsd()).isEqualTo("0.00041");
        assertThat(post.calculation()).contains("캐시 읽기를 모르는 호출 1건");
        assertThat(preparation.calculation()).doesNotContain("캐시 읽기를 모르는");
        assertThat(detail.status()).isEqualTo("PARTIAL");
    }

    @Test
    void activitiesInRangeWithoutAnyStoredUsageAreListedAsNotCollectedWithoutChangingTotals() {
        ActivityRow silent = row(UUID.randomUUID(), "FREE_TALK");
        ActivityRow laterUsage = row(UUID.randomUUID(), "FREE_TALK");
        ActivityRow realtimeOnly = row(UUID.randomUUID(), "FREE_TALK");
        when(activities.all(any())).thenReturn(List.of(row(activityId, "PRACTICE"), silent, laterUsage, realtimeOnly));
        when(usage.sessionsWithUsage(any())).thenReturn(Set.of(laterUsage.sessionId()));
        when(realtime.usageOf(any())).thenReturn(List.of(new UsageAggregate(realtimeOnly.sessionId(), "VOICE", "gpt-live-1", 1, 1,
                1, 1, 0, 60.0, null, null, null, null, null, null, null, AT.minusSeconds(86_400))));

        Costs costs = service.costs(ALL, 0, 8);

        assertThat(costs.activities().total()).as("사용량 있는 활동 1 + 미수집 1").isEqualTo(2);
        var item = costs.activities().items().stream().filter(c -> c.id().equals(silent.sessionId().toString())).findFirst()
                .orElseThrow();
        assertThat(item.status()).isEqualTo("NOT_COLLECTED");
        assertThat(item.estimatedUsd()).isNull();
        assertThat(item.inputTokens()).isNull();
        assertThat(item.voiceSeconds()).isNull();
        assertThat(item.observedAt()).as("목록 시각은 활동 시각").isEqualTo("2026-10-27T00:30:00Z");
        assertThat(costs.activities().items()).extracting(c -> c.id())
                .as("기간 밖에서 관측된 사용량이 있는 활동은 미수집이 아니다")
                .doesNotContain(laterUsage.sessionId().toString(), realtimeOnly.sessionId().toString());
        assertThat(costs.totals().collectedUsd()).isEqualTo("0.153332");
        assertThat(costs.totals().activityUsd()).isEqualTo("0.152432");
        assertThat(costs.totals().unknownCount()).isEqualTo(2);
    }

    @Test
    void aPeriodWithOnlyNotCollectedActivitiesStillHasUnknownTotals() {
        when(usage.linkedBetween(any(), any())).thenReturn(List.of());
        when(realtime.usageBetween(any(), any())).thenReturn(List.of());
        when(usage.unlinkedBetween(any(), any())).thenReturn(List.of());
        when(activities.all(any())).thenReturn(List.of(row(activityId, "FREE_TALK")));

        Costs costs = service.costs(ALL, 0, 8);

        assertThat(costs.activities().items()).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo("NOT_COLLECTED"));
        assertThat(costs.totals().collectedUsd()).isNull();
        assertThat(costs.totals().activityUsd()).isNull();
    }

    private static ActivityRow row(UUID id, String type) {
        Instant start = Instant.parse("2026-10-27T00:30:00Z");
        return new ActivityRow(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type, "ENDED", "CHILD_STOP", null,
                null, start, start, AT, "시험아이", true, 3, 3, "ENDED", "COMPLETED");
    }
}
