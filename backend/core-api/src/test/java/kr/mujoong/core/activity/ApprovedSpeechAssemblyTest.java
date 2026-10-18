package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.activity.ApprovedContentCatalog.SpeechItem;
import kr.mujoong.core.activity.RealtimeSessionClient.StartKind;
import kr.mujoong.core.activity.RealtimeSessionClient.StartResult;
import kr.mujoong.core.activity.SessionPackageBuilder.SessionPackage;
import kr.mujoong.core.activity.StartActivityService.StartRequest;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import kr.mujoong.core.catalog.ApprovedContentVersion;
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
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * U8 시작 패키지 승인 발화 조립(U8 functional-spec 8.1 · BR1.2 · BR1.3 · NFR6.1 · 설계 검토 R-01 ③).
 *
 * <p>진짜 {@link ApprovedContentCatalog}를 가짜 저장소 위에 두고 {@link StartActivityService}가 음성 없는 승인 발화를 approvedSpeech로만
 * 보내는지, 허용 목록 · 종류 · APPROVED 판으로 거르는지, 두 맵의 code가 겹치지 않는지, 발화가 없으면 옛 본문 경로를 그대로 쓰는지 본다.
 */
@Tag("live-content")
class ApprovedSpeechAssemblyTest {

    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final String CLOSING_AUDIO = "closing-v1.wav";
    private static final String FILLER_AUDIO = "waiting-filler-v1.wav";

    private final UUID guardianId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final ApprovedContentRepository repository = mock(ApprovedContentRepository.class);
    private final ApprovedContentCatalog catalog = new ApprovedContentCatalog(repository);
    private final RealtimeSessionClient realtime = mock(RealtimeSessionClient.class);
    private final Map<String, UUID> versionIds = new LinkedHashMap<>();
    private StartActivityService service;

    @BeforeEach
    void setUp() {
        MutableClock clock = new MutableClock(NOW);
        GuardianRepository guardians = mock(GuardianRepository.class);
        ChildProfileService children = mock(ChildProfileService.class);
        ActivityCopyRepository activities = mock(ActivityCopyRepository.class);
        ActivityContextRepository contexts = mock(ActivityContextRepository.class);
        ConsentPolicyService consent = mock(ConsentPolicyService.class);
        DeviceViewAssembler deviceView = mock(DeviceViewAssembler.class);
        when(guardians.findById(guardianId)).thenReturn(Optional.of(new Guardian(guardianId, NOW)));
        when(children.childContext(guardianId)).thenReturn(Optional.of(new ChildContext(UUID.randomUUID(), "하늘", 8,
                List.of("공룡"), List.of(new StopExpression(SessionPackageBuilder.DEFAULT_STOP_EXPRESSION, true)))));
        when(consent.check(guardianId)).thenReturn(new GateResult(true, Status.GIVEN, new Snapshot("1.0", true, true, true, true)));
        when(activities.findByIdempotencyKey(eq(guardianId), any())).thenReturn(Optional.empty());
        when(activities.findInProgressByGuardian(guardianId)).thenReturn(Optional.empty());
        when(activities.insertRequested(any())).thenReturn(true);
        when(deviceView.readiness(guardianId)).thenReturn(new DeviceViewAssembler.Readiness(deviceId, true, null));
        StartResult accepted = new StartResult(StartKind.ACCEPTED, SessionStatus.PREPARING, false);
        when(realtime.start(any())).thenReturn(accepted);
        when(realtime.start(any(), anyMap())).thenReturn(accepted);
        when(realtime.startPractice(any(), anyMap())).thenReturn(accepted);
        when(realtime.startPractice(any(), anyMap(), anyMap())).thenReturn(accepted);
        TestEvents events = new TestEvents(clock);
        service = new StartActivityService(
                new StartActivityService.Ports(guardians, children, activities, contexts, consent, deviceView, catalog),
                new SessionPackageBuilder(), realtime, mock(PlatformTransactionManager.class), JsonCodec.shared(),
                events.logger(), events.metrics(), clock, Runnable::run);
        service.practiceGuardianLock(mock(GuardianWriteLock.class));

        content(ApprovedContent.CODE_CLOSING, ApprovedContent.TYPE_APPROVED_AUDIO, "끝 인사", CLOSING_AUDIO, true);
        content(ApprovedContent.CODE_WAITING_FILLER, ApprovedContent.TYPE_APPROVED_AUDIO, "잠깐", FILLER_AUDIO, true);
    }

