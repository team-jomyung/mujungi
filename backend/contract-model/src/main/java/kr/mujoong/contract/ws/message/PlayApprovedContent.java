package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * PLAY_APPROVED_CONTENT(서버 → 기기, opId 필수). 미리 받은 승인 음성을 재생한다. U1은 CLOSING(BR6.10) · WAITING_FILLER(BR7.4).
 * 기기는 모르는 contentCode를 버린다(BR7.6). 선택 {@code text}는 그 문구의 글(기기 자막, 500자 이하)이다.
 * 글은 로그에 남기지 않으므로 {@link #toString()}에는 길이만 쓴다(R-F14).
 */
public record PlayApprovedContent(UUID playbackId, String contentCode, UUID contentVersionId, String text) implements WsPayload {

    /** 자막 없는 승인 문구 재생. */
    public PlayApprovedContent(UUID playbackId, String contentCode, UUID contentVersionId) {
        this(playbackId, contentCode, contentVersionId, null);
    }

    /** 승인 문구 재생과 그 글(기기 자막, {@link OutputBegin#subtitle}과 같은 다듬기 · 앞 공백 없음). 글이 없으면 자막 없이 재생한다. */
    public static PlayApprovedContent withSubtitle(UUID playbackId, String contentCode, UUID contentVersionId, String text) {
        String subtitle = OutputBegin.subtitle(text);
        return new PlayApprovedContent(playbackId, contentCode, contentVersionId, subtitle == null ? null : subtitle.strip());
    }

    /** 끝내기 마무리 인사. */
    public static final String CLOSING = "CLOSING";
    /** 게이트 폐기 뒤 대기 · 대체 안내. */
    public static final String WAITING_FILLER = "WAITING_FILLER";

    private static final Pattern CONTENT_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Override
    public WsMessageType messageType() {
        return WsMessageType.PLAY_APPROVED_CONTENT;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (playbackId == null) {
            problems.add("playbackId:missing");
        }
        if (contentCode == null || !CONTENT_CODE.matcher(contentCode).matches()) {
            problems.add("contentCode:invalid");
        }
        if (contentVersionId == null) {
            problems.add("contentVersionId:missing");
        }
        if (text != null && (text.isEmpty() || text.codePointCount(0, text.length()) > OutputBegin.TEXT_MAX_CHARS)) {
            problems.add("text:invalid");
        }
        return problems;
    }

    @Override
    public String toString() {
        return "PlayApprovedContent[playbackId=" + playbackId + ", contentCode=" + contentCode + ", contentVersionId="
                + contentVersionId + ", textChars=" + (text == null ? 0 : text.codePointCount(0, text.length())) + "]";
    }
}
