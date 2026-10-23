package kr.mujoong.realtime.speech;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.gate.SegmentBuffer;

/**
 * 세션들이 함께 쓰는 대본 줄 음성 장치(3회차 V1, contracts/ai-io.md 10.16, 편차 (PDS-V1)). 실행부가 연습 세션 시작 때(참여 확인 · 시작 안내 동안) 그
 * 세션의 대본 줄을 미리 맡기고, 낼 차례에 만든 음성을 기기 출력 묶음으로 보낸다(GPT-Live가 대본을 읽지 않는다).
 *
 * <ul>
 *   <li>같은 말투 · 같은 원문은 한 번만 만든다: 만드는 중이면 같은 약속을, 만든 것은 캐시({@code cache-max-bytes}, 오래 안 쓴 것부터 버림)를 돌려준다.
 *       일시 실패(시간 초과 · 요청 많음 · 공급자 · 전송 오류)는 곧바로 한 번 더 맡기고(4회차 R6), 그래도 실패한 줄은 캐시에 두지 않는다(다시 맡길 수 있다).
 *   <li>앱 전체 동시 합성은 {@code max-concurrency}개, 차례를 기다리는 요청은 {@code max-pending}개까지다. 넘으면 그 줄은 OVERLOADED이고 GPT-Live로
 *       읽는다.
 *   <li>음성은 24kHz s16 모노 PCM이고 비어 있지 않으며 출력 구간 상한(20초 분량, {@link SegmentBuffer#MAX_BYTES}) 안이어야 한다.
 *   <li>원문 · 음성은 로그에 남기지 않는다(R-F14). 실행부가 세션 사건으로 남기는 것은 개수 · 사유 코드 · 길이뿐이다.
 * </ul>
 */
public final class SpeechClipSupport implements AutoCloseable {

    /** 켜짐 상태(시험 · 하니스 확인용, 값 미출력). */
    public enum State {
        ENABLED,
        DISABLED_SWITCH_OFF,
        DISABLED_NO_KEY
    }

    /** 대본 줄 하나의 입력 상한(코드 포인트). 출력 구간 20초에 들어가는 문장보다 넉넉하다. */
    public static final int MAX_INPUT_LENGTH = 600;
    /** 4회차 R6: 한 줄을 만드는 시도 수 상한(처음 + 일시 실패 뒤 한 번, 시간 값이 아니다). */
    public static final int MAX_ATTEMPTS = 2;
    /** 합성 음성 상한(출력 구간 상한과 같은 20초 분량). */
    public static final int MAX_CLIP_BYTES = SegmentBuffer.MAX_BYTES;

    /** 꺼진 장치(합성하지 않는다 — 모든 대본 줄은 GPT-Live 원문 읽기다). */
    public static final SpeechClipSupport DISABLED = disabled(State.DISABLED_SWITCH_OFF);

    /**
     * 만든 음성 한 줄.
     *
     * @param pcm 24kHz s16 모노 PCM(바꾸지 않는다, 받는 쪽도 바꾸지 않는다)
     * @param synthesisLatency 맡긴 때부터 만든 때까지(캐시에서 받으면 처음 만들 때의 값)
     */
    public record Clip(byte[] pcm, Duration synthesisLatency) {

        public Clip {
            Objects.requireNonNull(pcm, "pcm");
            Objects.requireNonNull(synthesisLatency, "synthesisLatency");
        }

        /** 음성 길이(ms). */
        public long audioMs() {
            return pcm.length / 48L;
        }

        @Override
        public String toString() {
            return "Clip[audioMs=" + audioMs() + "]";
        }
    }

    private record Key(SpeechClipVoice voice, String text) {

        @Override
        public String toString() {
            return "Key[voice=" + voice + "]";
        }
    }

    private final State state;
    private final SpeechClipSynthesizer synthesizer;
    private final MujoongClock clock;
    private final Duration readyWait;
    private final int maxConcurrency;
    private final int maxPending;
    private final long cacheMaxBytes;
    private final int sessionMaxLines;
    private final Object lock = new Object();
    /** 만드는 중 · 만든 줄(접근 순서 — 앞이 오래 안 쓴 것). */
    private final LinkedHashMap<Key, CompletableFuture<Clip>> clips = new LinkedHashMap<>(16, 0.75f, true);
    private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
    private long cachedBytes;
    private int running;

