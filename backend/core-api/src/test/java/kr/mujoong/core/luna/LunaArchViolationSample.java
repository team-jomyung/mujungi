package kr.mujoong.core.luna;

import org.slf4j.LoggerFactory;

/** U9 LunaArchTest 자기 시험용 위반 표본(시험 소스 전용, 운영 가져오기에 들어가지 않음). 직접 로깅 · 표준 출력을 쓴다. */
public final class LunaArchViolationSample {

    private LunaArchViolationSample() {
    }

    /** 규칙이 잡아야 하는 두 가지 위반. */
    public static void violate() {
        LoggerFactory.getLogger(LunaArchViolationSample.class).info("sample");
        System.out.println("sample");
    }
}
