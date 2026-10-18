package kr.mujoong.core.messaging;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.core.config.CoreOutboxProperties.ControlEventPolicy;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.startup.StartupFailureLine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FAILED 제어 이벤트 재무장 함수 (U1 3.5 Step 20 · A1 · RD-10 · SD-21 · BR5.14 · J124).
 *
 * <p>조건 표(RD-10)를 이 한 곳에 둔다: 자기 Outbox의 FAILED 행 중 제어 이벤트(Core는 DeviceReleased · DeletionRequested)이고
 * last_error가 EVENT_CONFLICT · INVALID_ENVELOPE · ACCOUNT_DELETED가 아니며 payload를 아직 비우지 않은 행을 PENDING · retry_count 0 ·
 * available_at = now로 되돌린다(last_error는 둔다). 시작 복구 S3d와 계정 삭제 참가자(BR5.14: FAILED 제어 이벤트를 지우지 않고 되살림)가
 * 같은 함수를 부른다. 정책 {@code RETRY_FOREVER}는 보류 옵션이라 빈 생성 단계에서 기동을 거부한다(J124).
 */
@Component
public class ControlEventRearm {

    /** Core Outbox의 제어 이벤트(이벤트 종류 등록 표의 표식 · 방향에서 정한다). */
    public static final Set<EventType> CORE_CONTROL_EVENT_TYPES = Arrays.stream(EventType.values())
            .filter(EventType::isControlEvent)
            .filter(type -> type.direction() == EventType.Direction.CORE_TO_REALTIME)
            .collect(Collectors.toUnmodifiableSet());

    /** 재무장하지 않는 last_error: 다시 보내도 같은 결과(즉시 FAILED)이거나 계정 삭제로 일부러 닫은 행. */
    public static final Set<String> EXCLUDED_LAST_ERRORS = Set.of("EVENT_CONFLICT", "INVALID_ENVELOPE", "ACCOUNT_DELETED");

    private final CoreOutboxRepository outbox;

    @Autowired
    public ControlEventRearm(CoreOutboxRepository outbox, CoreProperties properties) {
        this(outbox, properties.outbox().controlEventPolicy());
    }

    public ControlEventRearm(CoreOutboxRepository outbox, ControlEventPolicy policy) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        requireSupported(policy);
    }

    /**
     * 정책 확인. {@code REARM_ON_START}만 지원한다(J124: {@code RETRY_FOREVER}는 보류 옵션 → 기동 실패, 거부 줄에는 키 이름만).
     */
    public static void requireSupported(ControlEventPolicy policy) {
        if (policy != ControlEventPolicy.REARM_ON_START) {
            String reason = "mujoong.outbox.control-event-policy는 REARM_ON_START만 지원한다(보류 옵션 거부, J124)";
            StartupFailureLine.print("ControlEventPolicyUnsupported", reason);
            throw new IllegalStateException(reason);
        }
    }

    /** S3d: 시작 복구. 되살린 행 수를 돌려준다(STARTUP_RECOVERY rearmedControlEvents). */
    public int rearmOnStart(Instant now) {
        return outbox.rearmFailedControlEvents(EnumSet.copyOf(CORE_CONTROL_EVENT_TYPES), EXCLUDED_LAST_ERRORS,
                Objects.requireNonNull(now, "now"));
    }

    /** BR5.14: 한 보호자의 FAILED 제어 이벤트를 같은 조건으로 되살린다(삭제 참가자가 지우기 전에 부른다). */
    public int rearmForGuardian(UUID guardianId, Instant now) {
        return outbox.rearmFailedControlEventsForGuardian(Objects.requireNonNull(guardianId, "guardianId"),
                EnumSet.copyOf(CORE_CONTROL_EVENT_TYPES), EXCLUDED_LAST_ERRORS, Objects.requireNonNull(now, "now"));
    }
}
