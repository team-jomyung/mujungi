package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;

/**
 * 연습 경로 확장점 (U4b · FR9.1 · BR10.1 ~ BR10.6 · R-10). U5b는 타입 있는 출력과 재생 증거로 ACTIVE 안의 연습 단계를 진행한다.
 *
 * <p>PRACTICE 세션에서만, 실행기 스레드에서 부른다(세션당 직렬). 세션마다 {@link Factory}가 한 개를 만든다. 들어오는 시점은 다섯 개다.
 *
 * <ul>
 *   <li>{@link #onActive}: 참여 확인 뒤 ACTIVE 진입. 출력권을 가진 확장은 자유 응답 요청을 생략한다.
 *   <li>{@link #onChildUtterance}: ACTIVE 중 아이 확정 전사. 출력권을 가진 확장에는 종료 의도가 맡은 발화를 전달하지 않는다.
 *   <li>{@link #onExtensionEvent}: 확장점 사건(재생 완료 · 확장점 시계 만료 · 비동기 결과). 실행기 편지함을 거쳐 실행기 스레드에서 들어온다.
 *   <li>{@link #onSettle}: 종료 전이 뒤 정산 창이 끝난 다음 한 번(남은 목표 NOT_REACHED 정리 등).
 *   <li>{@link #practiceRecord}: 스냅숏 직전 practice 값(객체 또는 null, FD-Q4).
 * </ul>
 *
 * <p>상태를 바꾸려면 {@link Signal}을 돌려준다: NEXT_GOAL은 계속, LAST_GOAL은 CLOSING(CHILD_STOP, end_detail PRACTICE_COMPLETED). 출력은
 * {@link PracticeOutput}의 능력으로만 낸다 — 상태 전이 · DB 쓰기 · 기기 원시 명령은 능력에 없다. ACTIVE가 아닐 때(CLOSING · 정산 창 · 종료)의
 * 출력 능력 호출은 거절되고 PRACTICE_EXTENSION_FAILED{reason: NOT_ACTIVE}로 남는다(R-10). CLOSING · 정산 창 안의 확장점 사건은 전달되지만 신호는
 * 무시한다. 출력권을 가진 확장의 예외는 기록을 보존하며 종료하고, 기존 확장의 예외는 이전 자유대화 경로를 유지한다.
 */
public interface PracticeSessionExtension {

    /** 목표 끝 신호(BR10.3). */
    enum Signal {
        /** 처리 안 함. */
        NONE,
        /** 목표 하나가 끝났다 — 다음 목표로 계속. */
        NEXT_GOAL,
        /** 마지막 목표가 끝났다 — CLOSING(CHILD_STOP, PRACTICE_COMPLETED). */
        LAST_GOAL
    }

    /**
     * ACTIVE 진입 때 넘기는 맥락.
     *
     * @param practiceJson 시작 패키지의 {@code practice} 조각(목표 목록 · 첫 상황, JSON 원문, 없으면 null)
     * @param approvedContentCodes 시작 패키지의 승인 문구 코드들({@link PracticeOutput#playApproved}에 쓸 수 있는 코드)
     * @param rawAudioConsent 동의 스냅숏의 원음 기록 동의
     * @param activeAt 활동 시작(ACTIVE 진입) 시각
     */
    record PracticeContext(UUID sessionId, String practiceJson, Set<String> approvedContentCodes,
            boolean rawAudioConsent, Instant activeAt) {

        public PracticeContext {
            Objects.requireNonNull(sessionId, "sessionId");
            approvedContentCodes = approvedContentCodes == null ? Set.of() : Set.copyOf(approvedContentCodes);
        }

        /** 연습 조각은 문자열 표현에 넣지 않는다. */
        @Override
        public String toString() {
            return "PracticeContext[sessionId=" + sessionId + "]";
        }
    }

    /**
     * 아이 확정 전사 하나.
     *
     * @param devicePlaying 확정 때 기기가 재생 중이었는가
     */
    record Utterance(int seq, Instant confirmedAt, String text, boolean uncertain, boolean devicePlaying) {

        /** 전사 원문은 문자열 표현에 넣지 않는다(BR12.1). */
        @Override
        public String toString() {
            return "Utterance[seq=" + seq + ", uncertain=" + uncertain + "]";
        }
    }

    /**
     * 확장점 사건(BR10.5).
     *
     * @param name 확장점이 붙인 이름(재생은 승인 문구 코드, 시계는 예약 이름, 비동기 결과는 post 이름)
     * @param correlationId 재생이면 playbackId(그 밖은 null)
     * @param value 비동기 결과 값(그 밖은 null)
     */
    record ExtensionEvent(Kind kind, String name, UUID correlationId, Object value) {

