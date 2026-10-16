package kr.mujoong.core.luna;

import java.util.Objects;
import java.util.Optional;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.startup.StartupFailureLine;

/**
 * Luna 기동 확인 (U9 W1 · BR1.3 · BR1.4 · NFR 설계 가정 5). 키 유무와 관계없이 기동 때 한 번 돈다.
 *
 * <ol>
 *   <li>{@code mujoong.luna.base-url}을 활성 프로필의 주소 규칙({@link LunaHosts#modeFor})으로 확인한다. 어기면 기동을 거부한다
 *       ({@code MUJOONG_STARTUP_REJECTED}, 값 없이 키 이름과 사유만).
 *   <li>{@code LUNA_STARTUP} 한 줄로 켜짐({@code ENABLED}) · 꺼짐({@code DISABLED_NO_KEY})을 남긴다. 키 값은 남기지 않는다.
 * </ol>
 *
 * <p>키가 없는 것은 거부 사유가 아니다(어댑터만 꺼지고 기존 실패 기본 빈이 쓰인다, BR1.2).
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
            throw new IllegalStateException(reason);
        }
        this.enabled = keyPresent;
        events.log(EventCode.LUNA_STARTUP, EventLog.Component.CORE_LUNA_CLIENT, null,
                entry -> entry.attr(AttrKey.LUNA_STATE, keyPresent ? "ENABLED" : "DISABLED_NO_KEY"));
    }

    /** 주소 규칙 모드(보내기 직전 확인에도 같은 모드를 쓴다). */
    public LunaHosts.Mode mode() {
        return mode;
    }

    /** 실제 어댑터가 켜졌는가. */
    public boolean enabled() {
        return enabled;
    }
}
