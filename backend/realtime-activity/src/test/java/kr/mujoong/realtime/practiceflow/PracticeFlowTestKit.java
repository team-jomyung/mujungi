package kr.mujoong.realtime.practiceflow;

import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practice.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.PlaybackFact;
import kr.mujoong.realtime.session.PracticeSessionExtension.PracticeContext;
import kr.mujoong.realtime.session.PracticeSessionExtension.PracticeOutput;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import kr.mujoong.realtime.session.PracticeSessionExtension.Utterance;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.testsupport.time.MutableClock;

/**
 * 새 연습 흐름 시험 도구(시험 소스 전용): 기록하는 가짜 {@link PracticeOutput}, 메모리 기록기, 수동 시계({@link MutableClock}) 위에서 상태 기계를
 * 움직이는 {@link Harness}. 가짜 공급자 · 가짜 판정만 쓰고 실제 AI · 실제 기기는 없다(R-F1, 테스트 대역 증거).
 */
final class PracticeFlowTestKit {

    private PracticeFlowTestKit() {
    }

    /** 기본 설정(시험용 값: 무응답 10초 · 집중 90초 · 목표 10분 · 슬롯 안전 20초 · 재시도 2 · 다시 설명 1). */
    static PracticeFlowSettings defaults() {
        return new PracticeFlowSettings(Duration.ofSeconds(10), Duration.ofSeconds(90), Duration.ofMinutes(10),
                Duration.ofSeconds(20), 2, 1);
    }

    /** 한 번의 {@code speak} · {@code speakGenerated} 호출. */
    record Slot(UUID correlationId, boolean generated, String role, String text, PracticeSpeech speech) {
    }

    /** 한 번의 판정 요청. */
    record Judgment(JudgmentPoint point, long seq, UUID correlationId, Map<String, Object> criteria) {
    }

    /** 기록하는 가짜 출력. 호출 결과는 필드로 바꾼다. */
    static final class Output implements PracticeOutput {

        final MutableClock clock;
        final List<Slot> slots = new ArrayList<>();
        final List<Judgment> judgments = new ArrayList<>();
        final List<String> announces = new ArrayList<>();
        final List<String> scheduleLog = new ArrayList<>();
        final List<String> cancelLog = new ArrayList<>();
        final List<UUID> cancelledSpeech = new ArrayList<>();
        final List<Boolean> holds = new ArrayList<>();
        final List<Map.Entry<String, Object>> posted = new ArrayList<>();
        final Map<String, Instant> timers = new LinkedHashMap<>();
        int calls;
        boolean speakResult = true;
        boolean generatedResult = true;
        boolean judgmentResult = true;
        boolean announceResult = true;

        Output(MutableClock clock) {
            this.clock = clock;
        }

        Slot lastSlot() {
            return slots.getLast();
        }

        Judgment lastJudgment() {
            return judgments.getLast();
        }

        List<Slot> generatedSlots() {
            return slots.stream().filter(Slot::generated).toList();
        }

        @Override
        public UUID sessionId() {
            return UUID.randomUUID();
        }

        @Override
        public boolean playApproved(String contentCode) {
            calls++;
            return false;
        }

        @Override
        public boolean instructProvider(String text) {
            calls++;
            return false;
        }

        @Override
        public boolean requestResponse() {
            calls++;
            return false;
        }

        @Override
        public boolean cancelResponse() {
            calls++;
            return false;
        }

        @Override
        public boolean holdOutput(boolean hold) {
            calls++;
            holds.add(hold);
            return true;
        }

        @Override
        public boolean schedule(String name, Duration delay) {
            calls++;
            timers.put(name, clock.now().plus(delay));
            scheduleLog.add(name + ":" + delay);
            return true;
        }

        @Override
        public boolean cancel(String name) {
            calls++;
            timers.remove(name);
            cancelLog.add(name);
            return true;
        }

        @Override
        public boolean post(String name, Object value) {
            posted.add(Map.entry(name, value));
            return true;
        }

        @Override
        public boolean speak(PracticeSpeech speech) {
            calls++;
            slots.add(new Slot(speech.correlationId(), false, speech.partnerRole(), speech.text(), speech));
            return speakResult;
        }

