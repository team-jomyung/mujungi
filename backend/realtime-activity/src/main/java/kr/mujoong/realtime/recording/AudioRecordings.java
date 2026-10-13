package kr.mujoong.realtime.recording;

import java.time.Instant;
import java.util.UUID;

/**
 * 원음 기록 입구 (U3 L3 · components AudioRecording · U3-R9 · U3-J14 · U3-J15 · K6 · AC6.4.7).
 *
 * <p>세션 실행기가 동의(consentSnapshot.rawAudio)가 있는 세션에서만 {@link #open}을 부른다 — 동의가 없으면 한 바이트도 디스크에 가지 않는다(BR7.1).
 * 받은 음성은 기다리지 않고 대기열에 넣고(가득 차면 버리고 센다), 쓰기는 기록기 전용 스레드가 한다(실행기 · 프레임 경로를 막지 않음, PD-1). 운영
 * 구현은 {@link FileAudioRecorder}, 실행기 단위 시험은 메모리 대역을 쓴다.
 */
public interface AudioRecordings {

    /** 세션 하나의 기록. 모든 메서드는 기다리지 않고 예외를 던지지 않는다. */
    interface Session {

        /** 아이 입력 프레임(중계가 보낸 순서, PCM16LE 24kHz 모노). */
        void child(byte[] pcm);

        /** 게이트를 통과해 기기로 보낸 AI 출력 조각(PCM16LE 24kHz 모노). */
        void ai(byte[] pcm);

        /** 세션 기록을 닫는다(파일을 닫고 메타에 바이트 수 · 끝 시각). 두 번 불러도 된다. */
        void close(Instant endedAt);
    }

    /** 동의된 세션의 기록을 연다. */
    Session open(UUID sessionId);
}
