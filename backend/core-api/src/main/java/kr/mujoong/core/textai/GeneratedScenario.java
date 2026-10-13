package kr.mujoong.core.textai;
/**
 * 공급자가 만든 상황. 게이트웨이와 부르는 쪽 검사가 끝나기 전에는 믿지 않는다. 상대 역할 지시는 모델에 받지 않고 Core의 고정 문장을 쓴다
 * (U5a-J22 · U5a 리뷰 7).
 */
public record GeneratedScenario(String scenarioText, String triggerScript,
        String relationType, String opportunityCondition, String modelVersion, String promptVersion) {
    @Override public String toString() { return "GeneratedScenario[redacted]"; }
}
