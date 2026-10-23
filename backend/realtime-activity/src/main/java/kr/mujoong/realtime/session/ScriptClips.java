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
 * ({@link SpeechClipSupport})에 맡기고, 낼 차례에 (말하는 사람 표시, 원문)으로 찾는다. 세션마다 {@code session-max-lines}줄까지만 맡긴다. 4회차 R6:
 * 일시 실패로 끝난 줄은 낼 차례에(그때는 GPT-Live 원문 읽기로 낸다) 다음을 위해 뒤에서 한 번 다시 맡긴다(줄마다 한 번). 원문 · 음성은 로그 · 사건에
 * 내지 않는다(R-F14).
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

    /** 맡긴 줄 하나(말투 · 음성 약속 · 한 번 다시 맡겼는가). */
    private record Entry(SpeechClipVoice voice, CompletableFuture<SpeechClipSupport.Clip> clip, boolean retried) {
    }

    private final SpeechClipSupport support;
    private final BiConsumer<String, String> onSettled;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();

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
            SpeechClipVoice voice = voice(line.voice());
            CompletableFuture<SpeechClipSupport.Clip> clip = support.clip(voice, line.text());
            entries.put(key, new Entry(voice, clip, false));
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
        Entry entry = speaker == null || text == null ? null : entries.get(new Key(speaker, text));
        if (entry == null) {
            return Lookup.ABSENT;
        }
        CompletableFuture<SpeechClipSupport.Clip> clip = entry.clip();
        if (!clip.isDone()) {
            return Lookup.PENDING;
        }
        try {
            return new Lookup(State.READY, clip.join(), null);
        } catch (CompletionException | CancellationException failed) {
            return new Lookup(State.FAILED, null, SpeechClipException.failureOf(failed));
        }
    }

    /**
     * 4회차 R6: 실패한 줄을 낼 차례가 왔다(그 줄은 지금 GPT-Live 원문 읽기로 낸다). 일시 실패(공유 장치가 이미 한 번 더 해 본 시간 초과 · 요청 많음 ·
     * 공급자 · 전송 오류, 장치 과부하)였고 아직 다시 맡기지 않은 줄이면 다음에 낼 때 쓰도록 뒤에서 한 번 다시 맡긴다. 기다리지 않는다. 세션 줄 수는 늘지
     * 않는다(같은 줄의 자리를 바꾼다).
     *
     * @return 다시 맡겼으면 참
     */
    boolean retryFailed(String speaker, String text) {
        if (!support.enabled() || speaker == null || text == null) {
            return false;
        }
        Key key = new Key(speaker, text);
        Entry entry = entries.get(key);
        if (entry == null || entry.retried()) {
            return false;
        }
        Lookup now = lookup(speaker, text);
        if (now.state() != State.FAILED || !retryable(now.failure())) {
            return false;
        }
        CompletableFuture<SpeechClipSupport.Clip> again = support.clip(entry.voice(), text);
        entries.put(key, new Entry(entry.voice(), again, true));
        if (!again.isDone()) {
            again.whenComplete((made, failure) -> onSettled.accept(speaker, text));
        }
        return true;
    }

    /** 다시 맡겨 볼 만한 실패(일시 실패 · 장치 과부하). */
    private static boolean retryable(SpeechClipFailure failure) {
        return failure == SpeechClipFailure.OVERLOADED || SpeechClipSupport.transientFailure(failure);
    }

    /** 맡긴 줄 수(시험 확인용). */
    int size() {
        return entries.size();
    }

    static SpeechClipVoice voice(PracticeSessionExtension.SpeechVoice voice) {
        return voice == PracticeSessionExtension.SpeechVoice.NARRATOR ? SpeechClipVoice.NARRATOR : SpeechClipVoice.PEER;
    }
}
