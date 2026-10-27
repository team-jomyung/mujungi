package kr.mujoong.core.usage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Luna 전송 시도 한 번의 사용량(원문 없음). 토큰 값 null은 미확인이며 실제 0과 다르다. 캐시 읽기({@code cachedInputTokens})와
 * 캐시 쓰기({@code cacheWriteTokens})는 서로 겹치지 않는 입력 토큰의 일부이고 단가가 다르다.
 *
 * @param id 행 키. 같은 기록을 다시 저장해도 한 행만 남는다
 * @param callId 한 호출(재시도 포함)을 묶는 값
 * @param purpose Luna 호출 목적 이름
 * @param link 활동 연결. 없으면 연결 미확인 호출이다
 * @param outcome 시도 결과({@code OK} 또는 실패 사유 코드)
 * @param observedAt 시도가 끝난 시각(기간 합계 기준)
 */
public record AiUsageRecord(UUID id, UUID callId, int attemptNo, String purpose, AiUsageLink link, String promptVersion,
        String model, String responseId, String outcome, Long inputTokens, Long outputTokens, Long cachedInputTokens,
        Long cacheWriteTokens, Long reasoningTokens, Status status, Instant observedAt) {

    public enum Status { UNKNOWN, PARTIAL, COMPLETE }

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

    public AiUsageRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        if (attemptNo < 1 || attemptNo > 3) {
            throw new IllegalArgumentException("시도 번호는 1 ~ 3이다");
        }
        if (purpose == null || !CODE.matcher(purpose).matches() || outcome == null || !CODE.matcher(outcome).matches()) {
            throw new IllegalArgumentException("목적 · 결과는 고정 코드다");
        }
        if (promptVersion == null || promptVersion.isBlank() || promptVersion.length() > 64) {
            throw new IllegalArgumentException("프롬프트 판이 올바르지 않다");
        }
        for (String value : new String[] {model, responseId}) {
            if (value != null && value.length() > 200) {
                throw new IllegalArgumentException("사용량 식별자 길이 초과");
            }
        }
        if (invalid(inputTokens) || invalid(outputTokens) || invalid(cachedInputTokens) || invalid(cacheWriteTokens)
                || invalid(reasoningTokens)) {
            throw new IllegalArgumentException("사용량은 0 이상이다");
        }
        if (cachedInputTokens != null && inputTokens != null && cachedInputTokens > inputTokens
                || cacheWriteTokens != null && inputTokens != null
                        && (cachedInputTokens == null ? 0 : cachedInputTokens) + cacheWriteTokens > inputTokens
                || reasoningTokens != null && outputTokens != null && reasoningTokens > outputTokens) {
            throw new IllegalArgumentException("세부 토큰은 전체 토큰을 넘을 수 없다");
        }
        if (status == Status.COMPLETE && (inputTokens == null || outputTokens == null)) {
            throw new IllegalArgumentException("완료 사용량에 입력 · 출력 토큰이 없다");
        }
    }

    private static boolean invalid(Long value) {
        return value != null && value < 0;
    }

    @Override
    public String toString() {
        return "AiUsageRecord[purpose=" + purpose + ", attempt=" + attemptNo + ", status=" + status + "]";
    }
}
