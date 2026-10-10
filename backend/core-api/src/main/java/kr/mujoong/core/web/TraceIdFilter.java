package kr.mujoong.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import kr.mujoong.contract.trace.TraceIds;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 필터 F1 traceId (U1 3.5 Step 31 · OD-7 · NFR5.7 · BR8.6 · SD-4 순서 1).
 *
 * <p>모든 요청의 맨 앞에서 traceId를 정한다: {@code X-Trace-Id} 헤더가 정확히 한 개이고 형식(32자 소문자 16진수)이 맞으면 그 값(Nginx가
 * {@code $request_id}로 덮어쓴 값, 내부 호출은 상대 앱의 값), 아니면 새로 만든다(위조 · 로그 주입 방지). 값을 MDC · 요청 속성에 두고 응답
 * 헤더 {@code X-Trace-Id}에 먼저 실어 오류 봉투의 traceId와 같게 한다.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter implements Ordered {

    /** 가장 먼저(준비 상태 게이트 · 한도 · 인증보다 앞). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;
    /** 요청 속성 이름(오류 처리 경로가 읽는다). */
    public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolve(request);
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        String previous = MDC.get(TraceIds.MDC_KEY);
        MDC.put(TraceIds.MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(TraceIds.MDC_KEY);
            } else {
                MDC.put(TraceIds.MDC_KEY, previous);
            }
        }
    }

    /** 헤더가 정확히 한 개이고 형식이 맞을 때만 이어 쓴다(OD-7 '검증 → 틀리면 새로'). */
    static String resolve(HttpServletRequest request) {
        List<String> values = request.getHeaders(TraceIds.HEADER) == null
                ? List.of()
                : Collections.list(request.getHeaders(TraceIds.HEADER));
        return values.size() == 1 ? TraceIds.orNew(values.get(0)) : TraceIds.newTraceId();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
