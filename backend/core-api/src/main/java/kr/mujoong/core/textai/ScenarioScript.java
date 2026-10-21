package kr.mujoong.core.textai;

import java.util.List;

/** U10: 미승인 생성물. 상대 역할은 발급 시 서버가 덮어쓴다. */
public record ScenarioScript(List<Scene> scenes) {
    public ScenarioScript { scenes = scenes == null ? List.of() : List.copyOf(scenes); }
    public record Scene(String sceneKey, String sceneKind, String sceneDescription,
            String opportunitySentence, String reexplanation, List<String> focusPoints,
            List<String> expressionExamples, String relation, String place, String topic,
            Preservation preservation, String partnerRole) {
        public Scene {
            focusPoints = focusPoints == null ? List.of() : List.copyOf(focusPoints);
            expressionExamples = expressionExamples == null ? List.of() : List.copyOf(expressionExamples);
        }
        public Scene withPartnerRole(String role) {
            return new Scene(sceneKey, sceneKind, sceneDescription, opportunitySentence, reexplanation,
                    focusPoints, expressionExamples, relation, place, topic, preservation, role);
        }
        @Override public String toString() { return "Scene[redacted]"; }
    }
    /**
     * 그래프 경로의 보존 정보. {@code relationship}은 변형 계획의 관계 좌표(아동용 열거값)이고, 장면의 {@code relation}(목표 상대를 나타내는
     * 자유 문자열)과 따로 둔다. 옛 행에는 없어 null일 수 있다.
     */
    public record Preservation(String skillRef, String invariantSentence, String preservationRule,
            String transferDistance, String changedElement, String participants, String situation, String relationship) {
        @Override public String toString() { return "Preservation[redacted]"; }
    }
    @Override public String toString() { return "ScenarioScript[redacted]"; }
}
