package kr.mujoong.core.tmpretry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 임시(병합 금지, JIRA-256 재시도 확인용): 처음 실행은 실패하고 재시도하면 통과한다. */
class TmpRetryFlakyTest {

    @Test
    void failsFirstThenPassesOnRetry() throws IOException {
        Path marker = Path.of(System.getProperty("mujoong.repo.root"), "backend/core-api/build/tmp-retry-marker");
        if (Files.notExists(marker)) {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "first");
            assertThat("첫 시도").as("임시 불안정 시험: 첫 시도는 일부러 실패").isEqualTo("재시도");
        }
    }
}
