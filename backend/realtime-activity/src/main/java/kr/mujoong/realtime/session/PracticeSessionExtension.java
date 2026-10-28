package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;

/**
 * 연습 경로 확장점 (U4b). U5b는 타입 있는 출력과 재생 증거로 ACTIVE 안의 연습 단계를 진행한다.
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
 * 출력 능력 호출은 거절되고 PRACTICE_EXTENSION_FAILED{reason: NOT_ACTIVE}로 남는다. CLOSING · 정산 창 안의 확장점 사건은 전달되지만 신호는
 * 무시한다. 출력권을 가진 확장의 예외는 기록을 보존하며 종료하고, 기존 확장의 예외는 이전 자유대화 경로를 유지한다.
 */
public interface PracticeSessionExtension {

    /** 목표 끝 신호. */
    enum Signal {
        /** 처리 안 함. */
        NONE,
        /** 목표 하나가 끝났다 — 다음 목표로 계속. */
        NEXT_GOAL,
        /** 마지막 목표가 끝났다 — CLOSING(CHILD_STOP, PRACTICE_COMPLETED). */
        LAST_GOAL
        // FT-1023-g: 출력 실패로 연습을 끝내는 신호(OUTPUT_FAILED)는 없앴다. 확장은 못 들려준 발화를 건너뛰고 잇는다.
    }

