package kr.mujoong.realtime.practiceflow;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.realtime.practiceflow.FlowTransitions.EndCause;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;

/**
 * 세션 메모리 안의 연습 흐름 상태 (U12 entities.md FlowState · GeneratedUtteranceSlot의 확장 쪽 표지). 영속하지 않고, 상태 기계
 * {@link PracticeConversation}만 바꾼다. 전이표({@link FlowTransitions})는 읽기만 한다.
 *
 * <p>한도는 {@code Instant} 차이가 아니라 시계 만료 표지({@link #focusLimitReached} · {@link #goalLimitReached})로 판단한다(D3, 검토 R-07). 시각 값은
 * 기록용이다. 아이 전사 · 대본 문장 · 판정 입력은 어느 칸에도 없다(R-F14).
 */
public final class FlowState {

    /** 단계(BR3.1). 참여 확인은 실행부 소유라 없다. 변형 장면은 {@code sceneKind=VARIANT}로 같은 단계를 다시 돈다. */
    public enum Phase {
        SCENE_INTRO, OPPORTUNITY, REASK, HELP_OFFER, FOCUS, RETRY, SCENE_WRAP, VARIANT_OFFER, NEXT_GOAL, CLOSING;

        /** 아이 말을 기다리는 단계(판정 지점 하나에 묶인다, §3.3). */
        public boolean awaitsInput() {
            return this == OPPORTUNITY || this == REASK || this == HELP_OFFER || this == RETRY || this == VARIANT_OFFER;
        }
    }

    /** 단계 안의 소단계(FOCUS 진입 ① · ②, SCENE_WRAP 소단계, 상황 다시 설명 재생 중). */
    public enum Sub {
        NONE,
        /** FOCUS 진입 ①: 역할 전환 알림 재생 중. */
        ROLE_NOTICE,
        /** FOCUS 진입 ②: 코치 발화 재생 중. */
        COACH_UTTERANCE,
        /** SCENE_WRAP: 복귀 알림 재생 중. */
        RETURN_NOTICE,
        /** SCENE_WRAP: 수행한 말에 친구 역할로 답한 뒤 장면을 마무리한다. */
        PEER_REPLY,
        /** OPPORTUNITY: 상대 역할의 답변. 재생 뒤 같은 역할 대화의 입력을 기다린다. */
        DIALOGUE_REPLY,
        /** SCENE_WRAP: 변형 평가(알림이 없거나 끝났다). */
        VARIANT_EVAL,
        /** OPPORTUNITY · RETRY: 상황 다시 설명(승인 발화) 재생 중. */
        REEXPLAIN
    }

    private Phase phase = Phase.SCENE_INTRO;
    private Sub sub = Sub.NONE;
    private FlowRole role = FlowRole.PEER;
    private int goalIndex;
    private boolean lastGoal;
    private SceneKind sceneKind = SceneKind.BASE;
    private int retryCount;
    private int reexplainCount;
    private boolean noResponseGuided;
    private boolean unreadableGuided;
    private final Set<UUID> handledSlotIds = new HashSet<>();
    private boolean focusLimitReached;
    private boolean goalLimitReached;
    private boolean judgmentOpen;
    private boolean helpConsented;
    private boolean endRequested;
    private EndCause sceneEndCause;
    private LiveTiming sceneTiming;
    private Instant goalActiveStartedAt;
    private Instant focusStartedAt;

    public Phase phase() {
        return phase;
    }

    public Sub sub() {
        return sub;
    }

    public FlowRole role() {
        return role;
    }

    public int goalIndex() {
        return goalIndex;
    }

    /** 이 목표가 마지막 목표인가(정리 뒤 NEXT_GOAL 대신 CLOSING으로 간다). */
    public boolean lastGoal() {
        return lastGoal;
    }

    public SceneKind sceneKind() {
        return sceneKind;
    }

    public int retryCount() {
        return retryCount;
    }

    public int reexplainCount() {
        return reexplainCount;
    }

    /** 무응답 첫 만료 재안내를 이미 했는가(단계가 바뀔 때마다 거짓으로 돌린다). */
    public boolean noResponseGuided() {
        return noResponseGuided;
    }

