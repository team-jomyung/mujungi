package kr.mujoong.realtime.practiceflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.guardrail.GuardrailVerdict;
import kr.mujoong.realtime.judgment.JudgeOutcome;
import kr.mujoong.realtime.judgment.JudgeRequest;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.practiceflow.FlowState.Phase;
import kr.mujoong.realtime.practiceflow.FlowState.Sub;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.GuardrailBlockedEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.PlaybackFact;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;

/**
 * 실음성 연습 대화 하나에서 모은 사실({@link PracticeVoiceDialogueLiveTest}). 실행기 · 공급자 리더 · 가상 기기 · 판정 · 아이 쪽 스레드가 함께 쓰므로
 * 목록은 이 객체로 잠그고, 아이 쪽이 읽는 값은 volatile이다. 다른 장치를 부르는 동안에는 잠그지 않는다. 시각은 대화 시작부터의 ms다.
 *
 * <p>이 객체는 관찰만 한다: 실행부 · 흐름에 값을 돌려주거나 동작을 바꾸지 않는다. 담는 글은 합성 대화(하니스 출력)라 들린 말 원문을 담는다.
 */
final class PracticeVoiceFacts {

    /** 무응답 시계 이름(PracticeConversation.TIMER_NO_RESPONSE). */
    static final String TIMER_NO_RESPONSE = PracticeConversation.TIMER_NO_RESPONSE;
    /** 4회차 R1 안전 대기 시계 이름(PracticeConversation.TIMER_CHILD_SPEECH). */
    static final String TIMER_CHILD_SPEECH = PracticeConversation.TIMER_CHILD_SPEECH;
    /** 슬롯 안전 시계 이름 앞머리(PracticeConversation.TIMER_SLOT_GUARD). */
    static final String TIMER_SLOT_GUARD = PracticeConversation.TIMER_SLOT_GUARD;

    // 판정 결과 · 읽기 결과 코드(출력 값)
    static final String FIRST_MATCH = "FIRST_MATCH";
    static final String RETRY_MATCH = "RETRY_MATCH";
    static final String RECOVERED = "RECOVERED";
    static final String FAILED = "FAILED";
    static final String INCOMPLETE = "INCOMPLETE";

    /** 흐름 상태 사본(실행기 스레드에서 찍는다). */
    record Flow(Phase phase, Sub sub, SceneKind sceneKind, boolean judgmentOpen, int goalIndex, int retryCount, int reexplainCount) {

        static Flow of(FlowState state) {
            return state == null ? null : new Flow(state.phase(), state.sub(), state.sceneKind(), state.judgmentOpen(), state.goalIndex(),
                    state.retryCount(), state.reexplainCount());
        }

        String label() {
            return phase.name() + (sub == Sub.NONE ? "" : "/" + sub.name()) + (sceneKind == SceneKind.VARIANT ? " · 변형" : "");
        }

        boolean sameStep(Flow other) {
            return other != null && phase == other.phase && sub == other.sub && sceneKind == other.sceneKind;
        }
    }

    /** 연습 흐름이 낸 말 요청 하나(승인 원문 읽기 · 생성형). */
    static final class Attempt {
        final int id;
        final UUID correlationId;
        final String kind;
        final String source;
        final String text;
        final String role;
        final Flow flow;
        final long atMs;
        /** 실행부가 요청을 받았는가(요청을 넘기기 전에 시도를 세우므로 넘긴 뒤 정해진다). */
        boolean accepted;
        Reading reading;
        int indexInReading;
        boolean recovery;
        Long requestSentAtMs;
        Long instructionAtMs;
        String instructionType;
        Long firstOutputAtMs;
        Long startedAtMs;
        Long endedAtMs;
        String end;
        /** 재생 시각으로 잇지 못한 응답 묶음 확정 행의 글(단위별 검사 경로). */
        String rowText;
        final List<String> discards = new ArrayList<>();
        final List<String> stages = new ArrayList<>();
        final List<UUID> playbacks = new ArrayList<>();

        Attempt(int id, UUID correlationId, String kind, String source, String text, String role, Flow flow, long atMs, boolean accepted) {
            this.id = id;
            this.correlationId = correlationId;
            this.kind = kind;
            this.source = source;
            this.text = text;
            this.role = role;
            this.flow = flow;
            this.atMs = atMs;
            this.accepted = accepted;
        }

        boolean heard() {
            return startedAtMs != null;
        }

        boolean open() {
            return end == null;
        }

        boolean approved() {
            return "APPROVED".equals(kind);
        }
    }

    /** 승인 문장 하나를 들려주려는 시도 묶음(첫 시도 · 같은 글 재시도 · '뜻 전하기' 복구). */
    static final class Reading {
        final int id;
        final String text;
        final String source;
        final long atMs;
        final List<Attempt> attempts = new ArrayList<>();

        Reading(int id, String text, String source, long atMs) {
            this.id = id;
            this.text = text;
            this.source = source;
            this.atMs = atMs;
        }

        Attempt last() {
            return attempts.getLast();
        }

        /** 읽기 결과: 첫 시도 일치 · 재시도 일치 · 뜻 전하기 복구 · 실패 · 미완(취소 · 끝남). */
        String outcome() {
            if (attempts.getFirst().heard()) {
                return FIRST_MATCH;
            }
            for (int i = 1; i < attempts.size(); i++) {
                Attempt attempt = attempts.get(i);
                if (attempt.heard()) {
                    return attempt.recovery ? RECOVERED : RETRY_MATCH;
                }
            }
            boolean anyOpen = attempts.stream().anyMatch(Attempt::open);
            boolean slotFailure = attempts.stream().anyMatch(a -> "FAILED".equals(a.end) || "BLOCKED".equals(a.end)
                    || "TIMEOUT".equals(a.end) || "NOT_SENT".equals(a.end));
            if (!anyOpen && (slotFailure || attempts.size() >= 2)) {
                return FAILED;
            }
            return INCOMPLETE;
        }
    }