        @Override
        public boolean cancelSpeech(UUID correlationId) {
            calls++;
            cancelledSpeech.add(correlationId);
            return true;
        }

        @Override
        public boolean requestJudgment(JudgmentPoint point, long transcriptSeq, UUID correlationId, Map<String, Object> criteria) {
            calls++;
            judgments.add(new Judgment(point, transcriptSeq, correlationId, criteria));
            return judgmentResult;
        }

        @Override
        public boolean announceRoleSwitch(String toRole) {
            calls++;
            announces.add(toRole);
            return announceResult;
        }

        @Override
        public boolean speakGenerated(UUID correlationId, String role, String instructionText) {
            calls++;
            slots.add(new Slot(correlationId, true, role, instructionText, null));
            return generatedResult;
        }
    }

    /** 메모리 기록기: 상태 기계가 남기는 장면 · 사실 · 도움을 그대로 쌓는다(원문 칸이 없다). */
    static final class MemoryRecorder implements FlowRecorder {

        final Map<UUID, SceneRunRecord> rows = new LinkedHashMap<>();
        final List<HelpRecord> helps = new ArrayList<>();
        boolean failOnSceneStarted;

        @Override
        public void sceneStarted(SceneRunRecord started) {
            if (failOnSceneStarted) {
                throw new IllegalStateException("기록 실패(시험)");
            }
            rows.put(started.id(), started);
        }

        @Override
        public void fact(UUID sceneRunId, FactKind kind, Instant at) {
            SceneRunRecord row = rows.get(sceneRunId);
            List<PracticeFlowTypes.Fact> facts = new ArrayList<>(row.facts());
            facts.add(new PracticeFlowTypes.Fact(kind, at));
            rows.put(sceneRunId, copy(row, row.endedAt(), row.liveTiming(), row.variantSkipReason(), row.retryCount(),
                    row.reexplainCount(), facts));
        }

        @Override
        public void help(HelpRecord help) {
            helps.add(help);
        }

        @Override
        public void sceneOutcome(UUID sceneRunId, Instant endedAt, LiveTiming timing, VariantSkipReason skip, int retryCount,
                int reexplainCount) {
            SceneRunRecord row = rows.get(sceneRunId);
            rows.put(sceneRunId, copy(row, endedAt, timing, skip, retryCount, reexplainCount, row.facts()));
        }

        @Override
        public Object practiceRecord() {
            return PracticeFlowRecordAssembler.assemble(List.copyOf(rows.values()));
        }

        private static SceneRunRecord copy(SceneRunRecord row, Instant endedAt, LiveTiming timing, VariantSkipReason skip,
                int retryCount, int reexplainCount, List<PracticeFlowTypes.Fact> facts) {
            return new SceneRunRecord(row.id(), row.sessionId(), row.guardianId(), row.goalRef(), row.goalIndex(),
                    row.scenarioRef(), row.sceneKind(), row.startedAt(), endedAt, timing, skip, retryCount, reexplainCount, facts);
        }

        List<SceneRunRecord> list() {
            return List.copyOf(rows.values());
        }

        SceneRunRecord only() {
            return list().getFirst();
        }

        List<FactKind> factKinds(SceneRunRecord row) {
            return row.facts().stream().map(PracticeFlowTypes.Fact::kind).toList();
        }
    }

    /** 상태 기계 + 가짜 출력 + 메모리 기록기 + 수동 시계. */
    static final class Harness {

        final MutableClock clock = new MutableClock();
        final Output out = new Output(clock);
        final MemoryRecorder recorder = new MemoryRecorder();
        final List<RuntimeException> failures = new ArrayList<>();
        final InternalHttpClient http = mock(InternalHttpClient.class);
        final PracticeConversation ext;
        final PracticeContext context;
        int seq;

        Harness(String practiceJson, Map<String, String> approvedSpeech) {
            this(practiceJson, approvedSpeech, defaults());
        }

        Harness(String practiceJson, Map<String, String> approvedSpeech, PracticeFlowSettings settings) {
            this(practiceJson, approvedSpeech, settings, Runnable::run);
        }

