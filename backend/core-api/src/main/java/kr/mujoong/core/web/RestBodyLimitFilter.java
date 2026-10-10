package kr.mujoong.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import kr.mujoong.core.config.CoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 본문 크기 한도 (U1 3.5 Step 31 · SD-10 · SD-11 · BR5.4 · 편차 (r) · J75(b)).
 *
 * <ul>
 *   <li>{@code /api/**}: {@code mujoong.limits.rest-body-max}(64KB). 넘으면 413이 아니라 400 VALIDATION_FAILED 봉투다(편차 (r)).
 *   <li>{@code /internal/**}: {@code mujoong.limits.internal-event-body-max}(2MB, 이벤트 · 세션 패키지 공통). {@code /internal/events}는 400
 *       INVALID_ENVELOPE, 그 밖의 내부 경로는 400 VALIDATION_FAILED다.
 * </ul>
 *
 * <p>선언한 Content-Length가 한도를 넘으면 본문을 읽지 않고 바로 거절한다. 길이를 선언하지 않은(chunked) 본문은 바이트를 세며 읽다가 한도를
 * 넘는 순간 {@link BodyTooLargeException}으로 끊고, 오류 처리기가 같은 400으로 답한다. Nginx {@code client_max_body_size 64k}가 앞에서
 * 먼저 막고 이 필터는 이중 방어다.
 */
@Component
public class RestBodyLimitFilter extends OncePerRequestFilter implements Ordered {

    /** bootstrap 한도 다음(인증 · 세션 조회보다 앞). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    private final long restLimit;
    private final long internalLimit;

    @Autowired
    public RestBodyLimitFilter(CoreProperties properties) {
        this(properties.limits().restBodyMax().toBytes(), properties.limits().internalEventBodyMax().toBytes());
    }

    public RestBodyLimitFilter(long restLimit, long internalLimit) {
        if (restLimit <= 0 || internalLimit <= 0) {
            throw new IllegalArgumentException("본문 한도는 0보다 커야 한다");
        }
        this.restLimit = restLimit;
        this.internalLimit = internalLimit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limitFor(ApiResponses.path(request)) < 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = ApiResponses.path(request);
        long limit = limitFor(path);
        if (request.getContentLengthLong() > limit) {
            ApiResponses.writeError(request, response, ApiResponses.invalidBodyCode(path));
            return;
        }
        chain.doFilter(new LimitedRequest(request, limit), response);
    }

    /** 경로의 한도(바이트). 대상이 아니면 -1. */
    long limitFor(String path) {
        if (path.startsWith(ApiResponses.API_PREFIX)) {
            return restLimit;
        }
        if (path.startsWith(ApiResponses.INTERNAL_PREFIX)) {
            return internalLimit;
        }
        return -1;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** 본문이 한도를 넘었다(오류 처리기가 400으로 바꾼다). 본문 내용은 담지 않는다. */
    public static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("요청 본문이 한도를 넘었다");
        }
    }

    /** 본문을 세며 읽는 요청. */
    static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public synchronized ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingInputStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /** 한도를 넘는 바이트를 읽으면 끊는 입력 흐름. */
    static final class CountingInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        CountingInputStream(ServletInputStream delegate, long limit) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                add(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                add(read);
            }
            return read;
        }

        private void add(long bytes) throws BodyTooLargeException {
            count += bytes;
            if (count > limit) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
