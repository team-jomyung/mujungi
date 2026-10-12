package kr.mujoong.core.activity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import org.springframework.stereotype.Service;

/**
 * 04 지금 활동 · 알림 진입 재조회 (U4a ActivityCoordinator · AC1.4.7 · AC1.4.10 · AC1.4.13 · AC1.7.4 · AC1.1.6 · 설계 R9).
 *
 * <p>지금 활동 = 보호자의 진행 중(REQUESTED ~ CLOSING) 사본 하나다(V1 부분 유일 색인이라 많아야 하나). 종료 상태는 '없음'이다 — 종료 확인 미확인
 * 표시는 17 · 27에서 한다. 사본은 Realtime 이벤트로만 진행하므로(BR6.17) Realtime에 다시 묻지 않는다(12 폴링이 최신을 읽는다).
 */
@Service
public class CurrentSessionService {

    private final ActivityCopyRepository activities;

    public CurrentSessionService(ActivityCopyRepository activities) {
        this.activities = Objects.requireNonNull(activities, "activities");
    }

    /** 진행 중 세션 사본(없으면 빈 값). */
    public Optional<ActivityCopy> current(UUID guardianId) {
        return activities.findInProgressByGuardian(Objects.requireNonNull(guardianId, "guardianId"));
    }
}
