package kr.mujoong.core.luna;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 호출자가 Luna 호출부에 넘기는 요청 한 건 (U9 entities LunaRequest · BR2.5). 문자열 표현은 본문을 숨긴다(BR6.2).
 *
 * @param purpose 목적(재시도 분류 · 출력 스키마 · 시한 키)
 * @param promptVersion 보낸 프롬프트 판(감사 로그 · 결과에 붙음)
 * @param instructions 버전 파일에서 읽은 시스템 지시. 요청 {@code instructions} 자리로만 간다
 * @param input 데이터. 정리(금지 키 · 별칭 · 연락처) 뒤 요청 {@code input} 자리에 JSON 문자열로 간다
 * @param aliases 아이 표시 이름 → '아이' 같은 별칭 표(비면 치환 없음). 긴 이름 먼저인 순서로 고정해 보관한다
 * @param deadline 호출자 마감({@code MujoongClock} 기준)
 * @param outputMode 계약 스키마로 받을지, 기존 인터페이스 어댑터처럼 JSON 객체로 한 번만 받을지
 */
public record LunaRequest(LunaPurpose purpose, String promptVersion, String instructions, Map<String, Object> input,
        Map<String, String> aliases, Instant deadline, OutputMode outputMode) {

    private static final Pattern PROMPT_VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /** 출력 요청 방식. */
    public enum OutputMode {
        /** 목적의 출력 스키마(있으면 json_schema, 없으면 json_object)와 목적의 재시도 분류를 쓴다. */
        CONTRACT,
        /** 기존 인터페이스 어댑터: json_object 1회, 재시도 없음(BR3.4 · BR4.2). */
        LEGACY_JSON_OBJECT
    }

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
        Objects.requireNonNull(outputMode, "outputMode");
    }

    /** 계약 스키마 요청(U10 · U14 · U15가 직접 부를 때). */
    public static LunaRequest of(LunaPurpose purpose, String promptVersion, String instructions, Map<String, Object> input,
            Map<String, String> aliases, Instant deadline) {
        return new LunaRequest(purpose, promptVersion, instructions, input, aliases, deadline, OutputMode.CONTRACT);
    }

    /** 기존 인터페이스 어댑터 요청(json_object 1회, BR3.4). */
    public static LunaRequest legacy(LunaPurpose purpose, String promptVersion, String instructions, Map<String, Object> input,
            Map<String, String> aliases, Instant deadline) {
        return new LunaRequest(purpose, promptVersion, instructions, input, aliases, deadline, OutputMode.LEGACY_JSON_OBJECT);
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
