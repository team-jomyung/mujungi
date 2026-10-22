package kr.mujoong.core.textai;

import java.util.List;

/** U10: 미승인 생성물. 상대 역할은 발급 시 서버가 덮어쓴다. */
public record ScenarioScript(List<Scene> scenes) {
    public ScenarioScript { scenes = scenes == null ? List.of() : List.copyOf(scenes); }
    /**
     * 장면 하나. 아이에게는 {@code sceneDescription}(무중이 역할 밖에서 하는 장면 설명) → {@code partnerOpener}(친구 역할 첫 대사) →
     * {@code opportunitySentence}(친구 역할의 기회 문장) 순서로 들린다. {@code partnerOpener}는 생성 판 v6부터 있고 옛 행에는 없어 null이다.
     */
    public record Scene(String sceneKey, String sceneKind, String sceneDescription, String partnerOpener,
            String opportunitySentence, String reexplanation, List<String> focusPoints,
            List<String> expressionExamples, String relation, String place, String topic,
            Preservation preservation, String partnerRole) {
        public Scene {
            focusPoints = focusPoints == null ? List.of() : List.copyOf(focusPoints);
            expressionExamples = expressionExamples == null ? List.of() : List.copyOf(expressionExamples);
        }
        /** 친구 첫 대사가 없는 옛 모양(생성 판 v5까지 · 저장된 행). */
        public Scene(String sceneKey, String sceneKind, String sceneDescription, String opportunitySentence, String reexplanation,
                List<String> focusPoints, List<String> expressionExamples, String relation, String place, String topic,
                Preservation preservation, String partnerRole) {
            this(sceneKey, sceneKind, sceneDescription, null, opportunitySentence, reexplanation, focusPoints, expressionExamples,
                    relation, place, topic, preservation, partnerRole);
        }
        public Scene withPartnerRole(String role) {
            return new Scene(sceneKey, sceneKind, sceneDescription, partnerOpener, opportunitySentence, reexplanation,
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
