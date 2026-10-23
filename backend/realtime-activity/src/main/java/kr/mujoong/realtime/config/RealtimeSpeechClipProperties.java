package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.httpUrl;
import static kr.mujoong.realtime.config.RealtimeProperties.invalid;
import static kr.mujoong.realtime.config.RealtimeProperties.positive;
import static kr.mujoong.realtime.config.RealtimeProperties.required;

import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.util.unit.DataSize;

/**
 * 3회차 V1: 연습 대본 줄 음성 미리 합성 {@code mujoong.speech-clip.*} (contracts/ai-io.md 10.16, 편차 (PDS-V1)). 시간 값이 아니다 — 합성 시한 · 대기는
 * {@code timing.speech-clip.*}(R-F7). 키 값은 env {@code OPENAI_API_KEY}로만 받고(새 비밀 없음) 이 레코드에 두지 않는다.
 *
 * @param enabled 스위치(기본 참). 참이어도 키가 없으면 꺼진다
 * @param baseUrl Audio API 기준 주소({@code {baseUrl}/audio/speech}). 허용 호스트 · 프로필 규칙은 Luna와 같다({@code LunaHosts})
 * @param model 합성 모델(기본 {@code gpt-4o-mini-tts})
 * @param voice 목소리(기본 {@code nova})
 * @param narratorStyle 무중(역할 밖 설명 · 제안 · 알림) 말투 지시. 비면 보내지 않는다
 * @param peerStyle 친구(장면 속 대사) 말투 지시. 비면 보내지 않는다
 * @param maxConcurrency 앱 전체에서 동시에 보내는 합성 요청 수
 * @param maxPending 차례를 기다리는 합성 요청 수 상한(넘으면 그 줄은 합성하지 않고 GPT-Live로 읽는다)
 * @param cacheMaxBytes 세션들이 함께 쓰는 합성 음성 캐시의 PCM 바이트 상한
 * @param sessionMaxLines 세션 하나가 미리 합성하는 대본 줄 수 상한
 */
public record RealtimeSpeechClipProperties(
        Boolean enabled,
        URI baseUrl,
        String model,
        String voice,
        String narratorStyle,
        String peerStyle,
        Integer maxConcurrency,
        Integer maxPending,
        DataSize cacheMaxBytes,
        Integer sessionMaxLines) {

    /** 모델 · 목소리 이름 형식(값을 로그에 남기지 않고 형식만 본다). */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    /** 말투 지시 상한(코드 포인트). */
    public static final int STYLE_MAX = 500;

    public RealtimeSpeechClipProperties {
        required(enabled, "mujoong.speech-clip.enabled");
        httpUrl(baseUrl, "mujoong.speech-clip.base-url");
        if (model == null || !NAME.matcher(model.strip()).matches()) {
            throw invalid("설정 키 형식이 올바르지 않다: mujoong.speech-clip.model");
        }
        model = model.strip();
        if (voice == null || !NAME.matcher(voice.strip()).matches()) {
            throw invalid("설정 키 형식이 올바르지 않다: mujoong.speech-clip.voice");
        }
        voice = voice.strip();
        narratorStyle = style(narratorStyle, "mujoong.speech-clip.narrator-style");
        peerStyle = style(peerStyle, "mujoong.speech-clip.peer-style");
        positive(maxConcurrency, "mujoong.speech-clip.max-concurrency");
        positive(maxPending, "mujoong.speech-clip.max-pending");
        positive(cacheMaxBytes, "mujoong.speech-clip.cache-max-bytes");
        positive(sessionMaxLines, "mujoong.speech-clip.session-max-lines");
    }

    private static String style(String value, String key) {
        String text = value == null ? "" : value.strip();
        if (text.codePointCount(0, text.length()) > STYLE_MAX || text.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("설정 키 형식이 올바르지 않다: " + key);
        }
        return text;
    }

    /** 말투 지시 원문은 문자열 표현에 내지 않는다. */
    @Override
    public String toString() {
        return "RealtimeSpeechClipProperties[enabled=" + enabled + ", model=" + model + ", voice=" + voice + "]";
    }
}
