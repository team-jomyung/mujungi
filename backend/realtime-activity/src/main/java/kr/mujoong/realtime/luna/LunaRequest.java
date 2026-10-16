package kr.mujoong.realtime.luna;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 판정 턴(U11)이 Luna 호출부에 넘기는 요청 한 건 — realtime (U9 entities LunaRequest · BR2.5). 문자열 표현은 본문을 숨긴다(BR6.2).
 * realtime은 아이 이름을 받지 않으므로 별칭 표는 보통 비어 있다(functional-design 가정 7).
 *
 * @param purpose 목적(지금은 JUDGMENT_LIVE뿐)
 * @param promptVersion 보낸 프롬프트 판
 * @param instructions 판 리소스의 시스템 지시. 요청 {@code instructions} 자리로만 간다
 * @param input 데이터({@code ai-judgment-request.schema.json} 모양). 요청 {@code input} 자리에 JSON 문자열로 간다
 * @param aliases 별칭 표(비면 치환 없음)
 * @param deadline 호출자 마감({@code MujoongClock} 기준)
 */
public record LunaRequest(LunaPurpose purpose, String promptVersion, String instructions, Map<String, Object> input,
        Map<String, String> aliases, Instant deadline) {

    private static final Pattern PROMPT_VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public LunaRequest {
        Objects.requireNonNull(purpose, "purpose");
        if (promptVersion == null || !PROMPT_VERSION.matcher(promptVersion).matches()) {
            throw new IllegalArgumentException("promptVersion 형식이 올바르지 않다");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions가 비었다");
        }
        Objects.requireNonNull(input, "input");
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        aliases = ordered(aliases);
        Objects.requireNonNull(deadline, "deadline");
    }

    /** 별칭 표를 치환 순서(긴 이름 먼저, 같으면 사전순)로 고정한 읽기 전용 사본. 빈 이름 · null 값은 뺀다(검토 R-01). */
    private static Map<String, String> ordered(Map<String, String> aliases) {
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> alias : LunaInputSanitizer.orderedAliases(aliases)) {
            copy.put(alias.getKey(), alias.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String toString() {
        return "LunaRequest[redacted]";
    }
}
