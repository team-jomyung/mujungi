package kr.mujoong.realtime.practiceflow;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practiceflow.FlowState.Phase;
import kr.mujoong.realtime.practiceflow.FlowState.Sub;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;

/**
 * 장면 전이표 (U12 BR3 ~ BR6 · functional-spec §3.3 · §3.3a). 현재 단계 · 소단계와 입력(판정 지점 + 선택지 코드, 슬롯 종료 사건, 시계 만료 표지)을
 * 받아 다음 단계 · 사실 · 변형 생략 사유 · 진행 중 판정 시점을 {@link Step}으로 돌려주는 <b>순수</b> 함수 묶음이다. 입출력(재생 · 판정 요청 ·
 * 시계 · 기록)은 하지 않고 {@link FlowState}를 바꾸지도 않는다 — 적용은 {@link PracticeConversation}이 한다.
 *
 * <p>한도는 {@code Instant} 차이가 아니라 시계 만료 표지({@link FlowState#focusLimitReached()} · {@link FlowState#goalLimitReached()})와 횟수로
 * 판단한다(D3). 표에 없는 입력은 오류가 아니라 {@link Action#IGNORE}다(BR3.1). 계약의 기록 사실 15종 · 변형 생략 사유 5종 · 진행 중 판정 시점
 * 5종만 쓴다. 아이 전사 · 대본 문장 · 판정 입력은 받지도 돌려주지도 않는다(R-F14).
 */
public final class FlowTransitions {

    /** 한 번의 입력이 상태 기계에 시키는 일. */
    public enum Action {
        /** 단계 · 소단계를 바꾸고 그 단계의 첫 행동(재생 · 판정 대기)을 시작한다. */
        ENTER,
        /** 같은 단계의 질문을 교정 없이 한 번 다시 안내한다(무응답 첫 만료 · 판독 불가 · 보류 · 불명확 재안내). */
        REGUIDE,
        /** 상황 다시 설명(승인 발화)을 들려주고 {@link Step#phase()}의 소단계 REEXPLAIN으로 둔다. */
        REEXPLAIN,
        /** 재생이 끝났으니 입력을 기다린다(무응답 시계를 건다). */
        AWAIT,
        /** 표에 없는 입력(중복 · 늦은 사건). 상태를 바꾸지 않는다. */
        IGNORE
    }

    /** 확장 슬롯이 닫힌 원인(§3.5). */
    public enum SlotEnd { PLAYED, INTERRUPTED, FAILED, BLOCKED }

    /** 어느 재안내 표지를 세우는가. */
    public enum Guard { NONE, NO_RESPONSE, UNREADABLE }

    /** 장면이 정리로 들어간 원인(§3.3a의 행). 변형 제안 · 생략 사유를 정한다. */
    public enum EndCause {
        /** 정상으로 수행되어 끝났다(FULL · DELIVERED · COPIED). */
        NORMAL,
        /** 도움 제안을 아이가 거절했다. */
        HELP_DECLINED,
        /** 무응답 둘째 만료(미실시). */
        TIMEOUT,
        /** 판독 불가 두 번째(미실시). */
        TRANSCRIPT_FAILED,
        /** 판정 보류 · 장애 두 번째. */
        ABSTAINED,
        /** 도움 제안에 불명확 두 번째. */
        HELP_UNCLEAR,
        /** 슬롯 두 번째 막힘. */
        SLOT_BLOCKED,
        /** 재생 실패 · 알림 끊김. */
        PLAYBACK_FAILED,
        /** 목표 10분 만료. */
        TIME_LIMIT,
        /** 집중 90초 · 다시 해 보기 한도. */
        FOCUS_LIMIT,
        /** 종료 · 휴식 의사. */
        END_REQUEST
    }

    /**
     * 전이 결과 한 건. 불변이 아니라 만든 쪽이 사슬로 채우는 값이지만 만든 뒤에는 읽기만 한다.
     *
     * <p>{@code cause}가 있으면 SCENE_WRAP 진입이고, {@code skip}이 있으면 변형을 생략한 것이다.
     */
    public static final class Step {

