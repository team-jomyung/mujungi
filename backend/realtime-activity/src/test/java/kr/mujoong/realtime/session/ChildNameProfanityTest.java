package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * JIRA-266 욕설 이름 · 욕설 출력 시험(가짜 공급자 · 합성 문장만, R-F1 · R-M11 — 실제 GPT-Live 동작 확인이 아니다).
 *
 * <ul>
 *   <li>A-2: 저장된 아이 이름이 공통 욕설 사전에 걸리면 세션 시작 지시문에 이름 대신 중립 호칭이 들어가고, 사유 코드 하나가 INFO로 남는다(원문 없음).
 *   <li>A-3: AI 출력에 욕설이 있으면 1차 출력 사전(PROFANITY)에서 막히고 같은 이름의 교정 지시가 나간다.
 * </ul>
 */
@Tag("voice-session")
class ChildNameProfanityTest {

    private final SessionHarness h = new SessionHarness();

    private String startInstructions() {
        return h.provider.last().requests(ProviderRequestFactory.SessionStart.class).getFirst().session().instructions();
    }

    private List<Map<String, Object>> replacedLogs() {
        return h.logs.events(EventCode.APP_ERROR).stream()
                .filter(event -> SessionExecutor.REASON_CHILD_NAME_REPLACED.equals(LogCapture.attrs(event).get("errorKind")))
                .toList();
    }

    @Test
    void aProfaneStoredNameIsReplacedByTheNeutralNameInTheStartInstructionsWithOneInfoReasonCode() {
        h.childContext = new ChildContext("씨발", 8, List.of("공룡"));
        h.boot();

        assertThat(startInstructions()).doesNotContain("씨발")
                .contains("\"" + ProviderRequestFactory.NEUTRAL_CHILD_NAME + "\"");
        assertThat(replacedLogs()).singleElement()
                .satisfies(event -> assertThat(event.get("level")).isEqualTo("INFO"));
        assertThat(h.logs.anyLineContains("씨발")).as("로그에 이름 원문이 없다(R-F14)").isFalse();
    }

    @Test
    void anOrdinaryNameStaysInTheStartInstructionsWithoutTheReasonCode() {
        h.boot();

        assertThat(startInstructions()).contains("\"하늘\"").doesNotContain("\"" + ProviderRequestFactory.NEUTRAL_CHILD_NAME + "\"");
        assertThat(replacedLogs()).isEmpty();
    }

    @Test
    void aProfaneOutputIsDiscardedByTheFirstPassDictionaryAndTheProfanityCorrectionIsSent() {
        h.activate();
        h.speak(".씨발아, 불렀어", 4_800);
        h.advance(Duration.ofMillis(800));
        h.settleSend();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).isEqualTo(1.0);
        List<String> instructions = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).stream()
                .map(ProviderRequestFactory.InstructionsAppend::content).toList();
        assertThat(instructions).contains(
                InstructionTemplates.correctionContent(DialogInstructions.Correction.PROFANITY, h.activityType));
        assertThat(instructions).noneMatch(content -> content.contains("씨발"));
    }
}
