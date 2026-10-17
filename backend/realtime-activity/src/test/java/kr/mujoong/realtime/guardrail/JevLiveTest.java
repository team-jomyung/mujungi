package kr.mujoong.realtime.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 제브 실호출 시험 (U13 Step 15b). 사람이 env {@code JEV_API_KEY} · {@code JEV_BASE_URL} · {@code JEV_MODEL}을 넣고
 * {@code ./gradlew :realtime-activity:jevLiveTest}로만 돈다(기본 test는 {@code jev-live}를 고정 제외한다). 키 · 주소 · 모델이 없으면 보고서에
 * {@code SKIPPED_*}를 쓰고 이 시험은 건너뜀으로 끝난다(통과 아님). 통과 조건은 모든 합성 표본이 실패 없이 판정을 받는 것(REACHED)이다 — 분류 품질은
 * 보고서의 관측으로 사람이 읽는다. 결과는 {@code [실호출-합성]} 증거다(R-M11).
 */
@Tag("jev-live")
class JevLiveTest {

    @Test
    void jevAnswersSyntheticCandidatesThroughTheOperationalAdapter() throws IOException {
        JevLiveProbe probe = JevLiveProbe.live();
        JevLiveProbe.Result result = probe.run();
        System.out.println(JevLiveProbe.oneLine(result) + " (보고서 " + probe.report() + ")");

        assumeTrue(result.outcome() != JevLiveProbe.Outcome.SKIPPED_NO_KEY && result.outcome() != JevLiveProbe.Outcome.SKIPPED_NO_CONFIG,
                result.outcome() + ": env JEV_API_KEY · JEV_BASE_URL · JEV_MODEL 필요(실호출 안 함, 통과 아님)");
        assertThat(result.outcome()).as("제브 도달 여부(보고서 " + probe.report() + ")").isEqualTo(JevLiveProbe.Outcome.REACHED);
    }
}
