package kr.mujoong.realtime.log;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 지연 측정 기록 (U1 3.5 Step 25 · OD-8 · PD-6 · NFR5.1 ~ 5.4 · R3-4 · R3-19 · J143).
 *
 * <p>턴 맥락(세션마다 하나)이 시각을 모아 턴이 닫힐 때 LATENCY_TURN 한 줄을 쓴다. 시각 키 8종(tSpeechEnd · tProviderFirstOut ·
 * tSegmentConfirmed · tGatePass · tFirstFrameSent · tDevicePlayStart · tDevicePlayStartRecv · tTranscriptFinal)과
 * tSpeechEndSource · clockOffsetMs · rttMs · providerResponseMs · quietWaitMs · relayInMs · relayOutMs를 남긴다. 닫는 때: 출력 묶음의
 * PLAYBACK_MARK(STARTED)가 왔을 때(정상), 게이트가 구간을 폐기했을 때(GATE_DISCARD), 출력이나 재생 시작 없이 다음 턴이 시작될 때
 * (NO_OUTPUT · NO_PLAYBACK_MARK), 세션이 끝날 때(SESSION_ENDED_EARLY). 기기 시계 값(tDevicePlayStart)은 원값 그대로 쓰고 환산하지 않는다.
 * 구간 길이(…Ms)는 단조 읽기 차이다. 프레임 단위 로그는 쓰지 않는다(NFR5.1).
 *
 * <p>SESSION_ITEMS_SERVED: 기록기 커밋 직후 tViewItemReady, 12 진행 조회가 그 행을 처음 실은 시각 tViewItemFirstServed를 행마다 한 줄로
 * 남긴다(이미 실린 행은 다시 쓰지 않는다). 전사 · 원음은 담지 않는다. 스레드: 턴 시각은 세션 실행기(첫 프레임만 연결 송신기),
 * tViewItemReady는 기록기 플러시, 실림은 진행 조회 핸들러가 부른다 — 세션 단위로 동기화한다.
 */
@Component
public class LatencyRecorder {

    /** tSpeechEnd 출처(우선순위 순, OD-8). */
    public enum SpeechEndSource {
        PROVIDER_EVENT,
        INPUT_TRANSCRIPT_FINAL,
        LAST_INPUT_FRAME
    }

    /** 미관측 사유(OD-8 unobservedReason, 앞선 것이 우선). */
    public enum Unobserved {
        NO_OUTPUT,
        GATE_DISCARD,
        NO_PLAYBACK_MARK,
        SESSION_ENDED_EARLY,
        CLOCK_OFFSET_MISSING
    }

    /** 12 응답에 실린 행. */
    public record ServedItem(int seq, Speaker speaker) {
    }

    /** 세션별 입력 쪽 값 공급자(입력 중계 링 p95 · 마지막 음성 프레임 시각). */
    public interface InputSide {

        /** 이 턴 입력 프레임 relayIn p95(ms)를 계산하고 비운다. 표본이 없으면 null. */
        Long drainRelayInP95Millis();

        /** 마지막 음성 활동 입력 프레임 수신 시각(LAST_INPUT_FRAME). 없으면 null. */
        Instant lastVoiceFrameAt();
    }

    private static final InputSide NO_INPUT = new InputSide() {
        @Override
        public Long drainRelayInP95Millis() {
            return null;
        }

        @Override
        public Instant lastVoiceFrameAt() {
            return null;
        }
    };

    private final EventLogger events;
    private final MujoongClock clock;
    private final int sampleRatePercent;
    private final Duration retainEnded;
    private final ConcurrentHashMap<UUID, SessionTimes> sessions = new ConcurrentHashMap<>();

    @Autowired
    public LatencyRecorder(EventLogger events, MujoongClock clock, RealtimeProperties properties) {
        this(events, clock, properties.observability().latencyLogSampleRate(), properties.session().tombstoneTtl());
    }

    /**
     * @param sampleRatePercent LATENCY_TURN 기록 비율(0 ~ 100, {@code mujoong.observability.latency-log-sample-rate})
     * @param retainEnded 끝난 세션의 행 시각을 남겨 두는 시간(끝난 뒤 12 조회의 실림 기록용)
     */
    public LatencyRecorder(EventLogger events, MujoongClock clock, int sampleRatePercent, Duration retainEnded) {
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (sampleRatePercent < 0 || sampleRatePercent > 100) {
            throw new IllegalArgumentException("sampleRatePercent는 0 ~ 100이다");
        }
        this.sampleRatePercent = sampleRatePercent;
        this.retainEnded = Objects.requireNonNull(retainEnded, "retainEnded");
    }

