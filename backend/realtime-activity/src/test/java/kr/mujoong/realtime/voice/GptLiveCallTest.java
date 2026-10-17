package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GPT-Live 격리 실호출 시험 (U11 FR9.1 · FR9.2 · BR9.1 · BR9.2 · 기능 설계 W7). 사람이 env {@code OPENAI_API_KEY}를 넣고
 * {@code ./gradlew :realtime-activity:gptLiveTest}로만 돈다(기본 test는 {@code gpt-live}를 고정 제외). 키가 없으면 보고서에
 * {@code SKIPPED_NO_KEY}를 쓰고 이 시험은 건너뜀으로 끝난다(통과 아님).
 *
 * <p>통과 조건은 계속 · 중단 기준인 ① 입력 표본율 수락(세션 시작 · 음성 송신 · 출력 수신)과 ⑦ append + create 뒤 응답(지원 목록 밖 type 오류 0건)이다.
 * ② ~ ⑥ · ⑧ ~ ⑨ · 사용량은 보고서 표에 관측으로 남기고 사람이 계속 · 중단을 정한다(BR9.2). 운영 송수신 부품(팩토리 · 세션 · 해석기)
 * 하나로만 시도한다. 결과는 {@code [실호출-합성]} 증거다(R-M11).
 */
@Tag("gpt-live")
class GptLiveCallTest {

    @Test
    void theRealProviderAcceptsTheSessionAudioAndAnInjectedInstruction() throws IOException {
        GptLiveProbe probe = GptLiveProbe.live();
        GptLiveProbe.Result result = probe.run();
        System.out.println(GptLiveProbe.summary(result) + " (보고서 " + probe.report() + ")");

        assumeTrue(result.outcome() != GptLiveProbe.Outcome.SKIPPED_NO_KEY,
                "SKIPPED_NO_KEY: env OPENAI_API_KEY 없음(실호출 안 함, 통과 아님)");
        assertThat(result.outcome()).as("① · ⑦ 실호출 결과(보고서 " + probe.report() + ")").isEqualTo(GptLiveProbe.Outcome.PASS);
    }
}
