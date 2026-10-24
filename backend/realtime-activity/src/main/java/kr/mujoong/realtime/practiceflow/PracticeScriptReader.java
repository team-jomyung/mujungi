package kr.mujoong.realtime.practiceflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeScriptException.Reason;

/**
 * U10 대본 읽기 (U12 FR1.3 · BR1.3). JSON 한 건을 {@link PracticeScript}로 바꾼다.
 *
 * <ul>
 *   <li>필수: {@code scenarioId}(UUID) · {@code scriptVersion}({@value PracticeScript#SCRIPT_VERSION_V1}) · {@code scenes[]} 1건 이상 · 장면
 *       필수 6필드 · 쓸 수 있는 원 장면(BASE) · {@code validation}(통과). 하나라도 어기면 {@link PracticeScriptException}(사유 코드만).
 *   <li>선택: {@code selfReview} · {@code generation} · 변형 장면 · 장면의 {@code focusPoints · expressionExamples · relation · place · topic}.
 *       없으면 빈 값으로 정규화한다(거절하지 않는다). 모르는 필드는 무시한다(계약 10절).
 *   <li>{@code scriptVersion}이 없으면 새 흐름 대상이 아니다({@link Reason#NO_SCRIPT_VERSION}, 분기는 BR1.4가 정한다).
 * </ul>
 *
 * <p>이 클래스는 로그를 쓰지 않고 예외에 문장을 담지 않는다. 레거시 {@code practice/} 패키지를 가져오지 않는다(BR10.2).
 */
public final class PracticeScriptReader {

    private PracticeScriptReader() {
    }

    // ---- 읽기 전용 JSON 모양(계약 필드 이름 그대로, 모든 값이 null일 수 있다) ----

    /** 장면 JSON 모양. */
    record RawScene(String sceneKey, String sceneKind, String sceneDescription, String opportunitySentence,
            String reexplanation, String partnerRole, List<String> focusPoints, List<String> expressionExamples,
            String relation, String place, String topic, String spokenIntroduction, String openingLine) {
    }

    /** 대본 검토 JSON 모양. */
    record RawSelfReview(String verdict, String model, String promptVersion) {
    }

    /** 대본 생성 메타 JSON 모양. */
    record RawGeneration(String model, String promptVersion, Boolean graphContextUsed, Integer calls, String kgVersion) {
    }

    /** 대본 검증 표시 JSON 모양. */
    record RawValidation(Boolean goalIncluded, Boolean triggerIncluded, Boolean forbiddenFound) {
    }

    /** 대본 JSON 모양. */
    record RawScript(String scenarioId, String kind, String scriptVersion, List<RawScene> scenes, RawSelfReview selfReview,
            RawGeneration generation, RawValidation validation) {
    }

    /** JSON 문자열 한 건을 읽는다. */
    public static PracticeScript read(String json) {
        return read(json, null);
    }

    /**
     * JSON 문자열 한 건을 읽고, {@code expectedKind}가 있으면 대본의 {@code kind}가 그 값(예: 다음 목표 대본 요청의 {@code ORIGINAL})과 같은지
     * 확인한다. 다르면 {@link Reason#VALIDATION_FAILED}다.
     */
    public static PracticeScript read(String json, String expectedKind) {
        if (json == null || json.isBlank()) {
            throw new PracticeScriptException(Reason.UNPARSABLE);
        }
        RawScript raw;
        try {
            raw = JsonCodec.shared().read(json, RawScript.class);
        } catch (RuntimeException e) {
            throw new PracticeScriptException(Reason.UNPARSABLE, e);
        }
        if (expectedKind != null && raw != null && !expectedKind.equals(raw.kind())) {
            throw new PracticeScriptException(Reason.VALIDATION_FAILED);
        }
        return read(raw);
    }