    /** 세션 시작(입력 쪽 값 공급자와 세션 traceId를 묶는다). */
    public void register(UUID sessionId, String traceId, InputSide inputSide) {
        sessions.put(Objects.requireNonNull(sessionId, "sessionId"),
                new SessionTimes(traceId, inputSide == null ? NO_INPUT : inputSide));
    }

    /** 아이 입력이 시작되거나 이어진다(입력 전사 조각 · 확정). 출력이 이미 나온 턴이면 그 턴을 닫고 새 턴을 연다. */
    public void inputActivity(UUID sessionId) {
        withSession(sessionId, s -> {
            if (s.turn == null || s.turn.hasOutput()) {
                if (s.turn != null) {
                    closeTurn(sessionId, s, s.turn.firstOut == null ? Unobserved.NO_OUTPUT : Unobserved.NO_PLAYBACK_MARK);
                }
                s.turn = new Turn(++s.turnSeq);
            }
        });
    }

    /** 말 끝 후보(우선순위가 높은 출처가 이긴다). */
    public void speechEnd(UUID sessionId, SpeechEndSource source, Instant at) {
        withSession(sessionId, s -> {
            if (s.turn == null) {
                s.turn = new Turn(++s.turnSeq);
            }
            s.turn.offerSpeechEnd(source, at);
        });
    }

    /** 아이 입력 전사 확정 처리 시각(커밋 전, NFR5.3 시작). 턴의 tTranscriptFinal과 행의 시각을 함께 둔다. */
    public void transcriptFinal(UUID sessionId, int seq, Instant at) {
        withSession(sessionId, s -> {
            s.items.computeIfAbsent(seq, key -> new ItemTimes(Speaker.CHILD)).transcriptFinal = at;
            if (s.turn == null) {
                s.turn = new Turn(++s.turnSeq);
            }
            s.turn.transcriptFinal = at;
            s.turn.offerSpeechEnd(SpeechEndSource.INPUT_TRANSCRIPT_FINAL, at);
        });
    }

    /** AI 행 번호가 정해졌다(행 시각 표에 화자를 남긴다). */
    public void aiItem(UUID sessionId, int seq) {
        withSession(sessionId, s -> s.items.computeIfAbsent(seq, key -> new ItemTimes(Speaker.AI)));
    }

    /** 응답의 첫 출력 조각(t1). 턴이 없으면(입력 없는 출력) 기록하지 않는다. */
    public void providerFirstOut(UUID sessionId, Instant at) {
        withSession(sessionId, s -> {
            if (s.turn != null && s.turn.firstOut == null) {
                s.turn.firstOut = at;
            }
        });
    }

    /**
     * 구간 확정(tSegmentConfirmed). providerResponseMs = 마지막 출력 − 첫 출력, quietWaitMs = 확정 − 마지막 출력(단조 읽기).
     */
    public void segmentConfirmed(UUID sessionId, Instant at, long confirmedNanos, long firstOutNanos, long lastOutNanos) {
        withSession(sessionId, s -> {
            if (s.turn == null) {
                return;
            }
            s.turn.segmentConfirmed = at;
            s.turn.providerResponseMs = nonNegativeMillis(lastOutNanos - firstOutNanos);
            s.turn.quietWaitMs = nonNegativeMillis(confirmedNanos - lastOutNanos);
        });
    }

    /** 송신 직전 재확인까지 통과(tGatePass). 이 턴의 출력 묶음 playbackId를 묶는다. */
    public void gatePassed(UUID sessionId, UUID playbackId, Instant at) {
        withSession(sessionId, s -> {
            if (s.turn != null) {
                s.turn.gatePass = at;
                s.turn.playbackId = playbackId;
            }
        });
    }

    /** 게이트가 구간을 폐기했다(턴을 GATE_DISCARD로 닫는다). */
    public void gateDiscarded(UUID sessionId) {
        withSession(sessionId, s -> {
            if (s.turn != null) {
                s.turn.discarded = true;
                closeTurn(sessionId, s, Unobserved.GATE_DISCARD);
            }
        });
    }

    /** 연결 송신기가 첫 0x02 프레임을 쓴 직후(tFirstFrameSent). */
    public void firstFrameSent(UUID sessionId, UUID playbackId, Instant at) {
        withSession(sessionId, s -> {
            if (s.turn != null && playbackId.equals(s.turn.playbackId) && s.turn.firstFrameSent == null) {
                s.turn.firstFrameSent = at;
            }
        });
    }

