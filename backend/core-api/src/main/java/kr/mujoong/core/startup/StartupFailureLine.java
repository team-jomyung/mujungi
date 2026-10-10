package kr.mujoong.core.startup;

import java.io.PrintStream;
import java.time.Instant;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.StructuredLogFormatter;
import kr.mujoong.contract.time.SystemMujoongClock;

/**
 * 기동 거부 사유를 표준 오류에 남긴다 (U1 3.5 Step 21 · SD-14 '거부 표현' · RD-12 · OD-3).
 *
 * <p>기동 가드(프로필 조합 · 필수 비밀)는 로깅 체계가 서기 전이거나, 서더라도 제3자 로그 변환(APP_ERROR)이 메시지를 버려 거부 사유가
 * 남지 않는다. 그래서 거부 때는 두 줄을 표준 오류에 직접 쓴다: OD-1 형식의 APP_ERROR 한 줄(errorKind만)과 사람이 읽는 사유 한 줄
 * ({@value #PREFIX} 접두, 프로필 이름 · 키 이름 · 환경 변수 이름만 담고 값은 없다). 시각은 기동 전이라 Clock 빈이 없어 운영 시간
 * 어댑터({@link SystemMujoongClock})를 직접 만들어 읽는다(진단 출력에만 쓰고 비교 · 저장에 쓰지 않는다, RD-2).
 */
public final class StartupFailureLine {

    /** 사유 줄 접두. */
    public static final String PREFIX = "MUJOONG_STARTUP_REJECTED ";

    private StartupFailureLine() {
    }

    /** 표준 오류에 쓴다. */
    public static void print(String errorKind, String reason) {
        print(System.err, new SystemMujoongClock().now(), errorKind, reason);
    }

    /** {@code out}에 쓴다(시험). */
    static void print(PrintStream out, Instant now, String errorKind, String reason) {
        out.println(StructuredLogFormatter.formatAppError(now, EventCode.Level.ERROR, null, "core-api/Maintenance",
                errorKind, null));
        out.println(PREFIX + reason);
        out.flush();
    }
}
