package kr.mujoong.realtime.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.WsError;
import kr.mujoong.realtime.gateway.DeviceConnection.Phase;

/**
 * 기기 WSS 프레임 규정 판정 (U1 3.5 Step 24 · 편차 (s) · J123 · BR3.6 ④ · NFR9.9 · SD-7 7단계).
 *
 * <p>상태와 프레임만 보고 결정하는 순수 함수다(DB · 소켓 없음).
 *
 * <ul>
 *   <li>텍스트: UTF-8 {@code mujoong.limits.ws-text-frame-max}(8KB) 초과는 4400. READY 전에는 형식이 맞는 HELLO만 받고 그 밖은 4400
 *       (AWAIT_VERIFY에 온 HELLO는 확인 뒤 처리하도록 미룬다). READY 뒤: JSON 봉투 필수 필드 위반은 4400, 모르는 type은
 *       ERROR(UNKNOWN_TYPE), 아는 type의 payload 위반은 ERROR(BAD_PAYLOAD)이고 연결을 유지한다. 방향이 틀린 type(서버 → 기기 메시지)은 4400.
 *       DEVICE_STATE · PLAYBACK_MARK(PROGRESS) · 두 번째 HELLO는 받아서 무시한다(ERROR 없음).
 *   <li>봉투 {@code sentAt}만 없거나 틀린 READY 뒤 메시지는 받되 sentAt 없음으로 표시한다 — HEARTBEAT의 clockOffsetMs를 생략하고
 *       CLOCK_OFFSET_MISSING으로 남기기 위해서다(OD-8 · R3-4).
 *   <li>바이너리: READY에서 0x01 · 965바이트만 받는다. 서버가 받은 0x02 · 길이 위반 · READY 전 프레임은 4400.
 * </ul>
 */
public final class WsFrameGuard {

    /** 판정. */
    public sealed interface Decision {
    }

    /** 처리할 제어 봉투. */
    public record Accept(WsEnvelope envelope, boolean sentAtMissing) implements Decision {
    }

    /** 처리할 입력 음성 프레임. */
    public record AcceptAudio(AudioFrameCodec.AudioFrame frame) implements Decision {
    }

    /** 확인이 끝난 뒤 처리할 HELLO. */
    public record Defer(WsEnvelope hello) implements Decision {
    }

    /** 연결을 닫는다. */
    public record Close(CloseCode code) implements Decision {
    }

    /** ERROR를 보내고 연결을 유지한다. */
    public record Error(WsError.Code code, Long refSeq) implements Decision {
    }

    /** 받고 무시한다. */
    public record Ignore() implements Decision {
    }

    private static final Close PROTOCOL_ERROR = new Close(CloseCode.PROTOCOL_ERROR);
    private static final Ignore IGNORE = new Ignore();
    private static final String SENT_AT = "sentAt";
    /** sentAt만 빠진 봉투를 해석하려고 잠시 넣는 자리값(해석 뒤 버린다). */
    private static final String SENT_AT_PLACEHOLDER = Instant.EPOCH.toString();

    private final JsonCodec json;
    private final int textMaxBytes;

    public WsFrameGuard(JsonCodec json, int textMaxBytes) {
        this.json = Objects.requireNonNull(json, "json");
        if (textMaxBytes <= 0) {
            throw new IllegalArgumentException("textMaxBytes는 0보다 커야 한다");
        }
        this.textMaxBytes = textMaxBytes;
    }

    /** 텍스트 프레임 판정. */
    public Decision onText(Phase phase, String text) {
        Objects.requireNonNull(phase, "phase");
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > textMaxBytes) {
            return PROTOCOL_ERROR;
        }
        WsEnvelope.Decoded decoded = json.decodeWs(text);
        return switch (phase) {
            case AWAIT_VERIFY, AWAIT_HELLO -> beforeReady(phase, decoded);
            case READY -> afterReady(text, decoded);
            case CLOSED -> IGNORE;
        };
    }

    /** 바이너리 프레임 판정. */
    public Decision onBinary(Phase phase, byte[] bytes) {
        Objects.requireNonNull(phase, "phase");
        if (phase == Phase.CLOSED) {
            return IGNORE;
        }
        if (phase != Phase.READY) {
            return PROTOCOL_ERROR;
        }
        try {
            return new AcceptAudio(AudioFrameCodec.decodeFromDevice(bytes));
        } catch (AudioFrameCodec.InvalidFrameException e) {
            return PROTOCOL_ERROR;
        }
    }

    private static Decision beforeReady(Phase phase, WsEnvelope.Decoded decoded) {
        if (!decoded.isOk() || decoded.envelope().messageType() != WsMessageType.HELLO) {
            return PROTOCOL_ERROR;
        }
        return phase == Phase.AWAIT_VERIFY ? new Defer(decoded.envelope()) : new Accept(decoded.envelope(), false);
    }

    private Decision afterReady(String text, WsEnvelope.Decoded decoded) {
        boolean sentAtMissing = false;
        if (decoded.outcome() == WsEnvelope.Outcome.MALFORMED && onlySentAtProblems(decoded.problems())) {
            WsEnvelope.Decoded patched = decodeWithPlaceholderSentAt(text);
            if (patched == null) {
                return PROTOCOL_ERROR;
            }
            decoded = patched;
            sentAtMissing = true;
        }
        WsMessageType type = WsMessageType.fromWire(decoded.type());
        switch (decoded.outcome()) {
            case MALFORMED:
                return PROTOCOL_ERROR;
            case UNKNOWN_TYPE:
                return new Error(WsError.Code.UNKNOWN_TYPE, decoded.seq());
            case BAD_PAYLOAD:
                if (type.direction() == WsMessageType.Direction.SERVER_TO_DEVICE) {
                    return PROTOCOL_ERROR;
                }
                return new Error(WsError.Code.BAD_PAYLOAD, decoded.seq());
            default:
                break;
        }
        WsEnvelope envelope = decoded.envelope();
        if (type.direction() != WsMessageType.Direction.DEVICE_TO_SERVER) {
            return PROTOCOL_ERROR;
        }
        if (type == WsMessageType.DEVICE_STATE || type == WsMessageType.HELLO) {
            return IGNORE;
        }
        if (type == WsMessageType.PLAYBACK_MARK
                && ((PlaybackMark) envelope.payload()).status() == PlaybackMark.Status.PROGRESS) {
            return IGNORE;
        }
        if (sentAtMissing) {
            envelope = new WsEnvelope(envelope.type(), envelope.seq(), null, envelope.opId(), envelope.sessionId(),
                    envelope.payload());
        }
        return new Accept(envelope, sentAtMissing);
    }

    private static boolean onlySentAtProblems(List<String> problems) {
        return !problems.isEmpty() && problems.stream().allMatch(p -> p.startsWith(SENT_AT + ":"));
    }

    @SuppressWarnings("unchecked")
    private WsEnvelope.Decoded decodeWithPlaceholderSentAt(String text) {
        try {
            Map<String, Object> raw = new LinkedHashMap<>(json.read(text, Map.class));
            raw.put(SENT_AT, SENT_AT_PLACEHOLDER);
            return json.decodeWs(json.write(raw));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
