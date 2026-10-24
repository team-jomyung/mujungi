package kr.mujoong.core.textai;

import java.util.List;

/** U10: 미승인 생성물. 상대 역할은 발급 시 서버가 덮어쓴다. */
public record ScenarioScript(List<Scene> scenes) {
    public ScenarioScript { scenes = scenes == null ? List.of() : List.copyOf(scenes); }

    /**
     * 장면 한 개. 뒤의 다섯 칸({@code opportunityRelay} · {@code askPrompt} · {@code reaskPrompt} · {@code helpSpeech} ·
     * {@code friendReaction})은 연습 대화 C안(무중이 직접 묻기, 대본 판 {@code scenario-script.v2})의 들리는 칸이다. 옛 대본(v1)에는 없어 null이고,
     * null인 칸은 JSON에 쓰지 않는다(docs/practice-direct-ask.md 4.2).
     */
    public record Scene(String sceneKey, String sceneKind, String sceneDescription,
            String opportunitySentence, String reexplanation, List<String> focusPoints,
            List<String> expressionExamples, String relation, String place, String topic,
            Preservation preservation, String partnerRole, String spokenIntroduction, String reflectionPrompt,
            String opportunityRelay, String askPrompt, String reaskPrompt, String helpSpeech, String friendReaction) {
        /** C안 칸이 없는 장면(옛 대본 v1 모양). */
        public Scene(String sceneKey, String sceneKind, String sceneDescription, String opportunitySentence,
                String reexplanation, List<String> focusPoints, List<String> expressionExamples, String relation,
                String place, String topic, Preservation preservation, String partnerRole, String spokenIntroduction,
                String reflectionPrompt) {
            this(sceneKey, sceneKind, sceneDescription, opportunitySentence, reexplanation, focusPoints, expressionExamples,
                    relation, place, topic, preservation, partnerRole, spokenIntroduction, reflectionPrompt,
                    null, null, null, null, null);
        }
        public Scene(String sceneKey, String sceneKind, String sceneDescription, String opportunitySentence,
                String reexplanation, List<String> focusPoints, List<String> expressionExamples, String relation,
                String place, String topic, Preservation preservation, String partnerRole, String spokenIntroduction) {
            this(sceneKey, sceneKind, sceneDescription, opportunitySentence, reexplanation, focusPoints, expressionExamples,
                    relation, place, topic, preservation, partnerRole, spokenIntroduction, null);
        }
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
            return new Scene(sceneKey, sceneKind, sceneDescription, opportunitySentence, reexplanation, focusPoints,
                    expressionExamples, relation, place, topic, preservation, role, spokenIntroduction, reflectionPrompt,
                    opportunityRelay, askPrompt, reaskPrompt, helpSpeech, friendReaction);
        }
        public Scene withDescription(String description) {
            return new Scene(sceneKey, sceneKind, description, opportunitySentence, reexplanation, focusPoints,
                    expressionExamples, relation, place, topic, preservation, partnerRole, spokenIntroduction, reflectionPrompt,
                    opportunityRelay, askPrompt, reaskPrompt, helpSpeech, friendReaction);
        }
        /** C안 들리는 칸 다섯 개만 바꾼 사본. 나머지 칸은 그대로다. */
        public Scene withDirectAsk(String relay, String ask, String reask, String help, String reaction) {
            return new Scene(sceneKey, sceneKind, sceneDescription, opportunitySentence, reexplanation, focusPoints,
                    expressionExamples, relation, place, topic, preservation, partnerRole, spokenIntroduction, reflectionPrompt,
                    relay, ask, reask, help, reaction);
        }
        @Override public String toString() { return "Scene[redacted]"; }
    }
    public record Preservation(String skillRef, String invariantSentence, String preservationRule,
            String transferDistance, String changedElement, String participants, String situation) {
        @Override public String toString() { return "Preservation[redacted]"; }
    }
    @Override public String toString() { return "ScenarioScript[redacted]"; }
}
