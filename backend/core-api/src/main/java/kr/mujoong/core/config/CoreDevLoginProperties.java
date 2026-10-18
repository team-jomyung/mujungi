package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.required;

/** BR8.3 · SD-14 시험 로그인 스위치(어느 프로필 파일에도 true로 두지 않는다). */
public record CoreDevLoginProperties(Boolean enabled) {
    public CoreDevLoginProperties {
        required(enabled, "mujoong.dev-login.enabled");
    }
}
