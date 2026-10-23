package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.invalid;
import static kr.mujoong.realtime.config.RealtimeProperties.positive;
import static kr.mujoong.realtime.config.RealtimeProperties.required;

import java.time.Duration;

/**
 * 계약 7절 시간 키와 7절 밖 시간 키(recorder). U8(근거: contracts/timing.md 13절)이 판정 · 가드레일 · 새 연습 흐름 묶음을 더했다.
 *
 * <p>{@code mujoong.timing.*} 아래 묶음을 한 파일에 모았다. 루트 설정 레코드 {@link RealtimeProperties}가 이 타입을 {@code timing}으로 받는다.
 */
public record RealtimeTimingProperties(
        SessionTiming session,
        DeviceTiming device,
        RecorderTiming recorder,
        JudgmentTiming judgment,
        GuardrailTiming guardrail,
        ConversationTiming conversation,
        ProviderTiming provider,
        SpeechClipTiming speechClip) {
    public RealtimeTimingProperties {
        required(session, "mujoong.timing.session");
        required(device, "mujoong.timing.device");
        required(recorder, "mujoong.timing.recorder");
        required(judgment, "mujoong.timing.judgment");
        required(guardrail, "mujoong.timing.guardrail");
        required(conversation, "mujoong.timing.conversation");
        required(provider, "mujoong.timing.provider");
        required(speechClip, "mujoong.timing.speech-clip");
    }

    /**
     * 3회차 V1(timing.md 15절): 연습 대본 줄 음성 미리 합성의 시간 값.
     *
     * @param synthesisTimeout 합성 요청 하나의 상한(보내기 → 본문 끝). 넘기면 그 줄은 합성 실패이고 GPT-Live 원문 읽기로 낸다
     * @param readyWait 대본 줄을 낼 차례에 그 줄의 합성이 아직 끝나지 않았을 때 기다리는 상한. 넘기면 GPT-Live 원문 읽기로 낸다
     */
    public record SpeechClipTiming(Duration synthesisTimeout, Duration readyWait) {
        public SpeechClipTiming {
            positive(synthesisTimeout, "mujoong.timing.speech-clip.synthesis-timeout");
            positive(readyWait, "mujoong.timing.speech-clip.ready-wait");
        }
    }

    /**
     * U11(timing.md 14절 · 설계 개정 A4 · A7): 공급자 출력 세대와 입력 전사 묶음의 시간 값.
     *
     * @param inputTranscriptQuiet 입력 전사 조각이 이만큼 오지 않으면 그 묶음을 확정 전사로 본다(말 끝 · 전사 완료 사건이 없다, A7)
     * @param newSpeechMargin 지시 뒤 새 말의 전사 시작 시각에 더하는 여유. 오디오 시계가 (새 말 시작 + 이 값)에 닿는 첫 음성 조각부터 받는다(A4)
     * @param instructionDeferMax 4회차 R2: 공급자가 말하는 중에 연습 발화 지시를 미루는 상한. 그 안에 출력이 조용해지면 그때 보내고, 넘으면 말하는 중에
     *     보낸다(A4-b, timing.md 14절)
     */
    public record ProviderTiming(Duration inputTranscriptQuiet, Duration newSpeechMargin, Duration instructionDeferMax) {
        public ProviderTiming {
            positive(inputTranscriptQuiet, "mujoong.timing.provider.input-transcript-quiet");
            positive(newSpeechMargin, "mujoong.timing.provider.new-speech-margin");
            positive(instructionDeferMax, "mujoong.timing.provider.instruction-defer-max");
        }
    }

    /** U8(timing.md 13절): 진행 중 판정 Luna 한 번의 상한 · 아이 말 끝 뒤 응답 준비 대기. U11이 읽는다. */
    public record JudgmentTiming(Duration liveBudget, Duration responseReady) {
        public JudgmentTiming {
            positive(liveBudget, "mujoong.timing.judgment.live-budget");
            positive(responseReady, "mujoong.timing.judgment.response-ready");
        }
    }

    /** U8(timing.md 13절): 제브 가드레일 검사 한 번의 상한. 넘기면 막힘이다. U13이 읽는다. */
    public record GuardrailTiming(Duration jevBudget) {
        public GuardrailTiming {
            positive(jevBudget, "mujoong.timing.guardrail.jev-budget");
        }
    }

    /**
     * U8(timing.md 13절): 새 연습 흐름의 무응답 재안내 대기(AC1.9.1) · 집중 연습 상한(AC1.5.4) · 목표 진행 상한. U5b의
     * {@code timing.practice}와 이름 공간을 나눴다. U12가 읽는다.
     */
    public record ConversationTiming(Duration noResponseWait, Duration focusMax, Duration goalActiveLimit) {
        public ConversationTiming {
            positive(noResponseWait, "mujoong.timing.conversation.no-response-wait");
            positive(focusMax, "mujoong.timing.conversation.focus-max");
            positive(goalActiveLimit, "mujoong.timing.conversation.goal-active-limit");
        }
    }

    /**
     * ② 기기 응답 20초((h) PROVIDER_UNAVAILABLE에도) · ③ 마무리 확인 30초. U4b: 참여 창 처음 20초 · 안내 뒤 20초
     * 공백 경고 4분 · 종료 6분, 종료 확인 질문 창 20초(계약 7절 밖), 정산 창 2초(
     * 계약 7절 밖). 공백 경고는 공백 종료보다 짧아야 한다.
     */
    public record SessionTiming(
            Duration deviceAckTimeout,
            Duration closingConfirm,
            Duration joinWait,
            Duration joinWaitAfterReprompt,
            Duration silenceWarning,
            Duration silenceEnd,
            Duration stopConfirmWait,
            Duration recordSettle) {

        public SessionTiming {
            positive(deviceAckTimeout, "mujoong.timing.session.device-ack-timeout");
            positive(closingConfirm, "mujoong.timing.session.closing-confirm");
            positive(joinWait, "mujoong.timing.session.join-wait");
            positive(joinWaitAfterReprompt, "mujoong.timing.session.join-wait-after-reprompt");
            positive(silenceWarning, "mujoong.timing.session.silence-warning");
            positive(silenceEnd, "mujoong.timing.session.silence-end");
            positive(stopConfirmWait, "mujoong.timing.session.stop-confirm-wait");
            positive(recordSettle, "mujoong.timing.session.record-settle");
            if (silenceWarning.compareTo(silenceEnd) >= 0) {
                throw invalid("mujoong.timing.session.silence-warning은 mujoong.timing.session.silence-end보다 짧아야 한다");
            }
        }
    }

    /**
     * 끊김 40초 · HEARTBEAT 주기(HELLO_OK로 내려줌) · HELLO 5초 · 상태 보고 10초 · 출력 대기열 20초 분량. U3: 불안정 판정의
     * 왕복 기준 1초와 끊김 횟수 창 5분(①).
     */
    public record DeviceTiming(
            Duration disconnect,
            Duration heartbeatInterval,
            Duration helloTimeout,
            Duration statusReportInterval,
            Duration outputQueueMax,
            Duration unstableRtt,
            Duration unstableDisconnectWindow,
            Duration gapDisplay) {

        /** U4b: 마지막 기기 메시지 뒤 이 시간이면 기술 일시정지 · 끊김 구간(점선 10초). 끊김 40초보다 짧아야 한다. */
        public DeviceTiming {
            positive(gapDisplay, "mujoong.timing.device.gap-display");
            if (gapDisplay != null && disconnect != null && gapDisplay.compareTo(disconnect) >= 0) {
                throw invalid("mujoong.timing.device.gap-display는 mujoong.timing.device.disconnect보다 짧아야 한다");
            }
            positive(disconnect, "mujoong.timing.device.disconnect");
            positive(heartbeatInterval, "mujoong.timing.device.heartbeat-interval");
            positive(helloTimeout, "mujoong.timing.device.hello-timeout");
            positive(statusReportInterval, "mujoong.timing.device.status-report-interval");
            positive(outputQueueMax, "mujoong.timing.device.output-queue-max");
            positive(unstableRtt, "mujoong.timing.device.unstable-rtt");
            positive(unstableDisconnectWindow, "mujoong.timing.device.unstable-disconnect-window");
        }
    }

    /** PD-5 기록기 플러시 주기(7절 밖 시간 키). */
    public record RecorderTiming(Duration flushInterval) {
        public RecorderTiming {
            positive(flushInterval, "mujoong.timing.recorder.flush-interval");
        }
    }
}
