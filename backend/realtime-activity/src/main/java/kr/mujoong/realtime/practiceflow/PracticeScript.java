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
 * @param scriptVersion 대본 판(지금은 {@value #SCRIPT_VERSION_V1} 하나)
 * @param scenes 장면(원 장면 BASE가 하나 이상, 변형 VARIANT는 없을 수 있다)
 */
public record PracticeScript(UUID scenarioId, String scriptVersion, List<Scene> scenes, SelfReview selfReview,
        Generation generation) {

    /** 새 흐름이 읽는 대본 판. */
    public static final String SCRIPT_VERSION_V1 = "scenario-script.v1";

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
     *
     * @param sceneKey 대본 안 장면 식별(장면 식별은 이 값뿐이다, 장면별 UUID 없음)
     * @param sceneDescription 상황 문장(SCENE_INTRO 승인 발화)
     * @param opportunitySentence 기회 문장
     * @param reexplanation 상황 다시 설명 문장
     * @param partnerRole 상대 역할 문구(core가 발급 때 덮어쓴 값)
     */
    public record Scene(String sceneKey, SceneKind sceneKind, String sceneDescription, String opportunitySentence,
            String reexplanation, String partnerRole, List<String> focusPoints, List<String> expressionExamples,
            String relation, String place, String topic) {

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
