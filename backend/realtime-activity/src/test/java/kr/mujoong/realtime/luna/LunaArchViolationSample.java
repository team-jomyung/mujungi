package kr.mujoong.realtime.luna;

import java.net.http.WebSocket;
import kr.mujoong.realtime.voice.ProviderHosts;

/** U9 LunaArchTest 자기 시험용 위반 표본(시험 소스 전용). 웹소켓 · voice 패키지 · 표준 출력을 쓴다. */
public final class LunaArchViolationSample {

    private LunaArchViolationSample() {
    }

    /** 규칙이 잡아야 하는 위반. */
    public static boolean violate(WebSocket socket) {
        System.err.println("sample");
        return socket != null && ProviderHosts.ALLOWED_HOSTS.isEmpty();
    }
}
