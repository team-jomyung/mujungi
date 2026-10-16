package kr.mujoong.core.luna;

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
 * 보내기 전 입력 정리 (U9 BR2.1 ~ BR2.4 · SD1 · NFR3.1 · NFR3.2).
 *
 * <ol>
 *   <li>입력을 JSON 값(객체 · 배열 · 문자열 · 수 · 참거짓 · null)으로 한 번 바꾼다(레코드 · UUID 등도 같은 모양이 된다).
 *   <li>모든 깊이의 객체에서 금지 키(contracts/ai-io.md 10.2 목록 + 기존 ResultTextGateway PRIVATE_KEYS)를 지운다. 대소문자는 가리지 않는다.
 *   <li>모든 문자열 값에서 별칭 표의 이름을 별칭('아이')으로 바꾸고, 이메일 · 휴대전화 번호 모양을 '연락처'로 가린다.
 *   <li>결과를 한 번 더 훑어 금지 키가 남았으면 {@link LunaFailureReason#FORBIDDEN_INPUT}으로 끝낸다(공급자 미호출).
 * </ol>
 *
 * <p>UUID → '참조N' 참조화와 복원은 호출자(지금은 ResultTextGateway)가 맡는다(BR2.3 · 검토 R-01). 별칭 표 밖 이름은 잔여 위험이다
 * (security-requirements 1절). 예외 메시지에 입력 값을 넣지 않는다(BR6.2).
 */
public final class LunaInputSanitizer {

    /** 어느 깊이에서도 보내지 않는 키(ai-io.md 10.2 · ResultTextGateway PRIVATE_KEYS, 소문자로 비교). */
    public static final Set<String> FORBIDDEN_KEYS = Set.of(
            "childid", "guardianid", "sessionid", "deviceid", "apikey", "audio", "audiorefs", "audiorefsjson", "email",
            "interests", "consent", "consents", "consentstatus", "guardianinput", "guardiannote", "token", "password",
            "secret", "childname");

    /** 연락처로 가리는 문자열 모양(ResultTextGateway와 같은 규칙). */
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
            return maskContacts(applyAliases(text, aliases));
        }
        return value;
    }

    private static String applyAliases(String text, Map<String, String> aliases) {
        String replaced = text;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            replaced = replaced.replace(alias.getKey(), alias.getValue());
        }
        return replaced;
    }

    private static String maskContacts(String text) {
        return MOBILE.matcher(EMAIL.matcher(text).replaceAll(CONTACT)).replaceAll(CONTACT);
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
