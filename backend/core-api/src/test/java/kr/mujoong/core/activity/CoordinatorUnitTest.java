package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.activity.RealtimeSessionClient.StartKind;
import kr.mujoong.core.activity.RealtimeSessionClient.StartResult;
import kr.mujoong.core.activity.SessionPackageBuilder.SessionPackage;
import kr.mujoong.core.activity.StartActivityService.Context;
import kr.mujoong.core.activity.StartActivityService.StartRequest;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.child.ChildProfileService.ChildContext;
import kr.mujoong.core.child.ChildProfileService.StopExpression;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.consent.ConsentPolicyService.GateResult;
import kr.mujoong.core.consent.ConsentPolicyService.Snapshot;
import kr.mujoong.core.consent.ConsentStatusRule.Status;
import kr.mujoong.core.device.DeviceViewAssembler;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * U4a ActivityCoordinator 단위 시험: 세션 패키지의 종료 표현 · 동의 스냅숏 · 승인 문구 고정(AC2.3.7 · AC6.3.7 · 설계 R2), 08 배경 저장 연결
 * (R3), 진행 보기 덧붙임 거르기(R4 · {@link LiveExtras}).
 */
@Tag("activity-session")
@org.junit.jupiter.api.Tag("service-guardrail")
class CoordinatorUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final ApprovedItem CLOSING = new ApprovedItem(UUID.randomUUID(), "끝 인사", "/media/approved/closing-v1.wav");
    private static final ApprovedItem FILLER = new ApprovedItem(UUID.randomUUID(), "잠깐", "/media/approved/filler-v1.wav");

    private final SessionPackageBuilder builder = new SessionPackageBuilder();

    // ------------------------------------------------------------------ 패키지

    @Test
    void stopExpressionsAreTheDefaultFirstThenTheChildsOwnInOrder() {
        ChildContext child = child(List.of(new StopExpression("무중아 그만할래", true), new StopExpression("이제 그만", false),
                new StopExpression("끝낼래", false)));

        SessionPackage pkg = builder.build(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), child,
                new Snapshot("2.0", true, true, true, true), approved());

        assertThat(pkg.stopExpressions()).extracting(SessionPackageBuilder.StopExpression::text)
                .containsExactly("무중아 그만할래", "이제 그만", "끝낼래");
        assertThat(pkg.stopExpressions()).extracting(SessionPackageBuilder.StopExpression::isDefault)
                .containsExactly(true, false, false);
        assertThat(pkg.consentSnapshot().policyVersion()).isEqualTo("2.0");
    }

    @Test
    void aSnapshotWithoutTheDefaultFirstOrWithAnOverlongTextIsRefusedWithoutTheText() {
        List<List<StopExpression>> broken = List.of(
                List.of(),
                List.of(new StopExpression("이제 그만", false)),
                List.of(new StopExpression("이제 그만", false), new StopExpression("무중아 그만할래", true)),
                List.of(new StopExpression("무중아 그만할래", true), new StopExpression("무중아 그만할래", true)),
                List.of(new StopExpression("무중아 그만할래", true), new StopExpression("가".repeat(21), false)),
                List.of(new StopExpression("무중아 그만할래", true), new StopExpression("  ", false)));
        for (List<StopExpression> stops : broken) {
            assertThatThrownBy(() -> builder.build(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    child(stops), new Snapshot("1.0", true, true, true, true), approved()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stopExpressions")
                    .hasMessageNotContaining("이제 그만");
        }
    }

    @Test
    void approvedContentNeedsClosingAndFillerAndKeepsTheGivenOrder() {
        Map<String, ApprovedItem> withExtra = approved();
        withExtra.put("INTRO_FREE_TALK", new ApprovedItem(UUID.randomUUID(), "안녕", "/media/approved/intro-v1.wav"));
        SessionPackage pkg = builder.build(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                child(defaultOnly()), new Snapshot("1.0", true, true, true, true), withExtra);
        assertThat(pkg.approvedContent()).containsOnlyKeys("CLOSING", "WAITING_FILLER", "INTRO_FREE_TALK");
        assertThat(JsonCodec.shared().write(pkg.approvedContent())).startsWith("{\"CLOSING\"");

        Map<String, ApprovedItem> missing = new HashMap<>(Map.of("CLOSING", CLOSING));
        assertThatThrownBy(() -> builder.build(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                child(defaultOnly()), new Snapshot("1.0", true, true, true, true), missing))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ 시작 서비스

    @Test
    void theStartWritesTheBackgroundForTheWinningRowAndPinsOnlyApprovedFreeTalkPhrases() {
        UUID guardianId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        MutableClock clock = new MutableClock(NOW);
        TestEvents events = new TestEvents(clock);
        GuardianRepository guardians = mock(GuardianRepository.class);
        ChildProfileService children = mock(ChildProfileService.class);
        ActivityCopyRepository activities = mock(ActivityCopyRepository.class);
        ActivityContextRepository contexts = mock(ActivityContextRepository.class);
        ConsentPolicyService consent = mock(ConsentPolicyService.class);
        DeviceViewAssembler deviceView = mock(DeviceViewAssembler.class);
        ApprovedContentCatalog catalog = mock(ApprovedContentCatalog.class);
        RealtimeSessionClient realtime = mock(RealtimeSessionClient.class);
        ChildContext child = child(defaultOnly());
        when(guardians.findById(guardianId)).thenReturn(Optional.of(new Guardian(guardianId, NOW)));
        when(children.childContext(guardianId)).thenReturn(Optional.of(child));
        when(consent.check(guardianId)).thenReturn(new GateResult(true, Status.GIVEN, new Snapshot("1.0", true, true, true, true)));
        when(activities.findByIdempotencyKey(eq(guardianId), any())).thenReturn(Optional.empty());
        when(activities.findInProgressByGuardian(guardianId)).thenReturn(Optional.empty());
        when(deviceView.readiness(guardianId)).thenReturn(new DeviceViewAssembler.Readiness(deviceId, true, null));
        when(catalog.closing()).thenReturn(Optional.of(CLOSING));
        when(catalog.waitingFiller()).thenReturn(Optional.of(FILLER));
        ApprovedItem intro = new ApprovedItem(UUID.randomUUID(), "안녕", "/media/approved/intro-v1.wav");
        when(catalog.sessionContent(StartActivityService.FREE_TALK_SESSION_CONTENT)).thenReturn(Map.of("INTRO_FREE_TALK", intro));
        when(activities.insertRequested(any())).thenReturn(true, false);
        when(realtime.start(any())).thenReturn(new StartResult(StartKind.ACCEPTED, SessionStatus.PREPARING, false));
        StartActivityService service = new StartActivityService(
                new StartActivityService.Ports(guardians, children, activities, contexts, consent, deviceView, catalog),
                builder, realtime, mock(PlatformTransactionManager.class), JsonCodec.shared(), events.logger(), events.metrics(),
                clock, Runnable::run);
        Context context = new Context("배경", null, null, null);

        UUID sessionId = service.start(guardianId, new StartRequest(UUID.randomUUID(), context)).sessionId();

        verify(contexts).insert(sessionId, guardianId, child.childId(), context, NOW);
        ArgumentCaptor<SessionPackage> sent = ArgumentCaptor.forClass(SessionPackage.class);
        verify(realtime).start(sent.capture());
        assertThat(sent.getValue().approvedContent()).containsOnlyKeys("CLOSING", "WAITING_FILLER", "INTRO_FREE_TALK");
        assertThat(StartActivityService.FREE_TALK_SESSION_CONTENT).doesNotContain("INTRO_PRACTICE", "HELP_OFFER");

        // 진 쪽(사본 넣기 0행)은 배경을 쓰지 않는다
        when(activities.findByIdempotencyKey(eq(guardianId), any())).thenReturn(Optional.empty());
        try {
            service.start(guardianId, new StartRequest(UUID.randomUUID(), context));
        } catch (RuntimeException expected) {
            // 409 SESSION_IN_PROGRESS
        }
        verify(contexts, times(1)).insert(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ 진행 보기 덧붙임

    @Test
    void liveExtrasKeepOnlyContractShapedValues() {
        List<Map<String, Object>> gaps = new java.util.ArrayList<>();
        gaps.add(Map.of("from", "2026-10-15T00:01:00Z", "to", "2026-10-15T00:01:30Z", "kind", "DISCONNECT", "x", 1));
        Map<String, Object> open = new HashMap<>();
        open.put("from", "2026-10-15T00:02:00Z");
        open.put("to", null);
        open.put("kind", "UNCONFIRMED");
        gaps.add(open);
        gaps.add(Map.of("from", "bad", "kind", "DISCONNECT"));
        gaps.add(Map.of("from", "2026-10-15T00:03:00Z", "kind", "OTHER"));
        gaps.add(Map.of("from", "2026-10-15T00:03:00Z", "to", "bad", "kind", "DISCONNECT"));
        gaps.add(null);

        assertThat(LiveExtras.gaps(gaps)).extracting(LiveExtras.Gap::kind).containsExactly("DISCONNECT", "UNCONFIRMED");
        assertThat(LiveExtras.gaps(null)).isEmpty();
        assertThat(LiveExtras.technicalPause(Map.of("kind", "PROVIDER", "since", "2026-10-15T00:00:00Z")).kind())
                .isEqualTo("PROVIDER");
        assertThat(LiveExtras.technicalPause(Map.of("kind", "OTHER", "since", "2026-10-15T00:00:00Z"))).isNull();
        assertThat(LiveExtras.technicalPause(null)).isNull();
        assertThat(LiveExtras.silenceWarning(Map.of("silentSince", "2026-10-15T00:00:00Z", "endsAt", "2026-10-15T00:06:00Z")))
                .isNotNull();
        assertThat(LiveExtras.silenceWarning(Map.of("silentSince", "2026-10-15T00:00:00Z"))).isNull();
        assertThat(LiveExtras.silenceWarning(null)).isNull();
        LiveExtras.Practice practice = LiveExtras.practice(Map.of("goalIndex", 0, "goalCount", 3, "goalName", "인사하기",
                "stage", "SECRET_STAGE", "label", "functional"));
        assertThat(practice).isEqualTo(new LiveExtras.Practice(0, 3, "인사하기", null));
        assertThat(LiveExtras.practice(Map.of("goalIndex", -1, "goalCount", 1.5))).isNull();
        assertThat(LiveExtras.practice(null)).isNull();
    }

    @Test
    void thePracticeExtensionPointKeepsJudgmentCriteriaOutOfItsStringForms() {
        PracticeStart.Goal goal = new PracticeStart.Goal(UUID.randomUUID(), 1, "인사하기", "GREET", 1, "판정 기준 본문");
        PracticeStart.PracticePackage pkg = new PracticeStart.PracticePackage(List.of(goal), Map.of());

        assertThat(pkg.goals()).containsExactly(goal);
        assertThat(pkg.toString() + goal).doesNotContain("판정 기준").doesNotContain("인사하기");
    }

    private static ChildContext child(List<StopExpression> stops) {
        return new ChildContext(UUID.randomUUID(), "하늘", 8, List.of("공룡"), stops);
    }

    private static List<StopExpression> defaultOnly() {
        return List.of(new StopExpression(SessionPackageBuilder.DEFAULT_STOP_EXPRESSION, true));
    }

    private static Map<String, ApprovedItem> approved() {
        Map<String, ApprovedItem> approved = new LinkedHashMap<>();
        approved.put("CLOSING", CLOSING);
        approved.put("WAITING_FILLER", FILLER);
        return approved;
    }
}