    /**
     * ACTIVE 진입 때 넘기는 맥락.
     *
     * @param practiceJson 시작 패키지의 {@code practice} 조각(목표 목록 · 첫 상황, JSON 원문, 없으면 null)
     * @param approvedContentCodes 시작 패키지의 승인 문구 코드들({@link PracticeOutput#playApproved}에 쓸 수 있는 코드)
     * @param rawAudioConsent 동의 스냅숏의 원음 기록 동의
     * @param activeAt 활동 시작(ACTIVE 진입) 시각
     * @param approvedSpeechTexts 시작 패키지 approvedSpeech의 승인 발화 문장 지도(code → text, U12 D7). 음성 파일이 없는 승인 발화의 원문이며
     *     {@link kr.mujoong.realtime.voice.PracticeSpeech}로 {@link PracticeOutput#speak}에 낼 때 쓴다. 문자열 표현에는 내지 않는다
     */
    record PracticeContext(UUID sessionId, String practiceJson, Set<String> approvedContentCodes,
            boolean rawAudioConsent, Instant activeAt, java.util.Map<String, String> approvedSpeechTexts) {

        public PracticeContext {
            Objects.requireNonNull(sessionId, "sessionId");
            approvedContentCodes = approvedContentCodes == null ? Set.of() : Set.copyOf(approvedContentCodes);
            approvedSpeechTexts = approvedSpeechTexts == null ? java.util.Map.of() : java.util.Map.copyOf(approvedSpeechTexts);
        }

        /** U12 이전 모양(승인 발화 문장 지도 없음). 기존 확장 · 시험이 그대로 쓴다. */
        public PracticeContext(UUID sessionId, String practiceJson, Set<String> approvedContentCodes,
                boolean rawAudioConsent, Instant activeAt) {
            this(sessionId, practiceJson, approvedContentCodes, rawAudioConsent, activeAt, java.util.Map.of());
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

        /** 전사 원문은 문자열 표현에 넣지 않는다. */
        @Override
        public String toString() {
            return "Utterance[seq=" + seq + ", uncertain=" + uncertain + "]";
        }
    }

    /**
     * 확장점 사건.
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
            ASYNC_RESULT,
            /** 판정 결과(이름 {@code judgment}, 값 {@code {point, firstOption, secondOption}} — 확률 없음). */
            JUDGMENT_RESULT,
            /** 판정 보류 · 장애(값 {@code {point, abstainReason}}, 장애는 사유 FAULT). */
            JUDGMENT_ABSTAINED,
            /** 판정 중 모은 출력 전사(값 {@code {text}}, 비어 있으면 보내지 않는다). */
            HELD_OUTPUT_TRANSCRIPT,
            /**
             * 자리 · 정의: 연습 모드 제브 검사가 재지시 뒤에도 막혔다(값 {@link GuardrailBlockedEvent}, 전사 없음). 운영에서
             * 연습 모드 검사를 시작해 이 사건을 만드는 것은 U12다 — 열린 생성형 슬롯({@link PracticeOutput#speakGenerated})의 발화가 제브(연습 모드)의 두 번째 막힘을 만날 때만 보낸다.
             */
            GUARDRAIL_BLOCKED,
            /** 아이가 쉬고 싶다는 뜻을 밝혔다(출력권 연습에만 알린다). */
            REST_REQUESTED
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
     * 연습 모드 제브 검사가 재지시 뒤에도 막혔다는 알림 값({@link ExtensionEvent.Kind#GUARDRAIL_BLOCKED}의 값). 전사 원문 · 후보 문장은
     * 담지 않는다(R-F14).
     *
     * @param correlationId 연습 발화 correlationId(검사를 시작한 연습 발화)
     * @param mode PRACTICE_PEER 또는 PRACTICE_COACH
     * @param reasonCode 막힘 사유 코드(제브 사유 코드 또는 실패 종류)
     * @param retried 재지시를 한 번 한 뒤의 두 번째 막힘이면 참(지금은 언제나 참이다)
     */
    record GuardrailBlockedEvent(UUID correlationId, kr.mujoong.realtime.guardrail.GuardrailRequest.Mode mode,
            String reasonCode, boolean retried) {

        public GuardrailBlockedEvent {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(reasonCode, "reasonCode");
            if (mode == kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.FREE_TALK) {
                throw new IllegalArgumentException("연습 모드의 사건만 확장점에 알린다");
            }
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
     * 실행기가 확장점에 주는 출력 능력. ACTIVE일 때만 받고, 아니면 거짓을 돌려주며 PRACTICE_EXTENSION_FAILED{reason: NOT_ACTIVE}로
     * 남긴다. {@link #post}만 어느 스레드에서나 부를 수 있고, 나머지는 확장점 시점 안(실행기 스레드)에서만 부른다.
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

        /**
         * 아이 말이 지금 들어오는 중인가(입력 전사 묶음이 열려 확정 전이다). 읽기만 하고 상태를 바꾸지 않는다. 무응답 시계가 아이가 말하는 도중에
         * 만료돼 엉뚱한 재촉 발화를 내고 그 말을 늦게 판정하던 문제(2026-10-27 실키)를 막으려고 쓴다.
         */
        default boolean childSpeaking() { return false; }

        /**
         * 아이 확정 전사 하나에 대한 진행 중 판정을 요청한다. 거절(ACTIVE 아님 · 열린 턴 있음 · 지점 밖 · 최근 전사 4개 밖 ·
         * 공급자 없음)이면 거짓이다. 결과는 JUDGMENT_RESULT · JUDGMENT_ABSTAINED 사건으로 온다.
         */
        default boolean requestJudgment(kr.mujoong.realtime.judgment.JudgmentPoint point, long transcriptSeq, UUID correlationId,
                java.util.Map<String, Object> criteria) { return false; }

        /** 역할 전환 안내의 자리(지금은 규칙만 있고 동작은 없다). */
        default boolean announceRoleSwitch(String toRole) { return false; }

        /**
         * U12 D6 · GPT-Live가 만드는 모든 역할 발화(친구 되묻기 · 재안내 · 코치 도움 · 생성형 알림 · 변형 제안)를 내는 단 하나의 능력.
         * 실행부가 한 번에 ① 생성형 슬롯을 열고 ② 연습 출력 보류를 풀고 ③ 출력 세대를 올리고 ④ 지시를 {@code replayable=true}로 보내며(제브 첫
         * 막힘 뒤 재지시가 같은 역할 지시문을 다시 보낸다) ⑤ 송신 실패 · 새 말 미시작이면 기존 실패 길로 알린다. 기본은 거짓(낼 수 없음)이고,
         * 호출한 확장은 거짓을 '그 발화는 나가지 않았다'로 받아 연습을 멈추지 않고 이어 간다.
         *
         * <p>슬롯은 어떤 원인으로 닫혀도 확장에 종료 사실({@link PlaybackFact})을 정확히 한 번 보내고, 제브의 두 번째 막힘은
         * {@link ExtensionEvent.Kind#GUARDRAIL_BLOCKED}로 알린다. 지시문은 서버가 만든 템플릿 문장이며 아이 발화 · 모델 출력 · 판정 라벨 · 확률 ·
         * 판정 기준을 담지 않는다(R-F14).
         *
         * @param correlationId 이 발화의 식별자(종료 사실 · 막힘 사건이 같은 값으로 돌아온다)
         * @param role 말하는 역할: {@code PEER} 또는 {@code COACH}(제브 모드 PRACTICE_PEER · PRACTICE_COACH로 대응)
         * @param instructionText 역할 지시문 템플릿 문장(공급자 지시 길이 상한 안)
         */
        default boolean speakGenerated(UUID correlationId, String role, String instructionText) { return false; }

        /** '생각 중' 표시를 유지하거나(true) 풀도록(false) 요청한다. ACTIVE에서만 받는다. */
        /** Executor owns drain, generation and collection deadlines; flow owns playback deadline. */
        default boolean managesSpeechTimeouts() { return false; }

        default boolean setThinking(boolean on) { return false; }
    }

    record PlaybackFact(kr.mujoong.realtime.voice.PracticeSpeech speech,
            kr.mujoong.realtime.practicelegacy.PracticePorts.Playback status, String text, Instant receivedAt, Duration pausedFor) {
        public PlaybackFact {
            Objects.requireNonNull(pausedFor, "pausedFor");
            if (pausedFor.isNegative()) throw new IllegalArgumentException("negative playback pause");
        }
        public PlaybackFact(kr.mujoong.realtime.voice.PracticeSpeech speech,
                kr.mujoong.realtime.practicelegacy.PracticePorts.Playback status, String text, Instant receivedAt) {
            this(speech, status, text, receivedAt, Duration.ZERO);
        }
        public PlaybackFact(kr.mujoong.realtime.voice.PracticeSpeech speech,
                kr.mujoong.realtime.practicelegacy.PracticePorts.Playback status, String text) { this(speech, status, text, null); }
        /** U12: 아직 끝나지 않은 시작 사실(STARTED). 종료 사실이 아니다. */
        public boolean started() { return status == kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.STARTED; }
        /** U12: 끝까지 재생됐다(COMPLETED). 새 연습 흐름이 레거시 패키지 타입을 직접 보지 않게 하는 읽기 도우미다. */
        public boolean played() { return status == kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.COMPLETED; }
        /** U12: 아이가 끼어들어 끊겼다(INTERRUPTED). */
        public boolean interrupted() { return status == kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.INTERRUPTED; }
        @Override public String toString() { return "PlaybackFact[status=" + status + "]"; }
    }

    record Progress(int goalIndex, int goalCount, String goalName, String stage) { }

    /** 실제 연습은 출력권을 소유하고, 무동작·기존 확장은 기존 경로를 유지한다. */
    default boolean controlsOutput() { return false; }

    /** 안내 수집 중 일반 입력은 대화 턴을 열지 않는다. 입력 기록과 종료·휴식 처리는 실행기가 계속한다. */
    default boolean collectingIntroduction() { return false; }
    /** Whether an unplayed generated conversational response may yield to new child input without completing a task. */
    default boolean canYieldUnplayedSpeech() { return false; }

    default Progress progress() { return null; }

    /** Fixed state identifiers for operational diagnostics; no user/model text. */
    /** Consumes correlated technical-audio recovery events. */
    default boolean handlesAudioRecovery() { return false; }

    default String diagnosticPhase() { return "NONE"; }
    default String diagnosticSub() { return "NONE"; }

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
