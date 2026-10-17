package kr.mujoong.realtime.voice;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 음성 모델 공급자 포트 (U1 3.5 Step 25 · J110 · R3-1 · SD-20 · contracts/ai-io.md 2 · 3절).
 *
 * <p>앱 안 이벤트 형식이다. 구현은 {@link ProviderClient}(JDK WebSocket, 선 형식 J110) 하나이고 시험은 대역을 쓴다. 요청은
 * {@link ProviderRequestFactory}가 만든 레코드만 받는다(SD-20). 연결 열기({@link #open})는 세션 실행기가 아니라 공급자 연결 스레드가
 * 부른다. 리더(수신 콜백)는 이벤트를 {@link Listener}에 넘기고 리스너는 세션 일반 편지함에 넣는다(가득 차면 기다림 = 의도된 배압).
 */
public interface ProviderPort {

    /** 연결할 주소(허용 호스트 검사 대상, J109 · J141). */
    URI endpoint();

    /**
     * 공급자 WSS 연결을 연다. {@code session.start}는 아직 보내지 않는다(연결 스레드가 ProviderConnected를 편지함에 넣은 뒤 보내
     * session.started가 ProviderConnected보다 먼저 처리되지 않게 한다).
     *
     * @throws ProviderUnavailableException 연결 거부 · 시간 초과 · 프로토콜 오류
     * @throws InterruptedException 연결 중 취소
     */
    ProviderSession open(Listener listener, Duration timeout) throws ProviderUnavailableException, InterruptedException;

    /** 열린 공급자 세션 하나. 송신은 직렬화돼 있다(여러 스레드가 불러도 된다). */
    interface ProviderSession extends AutoCloseable {

        /** {@code session.start}. */
        void start(ProviderRequestFactory.SessionStart request) throws ProviderUnavailableException;

        /** {@code session.input_audio.append}. */
        void sendInputAudio(ProviderRequestFactory.InputAudioAppend request) throws ProviderUnavailableException;

        /** {@code session.instructions.append}(게이트 폐기 뒤 고정 교정 문장 · 연습 지시문 · 판정 뒤 지시, U11 BR11.4). */
        void appendInstructions(ProviderRequestFactory.InstructionsAppend request) throws ProviderUnavailableException;

        /** {@code response.create}(발언권 반환 · 지시 뒤 응답 요청, 편차 (U4b-a) → (U11-a)). */
        void createResponse(ProviderRequestFactory.ResponseCreate request) throws ProviderUnavailableException;

        /** {@code session.close}를 보내고 연결을 닫는다. 두 번 불러도 된다. 예외를 던지지 않는다. */
        void close(ProviderRequestFactory.SessionClose request);

        /** 연결을 바로 끊는다(정리 경로). */
        @Override
        void close();

        /** 아직 열려 있는가. */
        boolean isOpen();
    }

    /** 공급자 이벤트 수신자. 리더 스레드에서 불린다. */
    @FunctionalInterface
    interface Listener {

        void onEvent(ProviderEvent event);
    }

    /** 공급자 이벤트(선 형식 J110의 앱 안 표현). 본문은 로그에 쓰지 않는다(SD-15). */
    sealed interface ProviderEvent {

        /** {@code session.started}. */
        record Started(String providerSessionId) implements ProviderEvent {
        }

        /** {@code session.input_audio.speech_stopped}(미확인 이름, tSpeechEnd 출처 PROVIDER_EVENT). */
        record SpeechStopped() implements ProviderEvent {
        }

        /** {@code session.input_transcript.delta}. */
        record InputTranscriptDelta(String itemId, String delta) implements ProviderEvent {
        }

        /**
         * {@code session.input_transcript.done}. U3: 공급자가 주면 확정 전사의 신뢰도 {@code confidence}(0 ~ 1, 불확실 표시 근거, U3-J17).
         * 범위 밖이거나 없으면 null이다.
         */
        record InputTranscriptDone(String itemId, String transcript, Double confidence) implements ProviderEvent {

            public InputTranscriptDone(String itemId, String transcript) {
                this(itemId, transcript, null);
            }
        }

        /**
         * 출력 전사 조각. 응답 id가 없다(U11 BR11.3). 사건 이름은 실호출로 확인되지 않았다(미확인, 기존 {@code session.output_transcript.delta}를
         * 임시로 쓰고 실호출 확인 단계에서 확정한다).
         */
        record OutputTranscriptDelta(String delta) implements ProviderEvent {
        }

        /** {@code session.output_audio.delta}(PCM16LE, 응답 id 없음 — 응답 경계는 출력 세대가 조각 사이 무음으로 본다). */
        record OutputAudioDelta(byte[] audio) implements ProviderEvent {

            public OutputAudioDelta {
                audio = Objects.requireNonNull(audio, "audio").clone();
            }

            @Override
            public byte[] audio() {
                return audio.clone();
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof OutputAudioDelta that && java.util.Arrays.equals(audio, that.audio);
            }

            @Override
            public int hashCode() {
                return java.util.Arrays.hashCode(audio);
            }

            @Override
            public String toString() {
                return "OutputAudioDelta[bytes=" + audio.length + "]";
            }
        }

        /** {@code session.usage.updated}: 누적 사용 초(지표용, 전사 · 음성 없음). */
        record UsageUpdated(double seconds) implements ProviderEvent {
        }

        /** {@code error{error{type, code, param}}}. code는 공급자가 준 짧은 값이고 로그에는 쓰지 않는다(message 원문은 읽지 않는다). */
        record Error(String code) implements ProviderEvent {
        }

        /** {@code session.closed} 또는 전송 끊김. */
        record Closed() implements ProviderEvent {
        }
    }

    /** 공급자 연결 불가(PROVIDER_UNAVAILABLE). 메시지에 주소 · 키 · 본문을 넣지 않는다. */
    final class ProviderUnavailableException extends Exception {

        private static final long serialVersionUID = 1L;

        /** 실패 사유(로그 errorKind 대용). */
        public enum Reason {
            HOST_NOT_ALLOWED,
            CONNECT_FAILED,
            TIMEOUT,
            SEND_FAILED,
            CLOSED
        }

        private final Reason reason;

        public ProviderUnavailableException(Reason reason) {
            super("공급자 연결 불가: " + reason);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public ProviderUnavailableException(Reason reason, Throwable cause) {
            super("공급자 연결 불가: " + reason, cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Reason reason() {
            return reason;
        }
    }
}