    /**
     * @param synthesizer 실제로 만드는 곳(운영 OpenAI Audio API, 시험 대역)
     * @param readyWait 낼 차례에 합성이 덜 끝났을 때 실행부가 기다리는 상한({@code timing.speech-clip.ready-wait})
     * @param sessionMaxLines 세션 하나가 맡기는 대본 줄 수 상한({@code mujoong.speech-clip.session-max-lines}, 실행부가 지킨다)
     */
    public SpeechClipSupport(SpeechClipSynthesizer synthesizer, MujoongClock clock, Duration readyWait, int maxConcurrency,
            int maxPending, long cacheMaxBytes, int sessionMaxLines) {
        this(State.ENABLED, Objects.requireNonNull(synthesizer, "synthesizer"), Objects.requireNonNull(clock, "clock"),
                Objects.requireNonNull(readyWait, "readyWait"), maxConcurrency, maxPending, cacheMaxBytes, sessionMaxLines);
        if (readyWait.isNegative() || maxConcurrency <= 0 || maxPending <= 0 || cacheMaxBytes <= 0 || sessionMaxLines <= 0) {
            throw new IllegalArgumentException("대기 · 동시 수 · 대기열 · 캐시 · 세션 줄 상한이 올바르지 않다");
        }
    }

    private SpeechClipSupport(State state, SpeechClipSynthesizer synthesizer, MujoongClock clock, Duration readyWait,
            int maxConcurrency, int maxPending, long cacheMaxBytes, int sessionMaxLines) {
        this.state = state;
        this.synthesizer = synthesizer;
        this.clock = clock;
        this.readyWait = readyWait;
        this.maxConcurrency = maxConcurrency;
        this.maxPending = maxPending;
        this.cacheMaxBytes = cacheMaxBytes;
        this.sessionMaxLines = sessionMaxLines;
    }

    /** 꺼진 장치(그 까닭). */
    public static SpeechClipSupport disabled(State why) {
        if (Objects.requireNonNull(why, "why") == State.ENABLED) {
            throw new IllegalArgumentException("꺼진 장치의 상태가 아니다");
        }
        return new SpeechClipSupport(why, null, null, Duration.ZERO, 1, 1, 1, 0);
    }

    public State state() {
        return state;
    }

    public boolean enabled() {
        return state == State.ENABLED;
    }

    /** 낼 차례에 합성이 덜 끝났을 때 기다리는 상한(꺼진 장치는 0). */
    public Duration readyWait() {
        return readyWait;
    }

    /** 세션 하나가 맡기는 대본 줄 수 상한(꺼진 장치는 0). */
    public int sessionMaxLines() {
        return sessionMaxLines;
    }

