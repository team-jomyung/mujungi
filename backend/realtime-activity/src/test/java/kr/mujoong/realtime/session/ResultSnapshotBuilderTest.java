package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.log.TranscriptRow;
import kr.mujoong.realtime.recording.AudioRecordingRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 결과 입력 스냅숏 조립 시험 (U4b L5 · FR7.1 · BR8.2 ~ BR8.4 · FD-Q4 · U4b-J16 · U4b-J17).
 *
 * <p>해시는 계약 정의(키 정렬 · 공백 없는 JSON의 UTF-8 sha256)대로 손으로 만든 정규화 문자열을 코드 밖({@code printf | sha256sum})에서 구한 골든
 * 값과 비교한다. null 값(practice · 열린 구간 to)은 키를 남기고 JSON null로 쓴다.
 */
@Tag("session-runtime")
class ResultSnapshotBuilderTest {

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final Instant CREATED = Instant.parse("2026-10-13T08:59:50Z");
    private static final Instant STARTED = Instant.parse("2026-10-13T09:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-10-13T09:10:00Z");
    private static final UUID SESSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** 계약 정의대로 손으로 만든 정규화 JSON. */
    private static final String CANONICAL = "{\"audio\":[],\"gaps\":[{\"from\":\"2026-10-13T09:05:00Z\",\"kind\":"
            + "\"DISCONNECT\",\"to\":\"2026-10-13T09:05:12Z\"}],\"practice\":null,\"recordScope\":{\"from\":"
            + "\"2026-10-13T09:00:00Z\",\"to\":\"2026-10-13T09:10:00Z\"},\"utterances\":[{\"endedAt\":"
            + "\"2026-10-13T09:00:02Z\",\"seq\":1,\"speaker\":\"AI\",\"startedAt\":\"2026-10-13T09:00:01Z\",\"text\":\"안녕\","
            + "\"uncertain\":false},{\"endedAt\":\"2026-10-13T09:00:06Z\",\"seq\":2,\"speaker\":\"CHILD\",\"startedAt\":"
            + "\"2026-10-13T09:00:05Z\",\"text\":\"공룡 좋아\",\"uncertain\":false}]}";
    /** {@code printf '%s' "$CANONICAL" | sha256sum}. */
    private static final String GOLDEN = "sha256:9c55b3c123c2bb7511e7fddf5cfad1dee5da65fcc37c48d3e9a503547429cf4f";

    @Test
    void theHashIsTheGoldenSha256OfTheKeySortedCompactJson() {
        ResultSnapshotBuilder.Built built = ResultSnapshotBuilder.build(input(session(SessionStatus.ENDED, STARTED),
                transcripts(), List.of(gap()), List.of(), null), JSON);

        assertThat(built.recordJson()).isEqualTo(CANONICAL);
        assertThat(built.snapshotHash()).isEqualTo(GOLDEN);
        // 같은 입력이면 같은 해시(결정적)
        assertThat(ResultSnapshotBuilder.build(input(session(SessionStatus.ENDED, STARTED), transcripts(),
                List.of(gap()), List.of(), null), JSON).snapshotHash()).isEqualTo(GOLDEN);
    }

    @Test
    void onlyFinalUndeletedChildRowsAndApprovedAiRowsAreUtterancesInSequenceOrder() {
        Map<String, Object> record = ResultSnapshotBuilder.build(input(session(SessionStatus.ENDED, STARTED),
                transcripts(), List.of(), List.of(), null), JSON).record();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> utterances = (List<Map<String, Object>>) record.get("utterances");
        assertThat(utterances).extracting(item -> item.get("seq")).containsExactly(1, 2);
        assertThat(utterances).extracting(item -> item.get("text")).doesNotContain("지운 말", "폐기한 말", "조각");
    }

