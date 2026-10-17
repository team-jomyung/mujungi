package kr.mujoong.realtime.judgment;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * 진행 중 판정 포트 (U11 BR1.1 · BR1.6). 판정은 이 포트로만 부르고 {@code EvaluationProvider}와 섞지 않는다. {@link #judge}는 예외를 던지지
 * 않고 모든 실패를 보류({@code ABSTAINED}) 결과로 완료한다. 결과는 다른 스레드에서 끝나므로 호출자는 실행기 편지함으로만 넘긴다(BR1.8).
 */
public interface JudgeClient {

    /** 공급자를 부를 수 있는가. 거짓이면 실행부는 판정 턴을 열지 않는다(BR1.6). */
    boolean available();

    /**
     * 판정 한 번. 공급자 호출은 최대 한 번이고 재시도하지 않는다(BR1.4).
     *
     * @param turnId 결과를 돌려받을 판정 턴. 공급자로 보내지 않고 로그에도 쓰지 않는다(호출자 상관용)
     */
    CompletionStage<JudgeOutcome> judge(JudgeRequest request, UUID turnId);
}
