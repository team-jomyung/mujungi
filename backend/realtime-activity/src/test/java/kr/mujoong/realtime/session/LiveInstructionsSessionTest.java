package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.dialog.LiveSessionInstructions;
import kr.mujoong.realtime.testing.FakeProviderPort;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 세션 계획의 활동 종류가 공급자 세션 지시문을 고르고, 공급자가 받아들인(session.started) 판 · 해시만 세션 사건으로 남는다(가짜 공급자). */
@Tag("voice-session")
class LiveInstructionsSessionTest {

    private static List<SessionEvent> liveEvents(SessionHarness h) {
        h.recorder.tick();
        return h.store.sessionEvents.stream()
                .filter(event -> SessionExecutor.EVENT_LIVE_INSTRUCTIONS.equals(event.getEventType())).toList();
    }

    private static ProviderRequestFactory.SessionStart firstStart(SessionHarness h) {
        return h.provider.last().requests(ProviderRequestFactory.SessionStart.class).getFirst();
    }

    private static String payload(LiveSessionInstructions.Body body) {
        return "{\"kind\":\"" + body.kind().name() + "\",\"version\":\"" + body.version() + "\",\"sha256\":\"" + body.sha256() + "\"}";
    }

    @Test
    void aPracticePlanStartsTheProviderWithThePracticeBodyAndRecordsOnlyItsVersionAndHash() {
        SessionHarness h = new SessionHarness();
        h.activityType = ActivityType.PRACTICE;

        h.boot();

        LiveSessionInstructions.Body practice = LiveSessionInstructions.loadDefault().of(ActivityType.PRACTICE);
        String instructions = firstStart(h).session().instructions();
        assertThat(instructions).endsWith(" " + practice.text()).contains("\"하늘\"").doesNotContain("관심사나 일상 질문");
        assertThat(liveEvents(h)).singleElement().satisfies(event -> {
            assertThat(event.getPayloadJson()).isEqualTo(payload(practice));
            assertThat(event.getPayloadJson()).doesNotContain("하늘", "공룡", "역할놀이");
        });
    }

    @Test
    void aFreeTalkPlanKeepsTheFreeTalkBodyAndRecordsItsVersion() {
        SessionHarness h = new SessionHarness();

        h.boot();

        LiveSessionInstructions.Body free = LiveSessionInstructions.loadDefault().of(ActivityType.FREE_TALK);
        assertThat(firstStart(h).session().instructions()).endsWith(" 참여가 확인되면 관심사나 일상 질문 하나로 시작한다.");
        assertThat(liveEvents(h)).singleElement().satisfies(event -> assertThat(event.getPayloadJson()).isEqualTo(payload(free)));
    }

    @Test
    void aProviderThatNeverConnectsLeavesNoInstructionsEvent() {
        SessionHarness h = new SessionHarness();
        h.activityType = ActivityType.PRACTICE;
        h.provider.mode(FakeProviderPort.Mode.REFUSE);

        h.boot();

        assertThat(liveEvents(h)).isEmpty();
    }

    @Test
    void aConnectedProviderWhoseSessionStartSendFailsLeavesNoInstructionsEvent() {
        SessionHarness h = new SessionHarness();
        h.activityType = ActivityType.PRACTICE;
        h.provider.mode(FakeProviderPort.Mode.START_FAILS);

        h.boot();

        assertThat(h.provider.openedCount()).isEqualTo(1);
        assertThat(liveEvents(h)).isEmpty();
    }

    @Test
    void aSentSessionStartWithoutSessionStartedLeavesNoInstructionsEvent() {
        SessionHarness h = new SessionHarness();
        h.activityType = ActivityType.PRACTICE;
        h.provider.mode(FakeProviderPort.Mode.NO_STARTED);

        h.boot();

        assertThat(h.provider.last().requests(ProviderRequestFactory.SessionStart.class)).hasSize(1);
        assertThat(liveEvents(h)).isEmpty();
    }
}
