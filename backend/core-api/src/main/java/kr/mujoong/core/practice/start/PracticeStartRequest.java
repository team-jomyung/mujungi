package kr.mujoong.core.practice.start;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.error.CoreApiException;

public record PracticeStartRequest(UUID idempotencyKey, List<UUID> goalSelectionIds) {
    /** 한 번에 연습할 목표 수 상한(2026-10-27 사람 결정 3 → 1, contracts/deviations.md FT-1027-a). */
    public static final int MAX_GOALS = 1;
    public PracticeStartRequest {
        if (idempotencyKey == null || goalSelectionIds == null || goalSelectionIds.isEmpty() || goalSelectionIds.size() > MAX_GOALS
                || goalSelectionIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(goalSelectionIds).size() != goalSelectionIds.size()) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        goalSelectionIds = goalSelectionIds.stream().sorted().toList();
    }
    public String requestHash() {
        return JsonCodec.shared().sha256OfCanonical(JsonCodec.shared().write(
                Map.of("activityType", "PRACTICE", "goalSelectionIds", goalSelectionIds)));
    }
}