    /** 판독 불가 · 보류 · 불명확 재안내를 이미 했는가(단계가 바뀔 때 돌린다). */
    public boolean unreadableGuided() {
        return unreadableGuided;
    }

    public boolean focusLimitReached() {
        return focusLimitReached;
    }

    public boolean goalLimitReached() {
        return goalLimitReached;
    }

    public boolean judgmentOpen() {
        return judgmentOpen;
    }

    /** 아이가 도움에 동의했거나 직접 요청했는가(코치 표현 예시의 조건, BR5.2). */
    public boolean helpConsented() {
        return helpConsented;
    }

    /** 종료 · 휴식 의사가 있었는가(변형 제안 조건 (2)). */
    public boolean endRequested() {
        return endRequested;
    }

    /** 장면이 정리로 들어간 원인(SCENE_WRAP 이후에만 값이 있다). */
    public EndCause sceneEndCause() {
        return sceneEndCause;
    }

    /** 장면이 정리로 들어갈 때 정해진 진행 중 판정 시점(기록용). */
    public LiveTiming sceneTiming() {
        return sceneTiming;
    }

    public Instant goalActiveStartedAt() {
        return goalActiveStartedAt;
    }

    public Instant focusStartedAt() {
        return focusStartedAt;
    }

    /** 이미 종료 사건을 처리했거나 확장이 스스로 닫은 슬롯인가(중복 · 늦은 사건 무시, D3 · §3.5). */
    public boolean isHandled(UUID slotId) {
        return handledSlotIds.contains(slotId);
    }

    // ---- 변경(상태 기계만 부른다) ----

    void phase(Phase next, Sub nextSub) {
        if (next != phase) {
            noResponseGuided = false;
            unreadableGuided = false;
        }
        this.phase = next;
        this.sub = nextSub;
    }

    void role(FlowRole value) {
        this.role = value;
    }

    void goal(int index, boolean last, Instant startedAt) {
        this.goalIndex = index;
        this.lastGoal = last;
        this.goalActiveStartedAt = startedAt;
        this.goalLimitReached = false;
        this.endRequested = false;
        resetScene(SceneKind.BASE);
    }

    /** 새 장면(원 장면 또는 변형)을 시작한다: 장면 안 횟수 · 표지를 되돌린다. */
    void resetScene(SceneKind kind) {
        this.sceneKind = kind;
        this.retryCount = 0;
        this.reexplainCount = 0;
        this.noResponseGuided = false;
        this.unreadableGuided = false;
        this.focusLimitReached = false;
        this.judgmentOpen = false;
        this.helpConsented = false;
        this.sceneEndCause = null;
        this.sceneTiming = null;
        this.focusStartedAt = null;
        this.role = FlowRole.PEER;
    }

    void incrementRetry() {
        retryCount++;
    }

    void incrementReexplain() {
        reexplainCount++;
    }

    void noResponseGuided(boolean value) {
        this.noResponseGuided = value;
    }

    void unreadableGuided(boolean value) {
        this.unreadableGuided = value;
    }

    void markHandled(UUID slotId) {
        if (slotId != null) {
            handledSlotIds.add(slotId);
        }
    }

    void focusLimitReached(boolean value) {
        this.focusLimitReached = value;
    }

    void goalLimitReached(boolean value) {
        this.goalLimitReached = value;
    }

    void judgmentOpen(boolean value) {
        this.judgmentOpen = value;
    }

    void helpConsented(boolean value) {
        this.helpConsented = value;
    }

    void endRequested(boolean value) {
        this.endRequested = value;
    }

    void sceneEnd(EndCause cause, LiveTiming timing) {
        this.sceneEndCause = cause;
        this.sceneTiming = timing;
    }

    void focusStartedAt(Instant value) {
        this.focusStartedAt = value;
    }

    /** 시험 · 전이표 입력용 복사 없이 만들 수 있는 새 상태(첫 장면 직전). */
    public static FlowState initial() {
        return new FlowState();
    }

    @Override
    public String toString() {
        return "FlowState[phase=" + phase + ", sub=" + sub + ", role=" + role + "]";
    }
}