    // ------------------------------------------------------------------ 카탈로그

    @Test
    void catalogPinsOnlyApprovedSpeechCodesThatHaveAnApprovedVersion() {
        content(ApprovedContent.CODE_D_REST_ACK, ApprovedContent.TYPE_APPROVED_SPEECH, "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.", null, true);
        content(ApprovedContent.CODE_HELP_OFFER, ApprovedContent.TYPE_APPROVED_SPEECH, "무중이가 조금 도와줄까?", null, false);
        content(ApprovedContent.CODE_CONSENT_POLICY, ApprovedContent.TYPE_POLICY_VERSION, "1.0", null, true);

        Map<String, SpeechItem> pinned = catalog.approvedSpeech(List.of(ApprovedContent.CODE_CONSENT_POLICY,
                ApprovedContent.CODE_HELP_OFFER, "NO_SUCH_CODE", ApprovedContent.CODE_D_REST_ACK, ApprovedContent.CODE_CLOSING));

        assertThat(pinned).as("정책 판 · DRAFT만 있는 code · 없는 code · 음성 문구는 빠진다")
                .containsOnlyKeys(ApprovedContent.CODE_D_REST_ACK);
        assertThat(pinned.get(ApprovedContent.CODE_D_REST_ACK).contentVersionId()).isEqualTo(versionIds.get("D_REST_ACK"));
        assertThat(pinned.get(ApprovedContent.CODE_D_REST_ACK).text()).isEqualTo("잠깐 쉬고 싶구나. 천천히 해도 괜찮아.");
    }

