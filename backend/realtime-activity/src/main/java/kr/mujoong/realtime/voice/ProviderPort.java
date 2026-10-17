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

        /**
         * {@code session.instructions.append}. 서버의 모든 발화는 이 지시 하나로 시작한다(응답 요청 type이 없다, 설계 개정 A2): 참여 뒤 첫 응답 ·
         * 이어 가기 · 연습 · 게이트 교정 · 판정 뒤 지시.
         */
        void appendInstructions(ProviderRequestFactory.InstructionsAppend request) throws ProviderUnavailableException;

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

        /**
         * {@code session.input_transcript.delta{start_ms, end_ms, delta, event_id}}(실호출 관측). 공급자는 입력 전사 항목 id도 완료 사건도 주지
         * 않는다 — {@code itemId}는 없다(null). 서버가 조각을 묶어 확정 전사를 만든다(설계 개정 A7).
         *
         * @param startMs 전사 시간선의 시작(ms, 없으면 -1)
         * @param endMs 전사 시간선의 끝(ms, 없으면 -1)
         */
        record InputTranscriptDelta(String itemId, String delta, long startMs, long endMs) implements ProviderEvent {

            public InputTranscriptDelta(String itemId, String delta) {
                this(itemId, delta, -1L, -1L);
            }
        }

        /**
         * 서버가 입력 전사 조각을 묶어 만든 <b>확정 전사</b>(설계 개정 A7). 공급자는 이 사건을 보내지 않는다 — 실행기가
         * {@code mujoong.timing.provider.input-transcript-quiet} 동안 조각이 없으면 이 레코드로 확정해 참여 확인 · 종료 의도 · 연습 흐름이 지금과
         * 같은 입구로 받게 한다. 신뢰도는 오지 않으므로(null) 불확실이 아니다(확실로 본다, U3-J17).
         */
        record InputTranscriptDone(String itemId, String transcript, Double confidence) implements ProviderEvent {

            public InputTranscriptDone(String itemId, String transcript) {
                this(itemId, transcript, null);
            }
        }

        /**
         * {@code session.output_transcript.delta{start_ms, end_ms, delta, event_id}}(실호출 관측). 응답 id가 없다(U11 BR11.3). 전사는 음성보다
         * 앞서 도착하고(0.2 ~ 2초) 시간은 전사 시간선이다 — 지시 뒤 새 말의 시작을 {@code startMs}로 정한다(설계 개정 A4).
         *
         * @param startMs 전사 시간선의 시작(ms, 없으면 -1)
         * @param endMs 전사 시간선의 끝(ms, 없으면 -1)
         */
        record OutputTranscriptDelta(String delta, long startMs, long endMs) implements ProviderEvent {

            public OutputTranscriptDelta(String delta) {
                this(delta, -1L, -1L);
            }
        }

        /**
         * {@code session.instructions.appended{client_event_id, start_ms, end_ms, event_id}}(실호출 관측). "세션 시간선이 추가한 지시의 추정 끝에
         * 닿을 때" 온다 — {@code endMs}가 지시 뒤 새 말이 시작할 수 있는 전사 시간선 위치의 하한이다(설계 개정 A4).
         *
         * @param clientEventId 우리가 {@code append}에 실은 {@code event_id}
         */
        record InstructionsAppended(String clientEventId, long startMs, long endMs) implements ProviderEvent {
        }

        /** {@code session.output_audio.delta}(PCM16LE 24kHz 모노, 응답 id · 시간 정보 없음 — 서버가 누적 바이트 / 48 = ms 오디오 시계를 센다). */
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
