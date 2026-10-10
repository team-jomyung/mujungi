package kr.mujoong.realtime.gate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * '재생 중인 승인 문구' 표시 (U1 3.5 Step 25 · PD-3 2.3 8단계 겹침 규칙 · R3-19 · BR7.4 · BR6.10).
 *
 * <p>세션 실행기가 PLAY_APPROVED_CONTENT(마무리 인사 · WAITING_FILLER)를 보내면 그 playbackId를 둔다. 값이 있는 동안 새 WAITING_FILLER는
 * 건너뛴다(기다렸다 다시 보내지 않는다). 지우는 때: 같은 playbackId의 종료 표식(COMPLETED · STOPPED · FAILED), STOP_OUTPUT 송신, 기기
 * 연결 교체 · 끊김, 세션 종료 상태 전이, 송신 뒤 {@code mujoong.timing.device.output-queue-max}(20초) 만기(세션 타이머). 실행기만 쓴다.
 */
public final class ApprovedPlayback {

    /** 재생 중인 문구. */
    public record Playing(UUID playbackId, String contentCode) {
    }

    private Playing playing;

    /** 보냈다. */
    public void started(UUID playbackId, String contentCode) {
        playing = new Playing(Objects.requireNonNull(playbackId, "playbackId"),
                Objects.requireNonNull(contentCode, "contentCode"));
    }

    public boolean isPlaying() {
        return playing != null;
    }

    public Optional<Playing> current() {
        return Optional.ofNullable(playing);
    }

    /** 같은 playbackId면 지운다. */
    public boolean clearIfMatches(UUID playbackId) {
        if (playing != null && playing.playbackId().equals(playbackId)) {
            playing = null;
            return true;
        }
        return false;
    }

    /** 지운다. */
    public void clear() {
        playing = null;
    }
}
