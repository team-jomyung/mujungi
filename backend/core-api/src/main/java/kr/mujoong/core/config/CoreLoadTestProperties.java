package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.required;

/** A6 부하 시험 스위치(application-load-test.yml에서만 true). */
public record CoreLoadTestProperties(Boolean enabled) {
    public CoreLoadTestProperties {
        required(enabled, "mujoong.load-test.enabled");
    }
}