        private final Action action;
        private final Phase phase;
        private final Sub sub;
        private final List<FactKind> facts = new ArrayList<>(2);
        private LiveTiming timing;
        private EndCause cause;
        private ConsentAnswer consent;
        private VariantSkipReason skip;
        private Guard guard = Guard.NONE;
        private FlowRole role;
        private boolean incrementRetry;
        private boolean incrementReexplain;
        private boolean startVariant;
        private boolean helpConsented;
        private boolean endRequest;

        private Step(Action action, Phase phase, Sub sub) {
            this.action = action;
            this.phase = phase;
            this.sub = sub;
        }

        static Step ignore() {
            return new Step(Action.IGNORE, null, null);
        }

        static Step await() {
            return new Step(Action.AWAIT, null, null);
        }

        static Step enter(Phase phase, Sub sub) {
            return new Step(Action.ENTER, phase, sub);
        }

        static Step reguide(Guard guard) {
            Step step = new Step(Action.REGUIDE, null, null);
            step.guard = guard;
            return step;
        }

        static Step reexplain(Phase phase) {
            return new Step(Action.REEXPLAIN, phase, Sub.REEXPLAIN).incrementReexplain().fact(FactKind.SITUATION_EXPLAINED);
        }

        Step fact(FactKind kind) {
            if (kind != null) {
                facts.add(kind);
            }
            return this;
        }

        Step timing(LiveTiming value) {
            this.timing = value;
            return this;
        }

        Step cause(EndCause value) {
            this.cause = value;
            return this;
        }

        Step consent(ConsentAnswer value) {
            this.consent = value;
            return this;
        }

        Step skip(VariantSkipReason value) {
            this.skip = value;
            return this;
        }

        Step role(FlowRole value) {
            this.role = value;
            return this;
        }

        Step incrementRetry() {
            this.incrementRetry = true;
            return this;
        }

        Step incrementReexplain() {
            this.incrementReexplain = true;
            return this;
        }

        Step startVariant() {
            this.startVariant = true;
            return this;
        }

        Step helpConsented() {
            this.helpConsented = true;
            return this;
        }

        Step endRequest() {
            this.endRequest = true;
            return this;
        }

        public Action action() {
            return action;
        }

        /** 다음 단계(IGNORE · AWAIT · REGUIDE는 null). */
        public Phase phase() {
            return phase;
        }

        public Sub sub() {
            return sub;
        }

        /** 이 전이에서 일어난 사실(순서대로, 없으면 빈 목록). */
        public List<FactKind> facts() {
            return List.copyOf(facts);
        }

        /** 사실 하나만 필요한 시험 · 호출용(없으면 null). */
        public FactKind fact() {
            return facts.isEmpty() ? null : facts.getFirst();
        }

        public LiveTiming timing() {
            return timing;
        }

        public EndCause cause() {
            return cause;
        }

        public ConsentAnswer consent() {
            return consent;
        }

        public VariantSkipReason skip() {
            return skip;
        }

        public Guard guard() {
            return guard;
        }

        /** 이 전이로 바꿀 역할(바꾸지 않으면 null). */
        public FlowRole role() {
            return role;
        }

        public boolean incrementsRetry() {
            return incrementRetry;
        }

        public boolean incrementsReexplain() {
            return incrementReexplain;
        }

        /** 변형 장면 시작인가(D9). */
        public boolean startsVariant() {
            return startVariant;
        }

        /** 아이가 도움에 동의했거나 요청했다. */
        public boolean consentsToHelp() {
            return helpConsented;
        }

        /** 종료 · 휴식 의사로 닫는다. */
        public boolean endsByRequest() {
            return endRequest;
        }

        @Override
        public String toString() {
            return "Step[" + action + ", " + phase + "/" + sub + ", facts=" + facts + "]";
        }
    }

    /** 변형 평가 결과(§3.3a). */
    public record VariantDecision(boolean offer, VariantSkipReason skip) {
    }

    private final int retryMax;
    private final int reexplainMax;

    /**
     * @param retryMax 장면당 다시 해 보기 최대 횟수(mujoong.practice-flow.retry-max)
     * @param reexplainMax 장면당 상황 다시 설명 최대 횟수(mujoong.practice-flow.reexplain-max)
     */
    public FlowTransitions(int retryMax, int reexplainMax) {
        if (retryMax < 0 || reexplainMax < 0) {
            throw new IllegalArgumentException("횟수 한도는 0 이상이어야 한다");
        }
        this.retryMax = retryMax;
        this.reexplainMax = reexplainMax;
    }

