package kr.mujoong.realtime.internalhttp;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Objects;
import kr.mujoong.contract.secret.SecretCompare;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Realtime 내부 호출 인증 헤더의 유일한 자리 (U1 3.5 Step 26 · BR3.9 · SD-8 · OD-7 · J124).
 *
 * <p>나가는 호출(Realtime → Core): 새로 만드는 요청에 {@code X-Internal-Token}(환경 변수 {@code INTERNAL_SERVICE_TOKEN})과
 * {@code X-Trace-Id}(주어진 traceId 또는 MDC 값이 형식에 맞으면 그 값, 아니면 새 값)를 붙인다. 들어온 요청의 헤더는 옮기지 않는다.
 *
 * <p>들어오는 호출: {@link #matches}가 토큰 헤더가 정확히 한 개이고 SHA-256 상수 시간 비교로 같을 때만 참이다(필터 몫은 Step 33). 토큰
 * 값은 문자열 표현 · 예외 메시지 · 로그에 나오지 않는다. Core에 같은 규칙의 클래스가 따로 있다(LC-11).
 */
@Component
public class InternalAuthInterceptor {

    /** 내부 토큰 헤더 이름. */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final String token;

    /** Spring 생성자. 기동 점검(S1b)을 먼저 받아 토큰 누락 · 형식 오류는 표준 거부 줄로 기동을 끝내게 한다. */
    @Autowired
    public InternalAuthInterceptor(Environment environment, RealtimeStartupChecks startupChecks) {
        this(environment.getProperty(RealtimeStartupChecks.INTERNAL_TOKEN));
        Objects.requireNonNull(startupChecks, "startupChecks");
    }

    /** 시험 · 대역 생성자. */
    public InternalAuthInterceptor(String token) {
        if (!RealtimeStartupChecks.isValidInternalToken(token)) {
            // 값은 쓰지 않고 환경 변수 이름만 쓴다(SD-12)
            throw new IllegalStateException(RealtimeStartupChecks.INTERNAL_TOKEN + "가 없거나 형식이 틀렸다");
        }
        this.token = token;
    }

    /** 나가는 요청에 두 헤더를 붙인다. traceId가 형식에 맞지 않으면 MDC 값, 그것도 아니면 새 값을 쓴다. */
    public HttpRequest.Builder apply(HttpRequest.Builder builder, String traceId) {
        Objects.requireNonNull(builder, "builder");
        String effective = TraceIds.isValid(traceId) ? traceId : currentTraceId();
        return builder.setHeader(TOKEN_HEADER, token).setHeader(TraceIds.HEADER, effective);
    }

    /** 지금 스레드의 traceId(MDC가 형식에 맞으면 그 값, 아니면 새 값, OD-7). */
    public static String currentTraceId() {
        return TraceIds.orNew(MDC.get(TraceIds.MDC_KEY));
    }

    /**
     * 들어온 토큰 헤더 값들이 정확히 한 개이고 맞는가(BR3.9 · SD-8).
     *
     * @param presented 요청의 {@code X-Internal-Token} 헤더 값 목록(없으면 빈 목록 또는 null)
     */
    public boolean matches(List<String> presented) {
        if (presented == null || presented.size() != 1) {
            return false;
        }
        String value = presented.get(0);
        return value != null && SecretCompare.constantTimeEquals(value, token);
    }

    @Override
    public String toString() {
        return "InternalAuthInterceptor[token=***]";
    }
}
