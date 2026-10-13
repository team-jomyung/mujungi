package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.log.TranscriptRow;
import kr.mujoong.realtime.recording.AudioRecordingRepository;

/**
 * 결과 입력 스냅숏 조립 (U4b L5 · FR7.1 · BR8.2 ~ BR8.4 · U4b-J16 · U4b-J17 · FD-Q4 · 계약 6절 SessionRecord).
 *
 * <p>순수 계산만 한다 — 세션 행 · 전사 · 구간 · 원음 메타 · practice 값을 받아 record와 snapshotHash를 돌려준다(DB · 시계 없음).
 *
 * <ul>
 *   <li>utterances: deleted_at이 빈 확정(FINAL) 행 중 아이 전사와 승인된 AI 말(게이트 통과 · 승인 문구 말풍선), sequence 순. 항목은
 *       seq · speaker · text · uncertain(= review_needed) · startedAt(없으면 행 생성 시각) · endedAt.
 *   <li>gaps: 끊김 · 미확인 구간(from · to · kind, 시작 순). 열린 구간은 to가 null이다.
 *   <li>recordScope: from = started_at(ACTIVE 전에 끝났으면 created_at), to = ended_at.
 *   <li>practice: 연습 확장점이 준 객체, 주지 않으면 null(자유대화 · DECLINED · ACTIVE 전 종료 · U5b 전 PRACTICE, FD-Q4).
 *   <li>audio: deleted_at이 빈 원음 메타(recordingId · speaker · storagePath(디렉터리 기준 상대 경로) · startedAt · endedAt).
 *   <li>DECLINED와 ACTIVE 전에 끝난 세션(started_at 없음)은 utterances · audio가 빈 목록이다(BR8.3).
 *   <li>snapshotHash = {@code "sha256:"} + record를 키 정렬 · 공백 없는 JSON으로 직렬화한 UTF-8 바이트의 sha256(BR8.4). null 값은 키를
 *       남기고 JSON null로 쓴다(계약 6절에 적은 규칙, contract-model SnapshotHashGoldenTest).
 * </ul>
 */
public final class ResultSnapshotBuilder {

    /** 조립 입력. */
    public record Input(ActivitySessionRow session, List<TranscriptRow> transcripts,
            List<SessionGapRepository.SessionGapRow> gaps, List<AudioRecordingRepository.AudioRecordingRow> audio,
            Object practice) {

        public Input {
            Objects.requireNonNull(session, "session");
            transcripts = transcripts == null ? List.of() : List.copyOf(transcripts);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
            audio = audio == null ? List.of() : List.copyOf(audio);
        }

        /** 원문을 담으므로 문자열 표현에 넣지 않는다(BR12.1). */
        @Override
        public String toString() {
            return "Input[sessionId=" + session.id() + "]";
        }
    }

    /**
     * 조립 결과.
     *
     * @param record SessionRecord(키 순서 그대로의 지도, 시각은 Instant)
     * @param recordJson 정규화 JSON(저장 · 해시 원천)
     * @param snapshotHash {@code sha256:} + 16진 64자
     */
    public record Built(Map<String, Object> record, String recordJson, String snapshotHash) {

        /** 원문을 담으므로 문자열 표현에 넣지 않는다(BR12.1). */
        @Override
        public String toString() {
            return "Built[snapshotHash=" + snapshotHash + "]";
        }
    }

    private ResultSnapshotBuilder() {
    }

    /** 조립한다. 세션은 종료 상태여야 한다. */
    public static Built build(Input input, JsonCodec json) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(json, "json");
        ActivitySessionRow session = input.session();
        if (!session.status().isTerminal()) {
            throw new IllegalArgumentException("스냅숏은 종료 세션에서만 만든다: " + session.status());
        }
        boolean emptyRecord = session.status() == SessionStatus.DECLINED || session.startedAt() == null;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("utterances", emptyRecord ? List.of() : utterances(input.transcripts()));
        record.put("gaps", gaps(input.gaps()));
        record.put("recordScope", scope(session));
        record.put("practice", input.practice());
        record.put("audio", emptyRecord ? List.of() : audio(input.audio()));
        String written = json.write(record);
        return new Built(record, json.canonicalize(written), "sha256:" + json.sha256OfCanonical(written));
    }

    private static List<Map<String, Object>> utterances(List<TranscriptRow> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        rows.stream().filter(ResultSnapshotBuilder::included)
                .sorted(Comparator.comparingInt(TranscriptRow::sequenceNo))
                .forEach(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("seq", row.sequenceNo());
                    item.put("speaker", row.speaker().name());
                    item.put("text", row.text() == null ? "" : row.text());
                    item.put("uncertain", row.reviewNeeded());
                    item.put("startedAt", row.startedAt() != null ? row.startedAt() : row.createdAt());
                    item.put("endedAt", row.endedAt());
                    items.add(item);
                });
        return items;
    }

    /** 확정되고 지워지지 않은 행 중 아이 전사와 승인된 AI 말만 넣는다. */
    private static boolean included(TranscriptRow row) {
        if (row.deletedAt() != null || !row.isFinal()) {
            return false;
        }
        return row.speaker() == TranscriptEntry.Speaker.CHILD || row.approvedForDisplay();
    }

    private static List<Map<String, Object>> gaps(List<SessionGapRepository.SessionGapRow> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        rows.stream().sorted(Comparator.comparing(SessionGapRepository.SessionGapRow::startedAt)).forEach(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", row.startedAt());
            item.put("to", row.endedAt());
            item.put("kind", row.kind().name());
            items.add(item);
        });
        return items;
    }

    private static Map<String, Object> scope(ActivitySessionRow session) {
        Map<String, Object> scope = new LinkedHashMap<>();
        Instant from = session.startedAt() != null ? session.startedAt() : session.createdAt();
        scope.put("from", from);
        scope.put("to", session.endedAt());
        return scope;
    }

    private static List<Map<String, Object>> audio(List<AudioRecordingRepository.AudioRecordingRow> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        rows.stream().filter(row -> row.deletedAt() == null)
                .sorted(Comparator.comparing(AudioRecordingRepository.AudioRecordingRow::startedAt)
                        .thenComparing(row -> row.speaker().name()))
                .forEach(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("recordingId", row.id().toString());
                    item.put("speaker", row.speaker().name());
                    item.put("storagePath", row.storagePath());
                    item.put("startedAt", row.startedAt());
                    item.put("endedAt", row.endedAt());
                    items.add(item);
                });
        return items;
    }
}
