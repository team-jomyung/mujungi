package kr.mujoong.core.messaging.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.messaging.CoreInboxReceiver;
import kr.mujoong.core.messaging.CoreInboxReceiver.Result;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영속 이벤트 수신 {@code POST /internal/events} (U1 3.5 Step 32 · 계약 6절 C11 · BR5.4 ~ BR5.6 · BR5.9 · RD-9 · 편차 (n)(p)).
 *
 * <p>체인 C-INT(내부 토큰 필수), 본문 상한 2MB({@code mujoong.limits.internal-event-body-max}, 넘으면 400 INVALID_ENVELOPE). 응답은 Inbox
 * 행을 커밋한 뒤에만 200 {@code {}}이다(새 행 · 같은 해시 중복 · IGNORED — ACK는 업무 완료가 아니다, BR5.6). 같은 eventId에 다른 해시면 409
 * EVENT_CONFLICT, 봉투 오류는 400 INVALID_ENVELOPE(재시도 대상 아님). 앱이 준비 상태가 아니면 F1b가 먼저 503이라 보내는 쪽 Outbox가 재시도한다.
 * 본문 · payload는 로그 · 오류 message에 넣지 않는다(BR5.13).
 */
@RestController
public class CoreInboxController {

    /** 200 본문(empty-object.schema.json). */
    static final String EMPTY_OBJECT = "{}";

    private final CoreInboxReceiver receiver;

    public CoreInboxController(CoreInboxReceiver receiver) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    @PostMapping(ApiResponses.EVENTS_PATH)
    ResponseEntity<String> receive(@RequestBody(required = false) String body, HttpServletRequest request) {
        Result result = receiver.receive(body);
        return switch (result.outcome()) {
            case ACCEPTED, DUPLICATE, IGNORED -> ApiResponses.json(200, EMPTY_OBJECT);
            case CONFLICT -> ApiResponses.errorEntity(request, ErrorCode.EVENT_CONFLICT);
            case INVALID -> ApiResponses.errorEntity(request, ErrorCode.INVALID_ENVELOPE);
        };
    }
}