        /** 사건 종류. */
        public enum Kind {
            PLAYBACK_DONE,
            PLAYBACK_FACT,
            TIMER_FIRED,
            ASYNC_RESULT
        }

        public ExtensionEvent {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String toString() {
            return "ExtensionEvent[kind=" + kind + ", name=" + name + "]";
        }
    }

    /**
     * 종료 맥락(정산 시점).
     *
     * @param eventsInSettleWindow 종료 전이 뒤 정산 창 안에 확장점에 전달한 사건 수
     */
    record EndContext(UUID sessionId, SessionStatus finalStatus, EndReason endReason, String endDetail, Instant endedAt,
            int eventsInSettleWindow) {
    }

    /**
     * 실행기가 확장점에 주는 출력 능력(BR10.4). ACTIVE일 때만 받고, 아니면 거짓을 돌려주며 PRACTICE_EXTENSION_FAILED{reason: NOT_ACTIVE}로
     * 남긴다(R-10). {@link #post}만 어느 스레드에서나 부를 수 있고, 나머지는 확장점 시점 안(실행기 스레드)에서만 부른다.
     */
    interface PracticeOutput {

        /** 이 세션. */
        UUID sessionId();

        /** 패키지의 승인 문구를 재생한다(말풍선 행). 끝나면 PLAYBACK_DONE이 돌아온다(패키지에 없으면 곧바로). */
        boolean playApproved(String contentCode);

        /** 공급자 지시문을 더한다(검사된 문장만, 길이 상한 · 제어 문자 금지). */
        boolean instructProvider(String text);

        /** 공급자에 응답 생성을 한 번 요청한다. */
        boolean requestResponse();

        /** 공급자의 지금 응답을 취소한다. */
        boolean cancelResponse();

        /** 공급자 출력을 막거나 푼다(막은 동안 응답은 취소되고 기기로 가지 않는다). */
        boolean holdOutput(boolean hold);

        /** 이름 붙은 확장점 시계를 건다(같은 이름은 바꾼다). 만료는 TIMER_FIRED로 돌아오고 기술 일시정지 동안 멈춘다. */
        boolean schedule(String name, Duration delay);

        /** 이름 붙은 확장점 시계를 취소한다. */
        boolean cancel(String name);

        /** 다른 스레드에서 끝난 비동기 작업 결과를 ASYNC_RESULT로 편지함에 넣는다. 세션이 끝났으면 거짓. */
        boolean post(String name, Object value);

        default boolean speak(kr.mujoong.realtime.voice.PracticeSpeech speech) { return false; }

        default boolean cancelSpeech(UUID correlationId) { return false; }
    }

    record PlaybackFact(kr.mujoong.realtime.voice.PracticeSpeech speech,
            kr.mujoong.realtime.practice.PracticePorts.Playback status, String text, Instant receivedAt, Duration pausedFor) {
        public PlaybackFact {
            Objects.requireNonNull(pausedFor, "pausedFor");
            if (pausedFor.isNegative()) throw new IllegalArgumentException("negative playback pause");
        }
        public PlaybackFact(kr.mujoong.realtime.voice.PracticeSpeech speech,
                kr.mujoong.realtime.practice.PracticePorts.Playback status, String text, Instant receivedAt) {
            this(speech, status, text, receivedAt, Duration.ZERO);
        }
        public PlaybackFact(kr.mujoong.realtime.voice.PracticeSpeech speech,
                kr.mujoong.realtime.practice.PracticePorts.Playback status, String text) { this(speech, status, text, null); }
        @Override public String toString() { return "PlaybackFact[status=" + status + "]"; }
    }

    record Progress(int goalIndex, int goalCount, String goalName, String stage) { }

    /** 실제 연습은 출력권을 소유하고, 무동작·기존 확장은 기존 경로를 유지한다. */
    default boolean controlsOutput() { return false; }

    default Progress progress() { return null; }

    default void onStopping() { }

    default Duration settlementDelay(Instant now) { return Duration.ZERO; }

    /** 세션마다 확장점 하나를 만든다(Spring 빈). U5b가 빈을 등록하지 않으면 {@link NoOpPracticeSessionExtension#FACTORY}다. */
    @FunctionalInterface
    interface Factory {

        PracticeSessionExtension create(UUID sessionId);
    }

    Signal onActive(PracticeContext context, PracticeOutput output);

    Signal onChildUtterance(Utterance utterance, PracticeOutput output);

    Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output);

    void onSettle(EndContext end);

    /** 스냅숏의 practice 값(객체 또는 null). */
    Object practiceRecord();
}
