package kr.mujoong.realtime.practiceflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;

/**
 * U10 대본을 새 흐름이 읽는 타입 (U12 FR1.3 · BR1.3, {@code contracts/schemas/practice-scenario.schema.json}의 {@code scriptVersion ·
 * scenes[] · selfReview · generation}). 레거시 {@code practice.PracticeScenarioClient.Scenario}와 별개이며 그쪽은 건드리지 않는다(BR10.1).
 * 읽기는 {@link PracticeScriptReader}가 하고, 선택 필드가 없으면 빈 값으로 정규화된 이 값을 만든다.
 *
 * <p>장면 문장은 승인 원문이라 문자열 표현 · 로그 · 기록에 내지 않는다(R-F14).
 *
 * @param scenarioId 대본 전체의 식별자(기록의 scenarioRef)
 * @param scriptVersion 대본 판({@value #SCRIPT_VERSION_V1} 또는 C안 칸이 있는 {@value #SCRIPT_VERSION_V2})
 * @param scenes 장면(원 장면 BASE가 하나 이상, 변형 VARIANT는 없을 수 있다)
 */
public record PracticeScript(UUID scenarioId, String scriptVersion, List<Scene> scenes, SelfReview selfReview,
        Generation generation) {

    /** 새 흐름이 읽는 대본 판(C안 칸이 없는 옛 판). */
    public static final String SCRIPT_VERSION_V1 = "scenario-script.v1";
    /** C안 칸(opportunityRelay · askPrompt · reaskPrompt · helpSpeech · friendReaction)이 있는 판(docs/practice-direct-ask.md 4.1). */
    public static final String SCRIPT_VERSION_V2 = "scenario-script.v2";

    /** 새 흐름이 읽는 판인가(v1 · v2). realtime이 두 판을 모두 읽은 뒤에 core가 v2를 낸다(배포 순서). */
    public static boolean readable(String scriptVersion) {
        return SCRIPT_VERSION_V1.equals(scriptVersion) || SCRIPT_VERSION_V2.equals(scriptVersion);
    }

    public PracticeScript {
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(scriptVersion, "scriptVersion");
        scenes = List.copyOf(Objects.requireNonNull(scenes, "scenes"));
        selfReview = selfReview == null ? SelfReview.EMPTY : selfReview;
        generation = generation == null ? Generation.EMPTY : generation;
        if (scenes.stream().noneMatch(scene -> scene.sceneKind() == SceneKind.BASE)) {
            throw new IllegalArgumentException("원 장면(BASE)이 없다");
        }
    }

    /**
     * 장면 하나. 필수 6필드는 비어 있지 않다. 선택 필드(focusPoints · expressionExamples · relation · place · topic)가 없으면 빈 값이다.
     * 들리는 선택 칸(spokenIntroduction · openingLine과 C안 칸 다섯 개)은 없으면 null이다 — 쓰는 쪽이 대체 문장을 고른다
     * ({@link DirectAskLines}). 옛 대본의 돌아보기 질문 · 요약(reflectionPrompt · reflectionSummary)은 읽지 않는다(FT-1023-g).
     *
     * @param sceneKey 대본 안 장면 식별(장면 식별은 이 값뿐이다, 장면별 UUID 없음)
     * @param sceneDescription 상황 문장(내부 사실. C안에서는 들려주지 않는다)
     * @param opportunitySentence 기회 문장(상대의 직접 대사. C안에서는 들려주지 않는다)
     * @param reexplanation 상황 다시 설명 문장
     * @param partnerRole 상대 역할 문구(core가 발급 때 덮어쓴 값)
     * @param openingLine 장면 안내 뒤 상대가 먼저 거는 여는 말(승인 문장, 선택). 없으면 바로 기회 문장. 상대 역할의 말이라 유연 대화 경로만 읽는다
     *     (C안은 읽지 않는다)
     * @param opportunityRelay C안: 상대가 한 말을 무중이 자기 말로 전하는 문장
     * @param askPrompt C안: "너라면 …?" 묻는 말 한 문장
     * @param reaskPrompt C안: 일부만 전했을 때 되묻는 질문
     * @param helpSpeech C안: 도움(요점 + 표현 예시 + 고정 꼬리 "네 말로 해 볼래?")
     * @param friendReaction C안: 목표를 이룬 뒤 무중이 전하는 상대의 반응
     */
    public record Scene(String sceneKey, SceneKind sceneKind, String sceneDescription, String opportunitySentence,
            String reexplanation, String partnerRole, List<String> focusPoints, List<String> expressionExamples,
            String relation, String place, String topic, String spokenIntroduction, String openingLine,
            String opportunityRelay, String askPrompt, String reaskPrompt, String helpSpeech, String friendReaction) {
        /** C안 칸이 없는 장면(옛 대본 v1 모양). */
        public Scene(String sceneKey, SceneKind sceneKind, String sceneDescription, String opportunitySentence,
                String reexplanation, String partnerRole, List<String> focusPoints, List<String> expressionExamples,
                String relation, String place, String topic, String spokenIntroduction, String openingLine) {
            this(sceneKey, sceneKind, sceneDescription, opportunitySentence, reexplanation, partnerRole, focusPoints,
                    expressionExamples, relation, place, topic, spokenIntroduction, openingLine, null, null, null, null, null);
        }

        public Scene(String sceneKey, SceneKind sceneKind, String sceneDescription, String opportunitySentence,
                String reexplanation, String partnerRole, List<String> focusPoints, List<String> expressionExamples,
                String relation, String place, String topic, String spokenIntroduction) {
            this(sceneKey,sceneKind,sceneDescription,opportunitySentence,reexplanation,partnerRole,focusPoints,
                    expressionExamples,relation,place,topic,spokenIntroduction,null);
        }

        public Scene(String sceneKey, SceneKind sceneKind, String sceneDescription, String opportunitySentence,
                String reexplanation, String partnerRole, List<String> focusPoints, List<String> expressionExamples,
                String relation, String place, String topic) {
            this(sceneKey, sceneKind, sceneDescription, opportunitySentence, reexplanation, partnerRole, focusPoints,
                    expressionExamples, relation, place, topic, null);
        }

        public Scene {
            requireText(sceneKey, "sceneKey");
            Objects.requireNonNull(sceneKind, "sceneKind");
            requireText(sceneDescription, "sceneDescription");
            requireText(opportunitySentence, "opportunitySentence");
            requireText(reexplanation, "reexplanation");
            requireText(partnerRole, "partnerRole");
            focusPoints = focusPoints == null ? List.of() : List.copyOf(focusPoints);
            expressionExamples = expressionExamples == null ? List.of() : List.copyOf(expressionExamples);
            relation = relation == null ? "" : relation;
            place = place == null ? "" : place;
            topic = topic == null ? "" : topic;
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + "이 비었다");
            }
        }

        /** 장면 문장은 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "Scene[sceneKind=" + sceneKind + "]";
        }
    }

    /** 대본 자체 검토 결과(선택). 없으면 {@link #EMPTY}. */
    public record SelfReview(String verdict, String model, String promptVersion) {

        /** 없는 선택 필드를 정규화한 빈 값. */
        public static final SelfReview EMPTY = new SelfReview("", "", "");

        public SelfReview {
            verdict = verdict == null ? "" : verdict;
            model = model == null ? "" : model;
            promptVersion = promptVersion == null ? "" : promptVersion;
        }
    }

    /** 대본 생성 메타(선택). 없으면 {@link #EMPTY}. */
    public record Generation(String model, String promptVersion, boolean graphContextUsed, int calls, String kgVersion) {

        /** 없는 선택 필드를 정규화한 빈 값. */
        public static final Generation EMPTY = new Generation("", "", false, 0, "");

        public Generation {
            model = model == null ? "" : model;
            promptVersion = promptVersion == null ? "" : promptVersion;
            kgVersion = kgVersion == null ? "" : kgVersion;
        }
    }

    /** 첫 원 장면(BASE). 생성자가 하나 이상을 보장한다. */
    public Scene baseScene() {
        return scenes.stream().filter(scene -> scene.sceneKind() == SceneKind.BASE).findFirst().orElseThrow();
    }

    /** 변형 장면(첫 VARIANT, FA4). 없으면 빈 값이고 그때 변형은 제안하지 않는다(사유 NO_VARIANT). */
    public Optional<Scene> variantScene() {
        return scenes.stream().filter(scene -> scene.sceneKind() == SceneKind.VARIANT).findFirst();
    }

    @Override
    public String toString() {
        return "PracticeScript[scenarioId=" + scenarioId + ", scenes=" + scenes.size() + "]";
    }
}
