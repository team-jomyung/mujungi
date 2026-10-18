package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.assertThat;
import static kr.mujoong.realtime.practicelegacy.PracticePorts.*;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.time.TimerDriver;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 공급자 완료와 타이머 콜백을 명시적으로 늦춰 실제 연결 경계의 경합을 재현한다. */
@Tag("u5b")
class PracticeRuntimeRaceTest {
    @Test void anAlreadyQueuedTimerCannotExpireTheOpportunityDuringTechnicalPause() {
        var h = new SessionHarness();
        var extension = new RecordingPracticeExtension();
        h.activityType = ActivityType.PRACTICE; h.practice = extension.factory; h.activate();
        assertThat(extension.output.schedule("first-response-wait", Duration.ofSeconds(20))).isTrue();

        // 실행기가 바쁜 동안 점선 시한과 반응 시한 콜백 모두 편지함에 들어간다.
        // 점선 사건이 먼저 처리되어 일시정지할 때 반응 콜백은 이미 취소 불가능하다.
        h.clock.advance(Duration.ofSeconds(20)); h.safety.runDue(); h.quiet.runDue(); h.drain();
        assertThat(h.executor.liveState().technicalPause()).isNotNull();
        assertThat(extension.events).noneMatch(e -> e.name().equals("first-response-wait"));

        h.link.touch(h.clock.now()); h.advanceAlive(Duration.ofSeconds(1));
        assertThat(h.executor.liveState().technicalPause()).isNull();
        assertThat(extension.events).filteredOn(e -> e.name().equals("first-response-wait")).hasSize(1);
    }

    @Test void aCueCompletingAfterItsFiveSecondDeadlineCannotSpeakDuringNewScenarioPreparation() {
        var f = new RuntimeFixture(); f.start(); f.completed();
        f.advance(Duration.ofSeconds(20));
        assertThat(f.lastSpeech().kind()).isEqualTo(OutputKind.HELP_QUESTION);
        f.completed();
        f.extension.onChildUtterance(new PracticeSessionExtension.Utterance(1, f.clock.now(), "응", false, false), f.output);
        assertThat(f.extension.progress().stage()).isEqualTo("CUE");
        f.advance(Duration.ofSeconds(5));
        assertThat(f.repository.snapshot(f.session).cues()).singleElement()
                .extracting(CueDelivery::deliveryState).isEqualTo(PracticeTypes.DeliveryState.FAILED);
        assertThat(f.repository.snapshot(f.session).attempts()).anyMatch(a -> a.state() == PracticeTypes.AttemptState.PREPARING);
        int before = f.output.speeches.size();

        f.cue.complete(new CueProvider.Result("친구가 기다리고 있네.", true, false)); f.drain();
        assertThat(f.output.speeches).as("기한이 지난 도움은 뒤늦게 재생하지 않는다").hasSize(before);
        assertThat(f.output.speeches).noneMatch(s -> s.kind() == OutputKind.CUE);
        assertThat(f.failures).isEmpty();

        var next = new Scene(UUID.randomUUID(), "새 놀이 상황", "친구", "먼저 해도 될까?");
        f.scene.complete(next); f.drain();
        assertThat(f.output.speeches).hasSize(before + 1);
        assertThat(f.lastSpeech().scenarioId()).isEqualTo(next.id());
    }

    static final class RuntimeFixture {
        final UUID session = UUID.randomUUID(), guardian = UUID.randomUUID();
        final MutableClock clock = new MutableClock();
        final ManualTimerDriver timers = new ManualTimerDriver(clock);
        final MemoryPracticeRepository repository = new MemoryPracticeRepository();
        final CompletableFuture<CueProvider.Result> cue = new CompletableFuture<>();
        final CompletableFuture<Scene> scene = new CompletableFuture<>();
        final List<RuntimeException> failures = new ArrayList<>();
        final ArrayDeque<ExtensionEvent> events = new ArrayDeque<>();
        final Output output = new Output();
        final RuntimePracticeExtension extension = new RuntimePracticeExtension(session, guardian, repository,
                clock, new EvaluationClock(clock), timers, Runnable::run, Runnable::run,
                new PracticeProperties(Duration.ofSeconds(20), Duration.ofSeconds(10), Duration.ofSeconds(5),
                        Duration.ofSeconds(20), Duration.ofSeconds(20), Duration.ofSeconds(15), Duration.ofSeconds(5),
                        Duration.ofSeconds(12), Duration.ofSeconds(2), Duration.ZERO, "시험 예산"),
                (id, goal, previous) -> scene,
                request -> CompletableFuture.failedFuture(new IllegalStateException("이 시험에서는 판정을 요청하지 않는다")),
                brief -> cue, failures::add);

        void start() {
            var first = new PracticeScenarioClient.Scenario(UUID.randomUUID(), "ORIGINAL", "친구와 놀이하는 상황", "친구",
                    "내 차례야.", new PracticeScenarioClient.Validation(true, true, false));
            var goal = new RuntimePracticeExtension.Goal(UUID.randomUUID(), 1, "차례 기다리기", "WAIT_TURN", 1, "합성 기준", first);
            extension.onActive(new PracticeSessionExtension.PracticeContext(session,
                    JsonCodec.shared().write(new RuntimePracticeExtension.Package(List.of(goal))), Set.of(), false, clock.now()), output);
        }
        PracticeSpeech lastSpeech() { return output.speeches.getLast(); }
        void completed() {
            var speech = lastSpeech();
            extension.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.PLAYBACK_FACT, "시험 재생 완료", speech.correlationId(),
                    new PracticeSessionExtension.PlaybackFact(speech, Playback.COMPLETED, speech.text())), output);
            drain();
        }
        void advance(Duration duration) { timers.advance(duration); drain(); }
        void drain() { while (!events.isEmpty()) extension.onExtensionEvent(events.removeFirst(), output); }
        final class Output implements PracticeSessionExtension.PracticeOutput {
            final List<PracticeSpeech> speeches = new ArrayList<>();
            final Map<String, TimerDriver.TimerHandle> scheduled = new HashMap<>();
            @Override public UUID sessionId() { return session; }
            @Override public boolean speak(PracticeSpeech speech) { speeches.add(speech); return true; }
            @Override public boolean cancelSpeech(UUID correlation) { return true; }
            @Override public boolean playApproved(String code) { throw new UnsupportedOperationException(); }
            @Override public boolean instructProvider(String text) { throw new UnsupportedOperationException(); }
            @Override public boolean requestResponse() { throw new UnsupportedOperationException(); }
            @Override public boolean cancelResponse() { throw new UnsupportedOperationException(); }
            @Override public boolean holdOutput(boolean hold) { return true; }
            @Override public boolean schedule(String name, Duration delay) {
                cancel(name); scheduled.put(name, timers.schedule(delay, () -> events.add(new ExtensionEvent(ExtensionEvent.Kind.TIMER_FIRED, name, null, null))));
                return true;
            }
            @Override public boolean cancel(String name) {
                var handle = scheduled.remove(name); return handle != null && handle.cancel();
            }
            @Override public boolean post(String name, Object value) { return events.add(new ExtensionEvent(ExtensionEvent.Kind.ASYNC_RESULT, name, null, value)); }
        }
    }
}
