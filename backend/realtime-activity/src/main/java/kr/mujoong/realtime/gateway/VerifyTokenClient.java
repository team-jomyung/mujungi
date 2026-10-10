package kr.mujoong.realtime.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 기기 토큰 확인 호출 (U1 3.5 Step 24 · BR3.4 · BR3.6 ② · 편차 (j) · RD-14 · SD-7 4단계).
 *
 * <p>WSS 연결마다 Core {@code POST /internal/devices/verify-token}을 한 번 부른다(제한 {@code mujoong.internal-http.verify-token-timeout}
 * 1초). 결과는 그 연결 하나에만 묶고 캐시하지 않는다(편차 (j)). 재시도 · 회로 차단기는 없다 — 기기가 5초 뒤 다시 접속한다(RD-14).
 * 200은 주체, 401은 거절(4401), 시간 초과 · 연결 실패 · 그 밖의 응답은 확인 불가(1013)다. 토큰은 본문으로만 보내고 로그 · 예외 메시지 ·
 * 문자열 표현에 쓰지 않는다(BR8.7).
 */
@Component
public class VerifyTokenClient {

    /** 호출 경로. */
    public static final String PATH = "/internal/devices/verify-token";

    /** 확인 결과. */
    public sealed interface Result {
    }

    /** 확인됨(REGISTERED 행의 토큰). */
    public record Verified(DeviceConnection.Principal principal) implements Result {
    }

    /** 토큰이 없거나 무효 · 해제(401). */
    public record Rejected() implements Result {
    }

    /** Core가 답하지 않았다(시간 초과 · 연결 실패 · 예상 밖 응답). */
    public record Unavailable() implements Result {
    }

    /** 요청 본문. 토큰은 문자열 표현에 나오지 않는다. */
    public record VerifyRequest(String deviceToken) {
        @Override
        public String toString() {
            return "VerifyRequest[***]";
        }
    }

    /** 응답 본문(계약 internal-verify-token-response). */
    public record VerifyResponse(UUID deviceId, UUID guardianId, UUID childId, String registrationStatus) {
    }

    private static final String REGISTERED = "REGISTERED";

    private final InternalHttpClient http;
    private final JsonCodec json;
    private final Duration timeout;

    @Autowired
    public VerifyTokenClient(InternalHttpClient http, JsonCodec json, RealtimeProperties properties) {
        this(http, json, properties.internalHttp().verifyTokenTimeout());
    }

    public VerifyTokenClient(InternalHttpClient http, JsonCodec json, Duration timeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** 한 번 확인한다. */
    public Result verify(String deviceToken, String traceId) {
        if (deviceToken == null || deviceToken.isBlank()) {
            return new Rejected();
        }
        InternalHttpClient.Response response;
        try {
            response = http.send(InternalHttpClient.Target.VERIFY_TOKEN, new InternalHttpClient.Call("POST", PATH,
                    json.write(new VerifyRequest(deviceToken)), timeout, 1, null, traceId));
        } catch (InternalHttpClient.CallException e) {
            return new Unavailable();
        }
        if (response.status() == 401) {
            return new Rejected();
        }
        if (response.status() != 200) {
            return new Unavailable();
        }
        try {
            VerifyResponse body = json.read(response.body(), VerifyResponse.class);
            if (body == null || body.deviceId() == null || body.guardianId() == null
                    || !REGISTERED.equals(body.registrationStatus())) {
                return new Unavailable();
            }
            return new Verified(new DeviceConnection.Principal(body.deviceId(), body.guardianId(), body.childId()));
        } catch (RuntimeException e) {
            return new Unavailable();
        }
    }
}
