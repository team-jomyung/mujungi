package kr.mujoong.core.internalhttp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Arrays;
import java.util.List;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.startup.RequiredSecretsCheck;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 내부 호출 인증 헤더 (BR3.9 · SD-8 · OD-7 · J124): 나가는 요청에 토큰 · traceId 두 헤더(MDC가 형식에 맞으면 그 값, 아니면 새 값),
 * 들어오는 헤더는 정확히 한 개 · 상수 시간 일치일 때만 참, 토큰 하한(base64url 43자) 미달은 이름만 담은 거부, 문자열 표현에 토큰 없음.
 */
@Tag("skeleton")
class InternalAuthInterceptorTest {

    private final String token = TestSecrets.internalServiceToken();
    private final InternalAuthInterceptor interceptor = new InternalAuthInterceptor(token);

    @AfterEach
    void clearMdc() {
        MDC.remove(TraceIds.MDC_KEY);
    }

    @Test
    void outgoingRequestCarriesTokenAndTheCurrentTraceId() {
        String traceId = TraceIds.newTraceId();
        MDC.put(TraceIds.MDC_KEY, traceId);

        HttpRequest request = interceptor.apply(HttpRequest.newBuilder(URI.create("http://127.0.0.1:9/internal/events"))
                .header(InternalAuthInterceptor.TOKEN_HEADER, "forwarded-from-somewhere")).build();

        assertThat(request.headers().allValues(InternalAuthInterceptor.TOKEN_HEADER)).containsExactly(token);
        assertThat(request.headers().allValues(TraceIds.HEADER)).containsExactly(traceId);
    }

    @Test
    void malformedOrMissingTraceIdIsReplacedWithAFreshValidOne() {
        MDC.put(TraceIds.MDC_KEY, "not a trace id\r\nX-Evil: 1");

        String first = InternalAuthInterceptor.currentTraceId();
        MDC.remove(TraceIds.MDC_KEY);
        String second = InternalAuthInterceptor.currentTraceId();

        assertThat(TraceIds.isValid(first)).isTrue();
        assertThat(TraceIds.isValid(second)).isTrue();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void incomingTokenMustBeExactlyOneMatchingHeader() {
        assertThat(interceptor.matches(List.of(token))).isTrue();
        assertThat(interceptor.matches(List.of(token, token))).isFalse();
        assertThat(interceptor.matches(List.of())).isFalse();
        assertThat(interceptor.matches(null)).isFalse();
        assertThat(interceptor.matches(Arrays.asList((String) null))).isFalse();
        assertThat(interceptor.matches(List.of(TestSecrets.internalServiceToken()))).isFalse();
        assertThat(interceptor.matches(List.of(token.substring(1)))).isFalse();
    }

    @Test
    void tokensBelowTheLowerBoundAreRejectedByNameOnly() {
        String shortToken = TestSecrets.base64Url(16);
        String notBase64Url = token.substring(0, 42) + "+";

        for (String bad : new String[] {null, "", shortToken, notBase64Url}) {
            assertThatThrownBy(() -> new InternalAuthInterceptor(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(RequiredSecretsCheck.INTERNAL_TOKEN)
                    .satisfies(e -> {
                        if (bad != null && !bad.isEmpty()) {
                            assertThat(e.getMessage()).doesNotContain(bad);
                        }
                    });
        }
    }

    @Test
    void stringFormDoesNotRevealTheToken() {
        assertThat(interceptor.toString()).doesNotContain(token).contains("***");
    }
}
