package kr.mujoong.realtime.speech;

import java.util.concurrent.CompletableFuture;

/**
 * 대본 줄 하나를 음성으로 만드는 곳(3회차 V1). 운영은 {@link OpenAiSpeechClipSynthesizer}(OpenAI Audio API, {@code response_format: pcm}),
 * 시험은 대역이다(실제 AI를 부르지 않는다, R-F1). 돌려주는 바이트는 24kHz s16 모노 PCM(GPT-Live 출력 · 기기 0x02 프레임과 같은 형식)이어야 한다.
 */
public interface SpeechClipSynthesizer extends AutoCloseable {

    /**
     * 합성한다. 기다리지 않는다. 실패는 {@link SpeechClipException}(사유 코드)으로 끝난다.
     *
     * @param voice 말투
     * @param text 승인된 대본 줄 원문(로그 · 예외 메시지에 넣지 않는다)
     */
    CompletableFuture<byte[]> synthesize(SpeechClipVoice voice, String text);

    /** 연결 자원을 닫는다(기본은 할 일 없음). */
    @Override
    default void close() {
    }
}
