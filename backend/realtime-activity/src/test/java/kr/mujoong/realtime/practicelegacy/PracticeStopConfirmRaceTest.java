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
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.realtime.voice.PracticeSpeech;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 종료 확인과 기기 일시정지가 연습 단계의 같은 남은 시간을 함께 보호한다. */
@Tag("u5b")
class PracticeStopConfirmRaceTest {
    private static final String RESPONSE_WAIT = "first-response-wait";
    private static final String ASYNC_READY = "scene-ready";

    @Test void confirmationHoldsTheRemainingOpportunityAndAsyncResultWithoutAFreeTalkResponse() {
        var f = new Fixture();
        f.askAtNineteenSeconds();
        f.probe.output.post(ASYNC_READY, "합성 결과"); f.h.drain();
        assertThat(f.probe.events).as("확인 질문을 재생하는 동안 연습 완료 사건을 보류한다").isEmpty();

        f.completeQuestion();
        f.h.advanceAlive(Duration.ofSeconds(2));
        assertThat(f.probe.events).as("원래 20초 시한을 지나도 확인 중에는 반응 기회를 닫지 않는다").isEmpty();
        f.h.childSays("continue", "아니야, 계속할래");
        assertThat(f.probe.events).extracting(PracticeSessionExtension.ExtensionEvent::name).containsExactly(ASYNC_READY);

        f.assertOneSecondRemains();
        f.assertNoFreeTalkResponse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bothHoldsMustEndBeforeTheOpportunityOrAsyncResultCanResume(boolean confirmationEndsFirst) {
        var f = new Fixture();
        f.askAtNineteenSeconds(); f.completeQuestion();
        // 마지막 기기 소식 뒤 10초가 지나 확인 대기와 기술 일시정지가 겹친다.
        f.h.advance(Duration.ofSeconds(11));
        assertThat(f.h.executor.liveState().technicalPause()).isNotNull();
        f.probe.output.post(ASYNC_READY, "합성 결과"); f.h.drain();
        assertThat(f.probe.events).isEmpty();

        if (confirmationEndsFirst) {
            // 공급자 전사는 기기 연결 복구 증거가 아니다.
            f.h.childSays("continue-before-device", "아니야, 계속할래");
            assertThat(f.h.executor.liveState().technicalPause()).isNotNull();
            f.h.advance(Duration.ofSeconds(2));
            assertThat(f.probe.events).as("확인이 끝나도 기기가 복구될 때까지 보류한다").isEmpty();
            f.deviceReturns();
        } else {
            f.deviceReturns();
            assertThat(f.h.executor.liveState().technicalPause()).isNull();
            f.h.advanceAlive(Duration.ofSeconds(2));
            assertThat(f.probe.events).as("기기가 복구되어도 종료 확인이 끝날 때까지 보류한다").isEmpty();
            f.h.childSays("continue-after-device", "아니야, 계속할래");
        }
        assertThat(f.h.executor.liveState().technicalPause()).isNull();
        assertThat(f.probe.events).extracting(PracticeSessionExtension.ExtensionEvent::name).containsExactly(ASYNC_READY);
        f.assertOneSecondRemains();
        f.assertNoFreeTalkResponse();
    }

    @Test void deferredPlaybackKeepsItsReceiptTimeAndCountsOnlyTheUnionOfPauseOverlap() {
        var f = new Fixture();
        f.h.advanceAlive(Duration.ofSeconds(1));
        var beforePause = new PracticeSessionExtension.PlaybackFact(
                new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 1, PracticePorts.OutputKind.SCENARIO,
                        "친구", "첫 번째 합성 상황이야."),
                PracticePorts.Playback.COMPLETED, "첫 번째 합성 상황이야.", f.h.clock.now());
        f.h.advanceAlive(Duration.ofSeconds(3));
        f.h.childSays("ask-stop", "쉬고 싶어"); f.completeQuestion(); // 4초: 첫 정지 시작
        f.h.advanceAlive(Duration.ofSeconds(2));
        var duringPause = new PracticeSessionExtension.PlaybackFact(
                new PracticeSpeech(UUID.randomUUID(), UUID.randomUUID(), 2, PracticePorts.OutputKind.SCENARIO,
                        "친구", "두 번째 합성 상황이야."),
                PracticePorts.Playback.COMPLETED, "두 번째 합성 상황이야.", f.h.clock.now());
        f.h.advanceAlive(Duration.ofSeconds(2)); // 8초: 수신보다 늦게 실행기에 도착
        for (var fact : List.of(beforePause, duringPause)) {
            f.h.mailbox.putSafety(new SessionMailbox.PracticeEvent(new PracticeSessionExtension.ExtensionEvent(
                    PracticeSessionExtension.ExtensionEvent.Kind.PLAYBACK_FACT, "지연된 실제 재생 사실",
                    fact.speech().correlationId(), fact)));
        }
        f.h.drain();
        assertThat(f.probe.events).isEmpty();

        f.h.advance(Duration.ofSeconds(11)); // 19초: 확인 대기와 기술 일시정지가 겹침
        assertThat(f.h.executor.liveState().technicalPause()).isNotNull();
        f.h.childSays("continue-before-device", "아니야, 계속할래");
        f.h.advance(Duration.ofSeconds(2));
        assertThat(f.probe.events).as("한 종류의 정지만 풀린 동안 재생 사실을 전달하지 않는다").isEmpty();
        f.deviceReturns(); // 21초: 두 정지 모두 해제, 정지 합집합은 [4초, 21초]

        var facts = f.probe.events.stream().map(PracticeSessionExtension.ExtensionEvent::value)
                .map(PracticeSessionExtension.PlaybackFact.class::cast).toList();
        assertThat(facts).hasSize(2);
        assertThat(facts.get(0)).usingRecursiveComparison().ignoringFields("pausedFor").isEqualTo(beforePause);
        assertThat(facts.get(0).pausedFor()).as("일반 큐 지연인 1~4초는 빼고 겹친 정지를 한 번만 센다")
                .isEqualTo(Duration.ofSeconds(17));
        assertThat(facts.get(1)).usingRecursiveComparison().ignoringFields("pausedFor").isEqualTo(duringPause);
        assertThat(facts.get(1).pausedFor()).as("정지가 먼저 시작했어도 실제 수신 6초 이후만 센다")
                .isEqualTo(Duration.ofSeconds(15));
    }

    private static final class Fixture {
        final SessionHarness h = new SessionHarness();
        final Probe probe = new Probe();

        Fixture() {
            h.activityType = ActivityType.PRACTICE;
            h.practice = ignored -> probe;
            h.extraApproved.put(SessionExecutor.STOP_CONFIRM, "이야기를 그만할까?");
            h.activate();
            assertThat(probe.output.schedule(RESPONSE_WAIT, Duration.ofSeconds(20))).isTrue();
        }
        void askAtNineteenSeconds() {
            h.advanceAlive(Duration.ofSeconds(19));
            h.childSays("ask-stop", "쉬고 싶어");
            assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
            assertThat(h.link.sent(PlayApprovedContent.class)).singleElement()
                    .extracting(PlayApprovedContent::contentCode).isEqualTo(SessionExecutor.STOP_CONFIRM);
        }
        void completeQuestion() {
            var question = h.link.sent(PlayApprovedContent.class).getLast();
            h.link.touch(h.clock.now());
            h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                    new PlaybackMark(question.playbackId(), PlaybackMark.Status.COMPLETED, null, h.clock.now(), null, null),
                    h.clock.now()));
            h.drain();
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
                    .as("연습은 확인 해제만으로 자유 응답을 새로 만들지 않는다").isEmpty();
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
