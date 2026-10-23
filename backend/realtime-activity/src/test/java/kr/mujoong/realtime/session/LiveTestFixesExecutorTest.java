package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 2026-10-23 로컬 실호출 시험에서 나온 세 문제의 재현 시험. 공급자는 가짜 대역이고 시각은 수동 시계다(R-F1). 여기의 통과는 테스트 대역 결과일 뿐
 * 실제 GPT-Live · 실제 기기 성공이 아니다(R-M11).
 * <ul>
 *   <li>JIRA-261: 참여 직후 스스로 시작 대기가 빈손으로 끝나 첫 응답 지시를 보낸 직후, 모델이 스스로 시작한 답(전사 시간선이 지시 확인보다 앞)이
 *       버려지지 않고 들린다.
 *   <li>JIRA-270: 아이 말 뒤 응답이 없거나 응답이 "생각해 볼게"로 끝나면 이어 말하기 지시를 한 번만 보낸다. 조건 밖에서는 보내지 않는다.
 *   <li>JIRA-262: 전사의 비언어 표지 · 앞머리 잡음은 저장 전에 지운다.
 * </ul>
 */
@Tag("realtime-live-fix")
class LiveTestFixesExecutorTest {

    private static final Duration QUIET = Duration.ofMillis(800);
    private static final Duration STALL = Duration.ofSeconds(6);

    // ------------------------------------------------------------------ JIRA-261

