package kr.mujoong.core.practice;

import java.text.Normalizer;
import java.util.Locale;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.textai.GeneratedScenario;

/** Conservative structural validation. No unverified model-supplied validation flags are trusted. */
public final class ScenarioContentPolicy {
    public static final String IMAGINARY_NOTICE = "지금부터는 상상 속 상황이야. ";
    public static final String PARTNER_ROLE = "제시된 상황의 상대 역할을 맡아. 아이의 답을 대신 말하거나 평가하지 마.";
    private ScenarioContentPolicy() {}
    public static boolean valid(GeneratedScenario generated, String relation, String opportunity) {
        if (generated == null || generated.scenarioText() == null || generated.triggerScript() == null) return false;
        return relation.equals(generated.relationType())
                && opportunity.equals(generated.opportunityCondition())
                && normalize(generated.scenarioText()).contains(normalize(opportunity))
                && !normalize(generated.triggerScript()).isBlank()
                && normalize(generated.scenarioText()).contains(normalize(generated.triggerScript()));
    }
    public static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replace(IMAGINARY_NOTICE.strip(), "")
                .replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }
    /** 아이별 같은 상황 재사용을 막는 지문. 기회 트리거는 본문에 들어 있어야 하므로(valid) 본문만 본다(U5a 리뷰 9). */
    public static String fingerprint(String scene) {
        return JsonCodec.shared().sha256OfCanonical(JsonCodec.shared().write(
                java.util.List.of(normalize(scene))));
    }
    public static String childText(String text) {
        return text.startsWith(IMAGINARY_NOTICE) ? text : IMAGINARY_NOTICE + text;
    }
}
