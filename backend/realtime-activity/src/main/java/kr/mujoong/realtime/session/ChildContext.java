package kr.mujoong.realtime.session;

import java.util.List;

/**
 * 세션 패키지의 아이 맥락(계약 5절 childContext · BR6.5 · NFR3.1 · SD-20).
 *
 * <p>필드는 표시 이름 · 나이 · 관심사 셋뿐이다. 08 입력 원문 · 보호자 정보 · 판정 기준은 이 자리에 들어올 수 없다 — 레코드에
 * 그 필드가 없다. {@code realtime_activity_session.child_context_json}에 이 모양 그대로 저장한다.
 *
 * @param displayName 표시 이름
 * @param age 나이(0 이상, 없으면 null)
 * @param interests 관심사(없으면 빈 목록)
 */
public record ChildContext(String displayName, Integer age, List<String> interests) {

    public ChildContext {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("childContext.displayName이 비어 있다");
        }
        if (age != null && age < 0) {
            throw new IllegalArgumentException("childContext.age는 0 이상이어야 한다");
        }
        interests = interests == null ? List.of() : List.copyOf(interests);
    }
}
