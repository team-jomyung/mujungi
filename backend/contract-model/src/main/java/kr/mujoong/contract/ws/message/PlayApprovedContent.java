package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * PLAY_APPROVED_CONTENT(서버 → 기기, opId 필수). 미리 받은 승인 음성을 재생한다. U1은 CLOSING(BR6.10) · WAITING_FILLER(BR7.4).
 * 기기는 모르는 contentCode를 버린다(BR7.6).
 */
public record PlayApprovedContent(UUID playbackId, String contentCode, UUID contentVersionId) implements WsPayload {

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
        return problems;
    }
}
