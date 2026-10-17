package kr.mujoong.core.knowledge;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;

/** BR4: 원문은 서버 검사에만 쓰고 예외/문자열 표현으로 내보내지 않는다. */
public final class SeedContentValidator {
    private static final Set<String> FAMILIES = Set.of("indirect_meaning", "listener_knowledge", "information_quantity",
            "repair_clarification", "topic_management", "reciprocity_responsiveness", "listener_relationship_adaptation", "OTHER");
    private static final Pattern PERSONAL = Pattern.compile(
            "(?:[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|\\d[\\d -]{7,}\\d|\\d{2,4}[./-]\\d{1,2}[./-]\\d{1,2}|"
            + "[가-힣]{2,}(?:초등학교|중학교|고등학교|대학교|학원|병원)|[가-힣]+(?:로|길)\\s*\\d+)");
    private final ForbiddenExpressionPolicy forbidden;
    public SeedContentValidator(ForbiddenExpressionPolicy forbidden) { this.forbidden = forbidden; }

    public KnowledgeSeed generated(String id, Map<?, ?> output, List<String> rawTexts, List<String> names) {
        if (!"GENERATED".equals(output.get("outcome")) || !"LOW".equals(output.get("misreadCost"))
                || !Boolean.TRUE.equals(output.get("recoverable"))) throw invalid();
        Map<?, ?> signature = output.get("signature") instanceof Map<?, ?> value ? value : Map.of();
        String label = checked(output, "skillName", rawTexts, names);
        String sentence = checked(output, "invariantSentence", rawTexts, names);
        String rule = checked(output, "preservationRule", rawTexts, names);
        String inference = checked(signature, "required_inference", rawTexts, names);
        if (sentence.codePointCount(0, sentence.length()) > 60 || sentence.matches("(?s).*[.!?。！？].*\\S.*")
                || sentence.startsWith("-") || sentence.matches("^[0-9]+[.)].*")) throw invalid();
        String family = value(output, "family");
        if (!FAMILIES.contains(family)) throw invalid();
        return new KnowledgeSeed(id, KnowledgeSeed.Origin.GENERATED, label,
                new StructureSignature(value(signature, "target_skill"), inference, value(signature, "perspective_structure"),
                        value(signature, "decidable_by_evidence"), value(signature, "direct_check_allowed")),
                sentence, family, rule, value(output, "relationship"), value(output, "situation"), true);
    }

    public String checked(Map<?, ?> data, String key, List<String> raw, List<String> names) {
        String text = value(data, key);
        if (text.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT)) throw invalid();
        text = Normalizer.normalize(text, Normalizer.Form.NFKC).strip();
        String compact = compact(text);
        if (!forbidden.isAllowed(text) || PERSONAL.matcher(text).find()
                || names.stream().filter(n -> n != null && !n.isBlank()).map(SeedContentValidator::compact).anyMatch(compact::contains)
                || raw.stream().anyMatch(source -> overlaps(compact, compact(source)))) throw invalid();
        return text;
    }

    static boolean overlaps(String output, String raw) {
        int[] points = output.codePoints().toArray();
        for (int i = 0; i + 5 <= points.length; i++) {
            if (raw.contains(new String(points, i, 5))) return true;
        }
        return false;
    }

    static String compact(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        Normalizer.normalize(text, Normalizer.Form.NFKC).codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString();
    }

    private static String value(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String text) || text.isBlank()) throw invalid();
        return text;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Seed content rejected"); }
}
