package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.realtime.voice.PracticeSpeech;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 연습 단계의 남은 시간 보호. 종료 확인 질문은 2026-10-22 결정으로 없앴으므로 불명확한 종료 신호("쉬고 싶어")는 연습 시계 · 비동기 결과를 멈추지
 * 않고, 남은 시간을 지키는 것은 기기 기술 일시정지뿐이다. 값은 합성이다(R-F1).
 */
@Tag("practice-legacy")
@org.junit.jupiter.api.Tag("service-guardrail")
class PracticeStopConfirmRaceTest {
    private static final String RESPONSE_WAIT = "first-response-wait";
    private static final String ASYNC_READY = "scene-ready";

    @Test void anUnclearStopSignalNoLongerHoldsTheOpportunityOrTheAsyncResult() {
        var f = new Fixture();
        f.saysUnclearStopAtNineteenSeconds();
        f.probe.output.post(ASYNC_READY, "합성 결과"); f.h.drain();
        assertThat(f.probe.events).as("확인 질문이 없으므로 연습 완료 사건을 곧바로 전달한다")
                .extracting(PracticeSessionExtension.ExtensionEvent::name).containsExactly("practice-rest", ASYNC_READY);

        f.assertOneSecondRemains();
        f.assertNoFreeTalkResponse();
    }

    @Test void aTechnicalPauseAloneHoldsTheAsyncResultUntilTheDeviceReturns() {
        var f = new Fixture();
        f.h.advanceAlive(Duration.ofSeconds(5));
        // 마지막 기기 소식 뒤 10초가 지나 기술 일시정지가 시작된다.
        f.h.advance(Duration.ofSeconds(11));
        assertThat(f.h.executor.liveState().technicalPause()).isNotNull();
        f.probe.output.post(ASYNC_READY, "합성 결과"); f.h.drain();
        assertThat(f.probe.events).isEmpty();

        // 공급자 전사는 기기 연결 복구 증거가 아니다.
        f.h.childSays("continue-before-device", "아니야, 계속할래");
        assertThat(f.h.executor.liveState().technicalPause()).isNotNull();
        assertThat(f.probe.events).as("기기가 복구될 때까지 보류한다").isEmpty();

        f.deviceReturns();
        assertThat(f.h.executor.liveState().technicalPause()).isNull();
        assertThat(f.probe.events).extracting(PracticeSessionExtension.ExtensionEvent::name).containsExactly(ASYNC_READY);
        assertThat(f.h.link.sent(PlayApprovedContent.class)).as("확인 질문은 없다")
                .noneMatch(play -> "STOP_CONFIRM".equals(play.contentCode()));
    }