    /**
     * PLAYBACK_MARK(STARTED). 이 턴의 묶음이면 기기 시각 원값 · 수신 시각 · 연결 값으로 턴을 닫는다(정상).
     *
     * @param clockOffsetMs 연결의 기기 − 서버(없으면 null → CLOCK_OFFSET_MISSING)
     */
    public void playbackStarted(UUID sessionId, UUID playbackId, Instant deviceObservedAt, Instant serverRecvAt,
            Long clockOffsetMs, Long rttMs) {
        withSession(sessionId, s -> {
            if (s.turn == null || !playbackId.equals(s.turn.playbackId)) {
                return;
            }
            s.turn.devicePlayStart = deviceObservedAt;
            s.turn.devicePlayStartRecv = serverRecvAt;
            s.turn.clockOffsetMs = clockOffsetMs;
            s.turn.rttMs = rttMs;
            closeTurn(sessionId, s, clockOffsetMs == null ? Unobserved.CLOCK_OFFSET_MISSING : null);
        });
    }

    /** 세션이 끝났다: 열린 턴을 닫는다(SESSION_ENDED_EARLY 등). */
    public void sessionEnded(UUID sessionId) {
        withSession(sessionId, s -> {
            if (s.turn != null) {
                closeTurn(sessionId, s, s.turn.firstOut == null ? Unobserved.NO_OUTPUT : Unobserved.SESSION_ENDED_EARLY);
            }
            s.endedAt = clock.now();
        });
    }

    /** 기록기 커밋 직후(tViewItemReady, FINAL 행만). */
    public void itemReady(UUID sessionId, int seq, Speaker speaker, Instant at) {
        withSession(sessionId, s -> {
            ItemTimes times = s.items.computeIfAbsent(seq, key -> new ItemTimes(speaker));
            if (times.ready == null) {
                times.ready = at;
            }
        });
    }

    /** 12 응답에 행이 실렸다. 처음 실린 행마다 SESSION_ITEMS_SERVED 한 줄. */
    public void itemsServed(UUID sessionId, List<ServedItem> items, Instant servedAt) {
        Objects.requireNonNull(items, "items");
        withSession(sessionId, s -> {
            for (ServedItem item : items) {
                if (!s.served.add(item.seq())) {
                    continue;
                }
                ItemTimes times = s.items.get(item.seq());
                events.log(EventCode.SESSION_ITEMS_SERVED, EventLog.Component.SESSION_LIFECYCLE, sessionId, s.traceId,
                        entry -> {
                            entry.attr(AttrKey.SEQ, (long) item.seq()).attr(AttrKey.SPEAKER, item.speaker())
                                    .attr(AttrKey.T_VIEW_ITEM_FIRST_SERVED, servedAt);
                            if (times != null) {
                                entry.attr(AttrKey.T_TRANSCRIPT_FINAL, times.transcriptFinal)
                                        .attr(AttrKey.T_VIEW_ITEM_READY, times.ready);
                            }
                        });
            }
        });
    }

