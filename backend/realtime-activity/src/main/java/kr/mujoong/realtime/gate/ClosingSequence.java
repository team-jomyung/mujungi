package kr.mujoong.realtime.gate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;

/**
 * 끝내기 순서 (U1 3.5 Step 25 · BR6.10 · R3-8 · SD-17 · AC3.3.2 ~ 3.3.7).
 *
 * <p>ACTIVE → CLOSING 전이를 이긴 뒤 세션 실행기가 {@link #begin}을 부른다: ① STOP_OUTPUT(GUARDIAN_STOP)과 SESSION_STATE{CLOSING} ②
 * CLOSING 승인 문구를 새 playbackId로 한 번 PLAY_APPROVED_CONTENT ③ 같은 playbackId의 종료 표식(COMPLETED · STOPPED · FAILED)을 받으면
 * {@link #onPlaybackMark}가 CLOSE_TRANSPORT(GUARDIAN_STOP)를 보낸다 ④ 기기의 VOICE_SESSION_CLOSED를 받으면 실행기가 공급자를 닫고
 * CLOSING → ENDED(confirmed) 뒤 STANDBY를 보낸다. 출력 세대는 실행기가 ① 직전에 올린다. 입력 중계는 CLOSE_TRANSPORT 송신까지 이어진다(R3-8).
 * 확인이 오지 않으면 30초 시한(BR6.12 ③)이 ENDED_UNCONFIRMED로 닫는다.
 */
public final class ClosingSequence {

    /** 기기로 보내는 명령(연결이 없으면 거짓). */
    public interface Device {

        boolean stopOutput(StopOutput.Reason reason);

        boolean sessionState(View view);

        boolean playApproved(UUID playbackId, String contentCode, UUID contentVersionId);

        boolean closeTransport(CloseTransport.Reason reason);
    }

    /** 단계. */
    public enum Step {
        NOT_STARTED,
        AWAITING_CLOSING_MARK,
        TRANSPORT_CLOSED
    }

    private Step step = Step.NOT_STARTED;
    private UUID closingPlaybackId;

    /** ① ② (한 번만). 마무리 문구 playbackId를 돌려준다. */
    public UUID begin(Device device, UUID closingContentVersionId, ApprovedPlayback playback, Supplier<UUID> ids) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(playback, "playback");
        if (step != Step.NOT_STARTED) {
            return closingPlaybackId;
        }
        device.stopOutput(StopOutput.Reason.GUARDIAN_STOP);
        playback.clear();
        device.sessionState(View.CLOSING);
        closingPlaybackId = ids.get();
        step = Step.AWAITING_CLOSING_MARK;
        if (closingContentVersionId != null
                && device.playApproved(closingPlaybackId, PlayApprovedContent.CLOSING, closingContentVersionId)) {
            playback.started(closingPlaybackId, PlayApprovedContent.CLOSING);
        }
        return closingPlaybackId;
    }

    /** ③ 마무리 문구의 종료 표식이면 CLOSE_TRANSPORT를 보내고 참. */
    public boolean onPlaybackMark(Device device, UUID playbackId, PlaybackMark.Status status) {
        if (step != Step.AWAITING_CLOSING_MARK || !Objects.equals(playbackId, closingPlaybackId)
                || !(status == PlaybackMark.Status.COMPLETED || status == PlaybackMark.Status.STOPPED
                        || status == PlaybackMark.Status.FAILED)) {
            return false;
        }
        device.closeTransport(CloseTransport.Reason.GUARDIAN_STOP);
        step = Step.TRANSPORT_CLOSED;
        return true;
    }

    /**
     * 마무리 문구 끝을 기다리지 않고 지금 CLOSE_TRANSPORT(GUARDIAN_STOP)를 보낸다 — 세션을 모르는 기기가 새로 붙어 마무리 문구를 재생할 수 없을
     * 때(리뷰 반영 5). 이미 보냈으면 같은 명령을 다시 보낸다.
     *
     * @return 이번이 첫 CLOSE_TRANSPORT면 참(호출자가 입력 중계를 멈추고 송신 시각을 적는다)
     */
    public boolean closeNow(Device device) {
        Objects.requireNonNull(device, "device");
        boolean first = step != Step.TRANSPORT_CLOSED;
        device.closeTransport(CloseTransport.Reason.GUARDIAN_STOP);
        step = Step.TRANSPORT_CLOSED;
        return first;
    }

    public Step step() {
        return step;
    }

    public UUID closingPlaybackId() {
        return closingPlaybackId;
    }
}