    /** 단계마다 묶인 판정 지점(OPPORTUNITY=J2, REASK=J3, HELP_OFFER=J4, RETRY=J5, VARIANT_OFFER=J4 재사용). */
    public static JudgmentPoint expectedPoint(Phase phase) {
        return switch (phase) {
            case OPPORTUNITY -> JudgmentPoint.J2;
            case REASK -> JudgmentPoint.J3;
            case HELP_OFFER, VARIANT_OFFER -> JudgmentPoint.J4;
            case RETRY -> JudgmentPoint.J5;
            default -> null;
        };
    }

    // ---- 판정 결과 ----

    /** 판정 결과 사건(지점 + 첫 선택지 코드)을 전이로 바꾼다. 모르는 코드는 보류와 같게 본다(교정 없이 이어 가기, BR3.7). */
    public Step onJudgment(FlowState s, JudgmentPoint point, String optionCode) {
        if (!acceptsJudgment(s, point)) {
            return Step.ignore();
        }
        String code = optionCode == null ? "" : optionCode;
        return switch (s.phase()) {
            case OPPORTUNITY -> opportunity(s, code);
            case REASK -> reask(s, code);
            case HELP_OFFER -> helpOffer(s, code);
            case RETRY -> retry(s, code);
            case VARIANT_OFFER -> variantOffer(s, code);
            default -> Step.ignore();
        };
    }

    /** 판정 보류 · 장애 사건. 판독 불가와 같은 재안내를 하되 두 번째에는 보류로 닫는다(§3.3a). */
    public Step onJudgmentUnavailable(FlowState s, JudgmentPoint point) {
        if (!acceptsJudgment(s, point)) {
            return Step.ignore();
        }
        return unavailable(s);
    }

    private static boolean acceptsJudgment(FlowState s, JudgmentPoint point) {
        return s.sub() == Sub.NONE && s.phase().awaitsInput() && expectedPoint(s.phase()) == point;
    }

    private Step opportunity(FlowState s, String code) {
        return switch (code) {
            case "FULL" -> wrap(s, EndCause.NORMAL, null, LiveTiming.IMMEDIATE);
            case "PARTIAL" -> Step.enter(Phase.REASK, Sub.NONE);
            case "HELP" -> helpRequested();
            case "NOT_UNDERSTOOD", "IRRELEVANT" -> s.reexplainCount() < reexplainMax
                    ? Step.reexplain(Phase.OPPORTUNITY)
                    : Step.enter(Phase.HELP_OFFER, Sub.NONE);
            case "UNREADABLE" -> unreadable(s);
            default -> unavailable(s);
        };
    }

    private Step reask(FlowState s, String code) {
        return switch (code) {
            case "DELIVERED" -> wrap(s, EndCause.NORMAL, FactKind.REASK_RESOLVED, LiveTiming.AFTER_REASK);
            case "STILL_SHORT" -> Step.enter(Phase.HELP_OFFER, Sub.NONE);
            case "HELP" -> helpRequested();
            case "UNREADABLE" -> unreadable(s);
            default -> unavailable(s);
        };
    }

    private Step helpOffer(FlowState s, String code) {
        return switch (code) {
            case "ACCEPT" -> Step.enter(Phase.FOCUS, Sub.ROLE_NOTICE).fact(FactKind.HELP_ACCEPTED).consent(ConsentAnswer.ACCEPT)
                    .helpConsented();
            case "REFUSE" -> wrap(s, EndCause.HELP_DECLINED, FactKind.HELP_DECLINED, LiveTiming.NONE).consent(ConsentAnswer.DECLINE);
            case "UNCLEAR" -> s.unreadableGuided()
                    ? wrap(s, EndCause.HELP_UNCLEAR, null, LiveTiming.NONE).consent(ConsentAnswer.UNCLEAR)
                    : Step.reguide(Guard.UNREADABLE);
            default -> unavailable(s);
        };
    }

