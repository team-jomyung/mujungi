package kr.mujoong.realtime.luna;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 보내기 전 입력 정리 — realtime (U9 BR2.1 ~ BR2.4 · SD1 · NFR3.1 · NFR3.2). core에 같은 규칙의 클래스가 따로 있다(코드 공유 없음, R-F5).
 *
 * <ol>
 *   <li>입력을 JSON 값으로 한 번 바꾼다.
 *   <li>모든 깊이의 객체에서 금지 키(contracts/ai-io.md 10.2 목록)를 지운다. 대소문자는 가리지 않는다.
 *   <li>모든 문자열 값에서 별칭 표의 이름을 별칭으로 바꾸고, 이메일 · 휴대전화 번호 모양을 '연락처'로 가린다.
 *   <li>결과에 금지 키가 남았으면 {@link LunaFailureReason#FORBIDDEN_INPUT}으로 끝낸다(공급자 미호출).
 * </ol>
 *
 * <p>요청 스키마({@code ai-judgment-request}) 검증은 호출부가 정리 결과에 한다(BR2.4). 예외 메시지에 입력 값을 넣지 않는다(BR6.2).
 */
public final class LunaInputSanitizer {

    /** 어느 깊이에서도 보내지 않는 키(ai-io.md 10.2, 소문자로 비교). core 목록과 같은 값이다. */
    public static final Set<String> FORBIDDEN_KEYS = Set.of(
            "childid", "guardianid", "sessionid", "deviceid", "apikey", "audio", "audiorefs", "audiorefsjson", "email",
            "interests", "consent", "consents", "consentstatus", "guardianinput", "guardiannote", "token", "password",
            "secret", "childname");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}(?![0-9])");
    private static final String CONTACT = "연락처";

    private final JsonCodec json;

    public LunaInputSanitizer(JsonCodec json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 입력을 정리한다.
     *
     * @throws LunaException 금지 키가 남거나 입력을 JSON 객체로 바꿀 수 없으면 {@code FORBIDDEN_INPUT}(시도 0)
     */
    @SuppressWarnings("unchecked")
    public SanitizedInput sanitize(Map<String, Object> input, Map<String, String> aliases) {
        Map<String, Object> plain;
        try {
            plain = json.read(json.write(input), Map.class);
        } catch (RuntimeException e) {
            // 입력을 JSON으로 바꾸지 못하면 보내지 않는다(원인 메시지는 입력을 담을 수 있어 버린다, BR6.2)
            throw new LunaException(LunaFailureReason.FORBIDDEN_INPUT, 0);
        }
        if (plain == null) {
            throw new LunaException(LunaFailureReason.FORBIDDEN_INPUT, 0);
        }
        Map<String, String> usableAliases = aliases == null ? Map.of() : aliases.entrySet().stream()
                .filter(alias -> alias.getKey() != null && !alias.getKey().isBlank() && alias.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        Map<String, Object> cleaned = (Map<String, Object>) clean(plain, usableAliases);
        if (containsForbiddenKey(cleaned)) {
            throw new LunaException(LunaFailureReason.FORBIDDEN_INPUT, 0);
        }
        return new SanitizedInput(cleaned, new ArrayList<>(cleaned.keySet()));
    }

    /** 이름이 금지 키인가(대소문자 무시). */
    public static boolean isForbiddenKey(String key) {
        return key != null && FORBIDDEN_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    private Object clean(Object value, Map<String, String> aliases) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                String name = String.valueOf(key);
                if (!isForbiddenKey(name)) {
                    cleaned.put(name, clean(child, aliases));
                }
            });
            return cleaned;
        }
        if (value instanceof Collection<?> list) {
            List<Object> cleaned = new ArrayList<>(list.size());
            for (Object child : list) {
                cleaned.add(clean(child, aliases));
            }
            return cleaned;
        }
        if (value instanceof String text) {
            String replaced = text;
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                replaced = replaced.replace(alias.getKey(), alias.getValue());
            }
            return MOBILE.matcher(EMAIL.matcher(replaced).replaceAll(CONTACT)).replaceAll(CONTACT);
        }
        return value;
    }

    /** 값 트리 어느 깊이에든 금지 키가 있는가(정리 뒤 안전망, BR2.4). */
    static boolean containsForbiddenKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isForbiddenKey(String.valueOf(entry.getKey())) || containsForbiddenKey(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> list) {
            return list.stream().anyMatch(LunaInputSanitizer::containsForbiddenKey);
        }
        return false;
    }
}
