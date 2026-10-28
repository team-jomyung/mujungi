package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * OUTPUT_BEGIN(서버 → 기기). 게이트를 통과한 AI 말 묶음 시작. 뒤따르는 0x02가 이 묶음이다(BR7.4 · BR7.6).
 *
 * <p>{@code text}는 선택 필드로, 게이트를 통과한 그 말의 확정 글이다(기기 자막). 없으면 자막 없이 재생한다. 글은 로그에 남기지 않으므로
 * {@link #toString()}에는 길이만 쓴다(R-F14).
 */
public record OutputBegin(UUID playbackId, Source source, String text) implements WsPayload {

    /** text 최대 글자 수(ws-messages.schema.json OUTPUT_BEGIN.text maxLength). */
    public static final int TEXT_MAX_CHARS = 500;

    /** 출력 원천. */
    public enum Source {
        GPT_LIVE,
        SCENARIO_SCRIPT,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    /** 자막 없는 묶음. */
    public OutputBegin(UUID playbackId, Source source) {
        this(playbackId, source, null);
    }

    /** 자막 글을 계약 길이 안으로 다듬는다. 비었으면 null(필드 없음), 길면 코드 포인트 경계에서 자른다. */
    public static String subtitle(String transcript) {
        if (transcript == null) {
            return null;
        }
        String trimmed = transcript.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) <= TEXT_MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, TEXT_MAX_CHARS));
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.OUTPUT_BEGIN;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (playbackId == null) {
            problems.add("playbackId:missing");
        }
        if (source == null || source == Source.UNRECOGNIZED) {
            problems.add("source:invalid");
        }
        if (text != null && (text.isEmpty() || text.codePointCount(0, text.length()) > TEXT_MAX_CHARS)) {
            problems.add("text:invalid");
        }
        return problems;
    }

    @Override
    public String toString() {
        return "OutputBegin[playbackId=" + playbackId + ", source=" + source + ", textChars="
                + (text == null ? 0 : text.length()) + "]";
    }
}
