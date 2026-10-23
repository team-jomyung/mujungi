package kr.mujoong.core.practicegoal;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.knowledge.Neo4jChildKnowledgeGraph;

/** 시험 관측 전용. 실제 client/request를 위임하며 본문·URI·헤더·예외 메시지를 내보내지 않는다. */
final class GraphHttpDiagnostics extends HttpClient {
    record Call(String operation, int status, String failureType, long elapsedMs, List<String> reasonCodes) { }
    private final HttpClient delegate;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    private GraphHttpDiagnostics(HttpClient delegate) { this.delegate = delegate; }

    static GraphHttpDiagnostics install(Neo4jChildKnowledgeGraph graph) throws ReflectiveOperationException {
        var field = Neo4jChildKnowledgeGraph.class.getDeclaredField("client");
        field.setAccessible(true);
        var observed = new GraphHttpDiagnostics((HttpClient) field.get(graph));
        field.set(graph, observed);
        return observed;
    }

    List<Call> snapshot() { return List.copyOf(calls); }

    @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        String operation = StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> frame.getClassName().equals(Neo4jChildKnowledgeGraph.class.getName()))
                .map(StackWalker.StackFrame::getMethodName)
                .filter(name -> !name.equals("request") && !name.equals("statement"))
                .findFirst().orElse("UNKNOWN"));
        long began = System.nanoTime();
        try {
            var response = delegate.send(request, handler);
            calls.add(new Call(operation, response.statusCode(), null, elapsed(began), reasons(response.body())));
            return response;
        } catch (IOException | InterruptedException failure) {
            calls.add(new Call(operation, 0, failure.getClass().getSimpleName(), elapsed(began), List.of()));
            throw failure;
        }
    }

    private static long elapsed(long began) { return Duration.ofNanos(System.nanoTime() - began).toMillis(); }

    private static List<String> reasons(Object body) {
        if (!(body instanceof String text)) return List.of();
        try {
            var raw = JsonCodec.shared().read(text, Map.class).get("errors");
            if (!(raw instanceof List<?> errors)) return List.of();
            return errors.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .map(error -> error.get("code")).filter(String.class::isInstance).map(String.class::cast)
                    .map(code -> code.matches("Neo\\.[A-Za-z0-9.]+") ? code : "UNKNOWN_CODE").limit(10).toList();
        } catch (RuntimeException unreadable) {
            return List.of("UNREADABLE_RESPONSE");
        }
    }

    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> handler) { return delegate.sendAsync(request, handler); }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
        return delegate.sendAsync(request, handler, push);
    }
}
