package kr.mujoong.realtime.log;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.realtime.log.TranscriptEntry.AssemblyStatus;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;

/**
 * {@code realtime.realtime_transcript} 한 행(12열, entities Transcript). 텍스트는 문자열 표현에 넣지 않는다(BR8.7 · NFR9.8).
 */
public record TranscriptRow(
        UUID id,
        UUID sessionId,
        int sequenceNo,
        Speaker speaker,
        String text,
        AssemblyStatus assemblyStatus,
        boolean reviewNeeded,
        boolean approvedForDisplay,
        Instant startedAt,
        Instant endedAt,
        Instant createdAt,
        Instant deletedAt) {

    /** 확정본인가. */
    public boolean isFinal() {
        return assemblyStatus == AssemblyStatus.FINAL;
    }

    @Override
    public String toString() {
        return "TranscriptRow[sessionId=" + sessionId + ", seq=" + sequenceNo + ", speaker=" + speaker + ", status="
                + assemblyStatus + ", approved=" + approvedForDisplay + "]";
    }
}
