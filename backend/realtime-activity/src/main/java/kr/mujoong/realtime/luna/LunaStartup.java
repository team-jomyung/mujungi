package kr.mujoong.realtime.luna;

import java.util.Objects;
import java.util.Optional;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import kr.mujoong.realtime.startup.StartupFailureLine;

/**
 * Luna 기동 확인 — realtime (U9 W1 · BR1.3 · BR1.4). 키 유무와 관계없이 기동 때 한 번 돈다: {@code mujoong.luna.base-url}을 활성 프로필의
 * 주소 규칙으로 확인하고(어기면 {@code MUJOONG_STARTUP_REJECTED}로 기동 거부), {@code LUNA_STARTUP} 한 줄로 켜짐 · 꺼짐을 남긴다. 키가 없는 것은
 * 거부 사유가 아니다. 키 값은 남기지 않는다.
 */
public final class LunaStartup {

    /** 거부 줄의 errorKind. */
    static final String ERROR_KIND = "LunaStartupRejected";

    private final LunaHosts.Mode mode;
    private final boolean enabled;

    /**
     * @param keyPresent env 키가 있는가({@link LunaKeyPresentCondition#isPresent})
     */
    public LunaStartup(LunaProperties properties, boolean keyPresent, EventLogger events, String... activeProfiles) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(events, "events");
        this.mode = LunaHosts.modeFor(activeProfiles);
        Optional<LunaHosts.Problem> problem = LunaHosts.check(properties.baseUrl(), mode);
        if (problem.isPresent()) {
            String reason = "Luna 기동 확인 거부(값 미출력): mujoong.luna.base-url(" + problem.get() + ", " + mode + ")";
            StartupFailureLine.print(ERROR_KIND, reason);
            throw new RealtimeStartupChecks.StartupRejectedException(reason);
        }
        this.enabled = keyPresent;
        events.log(EventCode.LUNA_STARTUP, EventLog.Component.REALTIME_LUNA_CLIENT, null,
                entry -> entry.attr(AttrKey.LUNA_STATE, keyPresent ? "ENABLED" : "DISABLED_NO_KEY"));
    }

    /** 주소 규칙 모드(보내기 직전 확인에도 같은 모드를 쓴다). */
    public LunaHosts.Mode mode() {
        return mode;
    }

    /** RealtimeLunaClient가 켜졌는가. */
    public boolean enabled() {
        return enabled;
    }
}