    private Step retry(FlowState s, String code) {
        return switch (code) {
            case "FULL", "COPIED" -> wrap(s, EndCause.NORMAL, null, LiveTiming.AFTER_HELP);
            case "PARTIAL", "HELP" -> retriesLeft(s)
                    ? Step.enter(Phase.FOCUS, Sub.COACH_UTTERANCE)
                    : wrap(s, EndCause.FOCUS_LIMIT, null, LiveTiming.NONE);
            case "NOT_UNDERSTOOD", "IRRELEVANT" -> s.reexplainCount() < reexplainMax && retriesLeft(s)
                    ? Step.reexplain(Phase.RETRY)
                    : wrap(s, EndCause.FOCUS_LIMIT, null, LiveTiming.NONE);
            case "UNREADABLE" -> unreadable(s);
            default -> unavailable(s);
        };
    }

    private Step variantOffer(FlowState s, String code) {
        return switch (code) {
            case "ACCEPT" -> Step.enter(Phase.SCENE_INTRO, Sub.NONE).startVariant();
            case "REFUSE" -> declineVariant(s, VariantSkipReason.DECLINED);
            case "UNCLEAR" -> s.unreadableGuided() ? declineVariant(s, VariantSkipReason.DECLINED) : Step.reguide(Guard.UNREADABLE);
            default -> unavailable(s);
        };
    }

    /** 다시 해 보기가 남았는가: 횟수와 집중 시계 만료 표지(BR6.3). */
    private boolean retriesLeft(FlowState s) {
        return s.retryCount() < retryMax && !s.focusLimitReached();
    }

    private static Step helpRequested() {
        return Step.enter(Phase.FOCUS, Sub.ROLE_NOTICE).consent(ConsentAnswer.REQUESTED).helpConsented();
    }

    /** UNREADABLE: 같은 질문을 교정 없이 한 번 다시, 두 번째면 판독 불가로 미실시(TRANSCRIPT_FAILED, NONE). */
    private Step unreadable(FlowState s) {
        if (!s.unreadableGuided()) {
            return Step.reguide(Guard.UNREADABLE);
        }
        return wrap(s, EndCause.TRANSCRIPT_FAILED, FactKind.TRANSCRIPT_FAILED, LiveTiming.NONE);
    }

    /** 보류 · 장애: 재안내 한 번, 두 번째면 보류로 닫는다(사실 없음, ABSTAINED). */
    private Step unavailable(FlowState s) {
        if (!s.unreadableGuided()) {
            return Step.reguide(Guard.UNREADABLE);
        }
        if (s.phase() == Phase.VARIANT_OFFER) {
            return declineVariant(s, VariantSkipReason.DECLINED);
        }
        return wrap(s, EndCause.ABSTAINED, null, LiveTiming.ABSTAINED);
    }

    // ---- 시계 ----

    /**
     * 무응답 시계 만료(이 단계의 확장 발화 재생이 끝난 뒤 건 시계). 첫 만료는 같은 단계에서 재안내 1회, 둘째 만료는 §3.3 '무응답' 열이다. 입력을
     * 기다리지 않는 단계 · 소단계의 늦은 만료는 무시한다.
     */
    public Step onNoResponse(FlowState s) {
        if (s.sub() != Sub.NONE || !s.phase().awaitsInput() || s.judgmentOpen()) {
            return Step.ignore();
        }
        if (!s.noResponseGuided()) {
            return Step.reguide(Guard.NO_RESPONSE);
        }
        return switch (s.phase()) {
            case VARIANT_OFFER -> declineVariant(s, VariantSkipReason.DECLINED);
            default -> wrap(s, EndCause.TIMEOUT, FactKind.TIMEOUT, LiveTiming.NONE);
        };
    }

    /** 집중 90초 시계 만료: FOCUS · RETRY면 장면을 정리한다(FOCUS_LIMIT). */
    public Step onFocusLimit(FlowState s) {
        if (s.phase() != Phase.FOCUS && s.phase() != Phase.RETRY) {
            return Step.ignore();
        }
        return wrap(s, EndCause.FOCUS_LIMIT, null, LiveTiming.NONE);
    }

