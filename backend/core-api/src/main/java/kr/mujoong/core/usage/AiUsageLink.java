package kr.mujoong.core.usage;

import java.util.Objects;
import java.util.UUID;

/**
 * 사용량을 붙일 대상(서버 내부 값). 공급자 요청 본문에는 넣지 않는다.
 *
 * @param guardianId 계정 삭제 때 사용량을 함께 지우는 기준
 * @param sessionId 활동 또는 연습 준비 ID. 준비가 성공하면 같은 ID로 활동이 시작된다
 */
public record AiUsageLink(UUID guardianId, UUID sessionId) {

    public AiUsageLink {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
