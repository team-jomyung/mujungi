package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.time.Duration;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.CoreMetrics.RequestTotals;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * REST · 12 조회 서버 처리 시간 계수 (U1 3.6 수정 · OD-6 restTotal · restLe300Ms · viewTotal · viewLe500Ms · NFR5.5 · J324). 처리 시간은
 * 가변 Clock을 체인 안에서 앞당겨 만든다(실제 시간 대기 없음).
 */
@Tag("u1")
class RestTimingFilterTest {

    private final MutableClock clock = new MutableClock();
    private final CoreMetrics metrics = CoreMetrics.inMemory();
    private final RestTimingFilter filter = new RestTimingFilter(clock, metrics);

    @Test
    void guardianAndDeviceRestIsCountedAndTheBoundaryIsFast() throws Exception {
        send("POST", "/api/activities/free", Duration.ofMillis(300));
        send("GET", "/api/device/bootstrap/status", Duration.ofMillis(301));

        assertThat(metrics.requestTotals()).isEqualTo(new RequestTotals(2, 1, 0, 0));
    }

    @Test
    void sessionViewIsCountedSeparatelyAgainstItsOwnBoundary() throws Exception {
        send("GET", "/api/sessions/3f2a9c1e-0000-4000-8000-000000000001", Duration.ofMillis(500));
        send("GET", "/api/sessions/3f2a9c1e-0000-4000-8000-000000000001", Duration.ofMillis(501));

        assertThat(metrics.requestTotals()).isEqualTo(new RequestTotals(0, 0, 2, 1));
    }

    @Test
    void endingASessionIsRestNotTheView() throws Exception {
        send("POST", "/api/sessions/3f2a9c1e-0000-4000-8000-000000000001/end", Duration.ofMillis(10));
        send("POST", "/api/sessions/3f2a9c1e-0000-4000-8000-000000000001", Duration.ofMillis(10));

        assertThat(metrics.requestTotals()).isEqualTo(new RequestTotals(2, 2, 0, 0));
    }

    @Test
    void healthInternalCallsAndOtherPathsAreNotCounted() throws Exception {
        send("GET", "/api/health", Duration.ofMillis(1));
        send("POST", "/internal/events", Duration.ofMillis(1));
        send("GET", "/actuator/prometheus", Duration.ofMillis(1));
        send("GET", "/device", Duration.ofMillis(1));

        assertThat(metrics.requestTotals()).isEqualTo(new RequestTotals(0, 0, 0, 0));
    }

    @Test
    void aFailingRequestIsStillTimed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/home");
        FilterChain failing = (req, res) -> {
            clock.advance(Duration.ofSeconds(2));
            throw new ServletException("처리 실패");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);

        assertThat(metrics.requestTotals()).isEqualTo(new RequestTotals(1, 0, 0, 0));
    }

    @Test
    void runsAfterTraceIdAndBeforeTheReadinessGate() {
        assertThat(filter.getOrder()).isGreaterThan(TraceIdFilter.ORDER).isLessThan(ReadinessGateFilter.ORDER)
                .isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
    }

    private void send(String method, String path, Duration took) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> clock.advance(took));
    }
}