    /** 이미 JSON에서 읽은 대본 모양을 검사해 값으로 바꾼다(시작 패키지의 firstScenario가 쓴다). */
    static PracticeScript read(RawScript raw) {
        if (raw == null) {
            throw new PracticeScriptException(Reason.UNPARSABLE);
        }
        if (raw.scriptVersion() == null || raw.scriptVersion().isBlank()) {
            throw new PracticeScriptException(Reason.NO_SCRIPT_VERSION);
        }
        if (!PracticeScript.SCRIPT_VERSION_V1.equals(raw.scriptVersion())) {
            throw new PracticeScriptException(Reason.UNSUPPORTED_VERSION);
        }
        UUID scenarioId = uuid(raw.scenarioId());
        if (scenarioId == null) {
            throw new PracticeScriptException(Reason.BAD_SCENARIO_ID);
        }
        if (raw.scenes() == null || raw.scenes().isEmpty()) {
            throw new PracticeScriptException(Reason.NO_SCENES);
        }
        List<PracticeScript.Scene> scenes = new ArrayList<>(raw.scenes().size());
        for (RawScene scene : raw.scenes()) {
            scenes.add(scene(scene));
        }
        if (scenes.stream().noneMatch(scene -> scene.sceneKind() == SceneKind.BASE)) {
            throw new PracticeScriptException(Reason.NO_BASE_SCENE);
        }
        RawValidation validation = raw.validation();
        if (validation == null || !Boolean.TRUE.equals(validation.goalIncluded())
                || !Boolean.TRUE.equals(validation.triggerIncluded()) || !Boolean.FALSE.equals(validation.forbiddenFound())) {
            throw new PracticeScriptException(Reason.VALIDATION_FAILED);
        }
        return new PracticeScript(scenarioId, raw.scriptVersion(), scenes, selfReview(raw.selfReview()),
                generation(raw.generation()));
    }

    private static PracticeScript.Scene scene(RawScene raw) {
        if (raw == null) {
            throw new PracticeScriptException(Reason.MISSING_SCENE_FIELD);
        }
        if (blank(raw.sceneKey()) || blank(raw.sceneKind()) || blank(raw.sceneDescription())
                || blank(raw.opportunitySentence()) || blank(raw.reexplanation()) || blank(raw.partnerRole())) {
            throw new PracticeScriptException(Reason.MISSING_SCENE_FIELD);
        }
        if (raw.spokenIntroduction() != null && (blank(raw.spokenIntroduction()) || raw.spokenIntroduction().length() > 500
                || raw.spokenIntroduction().chars().anyMatch(Character::isISOControl))) {
            throw new PracticeScriptException(Reason.VALIDATION_FAILED);
        }
        // 옛 대본의 돌아보기 질문(reflectionPrompt) · 원래 대화 요약(reflectionSummary)은 모르는 필드로 무시한다(FT-1023-g)
        if (badOptional(raw.openingLine(), 200)) {
            throw new PracticeScriptException(Reason.VALIDATION_FAILED);
        }
        SceneKind kind;
        try {
            kind = SceneKind.valueOf(raw.sceneKind());
        } catch (IllegalArgumentException e) {
            throw new PracticeScriptException(Reason.BAD_SCENE_KIND, e);
        }
        return new PracticeScript.Scene(raw.sceneKey(), kind, raw.sceneDescription(), raw.opportunitySentence(),
                raw.reexplanation(), raw.partnerRole(), raw.focusPoints(), raw.expressionExamples(), raw.relation(),
                raw.place(), raw.topic(), raw.spokenIntroduction(), raw.openingLine());
    }

    private static PracticeScript.SelfReview selfReview(RawSelfReview raw) {
        return raw == null ? PracticeScript.SelfReview.EMPTY
                : new PracticeScript.SelfReview(raw.verdict(), raw.model(), raw.promptVersion());
    }

    private static PracticeScript.Generation generation(RawGeneration raw) {
        return raw == null ? PracticeScript.Generation.EMPTY
                : new PracticeScript.Generation(raw.model(), raw.promptVersion(), Boolean.TRUE.equals(raw.graphContextUsed()),
                        Objects.requireNonNullElse(raw.calls(), 0), raw.kgVersion());
    }

    /** UUID 문자열이 아니면 null. */
    static UUID uuid(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean badOptional(String value, int max) {
        return value != null && (blank(value) || value.length() > max || value.chars().anyMatch(Character::isISOControl));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
