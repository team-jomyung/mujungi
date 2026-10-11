package kr.mujoong.realtime.testing;

import java.time.Duration;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;

/**
 * 시험용 사건 기록기(시험 소스 전용, 3.6 수정 J308 · BT-NFR6.1). 예전에는 운영 소스의 {@code EventLogger.forTest}였다 — 시험 값(병합 창
 * 10초 · 보안 사건 상한 60)이 운영 jar에 시간 숫자 상수로 남지 않게 시험 소스로 옮겼다. 값은 운영 기본값과 같다(application.yml
 * {@code mujoong.observability.log-merge-window} · {@code mujoong.security.event-log-rate-cap}).
 */
public final class TestEventLoggers {

    /** 병합 창(운영 기본값). */
    public static final Duration MERGE_WINDOW = Duration.ofSeconds(10);
    /** 보안 사건 type당 분당 줄 상한(운영 기본값). */
    public static final int SECURITY_CAP_PER_MINUTE = 60;

    private TestEventLoggers() {
    }

    /** 수집기에 쓰는 STRICT 기록기(허용 표 밖 키는 예외, 임시 ownerHash 비밀). */
    public static EventLogger strict(MujoongClock clock, EventLog.Sink sink, RealtimeMetrics metrics) {
        return new EventLogger(clock, OwnerHasher.ephemeral(), sink, metrics, EventLog.Strictness.STRICT, MERGE_WINDOW,
                SECURITY_CAP_PER_MINUTE);
    }
}
