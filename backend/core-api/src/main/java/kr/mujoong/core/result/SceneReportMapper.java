package kr.mujoong.core.result;
/** 저장한 장면 안 시점과 사전 연습 여부를 구분한다. 표시 칩에서 독립 능력을 추정하지 않는다. */
public final class SceneReportMapper {
    private SceneReportMapper(){}
    public static String text(SceneJudgmentRepository.Scene scene){
        if(PracticeRecordV2.VERSION.equals(scene.flowVersion())&&"functional".equals(scene.value())){
            if("AFTER_REASK".equals(scene.timing()))return context(scene,"되묻기 뒤 전달");
            if("AFTER_HELP".equals(scene.timing()))return context(scene,"도움 뒤 전달");
            return context(scene,"바로 전달");
        }
        return publicText(scene);
    }

    /** 기존 칩 종류·뜻은 유지하며, 사전 연습이 있었던 기록에는 그 조건을 함께 표시한다. */
    public static String publicText(SceneJudgmentRepository.Scene scene) {
        return context(scene, SceneChipMapper.text(scene.sceneChip(), scene.notDoneReason()));
    }

    private static String context(SceneJudgmentRepository.Scene scene, String text) {
        return PracticeRecordV2.VERSION.equals(scene.flowVersion()) && scene.facts().contains("PREPRACTICE_PROVIDED")
                ? "사전 연습 후 · " + text : text;
    }
}
