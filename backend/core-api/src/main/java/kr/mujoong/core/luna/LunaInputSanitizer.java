package kr.mujoong.core.luna;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 보내기 전 입력 정리 (U9 BR2.1 ~ BR2.4 · SD1 · NFR3.1 · NFR3.2).
 *
 * <ol>
 *   <li>입력을 JSON 값(객체 · 배열 · 문자열 · 수 · 참거짓 · null)으로 한 번 바꾼다(레코드 · UUID 등도 같은 모양이 된다).
 *   <li>모든 깊이의 객체에서 금지 키(contracts/ai-io.md 10.2 목록 + 기존 ResultTextGateway PRIVATE_KEYS)를 지운다. 대소문자와 {@code _} · {@code -}는 가리지 않는다.
 *   <li>모든 문자열 값에서 별칭 표의 이름을 별칭('아이')으로 바꾸고(긴 이름 먼저, 한 번만 훑음), 이메일 · 휴대전화 번호 모양을 '연락처'로 가린다.
 *   <li>결과를 독립된 두 번째 판정으로 한 번 더 훑어 금지 키가 남았으면 {@link LunaFailureReason#FORBIDDEN_INPUT}으로 끝낸다(공급자 미호출).
 * </ol>
 *
 * <p>UUID → '참조N' 참조화와 복원은 호출자(지금은 ResultTextGateway)가 맡는다(BR2.3 · 검토 R-01). 별칭 표 밖 이름은 잔여 위험이다
 * (security-requirements 1절). 예외 메시지에 입력 값을 넣지 않는다(BR6.2).
 */
public final class LunaInputSanitizer {

    /** 어느 깊이에서도 보내지 않는 키(ai-io.md 10.2 · ResultTextGateway PRIVATE_KEYS). 정규화한 꼴(소문자, {@code _} · {@code -} 없음)이다. */
    public static final Set<String> FORBIDDEN_KEYS = Set.of(
            "childid", "guardianid", "sessionid", "deviceid", "apikey", "audio", "audiorefs", "audiorefsjson", "email",
            "interests", "consent", "consents", "consentstatus", "guardianinput", "guardiannote", "token", "password",
            "secret", "childname");

    /** 별칭 치환 순서: 긴 이름 먼저, 길이가 같으면 사전순(검토 R-01). */
    private static final Comparator<Map.Entry<String, String>> ALIAS_ORDER =
            Comparator.<Map.Entry<String, String>>comparingInt(alias -> alias.getKey().length()).reversed()
                    .thenComparing(Map.Entry::getKey);

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
        List<Map.Entry<String, String>> usableAliases = orderedAliases(aliases);
        Map<String, Object> cleaned = (Map<String, Object>) clean(plain, usableAliases);
        if (containsForbiddenKey(cleaned)) {
            throw new LunaException(LunaFailureReason.FORBIDDEN_INPUT, 0);
        }
        return new SanitizedInput(cleaned, new ArrayList<>(cleaned.keySet()));
    }

    /** U10 Q1-A: 두 상황 목적의 최상위 interests만 허용한다. 일반 API·중첩·다른 목적 금지는 그대로다. */
    public SanitizedInput sanitize(Map<String,Object> input,Map<String,String> aliases,LunaPurpose purpose) {
        var general=sanitize(input,aliases);
        if(purpose!=LunaPurpose.SCENARIO_GENERATION&&purpose!=LunaPurpose.SCENARIO_REVIEW)return general;
        if(!(input.get("interests") instanceof Collection<?> values))return general;
        var strings=values.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(s->!s.isBlank()&&s.length()<=80&&s.matches("[\\p{L}\\p{N} _-]+"))
                .limit(10).toList();
        var masked=sanitize(Map.of("values",strings),aliases).payload().get("values");
        var clean=new LinkedHashMap<>(general.payload());clean.put("interests",masked);
        return new SanitizedInput(clean,new ArrayList<>(clean.keySet()));
    }

    /**
     * 별칭 표를 치환 순서로 정리한다(검토 R-01). 빈 이름 · null 값은 뺀다. 긴 이름이 먼저이고 길이가 같으면 사전순이라 호출자가 준 표의 순서와
     * 관계없이 결과가 같다. 이름끼리 포함 관계(예: '민수'와 '수')여도 긴 이름이 통째로 먼저 바뀐다.
     */
    public static List<Map.Entry<String, String>> orderedAliases(Map<String, String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        return aliases.entrySet().stream()
                .filter(alias -> alias.getKey() != null && !alias.getKey().isBlank() && alias.getValue() != null)
                .sorted(ALIAS_ORDER)
                .map(alias -> Map.entry(alias.getKey(), alias.getValue()))
                .toList();
    }

    /** 이름이 금지 키인가. 대소문자와 {@code _} · {@code -}를 가리지 않는다(예: {@code child_name} · {@code Child-Id}, 검토 R-02). */
    public static boolean isForbiddenKey(String key) {
        return key != null && FORBIDDEN_KEYS.contains(normalizeKey(key));
    }

    /** 1차 판정의 키 정규화: 소문자로 바꾸고 {@code _} · {@code -}를 지운다. */
    static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private Object clean(Object value, List<Map.Entry<String, String>> aliases) {
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

    /**
     * 왼쪽부터 한 번만 훑으며 각 자리에서 가장 긴 이름을 별칭으로 바꾼다(검토 R-01, BR2.2 · NFR3.1). 바꿔 넣은 별칭('아이')은 다시 훑지 않으므로
     * 짧은 이름이 별칭 안에서 다시 걸리지 않고, '민수'가 '민아이'처럼 조각으로 남지 않는다.
     *
     * @param aliases {@link #orderedAliases(Map)}가 정리한 표
     */
    static String applyAliases(String text, List<Map.Entry<String, String>> aliases) {
        if (aliases.isEmpty()) {
            return text;
        }
        StringBuilder replaced = new StringBuilder(text.length());
        int position = 0;
        while (position < text.length()) {
            Map.Entry<String, String> match = matchAt(text, position, aliases);
            if (match == null) {
                replaced.append(text.charAt(position));
                position++;
            } else {
                replaced.append(match.getValue());
                position += match.getKey().length();
            }
        }
        return replaced.toString();
    }

    private static Map.Entry<String, String> matchAt(String text, int position, List<Map.Entry<String, String>> aliases) {
        for (Map.Entry<String, String> alias : aliases) {
            if (text.startsWith(alias.getKey(), position)) {
                return alias;
            }
        }
        return null;
    }

    private static String maskContacts(String text) {
        return MOBILE.matcher(EMAIL.matcher(text).replaceAll(CONTACT)).replaceAll(CONTACT);
    }

    /**
     * 값 트리 어느 깊이에든 금지 키가 있는가(정리 뒤 안전망, BR2.4). 1차 판정({@link #isForbiddenKey})을 다시 쓰지 않는 독립된 두 번째 검사다(검토
     * R-02): 키에서 글자 · 숫자가 아닌 문자를 모두 지운 소문자 꼴로 금지 목록과 비교하므로 1차가 놓친 변형(예: {@code child.name})도 걸린다.
     */
    static boolean containsForbiddenKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (looksForbidden(String.valueOf(entry.getKey())) || containsForbiddenKey(entry.getValue())) {
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

    /** 안전망의 키 판정: 글자 · 숫자만 남긴 소문자 꼴이 금지 목록에 있는가. */
    private static boolean looksForbidden(String key) {
        StringBuilder letters = new StringBuilder(key.length());
        key.codePoints().filter(Character::isLetterOrDigit).forEach(letters::appendCodePoint);
        String folded = letters.toString().toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_KEYS) {
            if (forbidden.equals(folded)) {
                return true;
            }
        }
        return false;
    }
}