    /** 끝난 지 {@code retainEnded}가 지난 세션의 기록을 지운다(maintenance). 지운 세션 수. */
    public int purgeEnded(Instant now) {
        int removed = 0;
        for (Map.Entry<UUID, SessionTimes> entry : sessions.entrySet()) {
            SessionTimes times = entry.getValue();
            synchronized (times) {
                if (times.endedAt != null && !now.isBefore(times.endedAt.plus(retainEnded))
                        && sessions.remove(entry.getKey(), times)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 기록 중인 세션 수(시험 · 진단). */
    public int trackedSessions() {
        return sessions.size();
    }

    /** 시각 값 clockOffsetMs = sentAt − (수신 − rttMs ÷ 2), 오차 ± rttMs ÷ 2(OD-8 · R3-4). sentAt이 없으면 null. */
    public static Long clockOffsetMs(Instant deviceSentAt, Instant serverReceivedAt, Long rttMs) {
        if (deviceSentAt == null || serverReceivedAt == null) {
            return null;
        }
        long halfRtt = rttMs == null ? 0L : rttMs / 2;
        return deviceSentAt.toEpochMilli() - (serverReceivedAt.toEpochMilli() - halfRtt);
    }

    private void withSession(UUID sessionId, java.util.function.Consumer<SessionTimes> work) {
        SessionTimes times = sessions.get(Objects.requireNonNull(sessionId, "sessionId"));
        if (times == null) {
            return;
        }
        synchronized (times) {
            work.accept(times);
        }
    }

    private void closeTurn(UUID sessionId, SessionTimes s, Unobserved reason) {
        Turn turn = s.turn;
        s.turn = null;
        Long relayIn = s.inputSide.drainRelayInP95Millis();
        if (!sampled(turn.seq)) {
            return;
        }
        if (turn.speechEnd == null) {
            Instant lastFrame = s.inputSide.lastVoiceFrameAt();
            if (lastFrame != null) {
                turn.offerSpeechEnd(SpeechEndSource.LAST_INPUT_FRAME, lastFrame);
            }
        }
        Unobserved unobserved = turn.firstOut == null ? Unobserved.NO_OUTPUT : reason;
        events.log(EventCode.LATENCY_TURN, EventLog.Component.VOICE_RELAY, sessionId, s.traceId, entry -> {
            entry.attr(AttrKey.TURN_SEQ, (long) turn.seq)
                    .attr(AttrKey.T_SPEECH_END, turn.speechEnd)
                    .attr(AttrKey.T_SPEECH_END_SOURCE, turn.speechEndSource)
                    .attr(AttrKey.T_PROVIDER_FIRST_OUT, turn.firstOut)
                    .attr(AttrKey.T_SEGMENT_CONFIRMED, turn.segmentConfirmed)
                    .attr(AttrKey.T_GATE_PASS, turn.gatePass)
                    .attr(AttrKey.T_FIRST_FRAME_SENT, turn.firstFrameSent)
                    .attr(AttrKey.T_DEVICE_PLAY_START, turn.devicePlayStart)
                    .attr(AttrKey.T_DEVICE_PLAY_START_RECV, turn.devicePlayStartRecv)
                    .attr(AttrKey.T_TRANSCRIPT_FINAL, turn.transcriptFinal);
            putNumber(entry, AttrKey.CLOCK_OFFSET_MS, turn.clockOffsetMs);
            putNumber(entry, AttrKey.RTT_MS, turn.rttMs);
            putNumber(entry, AttrKey.RELAY_IN_MS, relayIn);
            if (turn.firstFrameSent != null && turn.segmentConfirmed != null) {
                entry.attr(AttrKey.RELAY_OUT_MS,
                        Math.max(0L, Duration.between(turn.segmentConfirmed, turn.firstFrameSent).toMillis()));
            }
            putNumber(entry, AttrKey.PROVIDER_RESPONSE_MS, turn.providerResponseMs);
            putNumber(entry, AttrKey.QUIET_WAIT_MS, turn.quietWaitMs);
            if (unobserved != null) {
                entry.attr(AttrKey.UNOBSERVED_REASON, unobserved);
            }
        });
    }

    private boolean sampled(int turnSeq) {
        if (sampleRatePercent >= 100) {
            return true;
        }
        return sampleRatePercent > 0 && ThreadLocalRandom.current().nextInt(100) < sampleRatePercent;
    }

    private static void putNumber(EventLog.Entry entry, AttrKey key, Long value) {
        if (value != null) {
            entry.attr(key, value.longValue());
        }
    }

    private static Long nonNegativeMillis(long nanos) {
        return Math.max(0L, Duration.ofNanos(nanos).toMillis());
    }

    /** 세션 하나의 시각들. */
    private static final class SessionTimes {
        private final String traceId;
        private final InputSide inputSide;
        private final Map<Integer, ItemTimes> items = new HashMap<>();
        private final Set<Integer> served = new HashSet<>();
        private Turn turn;
        private int turnSeq;
        private Instant endedAt;

        SessionTimes(String traceId, InputSide inputSide) {
            this.traceId = traceId;
            this.inputSide = inputSide;
        }
    }

    /** 행 하나의 시각들. */
    private static final class ItemTimes {
        private final Speaker speaker;
        private Instant transcriptFinal;
        private Instant ready;

        ItemTimes(Speaker speaker) {
            this.speaker = speaker;
        }

        @SuppressWarnings("unused")
        Speaker speaker() {
            return speaker;
        }
    }

    /** 턴 하나의 시각들. */
    private static final class Turn {
        private final int seq;
        private Instant speechEnd;
        private SpeechEndSource speechEndSource;
        private Instant transcriptFinal;
        private Instant firstOut;
        private Instant segmentConfirmed;
        private Long providerResponseMs;
        private Long quietWaitMs;
        private Instant gatePass;
        private UUID playbackId;
        private Instant firstFrameSent;
        private Instant devicePlayStart;
        private Instant devicePlayStartRecv;
        private Long clockOffsetMs;
        private Long rttMs;
        private boolean discarded;

        Turn(int seq) {
            this.seq = seq;
        }

        boolean hasOutput() {
            return firstOut != null || discarded;
        }

        void offerSpeechEnd(SpeechEndSource source, Instant at) {
            if (at == null) {
                return;
            }
            if (speechEndSource == null || source.ordinal() < speechEndSource.ordinal()) {
                speechEnd = at;
                speechEndSource = source;
            }
        }
    }

    /** 테스트 · 진단용으로 Supplier를 InputSide로 감싼다. */
    public static InputSide inputSide(Supplier<Long> relayInP95, Supplier<Instant> lastVoiceFrame) {
        return new InputSide() {
            @Override
            public Long drainRelayInP95Millis() {
                return relayInP95.get();
            }

            @Override
            public Instant lastVoiceFrameAt() {
                return lastVoiceFrame.get();
            }
        };
    }
}
