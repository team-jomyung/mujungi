package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.event.payload.DeletionRequested;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.log.RealtimeVoiceDeletionParticipant;
import kr.mujoong.realtime.messaging.InboxEventRow;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import kr.mujoong.realtime.messaging.RealtimeMessagingDeletionParticipant;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * U4b 개인정보 검사 시험 (NFR4 · BR12.1).
 *
 * <p>참여 확인 · 확인 질문 · 공백 · 정산 · 삭제 경로에서 운영 로그(LogCapture 줄) · SessionEvent payload · 상태 이벤트로 나가는 변경(SessionChange)
 * · DeletionCompleted payload에 아이 · AI 전사 원문과 원음 저장 경로가 한 번도 나오지 않는지 본다. 결과 스냅숏의 record(대화 원문을 싣는 것이
 * 계약)는 이 검사에서 뺀다 — 정산 입구에는 세션 id · practice · 마감 시각만 넘어가는지만 본다.
 */
@Tag("u4b")
class PrivacyLogU4bTest {

    private static final String INTRO = "안녕 나는 무중이야 오늘은 이야기를 나눌 거야 시작할까";
    private static final String RE_PROMPT_TEXT = "다시 한 번 물어볼게 이야기할까";
    private static final String ALTERNATIVE_TEXT = "그럼 다른 놀이를 해 볼까";
    private static final String STOP_QUESTION = "이야기를 그만할까";
    private static final String WARNING_TEXT = "무중이 기다리고 있어";
    private static final String AUDIO_PATH_MARK = "recordings/";

    private final SessionHarness h = new SessionHarness();

    @Test
    void theJoinPathKeepsRepliesAndPromptsOutOfLogsAndEvents() {
        withPrompts();
        h.boot();
        h.ackAndReady();
        mark(lastPlay());

        h.childSays("i-1", "모르겠는데 프테라노돈");
        mark(lastPlay());
        h.childSays("i-2", "이거 말고 다른 거 브라키오");
        mark(lastPlay());
        h.childSays("i-3", "좋아 스테고사우루스");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);

