package kr.mujoong.core.internalhttp;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Objects;
import kr.mujoong.contract.secret.SecretCompare;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.startup.RequiredSecretsCheck;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 내부 호출 인증 헤더의 유일한 자리 (U1 3.5 Step 21 · BR3.9 · SD-8 · OD-7 · J124).
 *
 * <p>나가는 호출: 새로 만드는 요청에 {@code X-Internal-Token}(환경 변수 {@code INTERNAL_SERVICE_TOKEN})과 {@code X-Trace-Id}(MDC 값이
 * 형식에 맞으면 그 값, 아니면 새 값)를 붙인다. 들어온 요청의 헤더는 옮기지 않는다(요청 객체를 늘 새로 만든다).
 *
 * <p>들어오는 호출: {@link #matches}가 토큰 헤더가 정확히 한 개이고 SHA-256 상수 시간 비교로 같을 때만 참이다(여러 개 · 없음 · 틀림은
 * 401 UNAUTHENTICATED, 필터 몫은 Step 31). 토큰 값은 문자열 표현 · 예외 메시지 · 로그에 나오지 않는다.
 */
@Component
public class InternalAuthInterceptor {

    /** 내부 토큰 헤더 이름. */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final String token;

    /**
     * Spring 생성자. {@link RequiredSecretsCheck}를 먼저 받아 토큰 누락 · 형식 오류는 표준 거부 줄로 기동을 끝내게 한다.
     */
    @Autowired
    public InternalAuthInterceptor(Environment environment, RequiredSecretsCheck requiredSecretsCheck) {
        this(environment.getProperty(RequiredSecretsCheck.INTERNAL_TOKEN));
        Objects.requireNonNull(requiredSecretsCheck, "requiredSecretsCheck");
    }

    /** 시험 · 대역 생성자. */
    public InternalAuthInterceptor(String token) {
        if (!RequiredSecretsCheck.isValidInternalToken(token)) {
            // 값은 쓰지 않고 환경 변수 이름만 쓴다(SD-12)
            throw new IllegalStateException(RequiredSecretsCheck.INTERNAL_TOKEN + "가 없거나 형식이 틀렸다");
        }
        this.token = token;
    }

    /** 나가는 요청에 두 헤더를 붙인다(같은 이름이 있으면 덮어쓴다). */
    public HttpRequest.Builder apply(HttpRequest.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        return builder.setHeader(TOKEN_HEADER, token).setHeader(TraceIds.HEADER, currentTraceId());
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
