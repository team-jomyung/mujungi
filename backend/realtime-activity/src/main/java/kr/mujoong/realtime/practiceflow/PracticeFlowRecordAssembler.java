package kr.mujoong.realtime.practiceflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.Fact;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;

/**
 * 장면 실행 행에서 {@code record.practice} 값을 만드는 순수 조립기 (U12 BR7.2 · FR4.2). 모양은
 * {@code contracts/schemas/practice-record-v2.schema.json}이다: {@code flowVersion = practice-conversation-v1}, 필수 4필드(sceneRunId ·
 * goalRef · sceneKind · startedAt)와 선택 scenarioRef · liveOutcome.timing · facts(쌓인 순서) · variantSkipReason. 판정 라벨 · 확률 · 전사 ·
 * 도움 내용은 어디에도 없다(R-F14). 정산 복구(실행기 없는 종료)가 쓰고, 정산 창의 {@code practiceRecord()}도 같은 조립을 쓴다.
 */
public final class PracticeFlowRecordAssembler {

    /** {@code practice-record-v2} flowVersion 상수. */
    public static final String FLOW_VERSION = "practice-conversation-v1";

    private PracticeFlowRecordAssembler() {
    }

    /** 장면 실행 목록(시작 시각 순)으로 {@code practice} 값을 만든다. 비어 있어도 스키마를 지키는 값(scenes 빈 배열)이다. */
    public static Map<String, Object> assemble(List<SceneRunRecord> runs) {
        Objects.requireNonNull(runs, "runs");
        List<Map<String, Object>> scenes = new ArrayList<>(runs.size());
        for (SceneRunRecord run : runs) {
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("sceneRunId", run.id());
            scene.put("goalRef", run.goalRef());
            scene.put("scenarioRef", run.scenarioRef());
            scene.put("sceneKind", run.sceneKind().name());
            scene.put("startedAt", run.startedAt());
            if (run.liveTiming() != null) {
                scene.put("liveOutcome", Map.of("timing", run.liveTiming().name()));
            }
            scene.put("facts", run.facts().stream().map(Fact::kind).map(Enum::name).toList());
            if (run.variantSkipReason() != null) {
                scene.put("variantSkipReason", run.variantSkipReason().name());
            }
            scenes.add(scene);
        }
        Map<String, Object> practice = new LinkedHashMap<>();
        practice.put("flowVersion", FLOW_VERSION);
        practice.put("scenes", List.copyOf(scenes));
        return practice;
    }
}
