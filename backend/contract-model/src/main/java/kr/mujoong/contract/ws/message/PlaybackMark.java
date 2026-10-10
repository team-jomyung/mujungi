package kr.mujoong.contract.ws.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * PLAYBACK_MARK(기기 → 서버). 기기 플레이어가 관측한 재생만 보고한다. observedAt은 기기 시계 값이다(OD-8 tDevicePlayStart).
 * U1 서버는 PROGRESS를 받아도 무시한다(J123).
 */
public record PlaybackMark(
        UUID playbackId,
        Status status,
        PlayedRange playedRange,
        Instant observedAt,
        StopCause stopCause,
        FailureReason failureReason)
        implements WsPayload {

    /** 재생 상태. */
    public enum Status {
        STARTED,
        PROGRESS,
        COMPLETED,
        STOPPED,
        FAILED,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    /** 멈춘 원인. */
    public enum StopCause {
        LOCAL_VOICE_ACTIVITY,
        STOP_OUTPUT,
        CLOSE_TRANSPORT,
        DISCONNECTED,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    /** 실패 원인. */
    public enum FailureReason {
        DECODE_ERROR,
        AUDIO_DEVICE_ERROR,
        QUEUE_OVERFLOW,
        FETCH_FAILED,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    /** 실제로 재생된 범위(모르면 null). */
    public record PlayedRange(Long fromMs, Long toMs) {
    }

    /** 이 마크가 재생의 끝(COMPLETED · STOPPED · FAILED)을 알리는가(BR6.10 ③). */
    public boolean isFinal() {
        return status == Status.COMPLETED || status == Status.STOPPED || status == Status.FAILED;
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.PLAYBACK_MARK;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (playbackId == null) {
            problems.add("playbackId:missing");
        }
        if (status == null) {
            problems.add("status:missing");
        }
        if (observedAt == null) {
            problems.add("observedAt:missing");
        }
        if (playedRange != null && (playedRange.fromMs() == null || playedRange.toMs() == null
                || playedRange.fromMs() < 0 || playedRange.toMs() < 0)) {
            problems.add("playedRange:invalid");
        }
        return problems;
    }
}
