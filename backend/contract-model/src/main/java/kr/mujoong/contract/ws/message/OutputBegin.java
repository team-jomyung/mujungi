package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * OUTPUT_BEGIN(서버 → 기기). 게이트를 통과한 AI 말 묶음 시작. 뒤따르는 0x02가 이 묶음이다(BR7.4 · BR7.6).
 *
 * <p>{@code text}는 선택 필드로, 게이트를 통과한 그 말의 확정 글이다(기기 자막). 없으면 자막 없이 재생한다. 앞 공백 한 칸은 앞 묶음 글과
 * 띄어 이으라는 뜻이다(나눠 보낸 응답). 글은 로그에 남기지 않으므로
 * {@link #toString()}에는 길이만 쓴다(R-F14).
 *
 * <p>{@code interruptible}은 선택 필드로, {@code false}면 기기가 아이 소리(끼어들기)로 이 묶음 재생을 멈추지 않는다. 없으면 끊을 수 있다
 * (연습 첫 대본, FT-1030-b).
 */
public record OutputBegin(UUID playbackId, Source source, String text, Boolean interruptible) implements WsPayload {

    /** text 최대 글자 수(ws-messages.schema.json OUTPUT_BEGIN.text maxLength). */
    public static final int TEXT_MAX_CHARS = 500;

    /** 출력 원천. */
    public enum Source {
        GPT_LIVE,
        SCENARIO_SCRIPT,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    /** 끊을 수 있는 묶음. */
    public OutputBegin(UUID playbackId, Source source, String text) {
        this(playbackId, source, text, null);
    }

    /** 자막 없는 묶음. */
    public OutputBegin(UUID playbackId, Source source) {
        this(playbackId, source, null, null);
    }

    /**
     * 자막 글을 계약 길이 안으로 다듬는다. 비었으면 null(필드 없음), 길면 코드 포인트 경계에서 자른다. 끝 공백은 지우고, 앞 공백은 한 칸으로
     * 남긴다: 한 말을 여러 묶음으로 나눠 보내면 묶음 경계가 낱말 중간일 수 있어, 기기는 앞 공백이 있는 글만 앞 묶음 글과 띄어 잇는다.
     */
    public static String subtitle(String transcript) {
        if (transcript == null) {
            return null;
        }
        String trimmed = transcript.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        boolean spaced = Character.isWhitespace(transcript.codePointAt(0));
        int max = spaced ? TEXT_MAX_CHARS - 1 : TEXT_MAX_CHARS;
        if (trimmed.codePointCount(0, trimmed.length()) > max) {
            trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(0, max));
        }
        return spaced ? " " + trimmed : trimmed;
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
                + (text == null ? 0 : text.length()) + (interruptible == null ? "" : ", interruptible=" + interruptible) + "]";
    }
}