    @Test void deferredPlaybackKeepsItsReceiptTimeAndCountsOnlyTheTechnicalPause() {
        var f = new Fixture();
        f.h.advanceAlive(Duration.ofSeconds(1)); // 1초: 마지막 기기 소식
        var beforePause = new PracticeSessionExtension.PlaybackFact(
                new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 1, PracticePorts.OutputKind.SCENARIO,
                        "친구", "첫 번째 합성 상황이야."),
                PracticePorts.Playback.COMPLETED, "첫 번째 합성 상황이야.", f.h.clock.now());
        f.h.advance(Duration.ofSeconds(11)); // 12초: 기술 일시정지 중
        assertThat(f.h.executor.liveState().technicalPause()).isNotNull();
        var duringPause = new PracticeSessionExtension.PlaybackFact(
                new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 2, PracticePorts.OutputKind.SCENARIO,
                        "친구", "두 번째 합성 상황이야."),
                PracticePorts.Playback.COMPLETED, "두 번째 합성 상황이야.", f.h.clock.now());
        f.h.advance(Duration.ofSeconds(2)); // 14초: 수신보다 늦게 실행기에 도착
        for (var fact : List.of(beforePause, duringPause)) {
            f.h.mailbox.putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                    PracticeSessionExtension.ExtensionEvent.Kind.PLAYBACK_FACT, "지연된 실제 재생 사실",
                    fact.speech().correlationId(), fact)));
        }
        f.h.drain();
        assertThat(f.probe.events).as("정지 동안 재생 사실을 전달하지 않는다").isEmpty();

        f.h.advance(Duration.ofSeconds(1));
        f.deviceReturns(); // 15초: 정지 해제

        var facts = f.probe.events.stream().map(PracticeSessionExtension.ExtensionEvent::value)
                .map(PracticeSessionExtension.PlaybackFact.class::cast).toList();
        assertThat(facts).hasSize(2);
        assertThat(facts.get(0)).usingRecursiveComparison().ignoringFields("pausedFor").isEqualTo(beforePause);
        assertThat(facts.get(0).pausedFor()).as("일반 큐 지연(1초 ~ 정지 시작)은 빼고 정지 시간만 센다")
                .isGreaterThan(Duration.ofSeconds(3)).isLessThanOrEqualTo(Duration.ofSeconds(4));
        assertThat(facts.get(1)).usingRecursiveComparison().ignoringFields("pausedFor").isEqualTo(duringPause);
        assertThat(facts.get(1).pausedFor()).as("정지가 먼저 시작했어도 실제 수신 12초 이후만 센다")
                .isEqualTo(Duration.ofSeconds(3));
    }

    private static final class Fixture {
        final SessionHarness h = new SessionHarness();
        final Probe probe = new Probe();

        Fixture() {
            h.activityType = ActivityType.PRACTICE;
            h.practice = ignored -> probe;
            h.extraApproved.put("STOP_CONFIRM", "이야기를 그만할까?");
            h.activate();
            assertThat(probe.output.schedule(RESPONSE_WAIT, Duration.ofSeconds(20))).isTrue();
        }
        void saysUnclearStopAtNineteenSeconds() {
            h.advanceAlive(Duration.ofSeconds(19));
            h.childSays("unclear-stop", "쉬고 싶어");
            assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
            assertThat(h.link.sent(PlayApprovedContent.class)).as("확인 질문을 재생하지 않는다")
                    .noneMatch(play -> "STOP_CONFIRM".equals(play.contentCode()));
        }
        void deviceReturns() {
            h.link.touch(h.clock.now());
            h.mailbox.offerGeneral(new SessionMailbox.DeviceStateReceived(h.link.connectionId(),
                    new DeviceState(DeviceState.Mic.OK, null, null, null), h.clock.now()));
            h.drain();
        }
        void assertOneSecondRemains() {
            h.advanceAlive(Duration.ofMillis(999));
            assertThat(probe.events).noneMatch(e -> e.name().equals(RESPONSE_WAIT));
            h.advanceAlive(Duration.ofMillis(1));
            assertThat(probe.events).filteredOn(e -> e.name().equals(RESPONSE_WAIT)).singleElement()
                    .extracting(PracticeSessionExtension.ExtensionEvent::kind)
                    .isEqualTo(PracticeSessionExtension.ExtensionEvent.Kind.TIMER_FIRED);
            assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        }
        void assertNoFreeTalkResponse() {
            h.launcher.runAll("provider-"); h.drain();
            assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class))
                    .as("연습은 불명확한 신호로 자유 응답을 새로 만들지 않는다").isEmpty();
        }
    }

    private static final class Probe implements PracticeSessionExtension {
        final List<ExtensionEvent> events = new ArrayList<>();
        PracticeOutput output;
        @Override public boolean controlsOutput() { return true; }
        @Override public Signal onActive(PracticeContext context, PracticeOutput value) { output = value; return Signal.NONE; }
        @Override public Signal onChildUtterance(Utterance utterance, PracticeOutput value) { return Signal.NONE; }
        @Override public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput value) { events.add(event); return Signal.NONE; }
        @Override public void onSettle(EndContext end) { }
        @Override public Object practiceRecord() { return null; }
    }
}