    /**
     * 그 대본 줄의 음성. 만드는 중이거나 만든 줄이면 같은 약속이고, 아니면 차례에 맞춰 맡긴다. 실패는 {@link SpeechClipException}(사유 코드)으로 끝난다.
     * 기다리지 않는다.
     */
    public CompletableFuture<Clip> clip(SpeechClipVoice voice, String text) {
        Objects.requireNonNull(voice, "voice");
        if (state != State.ENABLED) {
            return failed(SpeechClipFailure.DISABLED);
        }
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > MAX_INPUT_LENGTH) {
            return failed(SpeechClipFailure.INVALID_INPUT);
        }
        Key key = new Key(voice, text);
        CompletableFuture<Clip> result;
        Runnable start = null;
        synchronized (lock) {
            CompletableFuture<Clip> existing = clips.get(key);
            if (existing != null) {
                return existing;
            }
            if (running >= maxConcurrency && waiting.size() >= maxPending) {
                return failed(SpeechClipFailure.OVERLOADED);
            }
            result = new CompletableFuture<>();
            clips.put(key, result);
            CompletableFuture<Clip> target = result;
            Runnable task = () -> launch(key, target);
            if (running < maxConcurrency) {
                running++;
                start = task;
            } else {
                waiting.addLast(task);
            }
        }
        if (start != null) {
            start.run();
        }
        return result;
    }

    /** 캐시가 쥔 음성 바이트(시험 확인용). */
    public long cachedBytes() {
        synchronized (lock) {
            return cachedBytes;
        }
    }

    @Override
    public void close() {
        if (synthesizer != null) {
            synthesizer.close();
        }
    }

    @Override
    public String toString() {
        return "SpeechClipSupport[state=" + state + "]";
    }

    /**
     * 4회차 R6: 같은 줄을 한 번 더 맡겨 볼 만한 일시 실패인가(시간 초과 · 요청 많음 · 공급자 5xx · 전송 오류). 인증 · 입력 · 길이 · 음성 형식 실패는 다시
     * 해도 같으므로 아니다.
     */
    public static boolean transientFailure(SpeechClipFailure failure) {
        return failure == SpeechClipFailure.TIMEOUT || failure == SpeechClipFailure.RATE_LIMITED
                || failure == SpeechClipFailure.PROVIDER_ERROR || failure == SpeechClipFailure.TRANSPORT_ERROR;
    }

    private void launch(Key key, CompletableFuture<Clip> result) {
        attempt(key, result, clock.monotonicNanos(), 1);
    }

    /**
     * 합성 한 번. 4회차 R6: 일시 실패면 같은 차례(동시 합성 자리를 쥔 채)에서 곧바로 한 번 더 맡긴다(실측 B5: 47줄 중 9줄이 10초 시한을 넘겼고 같은 줄이
     * 두 세션 연속으로 넘기기도 했다). 그래도 실패하면 지금처럼 실패로 끝내 캐시에 두지 않는다. 만든 시간은 처음 맡긴 때부터 잰다.
     */
    private void attempt(Key key, CompletableFuture<Clip> result, long started, int attempt) {
        CompletableFuture<byte[]> call;
        try {
            call = synthesizer.synthesize(key.voice(), key.text());
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.whenComplete((pcm, failure) -> {
            Clip clip = null;
            SpeechClipFailure reason = null;
            if (failure != null) {
                reason = SpeechClipException.failureOf(failure);
            } else if (pcm == null || pcm.length == 0) {
                reason = SpeechClipFailure.EMPTY_AUDIO;
            } else if (pcm.length % 2 != 0) {
                reason = SpeechClipFailure.INVALID_AUDIO;
            } else if (pcm.length > MAX_CLIP_BYTES) {
                reason = SpeechClipFailure.AUDIO_TOO_LONG;
            } else {
                clip = new Clip(pcm, clock.elapsedSince(started));
            }
            if (clip == null && attempt < MAX_ATTEMPTS && transientFailure(reason)) {
                attempt(key, result, started, attempt + 1);
                return;
            }
            settle(key, result, clip, reason);
            next();
        });
    }

    private void settle(Key key, CompletableFuture<Clip> result, Clip clip, SpeechClipFailure reason) {
        synchronized (lock) {
            if (clip != null) {
                cachedBytes += clip.pcm().length;
                evict(key);
            } else {
                clips.remove(key, result);
            }
        }
        if (clip != null) {
            result.complete(clip);
        } else {
            result.completeExceptionally(new SpeechClipException(reason));
        }
    }

    /** 캐시 상한을 넘으면 오래 안 쓴 만든 줄부터 버린다(만드는 중인 줄과 방금 만든 줄은 남긴다). */
    private void evict(Key keep) {
        Iterator<Map.Entry<Key, CompletableFuture<Clip>>> entries = clips.entrySet().iterator();
        while (cachedBytes > cacheMaxBytes && entries.hasNext()) {
            Map.Entry<Key, CompletableFuture<Clip>> entry = entries.next();
            Clip done = entry.getValue().getNow(null);
            if (done == null || entry.getKey().equals(keep)) {
                continue;
            }
            cachedBytes -= done.pcm().length;
            entries.remove();
        }
    }

    private void next() {
        Runnable task;
        synchronized (lock) {
            task = waiting.pollFirst();
            if (task == null) {
                running--;
            }
        }
        if (task != null) {
            task.run();
        }
    }

    private static CompletableFuture<Clip> failed(SpeechClipFailure failure) {
        return CompletableFuture.failedFuture(new SpeechClipException(failure));
    }
}
