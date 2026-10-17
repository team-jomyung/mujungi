package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Luna 응답 여부 · 지연 확인 시험 — realtime (U11 Step 2 · 3 조사). 사람이 env {@code OPENAI_API_KEY}를 넣고
 * {@code ./gradlew :realtime-activity:lunaReachTest}로만 돈다(기본 test는 {@code luna-reach}를 고정 제외). 키가 없으면 보고서에
 * {@code SKIPPED_NO_KEY}를 쓰고 이 시험은 건너뜀으로 끝난다(통과 아님). 통과 조건은 1회 이상 OK(REACHED)다. 결과는 {@code [실호출-합성]}
 * 증거다(R-M11).
 */
@Tag("luna-reach")
class LunaReachTest {

    @Test
    void lunaAnswersASyntheticJudgmentWithAGenerousTimeout() throws IOException {
        LunaReachProbe probe = LunaReachProbe.live();
        LunaReachProbe.Result result = probe.run();
        System.out.println(LunaReachProbe.oneLine(result) + " (보고서 " + probe.report() + ")");

        assumeTrue(result.outcome() != LunaReachProbe.Outcome.SKIPPED_NO_KEY,
                "SKIPPED_NO_KEY: env OPENAI_API_KEY 없음(실호출 안 함, 통과 아님)");
        assertThat(result.outcome()).as("Luna 응답 여부(보고서 " + probe.report() + ")").isEqualTo(LunaReachProbe.Outcome.REACHED);
    }
}
