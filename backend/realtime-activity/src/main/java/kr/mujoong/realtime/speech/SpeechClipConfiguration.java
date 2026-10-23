package kr.mujoong.realtime.speech;

import java.net.URI;
import java.util.Optional;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.config.RealtimeSpeechClipProperties;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.realtime.runtime.TimerClocks;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import kr.mujoong.realtime.startup.StartupFailureLine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 대본 줄 음성 빈(3회차 V1). 키 · 스위치와 관계없이 기동 때 한 번 {@code mujoong.speech-clip.base-url}의 합성 끝점을 활성 프로필의 주소 규칙(Luna와 같은
 * {@link LunaHosts})으로 확인하고 어기면 기동을 거부한다(값 미출력). 스위치가 켜져 있고 env {@value OpenAiSpeechClipSynthesizer#API_KEY_ENV}가 있을 때만
 * 실제 합성기를 쓰고, 아니면 꺼진 장치({@link SpeechClipSupport#disabled})다 — 그때 모든 대본 줄은 이전처럼 GPT-Live 원문 읽기다. 시험은 키를
 * 지우므로(빌드 규칙) 꺼진다.
 */
@Configuration(proxyBeanMethods = false)
public class SpeechClipConfiguration {

    /** 거부 줄의 errorKind. */
    static final String ERROR_KIND = "SpeechClipStartupRejected";

    @Bean(destroyMethod = "close")
    SpeechClipSupport speechClipSupport(RealtimeProperties properties, Environment environment, TimerClocks clocks,
            MujoongClock clock, JsonCodec json) {
        RealtimeSpeechClipProperties settings = properties.speechClip();
        LunaHosts.Mode mode = LunaHosts.modeFor(environment.getActiveProfiles());
        URI endpoint = OpenAiSpeechClipSynthesizer.endpoint(settings.baseUrl());
        Optional<LunaHosts.Problem> problem = LunaHosts.check(endpoint, mode);
        if (problem.isPresent()) {
            String reason = "대본 줄 음성 합성 기동 확인 거부(값 미출력): mujoong.speech-clip.base-url(" + problem.get() + ", " + mode + ")";
            StartupFailureLine.print(ERROR_KIND, reason);
            throw new RealtimeStartupChecks.StartupRejectedException(reason);
        }
        if (!settings.enabled()) {
            return SpeechClipSupport.disabled(SpeechClipSupport.State.DISABLED_SWITCH_OFF);
        }
        if (!keyPresent(environment)) {
            return SpeechClipSupport.disabled(SpeechClipSupport.State.DISABLED_NO_KEY);
        }
        var timing = properties.timing().speechClip();
        OpenAiSpeechClipSynthesizer synthesizer = new OpenAiSpeechClipSynthesizer(settings.baseUrl(), mode,
                () -> environment.getProperty(OpenAiSpeechClipSynthesizer.API_KEY_ENV), settings.model(), settings.voice(),
                settings.narratorStyle(), settings.peerStyle(), timing.synthesisTimeout(), SpeechClipSupport.MAX_CLIP_BYTES,
                clocks.safety(), json);
        return new SpeechClipSupport(synthesizer, clock, timing.readyWait(), settings.maxConcurrency(), settings.maxPending(),
                settings.cacheMaxBytes().toBytes(), settings.sessionMaxLines());
    }

    /** env 키가 비어 있지 않은가(값은 보관하지 않는다). */
    static boolean keyPresent(Environment environment) {
        String key = environment.getProperty(OpenAiSpeechClipSynthesizer.API_KEY_ENV);
        return key != null && !key.isBlank();
    }
}
