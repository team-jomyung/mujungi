package kr.mujoong.core.textai;

import java.util.List;

/** U10: 미승인 생성물. 상대 역할은 발급 시 서버가 덮어쓴다. */
public record ScenarioScript(List<Scene> scenes) {
    public ScenarioScript { scenes = scenes == null ? List.of() : List.copyOf(scenes); }
    /**
     * 장면 하나. 아이에게는 {@code sceneDescription}(무중이 역할 밖에서 하는 장면 설명) → {@code partnerOpener}(친구 역할 첫 대사) →
     * {@code opportunitySentence}(친구 역할의 기회 문장) 순서로 들린다. {@code partnerOpener}는 생성 판 v6부터 있고 옛 행에는 없어 null이다.
     *
     * <p>{@code expressionFrames}는 생성 판 v7부터 있는 빈칸 예문이다: {@code expressionExamples}와 같은 순서 · 같은 개수로 핵심 내용 낱말 자리를
     * '뭐뭐'로 비운다. 코치 전용(도움 동의 뒤에만 들림)이고, 판 v6까지의 행 · 생성물에는 없어 null이다(빈 목록과 구분한다).
     */
    public record Scene(String sceneKey, String sceneKind, String sceneDescription, String partnerOpener,
            String opportunitySentence, String reexplanation, List<String> focusPoints,
            List<String> expressionExamples, List<String> expressionFrames, String relation, String place, String topic,
            Preservation preservation, String partnerRole) {
        public Scene {
            focusPoints = focusPoints == null ? List.of() : List.copyOf(focusPoints);
            expressionExamples = expressionExamples == null ? List.of() : List.copyOf(expressionExamples);
            expressionFrames = expressionFrames == null ? null : List.copyOf(expressionFrames);
        }
        /** 빈칸 예문이 없는 모양(생성 판 v6 · 저장된 행). */
        public Scene(String sceneKey, String sceneKind, String sceneDescription, String partnerOpener, String opportunitySentence,
                String reexplanation, List<String> focusPoints, List<String> expressionExamples, String relation, String place,
                String topic, Preservation preservation, String partnerRole) {
            this(sceneKey, sceneKind, sceneDescription, partnerOpener, opportunitySentence, reexplanation, focusPoints,
                    expressionExamples, null, relation, place, topic, preservation, partnerRole);
        }
        /** 친구 첫 대사가 없는 옛 모양(생성 판 v5까지 · 저장된 행). */
        public Scene(String sceneKey, String sceneKind, String sceneDescription, String opportunitySentence, String reexplanation,
                List<String> focusPoints, List<String> expressionExamples, String relation, String place, String topic,
                Preservation preservation, String partnerRole) {
            this(sceneKey, sceneKind, sceneDescription, null, opportunitySentence, reexplanation, focusPoints, expressionExamples,
                    null, relation, place, topic, preservation, partnerRole);
        }
        public Scene withPartnerRole(String role) {
            return new Scene(sceneKey, sceneKind, sceneDescription, partnerOpener, opportunitySentence, reexplanation,
                    focusPoints, expressionExamples, expressionFrames, relation, place, topic, preservation, role);
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