    @Test
    void speechItemHidesItsTextAndRefusesAnEmptyOrOverlongText() {
        SpeechItem item = new SpeechItem(UUID.randomUUID(), "잠깐 쉬고 싶구나.");

        assertThat(item.toString()).doesNotContain("쉬고");
        assertThat(JsonCodec.shared().write(item)).isEqualTo(
                "{\"contentVersionId\":\"" + item.contentVersionId() + "\",\"text\":\"잠깐 쉬고 싶구나.\"}");
        assertThatThrownBy(() -> new SpeechItem(UUID.randomUUID(), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeechItem(UUID.randomUUID(), "가".repeat(SpeechItem.TEXT_MAX + 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("가가");
    }

    // ------------------------------------------------------------------ 시작 서비스

    @Test
    void freeTalkSendsOnlyTheFreeTalkSpeechCodesAndKeepsApprovedContentAsBefore() {
        allFiveSpeechCodes();

        service.start(guardianId, new StartRequest(UUID.randomUUID(), null));

        ArgumentCaptor<SessionPackage> pkg = ArgumentCaptor.forClass(SessionPackage.class);
        ArgumentCaptor<Map<String, SpeechItem>> speech = speechCaptor();
        verify(realtime).start(pkg.capture(), speech.capture());
        assertThat(speech.getValue().keySet()).containsExactly("D_REST_ACK_START", "D_REST_ACK");
        assertThat(pkg.getValue().approvedContent()).as("기존 approvedContent는 그대로(음성 문구만)")
                .containsOnlyKeys("CLOSING", "WAITING_FILLER");
        assertThat(pkg.getValue().activityType()).isEqualTo(ActivityType.FREE_TALK);
    }

    @Test
    void practiceSendsTheFivePracticeCodesAndHelpOfferMovedOutOfApprovedContent() {
        allFiveSpeechCodes();

        service.startPractice(guardianId, UUID.randomUUID(), UUID.randomUUID(), "sha256:practice", Map.of("goals", List.of()));

        ArgumentCaptor<SessionPackage> pkg = ArgumentCaptor.forClass(SessionPackage.class);
        ArgumentCaptor<Map<String, SpeechItem>> speech = speechCaptor();
        verify(realtime).startPractice(pkg.capture(), anyMap(), speech.capture());
        assertThat(speech.getValue().keySet())
                .containsExactly("HELP_OFFER", "D_REST_ACK_START", "D_REST_ACK", "COACH_SWITCH", "PEER_RETURN");
        assertThat(speech.getValue().get("HELP_OFFER").contentVersionId()).isEqualTo(versionIds.get("HELP_OFFER"));
        assertThat(pkg.getValue().approvedContent()).doesNotContainKey("HELP_OFFER");
        assertThat(StartActivityService.PRACTICE_SESSION_CONTENT).doesNotContain("HELP_OFFER");
    }

    @Test
    void freeTalkAndPracticeAllowListsDifferAndNeverAllowPolicyOrTermsCodes() {
        assertThat(StartActivityService.FREE_TALK_APPROVED_SPEECH).isNotEqualTo(StartActivityService.PRACTICE_APPROVED_SPEECH);
        assertThat(StartActivityService.PRACTICE_APPROVED_SPEECH).containsAll(StartActivityService.FREE_TALK_APPROVED_SPEECH);
        assertThat(StartActivityService.FREE_TALK_APPROVED_SPEECH)
                .doesNotContain("HELP_OFFER", "COACH_SWITCH", "PEER_RETURN");
        for (List<String> allowed : List.of(StartActivityService.FREE_TALK_APPROVED_SPEECH,
                StartActivityService.PRACTICE_APPROVED_SPEECH)) {
            assertThat(allowed).doesNotContain(ApprovedContent.CODE_CONSENT_POLICY, ApprovedContent.CODE_CLOSING,
                    ApprovedContent.CODE_WAITING_FILLER, "D_REPROMPT");
            assertThat(allowed).noneMatch(code -> code.startsWith("POLICY_TERMS_"));
        }
    }

    @Test
    void aCodeAlreadyInApprovedContentNeverAlsoGoesToApprovedSpeech() {
        content(ApprovedContent.CODE_D_REST_ACK, ApprovedContent.TYPE_APPROVED_SPEECH, "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.", null, true);
        content(ApprovedContent.CODE_D_REST_ACK_START, ApprovedContent.TYPE_APPROVED_SPEECH, "잠깐 쉬고 싶구나. 괜찮아. 준비되면 말해 줘.",
                null, true);
        Map<String, ApprovedItem> approved = new LinkedHashMap<>();
        approved.put("D_REST_ACK", new ApprovedItem(UUID.randomUUID(), "음성 판", "/media/approved/rest-v1.wav"));

        Map<String, SpeechItem> speech = service.approvedSpeech(ActivityType.FREE_TALK, approved);

        assertThat(speech).containsOnlyKeys("D_REST_ACK_START");
        assertThat(speech.keySet()).doesNotContainAnyElementsOf(approved.keySet());
    }

    @Test
    void withoutAnyApprovedSpeechTheOldStartBodyPathIsUsed() {
        content(ApprovedContent.CODE_D_REST_ACK, ApprovedContent.TYPE_APPROVED_SPEECH, "잠깐 쉬고 싶구나.", null, false);

        service.start(guardianId, new StartRequest(UUID.randomUUID(), null));

        verify(realtime).start(any());
        verify(realtime, never()).start(any(), anyMap());
    }

    // ------------------------------------------------------------------ 도움

    private void allFiveSpeechCodes() {
        content(ApprovedContent.CODE_HELP_OFFER, ApprovedContent.TYPE_APPROVED_SPEECH, "무중이가 조금 도와줄까?", null, true);
        content(ApprovedContent.CODE_D_REST_ACK_START, ApprovedContent.TYPE_APPROVED_SPEECH, "잠깐 쉬고 싶구나. 괜찮아. 준비되면 말해 줘.",
                null, true);
        content(ApprovedContent.CODE_D_REST_ACK, ApprovedContent.TYPE_APPROVED_SPEECH, "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.", null, true);
        content(ApprovedContent.CODE_COACH_SWITCH, ApprovedContent.TYPE_APPROVED_SPEECH, "잠깐, 무중이 도와줄게", null, true);
        content(ApprovedContent.CODE_PEER_RETURN, ApprovedContent.TYPE_APPROVED_SPEECH, "다시 친구 역할로 돌아갈게", null, true);
    }

    /** 가짜 저장소에 종류 행과 (approved면) APPROVED 판 하나를 둔다. approved가 아니면 APPROVED 판이 없다(DRAFT만). */
    private void content(String code, String type, String text, String audio, boolean approved) {
        UUID contentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        versionIds.put(code, versionId);
        when(repository.findByContentCode(code)).thenReturn(Optional.of(new ApprovedContent(contentId, code, type, NOW)));
        when(repository.findLatestApproved(code)).thenReturn(approved
                ? Optional.of(new ApprovedContentVersion(versionId, contentId, 1, text, audio, ApprovedContentVersion.STATUS_APPROVED,
                        NOW))
                : Optional.empty());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, SpeechItem>> speechCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