        Harness(String practiceJson, Map<String, String> approvedSpeech, PracticeFlowSettings settings,
                java.util.concurrent.Executor calls) {
            ext = new PracticeConversation(UUID.randomUUID(), UUID.randomUUID(), clock, settings,
                    new PracticeScriptRequester(http, calls, Duration.ofSeconds(1)), recorder, failures::add);
            context = new PracticeContext(UUID.randomUUID(), practiceJson, java.util.Set.of(), false, clock.now(), approvedSpeech);
        }

        Signal activate() {
            return ext.onActive(context, out);
        }

        /** 맨 마지막으로 연 슬롯에 종료 사실을 준다. 생성형 슬롯은 speech 없이 사건의 correlationId로만 알린다. */
        Signal end(Playback status) {
            Slot slot = out.lastSlot();
            return slotEvent(slot.correlationId(), status, slot.speech(), slot.text());
        }

        Signal played() {
            return end(Playback.COMPLETED);
        }

        Signal slotEvent(UUID correlationId, Playback status, PracticeSpeech speech, String text) {
            return ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.PLAYBACK_FACT, "u5b-playback", correlationId,
                    new PlaybackFact(speech, status, text)), out);
        }

        /** 실행부가 연 승인 알림 슬롯(확장이 correlationId를 모른다)의 종료 사실. */
        Signal announcedNotice(Playback status) {
            UUID unknown = UUID.randomUUID();
            return slotEvent(unknown, status, null, "알림");
        }

        Signal child(String text) {
            return ext.onChildUtterance(new Utterance(++seq, clock.now(), text, false, false), out);
        }

        Signal result(String point, String option) {
            return result(out.lastJudgment().correlationId(), point, option);
        }

        Signal result(UUID correlationId, String point, String option) {
            return ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.JUDGMENT_RESULT, "judgment", correlationId,
                    Map.of("point", point, "firstOption", option, "secondOption", "NONE")), out);
        }

        Signal abstained(String point) {
            return ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.JUDGMENT_ABSTAINED, "judgment",
                    out.lastJudgment().correlationId(), Map.of("point", point, "abstainReason", "FAULT")), out);
        }

        Signal event(ExtensionEvent.Kind kind, String name, UUID correlationId, Object value) {
            return ext.onExtensionEvent(new ExtensionEvent(kind, name, correlationId, value), out);
        }

        /** 시계를 앞당기며 만기가 된 확장점 시계를 이름 순서대로 터뜨린다. 마지막 신호를 돌려준다. */
        Signal advance(Duration duration) {
            Instant until = clock.now().plus(duration);
            Signal last = Signal.NONE;
            while (true) {
                Map.Entry<String, Instant> due = out.timers.entrySet().stream().filter(entry -> !entry.getValue().isAfter(until))
                        .min(Map.Entry.comparingByValue()).orElse(null);
                if (due == null) {
                    break;
                }
                if (due.getValue().isAfter(clock.now())) {
                    clock.advance(Duration.between(clock.now(), due.getValue()));
                }
                out.timers.remove(due.getKey());
                Signal signal = ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.TIMER_FIRED, due.getKey(), null, null), out);
                if (signal != Signal.NONE) {
                    last = signal;
                }
            }
            if (until.isAfter(clock.now())) {
                clock.advance(Duration.between(clock.now(), until));
            }
            return last;
        }

        /** 비동기 결과(post) 편지함을 비우며 전달한다. 마지막 신호를 돌려준다. */
        Signal deliverPosted() {
            Signal last = Signal.NONE;
            for (Map.Entry<String, Object> entry : List.copyOf(out.posted)) {
                out.posted.remove(entry);
                Signal signal = ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.ASYNC_RESULT, entry.getKey(), null,
                        entry.getValue()), out);
                if (signal != Signal.NONE) {
                    last = signal;
                }
            }
            return last;
        }

        /** 활성화 → 도입 재생 완료 → 기회 문장 재생 완료(아이 말을 기다리는 상태). */
        void toOpportunity() {
            activate();
            played();
            played();
        }
    }
}