    /** 기기 재생 하나(GPT-Live 묶음 · 기기 승인 음성). */
    static final class Play {
        final UUID playbackId;
        final String kind;
        final String code;
        String text;
        final long queuedAtMs;
        long durationMs;
        long bytes;
        Long startedAtMs;
        Long endedAtMs;
        String end;
        Attempt attempt;

        Play(UUID playbackId, String kind, String code, String text, long queuedAtMs) {
            this.playbackId = playbackId;
            this.kind = kind;
            this.code = code;
            this.text = text;
            this.queuedAtMs = queuedAtMs;
        }

        boolean gptLive() {
            return "GPT_LIVE".equals(kind);
        }
    }

    /** 아이 차례 하나(말함 · 말하지 않음). */
    static final class ChildTurn {
        final int index;
        final long cueAtMs;
        final String act;
        final String key;
        final String text;
        final String rule;
        final Flow flow;
        final String status;
        String ttsOutcome;
        long ttsLatencyMs = -1;
        boolean ttsCached;
        long audioMs;
        Long speechStartMs;
        Long speechEndMs;
        Long sendEndMs;
        Integer seq;
        String transcript;
        Boolean uncertain;
        Long transcriptStartMs;
        Long transcriptConfirmedMs;
        Long flowReceivedMs;

        ChildTurn(int index, long cueAtMs, String act, String key, String text, String rule, Flow flow, String status) {
            this.index = index;
            this.cueAtMs = cueAtMs;
            this.act = act;
            this.key = key;
            this.text = text;
            this.rule = rule;
            this.flow = flow;
            this.status = status;
        }

        boolean spoke() {
            return speechStartMs != null;
        }
    }

    /** 실행기가 기록기에 넣은 확정 전사 행 하나(아이 · AI). */
    record Row(int seq, TranscriptEntry.Speaker speaker, String text, boolean approved, Instant startedAt, Instant endedAt, long atMs,
            boolean reviewNeeded) {
    }

    /** 흐름이 받은 아이 확정 전사 하나. */
    record Heard(int seq, long atMs, String text, boolean uncertain, boolean devicePlaying) {
    }

    /** 판정 한 번(실행부가 운영 Decisions 경로로 부른 것). */
    static final class JudgeCall {
        final int index;
        final String point;
        final String utterance;
        final boolean uncertain;
        final long atMs;
        final boolean capped;
        String kind;
        String first;
        Double firstProbability;
        String second;
        Double secondProbability;
        String abstainReason;
        String model;
        long latencyMs = -1;
        Long doneAtMs;
        boolean cancelledAtDeadline;
        Integer childTurn;
        String flowCorrelation;

        JudgeCall(int index, String point, String utterance, boolean uncertain, long atMs, boolean capped) {
            this.index = index;
            this.point = point;
            this.utterance = utterance;
            this.uncertain = uncertain;
            this.atMs = atMs;
            this.capped = capped;
        }
    }

    /** 흐름이 판정을 요청한 사실. */
    record JudgmentRequest(long atMs, String point, long transcriptSeq, UUID correlationId, boolean accepted, Flow flow) {
    }

    /** 흐름이 받은 판정 사건(결과 · 보류). */
    record JudgmentEvent(long atMs, String kind, UUID correlationId, String point, String firstOption, String secondOption,
            String abstainReason) {
    }

    /** 무응답 · 안전 대기 시계 사건과 아이 말 시작(4회차 R1). */
    record ClockEvent(long atMs, String kind, String name, Long delayMs, String flow) {
    }

    /** 실행부가 버린 출력 구간 하나(OUTPUT_DISCARDED 사건 · 그 구간의 AI 확정 행 글). */
    static final class Discard {
        final long atMs;
        final Integer seq;
        final String reason;
        final String detail;
        final String phase;
        final String sub;
        final Boolean generatedSpeech;
        Integer attemptId;
        String text;

        Discard(long atMs, Integer seq, String reason, String detail, String phase, String sub, Boolean generatedSpeech) {
            this.atMs = atMs;
            this.seq = seq;
            this.reason = reason;
            this.detail = detail;
            this.phase = phase;
            this.sub = sub;
            this.generatedSpeech = generatedSpeech;
        }
    }

    /** 제브 검사 한 번(운영 게이트가 생성형 연습 발화마다 부른 것). */
    static final class GuardCheck {
        final int index;
        final long atMs;
        final String mode;
        final String candidate;
        String decision;
        String failure;
        String reasonCode;
        List<String> categories = List.of();
        long latencyMs = -1;
        Long doneAtMs;

        GuardCheck(int index, long atMs, String mode, String candidate) {
            this.index = index;
            this.atMs = atMs;
            this.mode = mode;
            this.candidate = candidate;
        }
    }

    /** 장면 기록 한 줄(운영 흐름이 기록 자리에 남긴 값). */
    static final class SceneRow {
        final UUID id;
        final String sceneKind;
        final long startedAtMs;
        Long endedAtMs;
        String timing;
        String skipReason;
        int retryCount;
        int reexplainCount;
        int outcomeCalls;
        final List<String> facts = new ArrayList<>();

        SceneRow(UUID id, String sceneKind, long startedAtMs) {
            this.id = id;
            this.sceneKind = sceneKind;
            this.startedAtMs = startedAtMs;
        }
    }

