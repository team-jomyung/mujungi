package kr.mujoong.core.practice;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.textai.GeneratedScenario;

/** Conservative structural validation. No unverified model-supplied validation flags are trusted. */
public final class ScenarioContentPolicy {
    public static final String IMAGINARY_NOTICE = "지금부터는 상상 속 상황이야. ";
    /** 레거시 C10 {@code partnerRoleText}의 고정 역할 지시. 새 흐름 장면의 {@code partnerRole}에는 쓰지 않는다. */
    public static final String PARTNER_ROLE = "제시된 상황의 상대 역할을 맡아. 아이의 답을 대신 말하거나 평가하지 마.";
    /** 장면 {@code partnerRole}(짧은 역할 이름)의 상한(코드 포인트)과 대체값. 금지 규칙은 realtime 고정 지시가 맡는다. */
    public static final int PARTNER_ROLE_MAX = 20;
    public static final String DEFAULT_PARTNER_ROLE = "친구";
    private static final Map<String, String> RELATION_LABELS = Map.of(
            "PEER", "친구",
            "FRIEND_CASUAL", "친구",
            "FRIEND_CLOSE", "친한 친구",
            "CLASSMATE", "같은 반 친구",
            "CLUB_PEER", "동아리 친구",
            "CLUB_SENIOR", "동아리 선배",
            "CLUB_JUNIOR", "동아리 후배",
            "TEAMMATE", "같은 팀 친구",
            "TEACHER", "선생님",
            "FAMILY", "가족");
    private ScenarioContentPolicy() {}

    /**
     * 장면 관계에서 상대 역할 이름을 만든다. 관계 코드는 표로 바꾸고, 후보 목표의 자유 문구는 짧은 명사구일 때만 그대로 쓴다.
     * 지시문처럼 보이는 값(문장부호 · 제어 문자 · 상한 초과)은 대체값으로 접어 realtime 지시문 "너는 아이의 … 역할이다"가 깨지지 않게 한다.
     */
    public static String partnerRoleName(String relation) {
        if (relation == null) {
            return DEFAULT_PARTNER_ROLE;
        }
        String name = relation.strip().replaceAll("\\s+", " ");
        if (name.matches("[A-Z_]+")) {
            return RELATION_LABELS.getOrDefault(name, DEFAULT_PARTNER_ROLE);
        }
        boolean shortName = !name.isEmpty() && name.codePointCount(0, name.length()) <= PARTNER_ROLE_MAX;
        boolean plain = name.codePoints().noneMatch(c -> Character.isISOControl(c) || ".?!。".indexOf(c) >= 0);
        return shortName && plain ? name : DEFAULT_PARTNER_ROLE;
    }
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