    /**
     * 목표 ACTIVE 10분 시계 만료: 어느 단계든 장면을 정리해 다음 목표로(TIME_LIMIT). 이미 정리 중이거나 목표가 끝난 단계는 표지만 서고
     * 변형 평가가 TIME_LIMIT으로 읽는다.
     */
    public Step onGoalLimit(FlowState s) {
        return switch (s.phase()) {
            case NEXT_GOAL, CLOSING, SCENE_WRAP -> Step.ignore();
            case VARIANT_OFFER -> declineVariant(s, VariantSkipReason.TIME_LIMIT);
            default -> wrap(s, EndCause.TIME_LIMIT, null, LiveTiming.NONE);
        };
    }

    /** 종료 · 휴식 의사(REST_REQUESTED · 실행부 종료 신호): 어느 단계든 CLOSING(END_REQUEST). */
    public Step onEndRequested(FlowState s) {
        if (s.phase() == Phase.CLOSING || s.phase() == Phase.NEXT_GOAL) {
            return Step.ignore();
        }
        return Step.enter(Phase.CLOSING, Sub.NONE).fact(FactKind.END_REQUEST).cause(EndCause.END_REQUEST).endRequest();
    }

    // ---- 슬롯 종료 ----

    /**
     * 확장이 연 슬롯(재생 · 생성형 발화)이 닫혔다. 같은 correlationId의 두 번째 종료 사건은 호출하기 전에 {@link FlowState#isHandled}로 거른다.
     */
    public Step onSlotEnded(FlowState s, SlotEnd end) {
        boolean ended = end == SlotEnd.PLAYED || end == SlotEnd.INTERRUPTED;
        return switch (s.phase()) {
            case SCENE_INTRO -> ended ? Step.enter(Phase.OPPORTUNITY, Sub.NONE) : Step.enter(afterScene(s), Sub.NONE);
            case OPPORTUNITY, REASK, HELP_OFFER, RETRY -> inputPhaseSlot(s, end);
            case VARIANT_OFFER -> ended ? Step.await() : declineVariant(s, VariantSkipReason.NO_VARIANT);
            case FOCUS -> focusSlot(s, end);
            case SCENE_WRAP -> s.sub() == Sub.RETURN_NOTICE ? returnNoticeEnded(s, end) : Step.ignore();
            default -> Step.ignore();
        };
    }

    private Step inputPhaseSlot(FlowState s, SlotEnd end) {
        if (end == SlotEnd.PLAYED || end == SlotEnd.INTERRUPTED) {
            if (s.sub() == Sub.REEXPLAIN) {
                return s.phase() == Phase.RETRY ? Step.enter(Phase.FOCUS, Sub.COACH_UTTERANCE) : Step.enter(Phase.OPPORTUNITY, Sub.NONE);
            }
            return Step.await();
        }
        return failedWrap(s, end);
    }

    private Step focusSlot(FlowState s, SlotEnd end) {
        boolean ended = end == SlotEnd.PLAYED || end == SlotEnd.INTERRUPTED;
        if (s.sub() == Sub.ROLE_NOTICE) {
            if (end == SlotEnd.PLAYED) {
                return Step.enter(Phase.FOCUS, Sub.COACH_UTTERANCE).role(FlowRole.COACH).fact(FactKind.COACH_SWITCH);
            }
            // 알림이 끊기거나 실패 · 막힘이면 역할을 바꾸지 않고 도움 없이 정리한다(BR5.1 · BR5.5)
            return end == SlotEnd.INTERRUPTED ? wrap(s, EndCause.PLAYBACK_FAILED, null, LiveTiming.NONE) : failedWrap(s, end);
        }
        if (s.sub() == Sub.COACH_UTTERANCE) {
            return ended ? Step.enter(Phase.RETRY, Sub.NONE).incrementRetry() : failedWrap(s, end);
        }
        return Step.ignore();
    }

    private Step failedWrap(FlowState s, SlotEnd end) {
        return end == SlotEnd.BLOCKED
                ? wrap(s, EndCause.SLOT_BLOCKED, null, LiveTiming.NONE)
                : wrap(s, EndCause.PLAYBACK_FAILED, FactKind.PLAYBACK_FAILED, LiveTiming.NONE);
    }