    final long startNanos = System.nanoTime();
    /** 대화 시작의 벽시계 시각(기록기 칸의 Instant를 대화 시작부터의 ms로 바꿀 때 쓴다 — 실행부 시계도 시스템 시계다). */
    final Instant startInstant = Instant.now();

    // ---- 아이 쪽이 읽는 값 ----
    volatile Flow flow;
    volatile boolean active;
    volatile boolean noResponseArmed;
    volatile int noResponseArms;
    volatile String capHit;
    volatile String lastLineSource;
    volatile int joinPromptEnds;
    volatile int stopConfirmEnds;
    volatile int childUtterances;
    volatile boolean terminated;
    final AtomicInteger judgmentsInFlight = new AtomicInteger();

    // ---- 잠금 아래 목록 ----
    private final List<Attempt> attempts = new ArrayList<>();
    private final Map<UUID, Attempt> attemptsByCorrelation = new HashMap<>();
    private final List<Reading> readings = new ArrayList<>();
    private Reading currentReading;
    private final List<Play> plays = new ArrayList<>();
    private final Map<UUID, Play> playsById = new HashMap<>();
    private final Map<Instant, Play> startedAt = new HashMap<>();
    private final List<ChildTurn> childTurns = new ArrayList<>();
    private final List<Heard> heard = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Map<Integer, Instant> childFirstDelta = new HashMap<>();
    private final List<JudgeCall> judgeCalls = new ArrayList<>();
    private final List<JudgmentRequest> judgmentRequests = new ArrayList<>();
    private final List<JudgmentEvent> judgmentEvents = new ArrayList<>();
    private final List<ClockEvent> clockEvents = new ArrayList<>();
    private final List<Discard> discards = new ArrayList<>();
    private final List<Map<String, Object>> appErrors = new ArrayList<>();
    private final Map<String, Integer> eventCounts = new LinkedHashMap<>();
    private final List<Map<String, Object>> sessionEvents = new ArrayList<>();
    private final List<Map<String, Object>> flowTrace = new ArrayList<>();
    private final List<Map<String, Object>> signals = new ArrayList<>();
    private final List<String> flowFailures = new ArrayList<>();
    private final List<Map<String, Object>> notices = new ArrayList<>();
    private final List<GuardCheck> guardChecks = new ArrayList<>();
    private final Map<UUID, SceneRow> scenes = new LinkedHashMap<>();
    private final List<Map<String, Object>> helps = new ArrayList<>();
    private final List<Map<String, Object>> otherInstructions = new ArrayList<>();
    private final List<String> deviceViews = new ArrayList<>();
    private final Map<String, Integer> outputCalls = new LinkedHashMap<>();
    private final List<Map<String, Object>> providerErrors = new ArrayList<>();
    private final Map<String, Integer> instructionTypes = new LinkedHashMap<>();
    private Instant lastMarkAt = Instant.EPOCH;
    private Flow lastTraced;
    private Long activeAtMs;
    private Long providerOpenedAtMs;
    private Long providerStartedAtMs;
    private Long providerClosedAtMs;
    private double usageSeconds;
    private long outputAudioBytes;
    private long outputTranscriptDeltas;
    private long inputTranscriptDeltas;
    private long framesOffered;
    private long voicedFramesOffered;
    private Object practiceRecord;
    private Map<String, Object> settled;
    private Long noResponseArmedAtMs;
    private boolean childSpokeWhileArmed;
    private int noResponseMalfunctions;
    private final List<Map<String, Object>> malfunctionDetails = new ArrayList<>();

    // ------------------------------------------------------------------ 시각

    long ms() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    long msOf(long nanos) {
        return (nanos - startNanos) / 1_000_000L;
    }

    // ------------------------------------------------------------------ 흐름

    /** 확장점 시점마다 흐름 상태 사본을 둔다(단계가 바뀌면 흐름 자취에 남긴다). */
    synchronized void flow(Flow snapshot) {
        if (snapshot == null) {
            return;
        }
        flow = snapshot;
        if (!snapshot.sameStep(lastTraced)) {
            lastTraced = snapshot;
            flowTrace.add(PracticeVoiceReport.ordered("atMs", ms(), "step", snapshot.label()));
        }
    }

    synchronized void active() {
        active = true;
        activeAtMs = ms();
    }

    synchronized void signal(String where, String signal) {
        signals.add(PracticeVoiceReport.ordered("atMs", ms(), "where", where, "signal", signal));
    }

    synchronized void flowFailure(String code) {
        flowFailures.add(code);
    }

    synchronized void notice(String text) {
        notices.add(PracticeVoiceReport.ordered("atMs", ms(), "text", text));
    }

    /** 흐름이 받은 아이 확정 전사(실행부가 묶어 확정한 글). */
    synchronized void heard(int seq, String text, boolean uncertain, boolean devicePlaying) {
        heard.add(new Heard(seq, ms(), text, uncertain, devicePlaying));
        childUtterances++;
    }

    /** 4회차 R1: 실행부가 아이 말이 시작됐다고 알렸다. */
    synchronized void childSpeechStarted() {
        clockEvents.add(new ClockEvent(ms(), "CHILD_SPEECH_STARTED", null, null, flowLabel()));
    }

    synchronized void settled(Map<String, Object> end) {
        settled = end;
    }

    synchronized void practiceRecord(Object record) {
        practiceRecord = record;
    }

