package kr.mujoong.core.result;
/** Report projection reads stored timing, never infers it from the 27 display chip. */
public final class SceneReportMapper {
    private SceneReportMapper(){}
    public static String text(SceneJudgmentRepository.Scene scene){
        if(PracticeRecordV2.VERSION.equals(scene.flowVersion())&&"functional".equals(scene.value())){
            if("AFTER_REASK".equals(scene.timing()))return "되묻기 뒤 전달";
            if("AFTER_HELP".equals(scene.timing()))return "도움 뒤 전달";
            return "바로 전달";
        }
        return SceneChipMapper.text(scene.sceneChip(),scene.notDoneReason());
    }
}
