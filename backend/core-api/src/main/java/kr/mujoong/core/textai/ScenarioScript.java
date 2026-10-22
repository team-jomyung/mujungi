package kr.mujoong.core.textai;

import java.util.List;

/** U10: 미승인 생성물. 상대 역할은 발급 시 서버가 덮어쓴다. */
public record ScenarioScript(List<Scene> scenes) {
    public ScenarioScript { scenes = scenes == null ? List.of() : List.copyOf(scenes); }
    public record Scene(String sceneKey, String sceneKind, String sceneDescription,
            String opportunitySentence, String reexplanation, List<String> focusPoints,
            List<String> expressionExamples, String relation, String place, String topic,
            Preservation preservation, String partnerRole, String spokenIntroduction) {
        public Scene(String sceneKey, String sceneKind, String sceneDescription, String opportunitySentence,
                String reexplanation, List<String> focusPoints, List<String> expressionExamples, String relation,
                String place, String topic, Preservation preservation, String partnerRole) {
            this(sceneKey, sceneKind, sceneDescription, opportunitySentence, reexplanation, focusPoints,
                    expressionExamples, relation, place, topic, preservation, partnerRole, null);
        }
        public Scene {
            focusPoints = focusPoints == null ? List.of() : List.copyOf(focusPoints);
            expressionExamples = expressionExamples == null ? List.of() : List.copyOf(expressionExamples);
        }
        public Scene withPartnerRole(String role) {
            return new Scene(sceneKey, sceneKind, sceneDescription, opportunitySentence, reexplanation,
                    focusPoints, expressionExamples, relation, place, topic, preservation, role, spokenIntroduction);
        }
        @Override public String toString() { return "Scene[redacted]"; }
    }
    public record Preservation(String skillRef, String invariantSentence, String preservationRule,
            String transferDistance, String changedElement, String participants, String situation) {
        @Override public String toString() { return "Preservation[redacted]"; }
    }
    @Override public String toString() { return "ScenarioScript[redacted]"; }
}
