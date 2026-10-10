package kr.mujoong.realtime.messaging.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.messaging.RealtimeInboxReceiver;
import kr.mujoong.realtime.web.ApiExceptionHandler;
import kr.mujoong.realtime.web.InternalBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Realtime 이벤트 수신 끝점 {@code POST /internal/events} (U1 3.5 Step 33 · 계약 6절 C11 realtime-inbox · BR5.4 ~ BR5.9 · RD-9 ·
 * 편차 (n)(p)).
 *
 * <p>내부 토큰 · 준비 상태(NOT_READY · DRAINING이면 503, 보내는 쪽 Outbox 재시도)는 필터가 먼저 본다. 본문은
 * {@code mujoong.limits.internal-event-body-max}(2MB) 이하이고 넘거나 봉투가 틀리면 400 INVALID_ENVELOPE(재시도 대상 아님)다.
 * {@link RealtimeInboxReceiver}가 Inbox에 커밋한 뒤에만 200 {}이고(새 행 · 중복 · IGNORED), 같은 eventId에 다른 내용이면 409
 * EVENT_CONFLICT다. 본문은 로그에 쓰지 않는다(BR5.13).
 */
@RestController
public class RealtimeInboxController {

    private final RealtimeInboxReceiver receiver;
    private final JsonCodec json;
    private final long bodyMax;

    public RealtimeInboxController(RealtimeInboxReceiver receiver, JsonCodec json, RealtimeProperties properties) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        this.json = Objects.requireNonNull(json, "json");
        this.bodyMax = properties.limits().internalEventBodyMax().toBytes();
    }

    @PostMapping("/internal/events")
    ResponseEntity<String> receive(HttpServletRequest request) {
        String body = InternalBody.read(request, bodyMax, ErrorCode.INVALID_ENVELOPE);
        RealtimeInboxReceiver.Result result = receiver.receive(body);
        return switch (result.outcome()) {
            case ACCEPTED, DUPLICATE, IGNORED -> ResponseEntity.status(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON).body(json.write(Map.of()));
            case CONFLICT -> ApiExceptionHandler.errorEntity(json, ErrorCode.EVENT_CONFLICT, request);
            case INVALID -> ApiExceptionHandler.errorEntity(json, ErrorCode.INVALID_ENVELOPE, request);
        };
    }
}
