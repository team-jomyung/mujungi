package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.invalid;
import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import org.springframework.util.unit.DataSize;

/**
 * U3 원음 기록(components AudioRecording · K6): 동의된 세션의 아이 · AI 원음을
 * {@code <directory>/<sessionId>/child.pcm} · {@code ai.pcm}(PCM16LE 24kHz 모노 raw)으로 쓴다. 세션당 바이트 상한을 넘으면 그
 * 세션의 기록을 멈춘다(대화는 계속). 디렉터리는 운영에서 볼륨 경로를 환경 변수 MUJOONG_RECORDING_DIRECTORY로 준다.
 */
public record RealtimeRecordingProperties(String directory, DataSize maxBytesPerSession) {
    public RealtimeRecordingProperties {
        if (directory == null || directory.isBlank()) {
            throw invalid("필수 설정 키가 비어 있다: mujoong.recording.directory");
        }
        directory = directory.trim();
        positive(maxBytesPerSession, "mujoong.recording.max-bytes-per-session");
    }
}
