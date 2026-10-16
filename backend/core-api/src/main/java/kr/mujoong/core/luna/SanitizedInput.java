package kr.mujoong.core.luna;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공급자로 실제 나가는 입력 (U9 entities SanitizedInput · BR2.1 ~ BR2.4). 문자열 표현은 본문을 숨긴다(BR6.2).
 *
 * @param payload 금지 키 제거 · 별칭 치환 · 연락처 가림을 마친 데이터
 * @param sentFieldNames payload 최상위 키 이름(감사 로그 {@code sentFields})
 */
public record SanitizedInput(Map<String, Object> payload, List<String> sentFieldNames) {

    public SanitizedInput {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        sentFieldNames = List.copyOf(sentFieldNames);
    }

    @Override
    public String toString() {
        return "SanitizedInput[redacted]";
    }
}
