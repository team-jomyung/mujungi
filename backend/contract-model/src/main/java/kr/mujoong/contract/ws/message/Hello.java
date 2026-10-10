package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * HELLO(기기 → 서버, 접속 직후 첫 메시지). 토큰을 넣지 않는다(J29). 형식이 틀리면 4400(BR3.6 ④ · 편차 (s)). resume은 U3가 완성한다.
 */
public record Hello(String appVersion, List<String> audio, Resume resume) implements WsPayload {

    private static final Pattern AUDIO_FORMAT = Pattern.compile("^[a-z0-9]+/[0-9]+/[0-9]+$");
    private static final int APP_VERSION_MAX = 64;
    private static final int AUDIO_FORMATS_MAX = 8;

    /** 이어가기 요청. */
    public record Resume(UUID sessionId) {
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.HELLO;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (appVersion == null || appVersion.isBlank() || appVersion.length() > APP_VERSION_MAX) {
            problems.add("appVersion:invalid");
        }
        if (audio == null || audio.isEmpty() || audio.size() > AUDIO_FORMATS_MAX) {
            problems.add("audio:invalid");
        } else if (audio.stream().anyMatch(format -> format == null || !AUDIO_FORMAT.matcher(format).matches())) {
            problems.add("audio:format");
        }
        if (resume != null && resume.sessionId() == null) {
            problems.add("resume.sessionId:missing");
        }
        return problems;
    }
}
