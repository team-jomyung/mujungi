package kr.mujoong.realtime.content;

import kr.mujoong.contract.log.EventLog;

/**
 * {@code U8LiveContentArchTest}의 위반 표본(시험 소스 전용). content 패키지에 있으면서 로그 · 표준 출력에 기대는 클래스다. 시험 소스 집합이라
 * 운영 가져오기({@code DO_NOT_INCLUDE_TESTS})에는 들어가지 않는다.
 */
public final class U8ArchSamples {

    private U8ArchSamples() {
    }

    /** 구조화 로그 컴포넌트를 참조한다. */
    public static final class LogsWithEventLog {
        EventLog.Component component() {
            return EventLog.Component.SESSION_LIFECYCLE;
        }
    }

    /** 표준 오류로 쓴다. */
    public static final class PrintsToStderr {
        void print(String sentence) {
            System.err.println(sentence.length());
        }
    }
}