    /** 확장점 사건(재생 사실 · 비동기 결과 · 판정 · 시계 · 제브 막힘)을 시도 · 시계 사실로 옮긴다. */
    synchronized void extensionEvent(ExtensionEvent event) {
        switch (event.kind()) {
            case PLAYBACK_FACT -> {
                if (event.value() instanceof PlaybackFact fact) {
                    UUID correlation = fact.speech() != null ? fact.speech().correlationId() : event.correlationId();
                    Attempt attempt = correlation == null ? null : attemptsByCorrelation.get(correlation);
                    if (attempt != null) {
                        if (fact.started()) {
                            if (attempt.startedAtMs == null) {
                                attempt.startedAtMs = ms();
                                lastLineSource = attempt.source;
                            }
                        } else if (attempt.end == null) {
                            attempt.end = fact.played() ? "PLAYED" : fact.interrupted() ? "INTERRUPTED" : "FAILED";
                            attempt.endedAtMs = ms();
                        }
                    }
                }
            }
            case ASYNC_RESULT -> {
                Attempt attempt = event.correlationId() == null ? null : attemptsByCorrelation.get(event.correlationId());
                if (attempt == null && event.value() instanceof PracticeSpeech speech) {
                    attempt = attemptsByCorrelation.get(speech.correlationId());
                }
                if (attempt != null) {
                    switch (event.name()) {
                        case "approved-text-mismatch" -> endAttempt(attempt, "MISMATCH");
                        case "practice-audio-incomplete" -> endAttempt(attempt, attempt.heard() ? "PLAYED" : "INCOMPLETE");
                        case "practice-request-sent" -> {
                            if (attempt.requestSentAtMs == null) {
                                attempt.requestSentAtMs = ms();
                            }
                        }
                        default -> {
                            // 그 밖의 비동기 결과(다음 목표 대본)는 시도와 무관하다
                        }
                    }
                }
            }
            case GUARDRAIL_BLOCKED -> {
                if (event.value() instanceof GuardrailBlockedEvent blocked) {
                    Attempt attempt = attemptsByCorrelation.get(blocked.correlationId());
                    if (attempt != null) {
                        attempt.discards.add("GUARDRAIL_BLOCKED/" + blocked.reasonCode());
                        endAttempt(attempt, "BLOCKED");
                    }
                }
            }
            case JUDGMENT_RESULT, JUDGMENT_ABSTAINED -> {
                Map<?, ?> value = event.value() instanceof Map<?, ?> map ? map : Map.of();
                judgmentEvents.add(new JudgmentEvent(ms(), event.kind().name(), event.correlationId(), text(value.get("point")),
                        text(value.get("firstOption")), text(value.get("secondOption")), text(value.get("abstainReason"))));
            }
            case TIMER_FIRED -> timerFired(event.name());
            case REST_REQUESTED -> clockEvents.add(new ClockEvent(ms(), "REST_REQUESTED", event.name(), null, flowLabel()));
            default -> {
                // PLAYBACK_DONE · HELD_OUTPUT_TRANSCRIPT는 이 흐름에서 쓰지 않는다
            }
        }
    }

    private void endAttempt(Attempt attempt, String end) {
        if (attempt.end == null) {
            attempt.end = end;
            attempt.endedAtMs = ms();
        }
    }

    private void timerFired(String name) {
        if (TIMER_NO_RESPONSE.equals(name)) {
            noResponseArmed = false;
            Long armedAt = noResponseArmedAtMs;
            clockEvents.add(new ClockEvent(ms(), "NO_RESPONSE_FIRED", name, armedAt == null ? null : ms() - armedAt, flowLabel()));
            if (childSpokeWhileArmed) {
                // 시계가 걸린 뒤 아이가 실제로 말했는데 무응답으로 울렸다(4회차 R1이 막아야 하는 오작동)
                noResponseMalfunctions++;
                malfunctionDetails.add(PracticeVoiceReport.ordered("atMs", ms(), "flow", flowLabel(), "armedAtMs", armedAt));
            }
            childSpokeWhileArmed = false;
        } else if (TIMER_CHILD_SPEECH.equals(name)) {
            clockEvents.add(new ClockEvent(ms(), "SAFETY_WAIT_FIRED", name, null, flowLabel()));
        } else if (name != null && name.startsWith(TIMER_SLOT_GUARD)) {
            clockEvents.add(new ClockEvent(ms(), "SLOT_GUARD_FIRED", "slot-guard", null, flowLabel()));
            for (Attempt attempt : attempts) {
                if (attempt.open() && attempt.correlationId != null && name.contains(attempt.correlationId.toString())) {
                    attempt.discards.add("SLOT_GUARD");
                    endAttempt(attempt, "TIMEOUT");
                }
            }
        }
    }

    // ------------------------------------------------------------------ 흐름의 출력 능력 호출

    /**
     * 승인 원문 읽기 요청(같은 글 재시도는 같은 읽기 묶음에 붙는다). 실행부에 넘기기 <b>전에</b> 부른다: 실행부가 그 자리에서 공급자 지시를 보내므로,
     * 먼저 열어 둬야 그 지시 · 첫 출력이 이 시도에 붙는다. 넘긴 결과는 {@link #sendResult}로 적는다.
     */
    synchronized Attempt speakRequested(PracticeSpeech speech, Flow at, String source) {
        Attempt attempt = new Attempt(attempts.size() + 1, speech.correlationId(), "APPROVED", source, speech.text(), speech.partnerRole(),
                at, ms(), true);
        Reading reading = currentReading;
        boolean retry = reading != null && reading.text.equals(speech.text()) && !reading.last().open()
                && ("MISMATCH".equals(reading.last().end) || "INCOMPLETE".equals(reading.last().end));
        if (!retry) {
            reading = new Reading(readings.size() + 1, speech.text(), source, attempt.atMs);
            readings.add(reading);
            currentReading = reading;
        }
        attempt.reading = reading;
        attempt.indexInReading = reading.attempts.size();
        reading.attempts.add(attempt);
        register(attempt);
        return attempt;
    }

