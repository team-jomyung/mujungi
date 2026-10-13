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

        /** U3 {@code session.response.cancel}(끼어들기 · 종료 의도, U3-J11). */
        void cancelResponse(ProviderRequestFactory.ResponseCancel request) throws ProviderUnavailableException;

        /** U3 {@code session.instructions.correct}(게이트 폐기 뒤 고정 교정 문장, U3-J12). */
        void correctInstructions(ProviderRequestFactory.InstructionsCorrect request) throws ProviderUnavailableException;

        /** U4b {@code session.response.create}(발언권 반환, 편차 (U4b-a) · BR2.6). */
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

        /** {@code session.input_audio.speech_stopped}(tSpeechEnd 출처 PROVIDER_EVENT). */
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

        /** {@code session.output_transcript.delta}. */
        record OutputTranscriptDelta(String responseId, String delta) implements ProviderEvent {
        }

        /** {@code session.output_audio.delta}(PCM16LE). */
        record OutputAudioDelta(String responseId, byte[] audio) implements ProviderEvent {

            public OutputAudioDelta {
                audio = Objects.requireNonNull(audio, "audio").clone();
            }

            @Override
            public byte[] audio() {
                return audio.clone();
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof OutputAudioDelta that && Objects.equals(responseId, that.responseId)
                        && java.util.Arrays.equals(audio, that.audio);
            }

            @Override
            public int hashCode() {
                return Objects.hash(responseId, java.util.Arrays.hashCode(audio));
            }

            @Override
            public String toString() {
                return "OutputAudioDelta[responseId=" + responseId + ", bytes=" + audio.length + "]";
            }
        }

        /** {@code session.output.done}. */
        record OutputDone(String responseId) implements ProviderEvent {
        }

        /** {@code session.error}. code는 공급자가 준 짧은 값이고 로그에는 쓰지 않는다. */
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
