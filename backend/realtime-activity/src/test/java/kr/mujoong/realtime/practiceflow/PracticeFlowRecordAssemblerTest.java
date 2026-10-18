package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.Fact;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.testing.InternalApi;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code record.practice} 조립기 시험 (U12 BR7.2). 필수 4필드 · 선택 필드 · 사실 순서 · 라벨 · 확률 · 원문 없음, 그리고 결과가 비어 있어도
 * {@code practice-record-v2} 스키마를 지키는지 본다. DB 없음 · AI 없음(테스트 대역 증거).
 */
@Tag("practice-flow")
class PracticeFlowRecordAssemblerTest {

    private static final Instant T0 = Instant.parse("2026-10-18T09:00:00Z");

    private static SceneRunRecord run(SceneKind kind, int offset, LiveTiming timing, VariantSkipReason reason, List<Fact> facts) {
        return new SceneRunRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID(),
                kind, T0.plusSeconds(offset), null, timing, reason, 1, 0, facts);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFullSceneCarriesTheFourRequiredFieldsAndTheOptionalOnesInOrder() {
        SceneRunRecord base = run(SceneKind.BASE, 0, LiveTiming.AFTER_HELP, VariantSkipReason.FOCUS_LIMIT,
                List.of(new Fact(FactKind.COACH_SWITCH, T0), new Fact(FactKind.FOCUS_HELP, T0.plusSeconds(2)),
                        new Fact(FactKind.COACH_RETURN, T0.plusSeconds(5))));

        Map<String, Object> practice = PracticeFlowRecordAssembler.assemble(List.of(base));

        assertThat(practice).containsEntry("flowVersion", "practice-conversation-v1").hasSize(2);
        Map<String, Object> scene = ((List<Map<String, Object>>) practice.get("scenes")).getFirst();
        assertThat(scene).containsEntry("sceneRunId", base.id()).containsEntry("goalRef", base.goalRef())
                .containsEntry("scenarioRef", base.scenarioRef()).containsEntry("sceneKind", "BASE")
                .containsEntry("startedAt", T0).containsEntry("variantSkipReason", "FOCUS_LIMIT")
                .containsEntry("liveOutcome", Map.of("timing", "AFTER_HELP"))
                .containsEntry("facts", List.of("COACH_SWITCH", "FOCUS_HELP", "COACH_RETURN"));
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(practice))).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnfilledSceneOmitsTheOptionalFieldsAndNoJudgmentLabelOrProbabilityAppears() {
        SceneRunRecord bare = run(SceneKind.VARIANT, 30, null, null, List.of());

        Map<String, Object> practice = PracticeFlowRecordAssembler.assemble(List.of(bare));
        Map<String, Object> scene = ((List<Map<String, Object>>) practice.get("scenes")).getFirst();

        assertThat(scene).doesNotContainKeys("liveOutcome", "variantSkipReason");
        assertThat(scene.get("facts")).isEqualTo(List.of());
        String json = JsonCodec.shared().write(practice);
        assertThat(json).doesNotContain("label", "probability", "transcript", "text");
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", json)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void noScenesStillMakesAContractShapedValueAndEveryFactKindIsAcceptedByTheSchema() {
        Map<String, Object> empty = PracticeFlowRecordAssembler.assemble(List.of());
        assertThat((List<Object>) empty.get("scenes")).isEmpty();
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(empty))).isEmpty();

        List<Fact> all = java.util.Arrays.stream(FactKind.values()).map(kind -> new Fact(kind, T0)).toList();
        Map<String, Object> every = PracticeFlowRecordAssembler.assemble(List.of(run(SceneKind.BASE, 0, LiveTiming.ABSTAINED, null, all)));
        assertThat(FactKind.values()).as("계약 facts 15종").hasSize(15);
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(every))).isEmpty();
    }
}
