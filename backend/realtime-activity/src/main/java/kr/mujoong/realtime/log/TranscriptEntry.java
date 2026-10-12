package kr.mujoong.realtime.log;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 기록기 칸 하나 — 전사 행 한 번호의 지금 값(BR7.5 · PD-5 표 2.4-A · entities Transcript).
 *
 * <p>번호는 세션 안에서 1부터 오르고 확정 전 조각은 같은 번호로 덮어쓴다. AI 말은 게이트 판정과 함께 FINAL이 될 때만 승인될 수 있다
 * — 확정 전(PARTIAL) AI 칸은 approved가 false다(NFR1.1 · BR7.4). 텍스트는 문자열 표현에 넣지 않는다(BR8.7).
 *
 * @param sequenceNo 세션 안 번호(1부터)
 * @param text 전사 원문(빈 문자열 가능: 게이트가 폐기한 빈 구간)
 * @param approvedForDisplay 보호자 화면에 나갈 수 있는가(아이 전사는 확정 즉시 true, AI 말은 통과 때 true)
 * @param startedAt 발화 시작(없으면 null)
 * @param endedAt 발화 끝(없으면 null)
 * @param reviewNeeded U3: 불확실 표시(공급자 신뢰도가 기준 미만이거나 말 끝 뒤 확정 전사가 빔, AC6.4.7 · U3-R10). 아이 확정 전사만 true일 수 있다
 */
public record TranscriptEntry(
        UUID sessionId,
        int sequenceNo,
        Speaker speaker,
        String text,
        AssemblyStatus assemblyStatus,
        boolean approvedForDisplay,
        Instant startedAt,
        Instant endedAt,
        boolean reviewNeeded) {

    /** 말한 쪽(V1 CHECK). */
    public enum Speaker {
        CHILD,
        AI
    }

    /** 조립 상태(V1 CHECK). */
    public enum AssemblyStatus {
        PARTIAL,
        FINAL
    }

    public TranscriptEntry {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("sequenceNo는 1부터다(BR7.5)");
        }
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(assemblyStatus, "assemblyStatus");
        if (speaker == Speaker.AI && assemblyStatus == AssemblyStatus.PARTIAL && approvedForDisplay) {
            throw new IllegalArgumentException("AI 말은 게이트 판정과 함께 FINAL이 될 때만 승인된다(BR7.5)");
        }
        if (reviewNeeded && (speaker != Speaker.CHILD || assemblyStatus != AssemblyStatus.FINAL)) {
            throw new IllegalArgumentException("불확실 표시는 아이 확정 전사에만 붙는다(U3-R10)");
        }
    }

    /** U1 모양(불확실 표시 없음). */
    public TranscriptEntry(UUID sessionId, int sequenceNo, Speaker speaker, String text, AssemblyStatus assemblyStatus,
            boolean approvedForDisplay, Instant startedAt, Instant endedAt) {
        this(sessionId, sequenceNo, speaker, text, assemblyStatus, approvedForDisplay, startedAt, endedAt, false);
    }

    /** 확정 전 조각. */
    public static TranscriptEntry partial(UUID sessionId, int sequenceNo, Speaker speaker, String text,
            Instant startedAt) {
        return new TranscriptEntry(sessionId, sequenceNo, speaker, text, AssemblyStatus.PARTIAL, false, startedAt, null);
    }

    /** 확정본. 아이 전사는 승인 true, AI 말은 게이트 결과(통과 true · 폐기 false)를 같은 문장으로 쓴다. */
    public static TranscriptEntry finalized(UUID sessionId, int sequenceNo, Speaker speaker, String text,
            boolean approvedForDisplay, Instant startedAt, Instant endedAt) {
        return new TranscriptEntry(sessionId, sequenceNo, speaker, text, AssemblyStatus.FINAL, approvedForDisplay,
                startedAt, endedAt);
    }

    /** U3: 아이 확정 전사(승인 true)와 불확실 표시(U3-R10). */
    public static TranscriptEntry childFinal(UUID sessionId, int sequenceNo, String text, Instant startedAt,
            Instant endedAt, boolean reviewNeeded) {
        return new TranscriptEntry(sessionId, sequenceNo, Speaker.CHILD, text, AssemblyStatus.FINAL, true, startedAt,
                endedAt, reviewNeeded);
    }

    @Override
    public String toString() {
        return "TranscriptEntry[sessionId=" + sessionId + ", seq=" + sequenceNo + ", speaker=" + speaker + ", status="
                + assemblyStatus + ", approved=" + approvedForDisplay + ", reviewNeeded=" + reviewNeeded + "]";
    }
}
