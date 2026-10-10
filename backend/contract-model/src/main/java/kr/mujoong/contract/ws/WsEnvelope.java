package kr.mujoong.contract.ws;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 기기 WSS 제어 봉투 {@code {type, seq, sentAt, opId?, sessionId?, payload}}(계약 4.2 · J27 · schemas/ws-envelope.schema.json).
 *
 * <p>seq는 연결마다 · 방향마다 1부터 1씩 는다({@link Sequencer}). opId는 서버 명령 4종에만, sessionId는 세션 메시지에 붙는다.
 * sentAt은 보낸 쪽 시계 값이고 기기는 HEARTBEAT 송신 직전에 채운다(OD-8 · R3-4). JSON 변환은 {@code JsonCodec}이 한다.
 */
public record WsEnvelope(String type, long seq, Instant sentAt, UUID opId, UUID sessionId, WsPayload payload) {

    /** seq 상한(uint32). */
    public static final long MAX_SEQ = 0xFFFF_FFFFL;

    /** payload로 type을 정해 봉투를 만든다(보내는 쪽). */
    public static WsEnvelope of(WsPayload payload, long seq, Instant sentAt, UUID opId, UUID sessionId) {
        if (payload == null) {
            throw new IllegalArgumentException("payload가 없다");
        }
        return new WsEnvelope(payload.messageType().name(), seq, sentAt, opId, sessionId, payload);
    }

    /** type 해석(모르는 값은 UNRECOGNIZED). */
    public WsMessageType messageType() {
        return WsMessageType.fromWire(type);
    }

    /**
     * 계약 형식 위반 목록(필드 이름:위반 종류). 비어 있으면 통과. 보내기 전에 확인하고, 받은 봉투에도 같은 규칙을 쓴다.
     * 방향 검사는 받는 쪽이 {@link WsMessageType#direction()}으로 한다.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        WsMessageType messageType = messageType();
        if (messageType == WsMessageType.UNRECOGNIZED) {
            problems.add("type:unknown");
        }
        if (seq < 1 || seq > MAX_SEQ) {
            problems.add("seq:range");
        }
        if (sentAt == null) {
            problems.add("sentAt:missing");
        }
        if (payload == null) {
            problems.add("payload:missing");
        } else {
            if (messageType != WsMessageType.UNRECOGNIZED && payload.messageType() != messageType) {
                problems.add("payload:typeMismatch");
            }
            payload.problems().forEach(problem -> problems.add("payload." + problem));
        }
        if (messageType.isOpIdRequired() && opId == null) {
            problems.add("opId:missing");
        }
        if (!messageType.isOpIdRequired() && opId != null) {
            problems.add("opId:notAllowed");
        }
        if (messageType.isSessionIdRequired() && sessionId == null) {
            problems.add("sessionId:missing");
        }
        return problems;
    }

    /** 받은 텍스트 프레임을 해석한 결과(J123의 4400 · ERROR 갈림에 쓴다). */
    public enum Outcome {
        /** 형식이 맞는 아는 메시지. */
        OK,
        /** JSON이 아니거나 봉투 필수 필드(type · seq · sentAt · payload)가 없거나 틀림. */
        MALFORMED,
        /** 봉투는 맞는데 모르는 type. READY 뒤면 ERROR(UNKNOWN_TYPE). */
        UNKNOWN_TYPE,
        /** 아는 type인데 payload · opId · sessionId 규칙 위반. READY 뒤면 ERROR(BAD_PAYLOAD). */
        BAD_PAYLOAD
    }

    /**
     * 해석 결과. {@code envelope}은 OK일 때만 있다. {@code type} · {@code seq}는 읽을 수 있었으면 채운다(ERROR.refSeq).
     * {@code problems}에는 필드 이름과 위반 종류만 있고 값은 없다.
     */
    public record Decoded(Outcome outcome, WsEnvelope envelope, String type, Long seq, List<String> problems) {

        public Decoded {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** OK 결과. */
        public static Decoded ok(WsEnvelope envelope) {
            return new Decoded(Outcome.OK, envelope, envelope.type(), envelope.seq(), List.of());
        }

        /** 실패 결과. */
        public static Decoded failure(Outcome outcome, String type, Long seq, List<String> problems) {
            if (outcome == Outcome.OK) {
                throw new IllegalArgumentException("실패 결과에 OK를 쓸 수 없다");
            }
            return new Decoded(outcome, null, type, seq, problems);
        }

        /** 해석이 성공했는가. */
        public boolean isOk() {
            return outcome == Outcome.OK;
        }
    }

    /** 연결마다 · 방향마다 seq를 1부터 내주는 계수기(스레드 안전). */
    public static final class Sequencer {

        private final AtomicLong next = new AtomicLong(1);

        /** 다음 seq. */
        public long next() {
            long value = next.getAndIncrement();
            if (value > MAX_SEQ) {
                throw new IllegalStateException("seq가 uint32 범위를 넘었다");
            }
            return value;
        }
    }
}
