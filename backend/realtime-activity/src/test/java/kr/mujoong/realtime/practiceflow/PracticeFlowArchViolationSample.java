package kr.mujoong.realtime.practiceflow;

import kr.mujoong.realtime.practice.PracticePorts;
import kr.mujoong.realtime.session.PracticeSessionExtension.PracticeOutput;
import kr.mujoong.realtime.session.SessionExecutor;

/**
 * U12 {@code PracticeFlowArchTest} 자기 시험용 위반 표본(시험 소스 전용). 레거시 {@code practice} 패키지와 실행부 구현에 기대고, 금지된 출력 능력을
 * 부른다.
 */
public final class PracticeFlowArchViolationSample {

    private PracticeFlowArchViolationSample() {
    }

    /** 규칙이 잡아야 하는 위반. */
    public static boolean violate(PracticeOutput output, SessionExecutor executor) {
        PracticePorts.Playback legacy = PracticePorts.Playback.FAILED;
        return output.instructProvider("힌트") && output.requestResponse() && output.playApproved("X")
                && output.cancelResponse() && executor != null && legacy != null;
    }
}
