package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScriptWithVariant;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.Fact;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.testing.InternalApi;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 기록 모양 시험 (U12 BR7.1 ~ BR7.3, R-F14): 대화에 넣은 표식 문자열이 기록 · 도움 · 정산 값 · 앱 오류 · 지시문 어디에도 나타나지 않고, 기록 값
 * 타입에 문자열 원문 칸이 없으며, 도입 재생이 성공하지 못한 장면에는 행이 없다. 운영 기본 경로(C안 '무중이 직접 묻기')의 도움 · 변형까지
 * 가는 대화로 본다. 가짜 출력 · 가짜 판정 · 메모리 기록기 위의 합성 대화이고 실제 AI · 실제 기기가 아니다(테스트 대역 증거).
 */
@Tag("practice-flow")
class PracticeFlowRecordTest {

    private static final String MARKER_ONE = "표식하나QZ71";
    private static final String MARKER_TWO = "표식둘QZ72";
    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final Map<String, String> APPROVED = FIXED_SPEECH;

    private static Harness full() {
        return new Harness(packageJson(GOAL, directAskScriptWithVariant(SCENARIO)), APPROVED);
    }

    @Test
    void aMarkerTheChildSaysAppearsInNoRecordHelpSettlementValueAppErrorOrInstruction() {
        Harness h = full();
        h.toOpportunity();
        h.child("도와줘 " + MARKER_ONE);
        h.result("J2", "HELP");
        h.played();
        h.child("손 괜찮아 " + MARKER_TWO);
        h.result("J5", "FULL");
        h.played();
        h.played();
        h.child("응 " + MARKER_ONE);
        h.result("J4", "ACCEPT");
        h.played();
        h.played();
        h.child("졸리구나 " + MARKER_TWO);
        h.result("J2", "PARTIAL");

        List<String> everything = new ArrayList<>();
        everything.add(JsonCodec.shared().write(h.ext.practiceRecord()));
        everything.add(JsonCodec.shared().write(h.recorder.helps));
        h.recorder.list().forEach(row -> everything.add(row.toString() + row.facts()));
        h.failures.forEach(failure -> everything.add(String.valueOf(failure.getMessage())));
        h.out.slots.forEach(slot -> everything.add(String.valueOf(slot.text())));
        h.out.scheduleLog.forEach(everything::add);
        everything.add(h.ext.toString());
        everything.add(String.valueOf(h.ext.progress()));

        assertThat(h.recorder.list()).as("원 장면 · 변형 장면 두 행").hasSize(2);
        assertThat(h.recorder.helps).as("도움 기록은 있다(분류 코드와 길이만)").isNotEmpty()
                .allSatisfy(help -> assertThat(help.contentChars()).isNotNegative())
                .anySatisfy(help -> assertThat(help.contentChars()).isPositive());
        assertThat(String.join("\n", everything)).doesNotContain(MARKER_ONE, MARKER_TWO);
    }

    @Test
    void theRecordValueTypesHoldNoFreeTextColumnSoNoTranscriptOrHelpSentenceCanBeStored() {
        for (Class<?> type : List.of(SceneRunRecord.class, HelpRecord.class, Fact.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertThat(component.getType()).as(type.getSimpleName() + "." + component.getName())
                        .isNotEqualTo(String.class).isNotEqualTo(CharSequence.class);
            }
        }
    }

    @Test
    void anIntroThatNeverPlayedLeavesARowWithAPlaybackFailureAndTheSettlementValueStillFollowsTheContract() {
        Harness h = full();
        h.toIntroduction();
        h.end(Playback.FAILED);

        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED);
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(h.ext.practiceRecord())))
                .isEmpty();
    }

    @Test
    void aSceneWhoseIntroPlayedHasARowFromThatMomentAndTheValueFollowsTheContractAtEveryStep() {
        Harness h = full();
        h.activate();
        h.played();
        assertThat(h.recorder.list()).as("목표 안내는 장면 밖 말이고, 상황 안내 재생이 끝나기 전에는 행이 없다").isEmpty();
        h.played();

        assertThat(h.recorder.list()).hasSize(1);
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(h.ext.practiceRecord())))
                .isEmpty();
        h.played();
        h.child("손 괜찮아?");
        h.result("J2", "FULL");
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(h.ext.practiceRecord())))
                .isEmpty();
    }
}
