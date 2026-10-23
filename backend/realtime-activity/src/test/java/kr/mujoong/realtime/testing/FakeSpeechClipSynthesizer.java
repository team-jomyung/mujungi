package kr.mujoong.realtime.testing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.realtime.speech.SpeechClipException;
import kr.mujoong.realtime.speech.SpeechClipFailure;
import kr.mujoong.realtime.speech.SpeechClipSynthesizer;
import kr.mujoong.realtime.speech.SpeechClipVoice;

/**
 * 대본 줄 음성 합성 대역(시험 소스 전용, 3회차 V1). 실제 OpenAI Audio API를 부르지 않는다(R-F1). 기본은 부르는 즉시 원문 글자 수에 비례한 무음 PCM
 * ({@link #pcmFor})을 돌려주고, {@link #immediate}를 끄면 시험이 {@link #complete} · {@link #fail}로 푼다. 이 대역의 성공은 테스트 대역 증거이고
 * 실제 합성 · 실제 기기 재생 성공이 아니다(R-M11).
 */
public final class FakeSpeechClipSynthesizer implements SpeechClipSynthesizer {

    /** 글자 하나의 음성 길이(바이트, 24kHz s16 모노 100ms). */
    public static final int BYTES_PER_CHAR = 4_800;

    /** 합성 요청 하나. */
    public record Call(SpeechClipVoice voice, String text, CompletableFuture<byte[]> result) {

        @Override
        public String toString() {
            return "Call[voice=" + voice + ", length=" + text.length() + "]";
        }
    }

    public final List<Call> calls = new ArrayList<>();
    /** 원문 → 이 실패로 끝낸다. */
    public final Map<String, SpeechClipFailure> failures = new HashMap<>();
    /** 참이면 부르는 즉시 음성을 돌려준다. */
    public boolean immediate = true;
    public int closed;

    /** 그 원문의 대역 음성(글자마다 100ms 무음). */
    public static byte[] pcmFor(String text) {
        return new byte[text.codePointCount(0, text.length()) * BYTES_PER_CHAR];
    }

    /** 그 원문의 대역 음성 길이(ms). */
    public static long msFor(String text) {
        return pcmFor(text).length / 48L;
    }

    @Override
    public synchronized CompletableFuture<byte[]> synthesize(SpeechClipVoice voice, String text) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        calls.add(new Call(voice, text, result));
        SpeechClipFailure failure = failures.get(text);
        if (failure != null) {
            result.completeExceptionally(new SpeechClipException(failure));
        } else if (immediate) {
            result.complete(pcmFor(text));
        }
        return result;
    }

    /** 기다리게 둔 그 원문의 합성을 끝낸다(호출한 스레드에서 이어지는 일이 돈다). */
    public void complete(String text) {
        pending(text).complete(pcmFor(text));
    }

    /** 기다리게 둔 그 원문의 합성을 실패로 끝낸다. */
    public void fail(String text, SpeechClipFailure failure) {
        pending(text).completeExceptionally(new SpeechClipException(failure));
    }

    /** 그 원문으로 들어온 합성 요청 수. */
    public synchronized long callsFor(String text) {
        return calls.stream().filter(call -> call.text().equals(text)).count();
    }

    /** 합성을 맡긴 원문(순서대로). */
    public synchronized List<String> texts() {
        return calls.stream().map(Call::text).toList();
    }

    private synchronized CompletableFuture<byte[]> pending(String text) {
        return calls.stream().filter(call -> call.text().equals(text) && !call.result().isDone()).findFirst()
                .orElseThrow(() -> new IllegalStateException("기다리는 합성 요청이 없다")).result();
    }

    @Override
    public void close() {
        closed++;
    }
}
