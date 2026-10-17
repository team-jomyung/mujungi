package kr.mujoong.realtime.runtime;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gate.OutputGate;
import kr.mujoong.realtime.gateway.DeviceStatusPort;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.recording.AudioRecordings;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.NoOpPracticeSessionExtension;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.SessionGapPort;
import kr.mujoong.realtime.session.SessionSettlement;
import kr.mujoong.realtime.session.SessionTransitions;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import kr.mujoong.realtime.voice.ProviderConnector;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Realtime 실행 공통 빈 (U1 3.5 Step 23 · RD-2 · NFR6.4 · NFR7.3 · J139 · SD-18).
 *
 * <ul>
 *   <li>{@link MujoongClock}: 모든 시각 읽기의 유일한 원천. 시험 문맥은 가변 Clock 빈으로 바꿔 끼운다(빈이 이미 있으면 만들지 않는다).
 *   <li>{@link JsonCodec}: 계약 JSON 규칙(모르는 필드 무시 · 모르는 enum '알 수 없음' · ISO 8601 UTC). Jackson 직접 사용은 이 코덱 한 곳이다.
 *   <li>{@link TimerClocks}: 시계 1(안전 시한) · 시계 2(구간 조용 시간) 데몬 스레드 둘. 시험은 수동 구동기로 바꿔 끼운다.
 *   <li>{@link OutputGate}: 기동 점검(S1b)이 로딩한 시드 사전으로 만든 게이트(사전 로딩 완료가 readiness 조건, S6).
 *   <li>{@link TaskLauncher}: 보조 작업(세션 실행기 · 연결 송신기 · 입력 중계 · 공급자 연결 · 기록기 플러시)마다 가상 스레드.
 *   <li>{@link SessionExecutor.Services}: 세션 실행기들이 함께 쓰는 장치 묶음(PD-1).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class RealtimeRuntimeConfig {

    @Bean
    @ConditionalOnMissingBean(MujoongClock.class)
    MujoongClock mujoongClock() {
        return new SystemMujoongClock();
    }

    @Bean
    @ConditionalOnMissingBean(JsonCodec.class)
    JsonCodec jsonCodec() {
        return JsonCodec.shared();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(TimerClocks.class)
    TimerClocks timerClocks(MujoongClock clock) {
        return TimerClocks.system(clock);
    }

    @Bean
    @ConditionalOnMissingBean(TaskLauncher.class)
    TaskLauncher taskLauncher() {
        return TaskLauncher.virtualThreads();
    }

    @Bean
    OutputGate outputGate(RealtimeStartupChecks startupChecks) {
        return new OutputGate(startupChecks.dictionary());
    }

    @Bean
    SessionExecutor.Services sessionServices(
            SessionTransitions transitions,
            SocketRegistry sockets,
            DeviceStatusPort deviceStatus,
            ProviderConnector connector,
            ProviderRequestFactory requests,
            OutputGate gate,
            AsyncRecorder recorder,
            LatencyRecorder latency,
            TimerClocks clocks,
            TaskLauncher launcher,
            EventLogger events,
            RealtimeMetrics metrics,
            MujoongClock clock,
            RealtimeProperties properties,
            SessionGapPort gaps,
            AudioRecordings recordings,
            SessionSettlement settlement,
            org.springframework.beans.factory.ObjectProvider<PracticeSessionExtension.Factory> practice,
            kr.mujoong.realtime.judgment.JudgeClient judge,
            org.springframework.beans.factory.ObjectProvider<kr.mujoong.realtime.judgment.JudgmentLiveRecorder> judgmentRecorder,
            kr.mujoong.realtime.judgment.JudgmentPrompt judgmentPrompt,
            kr.mujoong.realtime.content.LiveContentRegistry liveContent,
            kr.mujoong.realtime.guardrail.GuardrailSupport guardrail) {
        // U4b(BR8.1): 종료 뒤 정산(결과 입력 스냅숏 + SessionRecordCaptured)은 RecordCaptureService가 맡는다.
        // U4b(BR10.2): 연습 확장점은 U5b가 빈을 등록하면 그것을, 없으면 무동작 공장을 쓴다
        return new SessionExecutor.Services(transitions, sockets::current, deviceStatus, connector, requests, gate,
                recorder, latency, clocks, launcher, events, metrics, clock, SessionExecutor.Settings.from(properties), gaps,
                recordings, settlement, practice.getIfAvailable(() -> NoOpPracticeSessionExtension.FACTORY),
                new kr.mujoong.realtime.session.JudgmentSupport(judge, judgmentRecorder.getIfAvailable(),
                        properties.timing().judgment().liveBudget(), judgmentPrompt.version(),
                        liveContent.resources().judgment()),
                // U13: 제브 게이트(모드는 기동 점검이 정했다). 정책 판은 세션에 고정되는 정책 리소스의 판이다. OFF면 검사 없음(DISABLED)이다
                guardrail.enabled() ? guardrail.withPolicyVersion(liveContent.resources().guardrail().version()) : guardrail);
    }
}