    /** 실행부가 말 요청을 받았는지(받지 않았으면 그 자리에서 '보내지 못함'으로 닫는다). */
    synchronized void sendResult(Attempt attempt, boolean accepted) {
        attempt.accepted = accepted;
        if (!accepted && attempt.end == null) {
            attempt.end = "NOT_SENT";
            attempt.endedAtMs = ms();
        }
    }

    /** 생성형 요청(넘기기 전에 부른다). 지금 읽기 묶음의 '뜻 전하기' 복구 지시면 그 묶음에 붙인다. */
    synchronized Attempt generatedRequested(UUID correlationId, String role, String instruction, Flow at, String source) {
        Attempt attempt = new Attempt(attempts.size() + 1, correlationId, "GENERATED", source, instruction, role, at, ms(), true);
        Reading reading = currentReading;
        if (reading != null && !reading.last().open() && !reading.last().heard()) {
            String expected = PracticeInstructions.recoverApprovedNarration(reading.last().text);
            if (expected != null && expected.equals(instruction)) {
                attempt.recovery = true;
                attempt.reading = reading;
                attempt.indexInReading = reading.attempts.size();
                reading.attempts.add(attempt);
            }
        }
        register(attempt);
        return attempt;
    }

    /** 지금 읽기 묶음의 마지막 시도 글('뜻 전하기' 출처 판단용, 없으면 null). */
    synchronized String lastApprovedText() {
        return currentReading == null ? null : currentReading.last().text;
    }

    private void register(Attempt attempt) {
        attempts.add(attempt);
        if (attempt.correlationId != null) {
            attemptsByCorrelation.put(attempt.correlationId, attempt);
        }
    }

    synchronized void speechCancelled(UUID correlationId) {
        Attempt attempt = correlationId == null ? null : attemptsByCorrelation.get(correlationId);
        if (attempt != null) {
            endAttempt(attempt, attempt.heard() ? "PLAYED_THEN_CANCELLED" : "CANCELLED");
        }
    }

    synchronized void outputCall(String name) {
        outputCalls.merge(name, 1, Integer::sum);
    }

    /** 흐름이 건 시계(무응답 · 안전 대기만 사건으로 남긴다). */
    synchronized void timerScheduled(String name, Duration delay, boolean accepted) {
        if (!accepted) {
            return;
        }
        if (TIMER_NO_RESPONSE.equals(name)) {
            noResponseArmed = true;
            noResponseArms++;
            noResponseArmedAtMs = ms();
            childSpokeWhileArmed = false;
            clockEvents.add(new ClockEvent(ms(), "NO_RESPONSE_ARMED", name, delay.toMillis(), flowLabel()));
        } else if (TIMER_CHILD_SPEECH.equals(name)) {
            clockEvents.add(new ClockEvent(ms(), "SAFETY_WAIT_ARMED", name, delay.toMillis(), flowLabel()));
        }
    }

    synchronized void timerCancelled(String name, boolean cancelled) {
        if (TIMER_NO_RESPONSE.equals(name)) {
            if (noResponseArmed) {
                clockEvents.add(new ClockEvent(ms(), "NO_RESPONSE_CANCELLED", name, null, flowLabel()));
            }
            noResponseArmed = false;
            childSpokeWhileArmed = false;
        } else if (TIMER_CHILD_SPEECH.equals(name) && cancelled) {
            clockEvents.add(new ClockEvent(ms(), "SAFETY_WAIT_CANCELLED", name, null, flowLabel()));
        }
    }

    synchronized void judgmentRequested(JudgmentPoint point, long transcriptSeq, UUID correlationId, boolean accepted) {
        judgmentRequests.add(new JudgmentRequest(ms(), point == null ? null : point.name(), transcriptSeq, correlationId, accepted, flow));
    }

    // ------------------------------------------------------------------ 기록기 · 사건 로그

    /** 기록기 입구의 전사 칸. 확정 행만 남기고, 아이 조각은 첫 조각 시각만 둔다. */
    synchronized void transcript(TranscriptEntry entry) {
        if (entry.assemblyStatus() == TranscriptEntry.AssemblyStatus.PARTIAL) {
            if (entry.speaker() == TranscriptEntry.Speaker.CHILD && entry.startedAt() != null) {
                childFirstDelta.putIfAbsent(entry.sequenceNo(), entry.startedAt());
            }
            return;
        }
        Row row = new Row(entry.sequenceNo(), entry.speaker(), entry.text(), entry.approvedForDisplay(), entry.startedAt(), entry.endedAt(),
                ms(), entry.reviewNeeded());
        rows.add(row);
        if (row.speaker() == TranscriptEntry.Speaker.AI) {
            if (row.approved() && row.startedAt() != null) {
                // 승인 음성의 확정 행은 기기 STARTED를 받은 시각(서버 수신 시각)이 시작 · 끝이다 — 그 시각으로 재생과 잇는다
                Play play = startedAt.get(row.startedAt());
                if (play != null && play.gptLive()) {
                    play.text = play.text == null || play.text.isEmpty() ? row.text() : play.text + row.text();
                } else if (play == null) {
                    // 응답 묶음 행(단위별 검사 경로)은 재생 시각이 아니라 응답 시작 · 끝 시각으로 닫힌다: 아직 글이 없는 가장 최근의 들린 말 요청에 붙인다
                    Attempt heardWithoutText = latestHeardWithoutText();
                    if (heardWithoutText != null) {
                        heardWithoutText.rowText = row.text();
                    }
                }
            } else if (!row.approved()) {
                for (Discard discard : discards) {
                    if (discard.text == null && discard.seq != null && discard.seq == row.seq()) {
                        discard.text = row.text();
                    }
                }
            }
        }
    }