        assertNoSecrets("모르겠는데 프테라노돈", "프테라노돈", "브라키오", "스테고사우루스", INTRO, RE_PROMPT_TEXT,
                ALTERNATIVE_TEXT);
        assertThat(sessionEvents()).extracting(SessionEvent::getEventType)
                .contains(SessionExecutor.EVENT_JOIN_PROMPTED, SessionExecutor.EVENT_JOIN_ACCEPTED);
    }

    @Test
    void aDeclinedJoinLeavesNoReplyTextBehindAnywhere() {
        withPrompts();
        h.boot();
        h.ackAndReady();
        mark(lastPlay());

        h.childSays("i-1", "싫어 안키로사우루스");
        mark(lastPlay());
        h.childSays("i-2", "안 할래 파키케팔로");

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
        assertNoSecrets("안키로사우루스", "파키케팔로");
        assertThat(sessionEvents()).extracting(SessionEvent::getEventType)
                .contains(SessionExecutor.EVENT_JOIN_DECLINED);
    }

    @Test
    void theStopConfirmPathKeepsTheChildsWordsAndTheQuestionOut() {
        h.extraApproved.put(SessionExecutor.STOP_CONFIRM, STOP_QUESTION);
        h.activate();
        h.childSays("i-1", "쉬고 싶어 이구아노돈");
        mark(lastPlay());
        h.childSays("i-2", "응");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertNoSecrets("쉬고 싶어 이구아노돈", "이구아노돈", STOP_QUESTION);
        assertThat(sessionEvents()).extracting(SessionEvent::getEventType)
                .contains(SessionExecutor.EVENT_STOP_CONFIRM_ASKED, SessionExecutor.EVENT_STOP_CONFIRM_RESOLVED);
    }

    @Test
    void theSilencePathLogsDurationsOnlyAndNotTheWarningOrTheNextUtterance() {
        h.extraApproved.put(SessionExecutor.SILENCE_WARNING, WARNING_TEXT);
        h.activate();
        h.advanceAlive(Duration.ofSeconds(241));
        mark(lastPlay());
        h.childSays("i-1", "여기 있어 디플로도쿠스");

        assertNoSecrets(WARNING_TEXT, "디플로도쿠스");
        assertThat(sessionEvents()).extracting(SessionEvent::getEventType)
                .contains(IdleClock.EVENT_SILENCE_WARNED, IdleClock.EVENT_SILENCE_RESET);
    }

    @Test
    void theSettlePathPassesOnlyIdsAndTimesToTheSettlementAndLogsNoLateTranscript() {
        List<Object[]> calls = new ArrayList<>();
        h.settlement = (sessionId, practice, cutoffAt) -> {
            calls.add(new Object[] {sessionId, practice, cutoffAt});
            return true;
        };
        h.rawAudioConsent = true;
        h.activate();
        h.childSays("i-1", "티라노가 제일 커 벨로키랍토르");
        h.speak("응 정말 커 트리케라톱스", 4_800);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("late", "마지막"));
        h.drain();
        CompletableFuture<SessionMailbox.EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(h.link.connectionId(),
                new VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("late", "마지막 한마디 스피노사우루스", 0.9));
        h.drain();
        h.advance(SessionHarness.LIFECYCLE.recordSettle());

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call[0]).isEqualTo(h.sessionId);
            assertThat(call[1]).isNull();
            assertThat(call[2]).isInstanceOf(Instant.class);
        });
        assertNoSecrets("벨로키랍토르", "트리케라톱스", "스피노사우루스");
    }

    @Test
    void theDeletionPathCountsRowsWithoutAnyContentOrAudioPath() {
        MutableClock clock = new MutableClock();
        LogCapture logs = LogCapture.detached();
        EventLogger events = TestEventLoggers.strict(clock, logs, RealtimeMetrics.inMemory());
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        RealtimeOutboxWriter outbox = mock(RealtimeOutboxWriter.class);
        RealtimeDeletionParticipant voice = mock(RealtimeDeletionParticipant.class);
        when(voice.order()).thenReturn(RealtimeDeletionParticipant.ORDER_VOICE);
        when(voice.deleteByGuardian(any(), any())).thenReturn(Map.of(RealtimeVoiceDeletionParticipant.TRANSCRIPT, 3,
                RealtimeVoiceDeletionParticipant.AUDIO_RECORDING, 1, RealtimeVoiceDeletionParticipant.AUDIO_FILES, 1));
        UUID guardian = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        DeletionRequestedHandler handler = new DeletionRequestedHandler(new SessionRegistry(), jdbc, List.of(voice),
                mock(RealtimeMessagingDeletionParticipant.class), outbox, JsonCodec.shared(), clock, events,
                Duration.ofSeconds(1));
        String payload = JsonCodec.shared().write(new DeletionRequested(request, guardian, null, clock.now()));

        handler.handle(new InboxEventRow(UUID.randomUUID(), "CORE", "DeletionRequested", 1, clock.now(), request,
                guardian, payload, "0".repeat(64), InboxEventRow.Status.RECEIVED, 0, clock.now(), clock.now(), null,
                null, null, null), clock.now());

        ArgumentCaptor<DeletionCompleted> completed = ArgumentCaptor.forClass(DeletionCompleted.class);
        verify(outbox).write(completed.capture(), eq(guardian), any(), any());
        String completedJson = JsonCodec.shared().write(completed.getValue());
        assertThat(completedJson).doesNotContain(AUDIO_PATH_MARK, ".wav", ".pcm");
        assertThat(completed.getValue().counts().transcripts()).isEqualTo(3);
        assertThat(logs.lines()).noneMatch(line -> line.contains(AUDIO_PATH_MARK) || line.contains(".wav"));
    }

    // ------------------------------------------------------------------------------------------------ 도우미

    private void withPrompts() {
        h.extraApproved.put(SessionExecutor.INTRO_FREE_TALK, INTRO);
        h.extraApproved.put(SessionExecutor.RE_PROMPT, RE_PROMPT_TEXT);
        h.extraApproved.put(SessionExecutor.DECLINE_ALTERNATIVE, ALTERNATIVE_TEXT);
    }

    private PlayApprovedContent lastPlay() {
        return h.link.sent(PlayApprovedContent.class).getLast();
    }

    private void mark(PlayApprovedContent play) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), PlaybackMark.Status.COMPLETED, null, h.clock.now(), null, null),
                h.clock.now()));
        h.drain();
    }

    private List<SessionEvent> sessionEvents() {
        h.recorder.tick();
        return List.copyOf(h.store.sessionEvents);
    }

    /** 로그 줄 · SessionEvent payload · 상태 변경(SessionStateChanged 원천)에 비밀 문자열과 원음 경로가 없다. */
    private void assertNoSecrets(String... secrets) {
        List<String> payloads = sessionEvents().stream().map(SessionEvent::getPayloadJson).filter(Objects::nonNull)
                .toList();
        List<String> changes = h.transitions.applied().stream().map(applied -> String.valueOf(applied.change()))
                .toList();
        for (String secret : secrets) {
            assertThat(h.logs.lines()).as("로그에 '%s'", secret).noneMatch(line -> line.contains(secret));
            assertThat(payloads).as("SessionEvent payload에 '%s'", secret).noneMatch(json -> json.contains(secret));
            assertThat(changes).as("상태 변경에 '%s'", secret).noneMatch(change -> change.contains(secret));
        }
        assertThat(h.logs.lines()).noneMatch(line -> line.contains(AUDIO_PATH_MARK));
        assertThat(payloads).noneMatch(json -> json.contains(AUDIO_PATH_MARK));
        // 전사 원문은 전사 행에만 있다(검사가 비어 있지 않음을 보인다)
        assertThat(h.store.transcripts).isNotEmpty();
    }
}
