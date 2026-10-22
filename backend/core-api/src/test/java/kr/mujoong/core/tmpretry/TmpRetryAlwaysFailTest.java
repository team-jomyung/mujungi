package kr.mujoong.core.tmpretry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 임시(병합 금지, JIRA-256 실패 차단 확인용): 재시도해도 언제나 실패한다. */
class TmpRetryAlwaysFailTest {

    @Test
    void failsOnEveryAttempt() {
        assertThat("첫 시도").as("임시 시험: 언제나 실패").isEqualTo("영원히 실패");
    }
}
