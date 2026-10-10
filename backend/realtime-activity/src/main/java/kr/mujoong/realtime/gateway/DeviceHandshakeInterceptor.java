package kr.mujoong.realtime.gateway;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 기기 WSS 업그레이드 검사 (U1 3.5 Step 24 · BR3.6 ① · SD-7 1 ~ 3단계 · J123 · NFR9.4).
 *
 * <p>Origin 헤더가 {@code mujoong.security.allowed-origin}과 정확히 같아야 한다(없거나 {@code null}이면 403, SECURITY_ORIGIN_DENIED
 * channel WSS). 기기 쿠키 {@code MUJOONG_DEVICE}는 값을 속성에 옮기기만 하고 확인은 업그레이드 뒤에 한다(쿠키가 없으면 업그레이드 뒤 4401).
 * {@code X-Trace-Id}가 형식에 맞으면 연결 traceId로 쓰고 아니면 새로 만든다. 토큰 값은 로그에 쓰지 않는다(BR8.7).
 */
@Component
public class DeviceHandshakeInterceptor implements HandshakeInterceptor {

    /** 기기 쿠키 이름(계약 device-api). */
    public static final String DEVICE_COOKIE = "MUJOONG_DEVICE";
    /** 속성 키: 기기 토큰(없으면 없음). */
    public static final String ATTR_DEVICE_TOKEN = "mujoong.deviceToken";
    /** 속성 키: 연결 traceId. */
    public static final String ATTR_TRACE_ID = "mujoong.traceId";

    private final String allowedOrigin;
    private final EventLogger events;

    @Autowired
    public DeviceHandshakeInterceptor(RealtimeProperties properties, EventLogger events) {
        this(properties.security().allowedOrigin(), events);
    }

    public DeviceHandshakeInterceptor(String allowedOrigin, EventLogger events) {
        this.allowedOrigin = allowedOrigin;
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        String traceId = TraceIds.orNew(request.getHeaders().getFirst(TraceIds.HEADER));
        attributes.put(ATTR_TRACE_ID, traceId);
        if (!originAllowed(request.getHeaders().get(HttpHeaders.ORIGIN))) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            events.log(EventCode.SECURITY_ORIGIN_DENIED, EventLog.Component.DEVICE_GATEWAY, null, traceId,
                    entry -> entry.attr(AttrKey.CHANNEL, "WSS").attr(AttrKey.STATUS_CODE, 403L));
            return false;
        }
        String token = cookie(request.getHeaders().get(HttpHeaders.COOKIE), DEVICE_COOKIE);
        if (token != null) {
            attributes.put(ATTR_DEVICE_TOKEN, token);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Exception exception) {
        // 업그레이드 뒤 처리는 DeviceWebSocketHandler가 한다
    }

    /** Origin이 정확히 하나이고 허용 값과 같은가. */
    boolean originAllowed(List<String> origins) {
        if (allowedOrigin == null || allowedOrigin.isBlank() || origins == null || origins.size() != 1) {
            return false;
        }
        String origin = origins.get(0);
        return origin != null && !"null".equals(origin) && origin.equals(allowedOrigin);
    }

    /** Cookie 헤더들에서 이름이 같은 첫 값. 없거나 비었으면 null. */
    static String cookie(List<String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            if (header == null) {
                continue;
            }
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).trim().equals(name)) {
                    String value = trimmed.substring(eq + 1).trim();
                    return value.isEmpty() ? null : value;
                }
            }
        }
        return null;
    }
}
