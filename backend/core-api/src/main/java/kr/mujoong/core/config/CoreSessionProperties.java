package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.positive;

import java.time.Duration;

/** Core ① DB 점검 주기(reliability-design.md 3.6). */
public record CoreSessionProperties(Duration timerTick) {
    public CoreSessionProperties {
        positive(timerTick, "mujoong.session.timer-tick");
    }
}
