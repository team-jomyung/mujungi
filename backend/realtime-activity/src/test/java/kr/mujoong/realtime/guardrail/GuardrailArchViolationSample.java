package kr.mujoong.realtime.guardrail;

import kr.mujoong.realtime.judgment.NextAction;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.realtime.voice.ProviderHosts;

/** U13 GuardrailPackageArchTest 자기 시험용 위반 표본(시험 소스 전용). luna · voice · judgment 패키지를 가져온다. */
public final class GuardrailArchViolationSample {

    private GuardrailArchViolationSample() {
    }

    /** 규칙이 잡아야 하는 위반. */
    public static boolean violate() {
        return LunaHosts.ALLOWED_HOSTS.isEmpty() || ProviderHosts.ALLOWED_HOSTS.isEmpty() || NextAction.values().length == 0;
    }
}
