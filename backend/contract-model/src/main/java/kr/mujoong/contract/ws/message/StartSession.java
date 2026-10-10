package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * START_SESSION(서버 → 기기, opId 필수). 세션 PREPARING 커밋 뒤 먼저 보낸다(R3-1). 기기는 ACK 뒤 승인 음성을 미리 받고 DEVICE_READY를
 * 보낸다. 송신 시도부터 20초 안에 ACTIVE가 되지 않으면 FAILED다(BR6.12 ② · 편차 (h)).
 */
public record StartSession(List<ApprovedAudio> approvedAudio) implements WsPayload {

    private static final Pattern CONTENT_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final Pattern AUDIO_URL = Pattern.compile("^/media/approved/[A-Za-z0-9._-]+\\.wav$");

    /** 미리 받을 승인 음성(세션에 고정된 버전). audioUrl은 같은 origin 상대 경로다. */
    public record ApprovedAudio(String contentCode, UUID contentVersionId, String audioUrl) {
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.START_SESSION;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (approvedAudio == null) {
            problems.add("approvedAudio:missing");
            return problems;
        }
        for (ApprovedAudio audio : approvedAudio) {
            if (audio == null || audio.contentCode() == null || !CONTENT_CODE.matcher(audio.contentCode()).matches()
                    || audio.contentVersionId() == null || audio.audioUrl() == null
                    || !AUDIO_URL.matcher(audio.audioUrl()).matches()) {
                problems.add("approvedAudio:invalid");
                break;
            }
        }
        return problems;
    }
}