    @Test
    void aDeclinedSessionAndOneEndedBeforeActiveHaveEmptyUtterancesAndAudio() {
        ResultSnapshotBuilder.Built declined = ResultSnapshotBuilder.build(input(session(SessionStatus.DECLINED, null),
                transcripts(), List.of(), List.of(audio(null)), null), JSON);
        ResultSnapshotBuilder.Built failed = ResultSnapshotBuilder.build(input(session(SessionStatus.FAILED, null),
                transcripts(), List.of(), List.of(audio(null)), null), JSON);

        assertThat(declined.record().get("utterances")).isEqualTo(List.of());
        assertThat(declined.record().get("audio")).isEqualTo(List.of());
        assertThat(failed.record().get("utterances")).isEqualTo(List.of());
        assertThat(declined.record()).containsEntry("practice", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> scope = (Map<String, Object>) failed.record().get("recordScope");
        assertThat(scope).as("ACTIVE 전에 끝나면 생성 시각부터(U4b-J17)").containsEntry("from", CREATED)
                .containsEntry("to", ENDED);
    }

    @Test
    void thePracticeIsAnObjectOrNullAndChangesTheHash() {
        ResultSnapshotBuilder.Built none = ResultSnapshotBuilder.build(input(session(SessionStatus.ENDED, STARTED),
                transcripts(), List.of(), List.of(), null), JSON);
        ResultSnapshotBuilder.Built withPractice = ResultSnapshotBuilder.build(input(session(SessionStatus.ENDED,
                STARTED), transcripts(), List.of(), List.of(), Map.of("goals", List.of())), JSON);

        assertThat(none.recordJson()).contains("\"practice\":null");
        assertThat(withPractice.recordJson()).contains("\"practice\":{\"goals\":[]}");
        assertThat(withPractice.snapshotHash()).isNotEqualTo(none.snapshotHash());
    }

    @Test
    void deletedAudioIsLeftOutAndPathsStayRelative() {
        ResultSnapshotBuilder.Built built = ResultSnapshotBuilder.build(input(session(SessionStatus.ENDED, STARTED),
                List.of(), List.of(), List.of(audio(null), audio(ENDED)), null), JSON);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> audio = (List<Map<String, Object>>) built.record().get("audio");
        assertThat(audio).singleElement().satisfies(item -> {
            assertThat(item.get("storagePath")).isEqualTo(SESSION + "/child.pcm");
            assertThat(item.get("speaker")).isEqualTo("CHILD");
        });
    }

    @Test
    void anOpenGapKeepsItsEndAsNullAndANonTerminalSessionIsRejected() {
        SessionGapRepository.SessionGapRow open = new SessionGapRepository.SessionGapRow(UUID.randomUUID(), SESSION,
                SessionGapRepository.Kind.DISCONNECT, STARTED.plusSeconds(30), null);

        ResultSnapshotBuilder.Built built = ResultSnapshotBuilder.build(input(session(SessionStatus.ABORTED, STARTED),
                List.of(), List.of(open), List.of(), null), JSON);

        assertThat(built.recordJson()).contains("\"to\":null");
        assertThatThrownBy(() -> ResultSnapshotBuilder.build(input(session(SessionStatus.ACTIVE, STARTED), List.of(),
                List.of(), List.of(), null), JSON)).isInstanceOf(IllegalArgumentException.class);
        assertThat(built.toString()).doesNotContain("utterances");
    }

    private static ResultSnapshotBuilder.Input input(ActivitySessionRow session, List<TranscriptRow> transcripts,
            List<SessionGapRepository.SessionGapRow> gaps, List<AudioRecordingRepository.AudioRecordingRow> audio,
            Object practice) {
        return new ResultSnapshotBuilder.Input(session, transcripts, gaps, audio, practice);
    }

    private static ActivitySessionRow session(SessionStatus status, Instant startedAt) {
        return new ActivitySessionRow(SESSION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, status, 6, "key", null, null, CREATED, "{}", "1.0", true, true, null, null, null,
                status == SessionStatus.DECLINED ? EndReason.DECLINED : EndReason.GUARDIAN_STOP, null, null, null, null,
                null, startedAt, ENDED, 1L, CREATED, ENDED);
    }

    private static List<TranscriptRow> transcripts() {
        return List.of(
                row(2, TranscriptEntry.Speaker.CHILD, "공룡 좋아", true, STARTED.plusSeconds(5), null,
                        TranscriptEntry.AssemblyStatus.FINAL),
                row(1, TranscriptEntry.Speaker.AI, "안녕", true, STARTED.plusSeconds(1), null,
                        TranscriptEntry.AssemblyStatus.FINAL),
                row(3, TranscriptEntry.Speaker.AI, "폐기한 말", false, STARTED.plusSeconds(8), null,
                        TranscriptEntry.AssemblyStatus.FINAL),
                row(4, TranscriptEntry.Speaker.CHILD, "지운 말", true, STARTED.plusSeconds(9), ENDED,
                        TranscriptEntry.AssemblyStatus.FINAL),
                row(5, TranscriptEntry.Speaker.CHILD, "조각", true, STARTED.plusSeconds(9), null,
                        TranscriptEntry.AssemblyStatus.PARTIAL));
    }

    private static TranscriptRow row(int seq, TranscriptEntry.Speaker speaker, String text, boolean approved,
            Instant startedAt, Instant deletedAt, TranscriptEntry.AssemblyStatus status) {
        return new TranscriptRow(UUID.randomUUID(), SESSION, seq, speaker, text, status, false, approved, startedAt,
                startedAt.plusSeconds(1), startedAt, deletedAt);
    }

    private static SessionGapRepository.SessionGapRow gap() {
        return new SessionGapRepository.SessionGapRow(UUID.randomUUID(), SESSION, SessionGapRepository.Kind.DISCONNECT,
                Instant.parse("2026-10-13T09:05:00Z"), Instant.parse("2026-10-13T09:05:12Z"));
    }

    private static AudioRecordingRepository.AudioRecordingRow audio(Instant deletedAt) {
        return new AudioRecordingRepository.AudioRecordingRow(UUID.randomUUID(), SESSION,
                AudioRecordingRepository.Speaker.CHILD, SESSION + "/child.pcm", "pcm16le/24000/1", 4800L, STARTED,
                ENDED, deletedAt);
    }
}