    /** 재생이 시작됐는데 그 재생들에 이어진 글이 아직 없는 가장 최근 말 요청(없으면 null). */
    private Attempt latestHeardWithoutText() {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            Attempt attempt = attempts.get(i);
            if (attempt.startedAtMs == null || attempt.rowText != null) {
                continue;
            }
            boolean hasText = false;
            for (UUID playbackId : attempt.playbacks) {
                Play play = playsById.get(playbackId);
                if (play != null && play.text != null && !play.text.isEmpty()) {
                    hasText = true;
                }
            }
            if (!hasText) {
                return attempt;
            }
        }
        return null;
    }

    synchronized void sessionEvent(SessionEvent event) {
        sessionEvents.add(PracticeVoiceReport.ordered("atMs", ms(), "type", event.getEventType(), "payload", event.getPayloadJson()));
    }

    /** 사건 로그 한 줄(JSON 객체). 버린 구간 · 연습 음성 단계 · 앱 오류를 지금 시도에 붙인다. */
    synchronized void logLine(Map<String, Object> event) {
        String code = String.valueOf(event.get("event"));
        eventCounts.merge(code, 1, Integer::sum);
        Map<?, ?> attrs = event.get("attrs") instanceof Map<?, ?> map ? map : Map.of();
        switch (code) {
            case "OUTPUT_DISCARDED" -> {
                Integer seq = attrs.get("seq") instanceof Number number ? number.intValue() : null;
                Boolean generated = attrs.get("generatedSpeech") instanceof Boolean flag ? flag : null;
                Discard discard = new Discard(ms(), seq, text(attrs.get("discardReason")), text(attrs.get("discardDetail")),
                        text(attrs.get("practicePhase")), text(attrs.get("practiceSub")), generated);
                Attempt open = generated == null ? null : openAttempt();
                if (open != null) {
                    discard.attemptId = open.id;
                    open.discards.add(discard.reason + "/" + discard.detail);
                }
                for (Row row : rows) {
                    if (seq != null && row.seq() == seq && row.speaker() == TranscriptEntry.Speaker.AI && !row.approved()) {
                        discard.text = row.text();
                    }
                }
                discards.add(discard);
            }
            case "PRACTICE_AUDIO_STAGE" -> {
                Attempt open = openAttempt();
                String stage = text(attrs.get("audioStage"));
                if (open != null && stage != null) {
                    open.stages.add(PracticeVoiceReport.formatMs(ms()) + " " + stage);
                    if ("REQUEST_SENT".equals(stage) && open.requestSentAtMs == null) {
                        // 실행부가 앞 말이 끝나기를 기다린 뒤 지시를 보냈다(그 시각부터 첫 출력을 잰다)
                        open.requestSentAtMs = ms();
                    }
                    if ("FIRST_OUTPUT_TIMEOUT".equals(stage) || "SEND_TIMEOUT".equals(stage) || "DRAIN_TIMEOUT".equals(stage)
                            || "COLLECTION_TIMEOUT".equals(stage) || "PLAYBACK_TIMEOUT".equals(stage)) {
                        open.discards.add("STAGE/" + stage);
                    }
                }
            }
            case "APP_ERROR" -> appErrors.add(PracticeVoiceReport.ordered("atMs", ms(), "errorKind", text(attrs.get("errorKind")), "repeat",
                    attrs.get("repeat"), "level", event.get("level"), "flow", flowLabel()));
            default -> {
                // 그 밖의 사건은 개수만 센다
            }
        }
    }

    /** 가장 최근의 아직 끝나지 않은 말 요청(없으면 null). */
    private Attempt openAttempt() {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            Attempt attempt = attempts.get(i);
            if (attempt.open()) {
                return attempt;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 공급자

    synchronized void providerOpened() {
        if (providerOpenedAtMs == null) {
            providerOpenedAtMs = ms();
        }
    }

    synchronized void providerClosed() {
        if (providerClosedAtMs == null) {
            providerClosedAtMs = ms();
        }
    }

    /** 공급자에 보낸 지시 하나(연습 말 요청이 열려 있으면 그 시도의 지시로 본다). */
    synchronized void instructionSent(ProviderRequestFactory.InstructionsAppend request) {
        instructionTypes.merge(request.type(), 1, Integer::sum);
        Attempt open = openAttempt();
        boolean approvedReading = request.content() != null && request.content().contains("<approved_speech>");
        boolean commentary = ProviderRequestFactory.TYPE_COMMENTARY_APPEND.equals(request.type());
        if (open != null && ((open.approved() && approvedReading) || (!open.approved() && commentary))) {
            if (open.instructionAtMs == null) {
                open.instructionAtMs = ms();
                open.instructionType = request.type();
            }
            return;
        }
        otherInstructions.add(PracticeVoiceReport.ordered("atMs", ms(), "type", request.type(), "approvedReading", approvedReading,
                "flow", flowLabel()));
    }

    synchronized void providerEvent(ProviderEvent event) {
        switch (event) {
            case ProviderEvent.Started ignored -> {
                if (providerStartedAtMs == null) {
                    providerStartedAtMs = ms();
                }
            }
            case ProviderEvent.OutputTranscriptDelta delta -> {
                outputTranscriptDeltas++;
                markFirstOutput();
            }
            case ProviderEvent.OutputAudioDelta audio -> {
                int bytes = audio.audio().length;
                outputAudioBytes += bytes;
                if (bytes > 0) {
                    markFirstOutput();
                }
            }
            case ProviderEvent.UsageUpdated usage -> usageSeconds = Math.max(usageSeconds, usage.seconds());
            case ProviderEvent.InputTranscriptDelta delta -> inputTranscriptDeltas++;
            case ProviderEvent.Error error -> providerErrors.add(PracticeVoiceReport.ordered("atMs", ms(), "kind", error.diagnosticKind()));
            case ProviderEvent.Closed ignored -> providerClosed();
            default -> {
                // 확인 사건 · 입력 전사 완료 · 무음 조각은 세지 않는다
            }
        }
    }

    private void markFirstOutput() {
        Attempt open = openAttempt();
        if (open != null && open.instructionAtMs != null && open.firstOutputAtMs == null) {
            open.firstOutputAtMs = ms();
        }
    }

    synchronized double usageSeconds() {
        return usageSeconds;
    }

    /** 공급자 연결이 열려 있던 초(아직 열려 있으면 지금까지). */
    synchronized double connectedSeconds() {
        if (providerOpenedAtMs == null) {
            return 0;
        }
        long end = providerClosedAtMs != null ? providerClosedAtMs : ms();
        return Math.max(0, end - providerOpenedAtMs) / 1000.0;
    }

    synchronized boolean providerStarted() {
        return providerStartedAtMs != null;
    }

    // ------------------------------------------------------------------ 판정 · 제브

    synchronized JudgeCall judgeStarted(JudgeRequest request, boolean capped) {
        JudgeCall call = new JudgeCall(judgeCalls.size() + 1, request.point().name(), request.childUtterance(), request.uncertain(), ms(),
                capped);
        ChildTurn turn = lastSpokenTurn();
        call.childTurn = turn == null ? null : turn.index;
        judgeCalls.add(call);
        return call;
    }

    synchronized void judgeFinished(JudgeCall call, JudgeOutcome outcome, String failure, boolean cancelled) {
        if (call.doneAtMs != null) {
            return;
        }
        call.doneAtMs = ms();
        call.cancelledAtDeadline = cancelled;
        if (cancelled) {
            call.kind = "CANCELLED_AT_DEADLINE";
            call.latencyMs = call.doneAtMs - call.atMs;
            return;
        }
        if (outcome == null) {
            call.kind = failure == null ? "NO_OUTCOME" : failure;
            call.latencyMs = call.doneAtMs - call.atMs;
            return;
        }
        call.kind = outcome.kind().name();
        call.first = outcome.firstOption();
        call.firstProbability = outcome.firstProbability();
        call.second = outcome.secondOption();
        call.secondProbability = outcome.secondProbability();
        call.abstainReason = outcome.abstainReason() == null ? null : outcome.abstainReason().name();
        call.model = PracticeVoiceReport.safeToken(outcome.model());
        call.latencyMs = outcome.latency().toMillis();
    }

    synchronized GuardCheck guardStarted(GuardrailRequest request) {
        GuardCheck check = new GuardCheck(guardChecks.size() + 1, ms(), request.mode().name(), request.candidate());
        guardChecks.add(check);
        return check;
    }

    synchronized void guardFinished(GuardCheck check, GuardrailVerdict verdict, String failure) {
        if (check.doneAtMs != null) {
            return;
        }
        check.doneAtMs = ms();
        if (verdict == null) {
            check.decision = "EXCEPTION";
            check.failure = failure;
            check.latencyMs = check.doneAtMs - check.atMs;
            return;
        }
        check.decision = verdict.decision().name();
        check.failure = verdict.failure() == null ? null : verdict.failure().name();
        check.reasonCode = verdict.reasonCode();
        check.categories = verdict.categories();
        check.latencyMs = verdict.latencyMs();
    }

    // ------------------------------------------------------------------ 장면 기록

    synchronized void sceneStarted(UUID id, String sceneKind) {
        scenes.put(id, new SceneRow(id, sceneKind, ms()));
    }

    synchronized void sceneFact(UUID id, String kind) {
        SceneRow row = scenes.get(id);
        if (row != null) {
            row.facts.add(kind);
        }
    }

    synchronized void sceneOutcome(UUID id, String timing, String skip, int retryCount, int reexplainCount) {
        SceneRow row = scenes.get(id);
        if (row != null) {
            row.endedAtMs = ms();
            row.timing = timing;
            row.skipReason = skip;
            row.retryCount = retryCount;
            row.reexplainCount = reexplainCount;
            row.outcomeCalls++;
        }
    }

    synchronized void help(Map<String, Object> help) {
        helps.add(help);
    }

    // ------------------------------------------------------------------ 기기

    synchronized Play localQueued(UUID playbackId, String code, String text, long durationMs) {
        Play play = new Play(playbackId, "APPROVED_AUDIO", code, text, ms());
        play.durationMs = durationMs;
        plays.add(play);
        playsById.put(playbackId, play);
        return play;
    }

    /** 기기에 온 GPT-Live 묶음(그때 열린 말 요청의 묶음으로 본다). */
    synchronized Play bundleQueued(UUID playbackId, long bytes) {
        Play play = new Play(playbackId, "GPT_LIVE", null, null, ms());
        play.bytes = bytes;
        play.durationMs = bytes / PracticeVoiceDevice.BYTES_PER_MS;
        Attempt open = openAttempt();
        if (open != null) {
            play.attempt = open;
            open.playbacks.add(playbackId);
        }
        plays.add(play);
        playsById.put(playbackId, play);
        return play;
    }

    /** 기기 STARTED를 보낼 시각(앞 표식보다 늦은 값으로 맞춘다 — 그 시각이 확정 행의 시작이라 재생과 잇는 열쇠다). */
    synchronized Instant markInstant(Instant now) {
        Instant at = now.isAfter(lastMarkAt) ? now : lastMarkAt.plusNanos(1_000L);
        lastMarkAt = at;
        return at;
    }

    synchronized void playStarted(UUID playbackId, Instant markAt) {
        Play play = playsById.get(playbackId);
        if (play != null && play.startedAtMs == null) {
            play.startedAtMs = ms();
            startedAt.put(markAt, play);
            if (!play.gptLive()) {
                lastLineSource = "승인 음성:" + play.code;
            }
        }
    }

    synchronized void playEnded(UUID playbackId, String end, long playedMs) {
        Play play = playsById.get(playbackId);
        if (play != null && play.end == null) {
            play.end = end;
            play.endedAtMs = ms();
            if (play.startedAtMs == null) {
                play.durationMs = 0;
            } else if (!"COMPLETED".equals(end)) {
                play.durationMs = playedMs;
            }
            if ("COMPLETED".equals(end) && !play.gptLive()) {
                if ("INTRO_PRACTICE".equals(play.code) || "RE_PROMPT".equals(play.code) || "DECLINE_ALTERNATIVE".equals(play.code)) {
                    joinPromptEnds++;
                } else if ("STOP_CONFIRM".equals(play.code)) {
                    stopConfirmEnds++;
                }
            }
        }
    }

    synchronized void deviceView(String view) {
        deviceViews.add(PracticeVoiceReport.formatMs(ms()) + " " + view);
    }

    synchronized void frameOffered(boolean voiced) {
        framesOffered++;
        if (voiced) {
            voicedFramesOffered++;
            if (noResponseArmed) {
                childSpokeWhileArmed = true;
            }
        }
    }

    // ------------------------------------------------------------------ 아이

    synchronized ChildTurn childTurn(String act, String key, String text, String rule, String status) {
        ChildTurn turn = new ChildTurn(childTurns.size() + 1, ms(), act, key, text, rule, flow, status);
        childTurns.add(turn);
        return turn;
    }

    /** 아이 차례 값을 잠금 아래에서 바꾼다(다른 스레드가 같은 차례를 읽는다). */
    synchronized void update(Runnable change) {
        change.run();
    }

    private ChildTurn lastSpokenTurn() {
        for (int i = childTurns.size() - 1; i >= 0; i--) {
            if (childTurns.get(i).spoke()) {
                return childTurns.get(i);
            }
        }
        return null;
    }

    synchronized int childRowCount() {
        int count = 0;
        for (Row row : rows) {
            if (row.speaker() == TranscriptEntry.Speaker.CHILD) {
                count++;
            }
        }
        return count;
    }

    private String flowLabel() {
        Flow current = flow;
        return current == null ? null : current.label();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ------------------------------------------------------------------ 보고서용 사본(세션이 끝난 뒤)

    synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(attempts), List.copyOf(readings), List.copyOf(plays), List.copyOf(childTurns), List.copyOf(heard),
                List.copyOf(rows), Map.copyOf(childFirstDelta), List.copyOf(judgeCalls), List.copyOf(judgmentRequests),
                List.copyOf(judgmentEvents), List.copyOf(clockEvents), List.copyOf(discards), List.copyOf(appErrors),
                new LinkedHashMap<>(eventCounts), List.copyOf(sessionEvents), List.copyOf(flowTrace), List.copyOf(signals),
                List.copyOf(flowFailures), List.copyOf(notices), List.copyOf(guardChecks), List.copyOf(scenes.values()), List.copyOf(helps),
                List.copyOf(otherInstructions), List.copyOf(deviceViews), new LinkedHashMap<>(outputCalls), List.copyOf(providerErrors),
                new LinkedHashMap<>(instructionTypes), activeAtMs, providerOpenedAtMs, providerStartedAtMs, providerClosedAtMs,
                usageSeconds, outputAudioBytes, outputTranscriptDeltas, inputTranscriptDeltas, framesOffered, voicedFramesOffered,
                practiceRecord, settled, noResponseMalfunctions, List.copyOf(malfunctionDetails), startInstant);
    }

    /** 세션이 끝난 뒤 보고서가 읽는 사본(목록은 사본, 원소는 끝난 뒤라 더 바뀌지 않는다). */
    record Snapshot(List<Attempt> attempts, List<Reading> readings, List<Play> plays, List<ChildTurn> childTurns, List<Heard> heard,
            List<Row> rows, Map<Integer, Instant> childFirstDelta, List<JudgeCall> judgeCalls, List<JudgmentRequest> judgmentRequests,
            List<JudgmentEvent> judgmentEvents, List<ClockEvent> clockEvents, List<Discard> discards, List<Map<String, Object>> appErrors,
            Map<String, Integer> eventCounts, List<Map<String, Object>> sessionEvents, List<Map<String, Object>> flowTrace,
            List<Map<String, Object>> signals, List<String> flowFailures, List<Map<String, Object>> notices, List<GuardCheck> guardChecks,
            List<SceneRow> scenes, List<Map<String, Object>> helps, List<Map<String, Object>> otherInstructions, List<String> deviceViews,
            Map<String, Integer> outputCalls, List<Map<String, Object>> providerErrors, Map<String, Integer> instructionTypes,
            Long activeAtMs, Long providerOpenedAtMs, Long providerStartedAtMs, Long providerClosedAtMs, double usageSeconds,
            long outputAudioBytes, long outputTranscriptDeltas, long inputTranscriptDeltas, long framesOffered, long voicedFramesOffered,
            Object practiceRecord, Map<String, Object> settled, int noResponseMalfunctions, List<Map<String, Object>> malfunctionDetails,
            Instant startInstant) {

        /** 기록기 칸 시각을 대화 시작부터의 ms로(없으면 null). */
        Long msOf(Instant at) {
            return at == null ? null : Duration.between(startInstant, at).toMillis();
        }
    }
}
