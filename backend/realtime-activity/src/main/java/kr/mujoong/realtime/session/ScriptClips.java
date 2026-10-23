package kr.mujoong.realtime.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import kr.mujoong.realtime.speech.SpeechClipException;
import kr.mujoong.realtime.speech.SpeechClipFailure;
import kr.mujoong.realtime.speech.SpeechClipSupport;
import kr.mujoong.realtime.speech.SpeechClipVoice;

/**
 * 세션 하나의 대본 줄 음성(3회차 V1, 실행기 스레드 전용). 시작 때(참여 확인 진입) · 다음 목표 대본이 왔을 때 확장이 알려 준 대본 줄을 공유 장치
 * ({@link SpeechClipSupport})에 맡기고, 낼 차례에 (말하는 사람 표시, 원문)으로 찾는다. 세션마다 {@code session-max-lines}줄까지만 맡긴다. 원문 · 음성은 로그
 * · 사건에 내지 않는다(R-F14).
 */
final class ScriptClips {

    /** 그 대본 줄 음성의 지금 상태. */
    enum State {
        /** 맡긴 적이 없다(미리 알려 주지 않은 줄 · 꺼진 장치 · 줄 수 상한). */
        ABSENT,
        /** 만드는 중이다. */
        PENDING,
        /** 만들었다. */
        READY,
        /** 만들지 못했다. */
        FAILED
    }

    /** 찾은 결과. */
    record Lookup(State state, SpeechClipSupport.Clip clip, SpeechClipFailure failure) {

        static final Lookup ABSENT = new Lookup(State.ABSENT, null, null);
        static final Lookup PENDING = new Lookup(State.PENDING, null, null);
    }

    /** 한 번 맡긴 결과(개수만). */
    record Prepared(int lines, int started, int cached, int known, int refused) {
    }

    private record Key(String speaker, String text) {

        @Override
        public String toString() {
            return "Key[length=" + text.length() + "]";
        }
    }

    private final SpeechClipSupport support;
    private final BiConsumer<String, String> onSettled;
    private final Map<Key, CompletableFuture<SpeechClipSupport.Clip>> entries = new LinkedHashMap<>();

    /**
     * @param onSettled 맡긴 줄의 음성이 만들어지거나 실패했을 때(말하는 사람 표시, 원문). 아무 스레드에서나 불리므로 편지함에 넣기만 해야 한다
     */
    ScriptClips(SpeechClipSupport support, BiConsumer<String, String> onSettled) {
        this.support = Objects.requireNonNull(support, "support");
        this.onSettled = Objects.requireNonNull(onSettled, "onSettled");
    }

    boolean enabled() {
        return support.enabled();
    }

    /** 낼 차례에 음성이 아직 만들어지는 중일 때 기다리는 상한(꺼진 장치는 0). */
    java.time.Duration readyWait() {
        return support.readyWait();
    }

    /** 대본 줄들을 맡긴다. 이미 맡긴 줄은 다시 맡기지 않고, 세션 줄 수 상한을 넘는 줄은 맡기지 않는다. */
    Prepared prepare(List<PracticeSessionExtension.SpeechLine> lines) {
        int total = lines == null ? 0 : lines.size();
        if (!support.enabled() || lines == null) {
            return new Prepared(total, 0, 0, 0, 0);
        }
        int started = 0;
        int cached = 0;
        int known = 0;
        int refused = 0;
        for (PracticeSessionExtension.SpeechLine line : lines) {
            Key key = new Key(line.speaker(), line.text());
            if (entries.containsKey(key)) {
                known++;
                continue;
            }
            if (entries.size() >= support.sessionMaxLines()) {
                refused++;
                continue;
            }
            CompletableFuture<SpeechClipSupport.Clip> clip = support.clip(voice(line.voice()), line.text());
            entries.put(key, clip);
            if (clip.isDone()) {
                cached++;
            } else {
                started++;
                clip.whenComplete((made, failure) -> onSettled.accept(key.speaker(), key.text()));
            }
        }
        return new Prepared(total, started, cached, known, refused);
    }

    /** 그 줄 음성의 지금 상태. */
    Lookup lookup(String speaker, String text) {
        CompletableFuture<SpeechClipSupport.Clip> clip = speaker == null || text == null ? null : entries.get(new Key(speaker, text));
        if (clip == null) {
            return Lookup.ABSENT;
        }
        if (!clip.isDone()) {
            return Lookup.PENDING;
        }
        try {
            return new Lookup(State.READY, clip.join(), null);
        } catch (CompletionException | CancellationException failed) {
            return new Lookup(State.FAILED, null, SpeechClipException.failureOf(failed));
        }
    }

    /** 맡긴 줄 수(시험 확인용). */
    int size() {
        return entries.size();
    }

    static SpeechClipVoice voice(PracticeSessionExtension.SpeechVoice voice) {
        return voice == PracticeSessionExtension.SpeechVoice.NARRATOR ? SpeechClipVoice.NARRATOR : SpeechClipVoice.PEER;
    }
}