    @Test
    void aSelfStartedAnswerArrivingRightAfterTheMissedWaitInstructionIsHeard() {
        SessionHarness h = new SessionHarness(settings(Duration.ofSeconds(1), Duration.ZERO));
        h.activateUnsettled();
        assertThat(h.launcher.names()).as("대기 중에는 지시를 보내지 않는다").noneMatch(name -> name.startsWith("provider-respond-"));
        h.provider.last().newSpeechMargin(null);

        // 1초 동안 조각이 없었다 → 첫 응답 지시
        h.advanceAlive(Duration.ofSeconds(1));
        h.settleSend();
        List<InstructionsAppend> sent = h.provider.last().requests(InstructionsAppend.class);
        assertThat(sent).hasSize(1);
        String eventId = sent.getLast().eventId();

        // 실호출 관측 모양: 지시 확인이 먼저 오고, 모델이 대기 직후 스스로 시작한 답은 전사 시간선이 확인 끝보다 앞이다
        long clock = h.provider.last().audioClockMs();
        h.provider.last().emit(new ProviderEvent.InstructionsAppended(eventId, clock, clock + 500L));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("좋아, 그럼 공룡 이야기 해 보자.", clock + 100L, clock + 400L));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[9_600]));
        h.drain();
        h.advance(QUIET);

        assertThat(h.link.outputs()).as("버리지 않고 기기로 나간다").hasSize(1);
        assertThat(h.link.outputs().getFirst().totalMs()).isEqualTo(200L);
        assertThat(kinds(h)).contains("JoinSelfStartMissed", "JoinSelfStartLateAccepted")
                .doesNotContain("OutputDiscardedStale", "OutputNewSpeechNotObserved");
        h.advanceAlive(Duration.ofSeconds(10));
        assertThat(kinds(h)).doesNotContain("OutputNewSpeechNotObserved", "OutputNotObservedOnlyStaleChunks");
    }

    @Test
    void anOrdinaryFirstInstructionStillDropsAStaleTailWhoseTranscriptPrecedesTheAck() {
        // 대기 없이(0) 보낸 일반 첫 응답 지시: 확인 전 전사 시간선의 조각은 앞 응답의 꼬리일 수 있어 그대로 버린다(낡은 꼬리가 새지 않는다)
        SessionHarness h = new SessionHarness();
        h.activate();
        h.provider.last().newSpeechMargin(null);
        String eventId = h.provider.last().requests(InstructionsAppend.class).getLast().eventId();
        h.provider.last().emit(new ProviderEvent.InstructionsAppended(eventId, 0L, 500L));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("앞 응답의 꼬리", 100L, 400L));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[9_600]));
        h.drain();
        h.advance(QUIET);

        assertThat(h.link.outputs()).isEmpty();
        assertThat(kinds(h)).doesNotContain("JoinSelfStartLateAccepted");
    }

    @Test
    void chunksDuringTheJoinWaitAreTheFirstResponseAndNoInstructionIsSent() {
        SessionHarness h = new SessionHarness(settings(Duration.ofSeconds(1), Duration.ZERO));
        h.activateUnsettled();
        h.provider.last().newSpeechMargin(null);
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("좋아, 그럼 공룡 이야기 해 보자.", 100L, 400L));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[9_600]));
        h.drain();
        h.advanceAlive(Duration.ofSeconds(1));
        h.settleSend();

        assertThat(h.provider.last().requests(InstructionsAppend.class)).isEmpty();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(kinds(h)).contains("JoinSelfStartSeen").doesNotContain("JoinSelfStartMissed");
    }

    // ------------------------------------------------------------------ JIRA-270

    @Test
    void aChildTurnWithNoResponseGetsExactlyOneResumeInstruction() {
        SessionHarness h = stallHarness();
        h.childSays("c1", "나 오늘 공룡 박물관 갔어");
        int before = instructions(h);

        h.advanceAlive(STALL.minusMillis(100));
        h.settleSend();
        assertThat(instructions(h)).as("시한 전에는 보내지 않는다").isEqualTo(before);

        h.advanceAlive(Duration.ofMillis(100));
        h.settleSend();
        assertThat(h.provider.last().requests(InstructionsAppend.class)).hasSize(before + 1);
        assertThat(h.provider.last().requests(InstructionsAppend.class).getLast().content())
                .isEqualTo(InstructionTemplates.ResponseStart.STALL_RESUME.content(h.activityType));
        assertThat(kinds(h)).contains("ResponseStallNudge");

        // 이어 말하기 지시에도 답이 없어도 같은 아이 턴에서는 다시 보내지 않는다
        h.advanceAlive(Duration.ofSeconds(40));
        h.settleSend();
        assertThat(instructions(h)).isEqualTo(before + 1);
    }

    @Test
    void aResponseEndingWithADeferralGetsOneResumeInstructionOnlyAfterPlaybackEnds() {
        SessionHarness h = stallHarness();
        h.childSays("c1", "공룡은 왜 없어졌어?");
        int before = instructions(h);
        UUID playback = respond(h, "음, 그거 좋은 질문이야. 잠깐 생각해볼게 [hum");
        mark(h, playback, PlaybackMark.Status.STARTED);
        assertThat(kinds(h)).contains("ResponseStallDeferralEnd");

        // 기기가 아직 재생 중이면 보내지 않고 다시 기다린다
        h.advanceAlive(STALL);
        h.settleSend();
        assertThat(instructions(h)).isEqualTo(before);

        mark(h, playback, PlaybackMark.Status.COMPLETED);
        h.advanceAlive(STALL);
        h.settleSend();
        assertThat(instructions(h)).isEqualTo(before + 1);
        assertThat(h.provider.last().requests(InstructionsAppend.class).getLast().content())
                .contains("답을 미루지 말고");
    }

    @Test
    void anOrdinaryAnswerIsNotFollowedByAnInstruction() {
        SessionHarness h = stallHarness();
        h.childSays("c1", "공룡은 왜 없어졌어?");
        int before = instructions(h);
        UUID playback = respond(h, "아주 큰 돌이 떨어져서 날씨가 많이 바뀌었대.");
        mark(h, playback, PlaybackMark.Status.STARTED);
        mark(h, playback, PlaybackMark.Status.COMPLETED);

        h.advanceAlive(Duration.ofSeconds(30));
        h.settleSend();
        assertThat(instructions(h)).as("아이가 말할 차례다 — 침묵을 서버 지시로 채우지 않는다").isEqualTo(before);
        assertThat(kinds(h)).doesNotContain("ResponseStallNudge", "ResponseStallDeferralEnd");
    }

    @Test
    void aChildAskingToWaitIsRespected() {
        SessionHarness h = stallHarness();
        h.childSays("c1", "음 잠깐만");
        int before = instructions(h);
        h.advanceAlive(Duration.ofSeconds(30));
        h.settleSend();
        assertThat(instructions(h)).isEqualTo(before);
    }

    @Test
    void theChildSpeakingAgainRestartsTheWait() {
        SessionHarness h = stallHarness();
        h.childSays("c1", "나 오늘 공룡 박물관 갔어");
        int before = instructions(h);
        h.advanceAlive(Duration.ofSeconds(3));
        // 아이가 다시 말하기 시작했다(입력 전사 조각): 시계를 끄고, 말이 확정되면 그때부터 다시 잰다
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "그리고 티라노도 봤어"));
        h.drain();
        h.advanceAlive(Duration.ofSeconds(4));
        h.settleSend();
        assertThat(instructions(h)).as("첫 확정에서 6초가 지났지만 아이 말이 이어졌다").isEqualTo(before);

        h.advanceAlive(STALL);
        h.settleSend();
        assertThat(instructions(h)).isEqualTo(before + 1);
    }

    @Test
    void theSafetyNetIsOffWhenTheWaitIsZero() {
        SessionHarness h = new SessionHarness();
        h.activate();
        respond(h, "만나서 좋아.");
        h.childSays("c1", "나 오늘 공룡 박물관 갔어");
        int before = instructions(h);
        h.advanceAlive(Duration.ofSeconds(30));
        h.settleSend();
        assertThat(instructions(h)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ JIRA-262

    @Test
    void markersAndLeadingNoiseAreRemovedBeforeStorageAndPlainLinesStayAsIs() {
        SessionHarness h = new SessionHarness();
        h.activate();
        h.childSays("c1", ".. 나 공룡 좋아 [cry");
        h.childSays("c2", "[cry]");
        h.childSays("c3", "응, 진짜 좋아!");
        respond(h, "응, 공룡 정말 멋지지! [hum");

        assertThat(finalText(h, TranscriptEntry.Speaker.CHILD)).containsSubsequence("나 공룡 좋아", "", "응, 진짜 좋아!");
        assertThat(finalText(h, TranscriptEntry.Speaker.AI)).contains("응, 공룡 정말 멋지지!")
                .noneMatch(text -> text.contains("[") || text.contains("]"));
        TranscriptEntry markerOnly = h.store.transcripts.stream()
                .filter(row -> row.speaker() == TranscriptEntry.Speaker.CHILD && row.assemblyStatus() == TranscriptEntry.AssemblyStatus.FINAL
                        && row.text().isEmpty()).findFirst().orElseThrow();
        assertThat(markerOnly.reviewNeeded()).as("표지만 있던 말은 빈 전사 + 검토 표시").isTrue();
    }

    // ------------------------------------------------------------------ 도움

    private static SessionExecutor.Settings settings(Duration joinSelfStartWait, Duration responseStallWait) {
        SessionExecutor.Settings b = SessionHarness.SETTINGS;
        return new SessionExecutor.Settings(b.deviceAckTimeout(), b.closingConfirm(), b.disconnect(), b.segmentQuiet(),
                b.playbackExpiry(), b.timerTick(), b.inputQueueFrames(), b.mailboxCapacity(), b.uncertainBelowPercent(),
                b.lifecycle(), b.inputTranscriptQuiet(), b.newSpeechMargin(), b.instructionWait(), joinSelfStartWait,
                responseStallWait);
    }

    /** 안전망을 켠 세션: 참여 뒤 첫 응답까지 듣고 재생을 끝낸 상태. */
    private static SessionHarness stallHarness() {
        SessionHarness h = new SessionHarness(settings(Duration.ZERO, STALL));
        h.activate();
        UUID first = respond(h, "만나서 좋아.");
        mark(h, first, PlaybackMark.Status.STARTED);
        mark(h, first, PlaybackMark.Status.COMPLETED);
        return h;
    }

    private static UUID respond(SessionHarness h, String text) {
        int before = h.link.outputs().size();
        h.speak(text, 4_800);
        h.advance(QUIET);
        assertThat(h.link.outputs()).hasSize(before + 1);
        return h.link.outputs().get(before).playbackId();
    }

    private static void mark(SessionHarness h, UUID playbackId, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(playbackId, status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private static int instructions(SessionHarness h) {
        return h.provider.last().requests(InstructionsAppend.class).size();
    }

    private static List<String> kinds(SessionHarness h) {
        return h.logs.events(EventCode.APP_ERROR).stream().map(event -> (String) LogCapture.attrs(event).get("errorKind")).toList();
    }

    private static List<String> finalText(SessionHarness h, TranscriptEntry.Speaker speaker) {
        return h.store.transcripts.stream()
                .filter(row -> row.speaker() == speaker && row.assemblyStatus() == TranscriptEntry.AssemblyStatus.FINAL)
                .map(TranscriptEntry::text).toList();
    }
}
