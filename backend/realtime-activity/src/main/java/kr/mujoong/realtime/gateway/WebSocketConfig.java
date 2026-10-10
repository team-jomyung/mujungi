package kr.mujoong.realtime.gateway;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import java.util.Objects;
import kr.mujoong.realtime.config.RealtimeProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 기기 WSS 끝점 등록 (U1 3.5 Step 33 · 계약 4.2 · BR3.6 · SD-7 · J123 · security-design 2.3 Realtime 체인 WS).
 *
 * <ul>
 *   <li>{@value #DEVICE_PATH} 하나에 {@link DeviceWebSocketHandler}와 업그레이드 검사 {@link DeviceHandshakeInterceptor}를 붙인다. 허용
 *       origin은 {@code mujoong.security.allowed-origin} 하나다 — 이 값을 넣지 않으면 Spring 기본값(같은 출처만)이 기기 접속을 403으로
 *       막는다. 검사 순서는 우리 인터셉터(정확 일치 · 없음 · {@code null} 거부 + SECURITY_ORIGIN_DENIED 기록)가 먼저다.
 *   <li>컨테이너 메시지 버퍼는 텍스트 · 바이너리 모두 {@code mujoong.limits.ws-text-frame-max}의 {@value #CONTAINER_BUFFER_FACTOR}배
 *       (기본 16KB, J123)다. 8KB 초과 텍스트 · 965B가 아닌 0x01은 컨테이너가 아니라 {@link WsFrameGuard}가 판정해 4400으로 닫게 하고,
 *       버퍼를 넘는 것만 컨테이너가 1009로 끊는다. 새 설정 키를 만들지 않으려고 기존 한도에서 계산한다. 값은 내장 서버가 만든
 *       {@link ServerContainer}의 기본값으로 건다(연결마다 그 값을 쓴다). {@code ServletServerContainerFactoryBean}은 시험 기동의 이른
 *       형식 조회가 설정 바인딩 전에 만들어 버려서 쓰지 않는다.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer, ServletContextAware {

    /** 기기 WSS 경로(Nginx {@code location = /ws/device}). */
    public static final String DEVICE_PATH = "/ws/device";
    /** 컨테이너 버퍼 = 텍스트 프레임 상한 × 이 값. */
    public static final int CONTAINER_BUFFER_FACTOR = 2;

    private final DeviceWebSocketHandler handler;
    private final DeviceHandshakeInterceptor interceptor;
    private final String allowedOrigin;
    private final int containerBuffer;

    public WebSocketConfig(DeviceWebSocketHandler handler, DeviceHandshakeInterceptor interceptor,
            RealtimeProperties properties) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.interceptor = Objects.requireNonNull(interceptor, "interceptor");
        this.allowedOrigin = properties.security().allowedOrigin();
        this.containerBuffer = containerBuffer(properties);
    }

    /** 컨테이너 텍스트 · 바이너리 버퍼 바이트(J123 16KB). */
    public static int containerBuffer(RealtimeProperties properties) {
        return Math.toIntExact(properties.limits().wsTextFrameMax().toBytes() * CONTAINER_BUFFER_FACTOR);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, DEVICE_PATH).addInterceptors(interceptor).setAllowedOrigins(allowedOrigin);
    }

    /** 내장 서버의 WebSocket 컨테이너 기본 버퍼를 건다. 컨테이너가 없으면 기동을 멈춘다(기기 WSS를 열 수 없다). */
    @Override
    public void setServletContext(ServletContext servletContext) {
        Object attribute = servletContext == null ? null : servletContext.getAttribute(ServerContainer.class.getName());
        if (!(attribute instanceof ServerContainer container)) {
            throw new IllegalStateException("WebSocket 서버 컨테이너가 없다");
        }
        container.setDefaultMaxTextMessageBufferSize(containerBuffer);
        container.setDefaultMaxBinaryMessageBufferSize(containerBuffer);
    }
}
