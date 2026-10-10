package kr.mujoong.core.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.trace.TraceIds;
import org.apache.catalina.Container;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 컨테이너 수준 오류도 같은 봉투로 (U1 3.5 Step 31 · BR8.6 · SD-11 'URL 정규화' · OD-7 · 편차 (o)).
 *
 * <p>Tomcat이 앱 필터 · 서블릿에 닿기 전에 거절한 요청(예: 인코딩된 {@code /} · NUL이 든 URI)은 {@code /error} 디스패치를 거치지 않고
 * Host의 오류 보고 밸브가 HTML을 쓴다. 그 밸브를 이것으로 바꿔 {@code {code, message, traceId}} · {@code X-Trace-Id} · {@code no-store}로
 * 답한다(상태 → code 대응은 {@link ApiErrorController#codeFor}와 같다). 서버 정보 · 스택 · 입력은 쓰지 않는다. 앱이 이미 본문을 쓴
 * 응답({@code /error} 처리 포함)은 건드리지 않는다.
 */
public final class ErrorEnvelopeValve extends ErrorReportValve {

    public ErrorEnvelopeValve() {
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        ErrorCode code = ApiErrorController.codeFor(status);
        String traceId = ApiResponses.traceId(request);
        try {
            response.setStatus(code.httpStatus());
            response.setHeader(TraceIds.HEADER, traceId);
            response.setHeader(HttpHeaders.CACHE_CONTROL, ApiResponses.NO_STORE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            PrintWriter writer = response.getReporter();
            if (writer != null) {
                writer.write(ApiResponses.errorBody(code, traceId));
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException e) {
            // 연결이 이미 끊겼거나 응답이 닫혔다: 더 쓸 곳이 없다(상태 줄은 이미 정해졌다)
        }
    }

    /** Host 파이프라인의 기본 오류 보고 밸브(Boot가 넣은 것 포함)를 이 밸브로 바꾼다. 관리 포트 서버는 바꾸지 않는다. */
    @Component
    static class Installer implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>, Ordered {

        @Override
        public void customize(ConfigurableTomcatWebServerFactory factory) {
            factory.addContextCustomizers(context -> {
                Container host = context.getParent();
                if (host == null) {
                    return;
                }
                Pipeline pipeline = host.getPipeline();
                for (Valve valve : pipeline.getValves()) {
                    if (valve instanceof ErrorReportValve && !(valve instanceof ErrorEnvelopeValve)) {
                        pipeline.removeValve(valve);
                    }
                }
                pipeline.addValve(new ErrorEnvelopeValve());
                if (host instanceof StandardHost standardHost) {
                    // 기동 때 Host가 이름이 다른 기본 밸브를 다시 넣지 않게 한다
                    standardHost.setErrorReportValveClass(ErrorEnvelopeValve.class.getName());
                }
            });
        }

        /** Boot의 Tomcat 설정(오류 밸브 추가 포함) 뒤에 돈다. */
        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