    private Step returnNoticeEnded(FlowState s, SlotEnd end) {
        // 알림이 어떻게 끝나든 역할은 친구로 돌아간다. 이 단계의 실패는 다시 알림을 만들지 않고 변형 평가로 넘어간다(BR5.3)
        Step step = Step.enter(Phase.SCENE_WRAP, Sub.VARIANT_EVAL).role(FlowRole.PEER).fact(FactKind.COACH_RETURN);
        if (s.sceneEndCause() == EndCause.NORMAL) {
            step.fact(FactKind.ORIGINAL_SCENE_RETURN);
        }
        if (end == SlotEnd.FAILED) {
            step.fact(FactKind.PLAYBACK_FAILED);
        }
        return step;
    }

    // ---- 장면 정리 · 변형 ----

    /**
     * SCENE_WRAP 변형 평가(§3.3a · BR6.1 · BR6.2). 변형 장면(VARIANT)의 정리 뒤에는 변형을 다시 제안하지 않는다(D9).
     *
     * @param hasVariantScene 쓸 수 있는 변형 장면이 대본에 있는가
     * @return 제안하면 VARIANT_OFFER, 아니면 다음 목표 · CLOSING으로 가는 전이(생략 사유와 사실 포함)
     */
    public Step evaluateVariant(FlowState s, boolean hasVariantScene) {
        if (s.sceneKind() == SceneKind.VARIANT) {
            return Step.enter(afterScene(s), Sub.NONE).fact(FactKind.VARIANT_PLAYED);
        }
        VariantDecision decision = decideVariant(s, hasVariantScene);
        if (decision.offer()) {
            return Step.enter(Phase.VARIANT_OFFER, Sub.NONE);
        }
        return Step.enter(afterScene(s), Sub.NONE).fact(FactKind.VARIANT_SKIPPED).skip(decision.skip());
    }

    /** 원인별 변형 제안 · 생략 사유 표(§3.3a). 네 조건의 확인 순서는 BR6.1의 (1) 변형 있음 (2) 종료 · 휴식 (3) 목표 10분 (4) 집중 한도다. */
    public VariantDecision decideVariant(FlowState s, boolean hasVariantScene) {
        EndCause cause = s.sceneEndCause() == null ? EndCause.NORMAL : s.sceneEndCause();
        VariantSkipReason reason = switch (cause) {
            case NORMAL, HELP_DECLINED -> normalReason(s, hasVariantScene);
            case TIMEOUT, TRANSCRIPT_FAILED, END_REQUEST -> VariantSkipReason.END_OR_REST;
            case ABSTAINED, HELP_UNCLEAR, SLOT_BLOCKED, PLAYBACK_FAILED -> VariantSkipReason.NO_VARIANT;
            case TIME_LIMIT -> VariantSkipReason.TIME_LIMIT;
            case FOCUS_LIMIT -> VariantSkipReason.FOCUS_LIMIT;
        };
        return new VariantDecision(reason == null, reason);
    }

    private VariantSkipReason normalReason(FlowState s, boolean hasVariantScene) {
        if (!hasVariantScene) {
            return VariantSkipReason.NO_VARIANT;
        }
        if (s.endRequested()) {
            return VariantSkipReason.END_OR_REST;
        }
        if (s.goalLimitReached()) {
            return VariantSkipReason.TIME_LIMIT;
        }
        boolean focusExhausted = s.focusLimitReached() || (s.retryCount() > 0 && s.retryCount() >= retryMax);
        return focusExhausted ? VariantSkipReason.FOCUS_LIMIT : null;
    }

    private Step wrap(FlowState s, EndCause cause, FactKind fact, LiveTiming timing) {
        Sub sub = s.role() == FlowRole.COACH ? Sub.RETURN_NOTICE : Sub.VARIANT_EVAL;
        return Step.enter(Phase.SCENE_WRAP, sub).cause(cause).fact(fact).timing(timing);
    }

    private Step declineVariant(FlowState s, VariantSkipReason reason) {
        return Step.enter(afterScene(s), Sub.NONE).fact(FactKind.VARIANT_SKIPPED).skip(reason);
    }

    private static Phase afterScene(FlowState s) {
        return s.lastGoal() ? Phase.CLOSING : Phase.NEXT_GOAL;
    }
}
