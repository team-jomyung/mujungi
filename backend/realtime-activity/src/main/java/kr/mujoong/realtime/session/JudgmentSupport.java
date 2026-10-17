package kr.mujoong.realtime.session;

import java.time.Duration;
import java.util.Objects;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.judgment.JudgeClient;
import kr.mujoong.realtime.judgment.JudgmentLiveRecorder;
import kr.mujoong.realtime.judgment.UnavailableJudgeClient;

/**
 * 세션 실행기가 판정 턴을 열 때 쓰는 장치 묶음 (U11 BR1.6 · BR2.1). {@link #NONE}은 공급자가 없는 모양이다: 판정 턴을 열지 않고 지금 흐름이 그대로
 * 돈다(U3 · U4b · U5b 시험이 쓰는 기본값).
 *
 * @param judge 판정 포트. {@code available()}이 거짓이면 턴을 열지 않는다
 * @param recorder 판정 기록기(없으면 기록하지 않는다)
 * @param liveBudget 판정 시한({@code mujoong.timing.judgment.live-budget}, 4s)
 * @param promptVersion 판정 지시문 판(기록용)
 * @param optionSet 세션에 고정되는 판정 선택지 판(공급자가 없으면 null)
 */
public record JudgmentSupport(JudgeClient judge, JudgmentLiveRecorder recorder, Duration liveBudget, String promptVersion,
        LiveContentResources.JudgmentOptionSet optionSet) {

    /** 공급자 없음(키 없음 · 스위치 꺼짐과 같은 모양). 판정 시한은 쓰이지 않아 0이다(시간 값은 설정 키에서만 읽는다, R-F7). */
    public static final JudgmentSupport NONE = new JudgmentSupport(new UnavailableJudgeClient("prompt-v1"), null,
            Duration.ZERO, "prompt-v1", null);

    public JudgmentSupport {
        Objects.requireNonNull(judge, "judge");
        Objects.requireNonNull(liveBudget, "liveBudget");
        Objects.requireNonNull(promptVersion, "promptVersion");
        if (judge.available() && optionSet == null) {
            throw new IllegalArgumentException("공급자가 있으면 판정 선택지 판이 있어야 한다");
        }
    }
}
