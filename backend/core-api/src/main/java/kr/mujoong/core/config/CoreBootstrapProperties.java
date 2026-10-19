package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.positive;

/** 코드 추첨 시도 수. */
public record CoreBootstrapProperties(Integer codeDrawAttempts) {
    public CoreBootstrapProperties {
        positive(codeDrawAttempts, "mujoong.bootstrap.code-draw-attempts");
    }
}
