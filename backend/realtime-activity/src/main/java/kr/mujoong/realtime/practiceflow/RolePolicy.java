package kr.mujoong.realtime.practiceflow;

import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;

/**
 * 역할별 발화 정책 (U12 BR5.2). 친구는 정답 · 모범 문장을 미리 말하지 않고, 코치는 아이가 동의한 뒤에만 표현 예시를 줄 수 있다. 레거시
 * CueProvider의 전역 금지를 이 역할별 정책으로 나눈 것이다. 정책의 문장은 {@link PracticeInstructions}가 지시문 템플릿에 담는다.
 */
final class RolePolicy {

    /** 코치 발화의 말하는 사람 표시(친구는 대본의 partnerRole을 쓴다). */
    static final String COACH_LABEL = "코치";

    private RolePolicy() {
    }

    /** {@code speakGenerated}의 역할 인자(제브 모드 PRACTICE_PEER · PRACTICE_COACH로 대응한다). */
    static String slotRole(FlowRole role) {
        return role.name();
    }

    /** 말하는 사람 표시: 친구는 대본의 상대 역할 문구, 코치는 고정 문구. */
    static String partnerLabel(FlowRole role, String scenePartnerRole) {
        return role == FlowRole.COACH ? COACH_LABEL : scenePartnerRole;
    }

    /**
     * 코치가 표현 예시를 줄 수 있는가: 아이가 도움에 동의했거나 직접 요청했고(BR5.2), 첫 코치 발화 뒤에 다시 해 보게 된 경우다. 첫 코치 발화는
     * 예시 없이 상황을 다시 떠올리는 단서만 준다(이 단위가 정한 지시문 단계).
     *
     * @param consented 아이가 도움에 동의했거나 요청했다
     * @param retryCount 지금까지 다시 해 보기에 들어간 횟수
     */
    static boolean mayGiveExample(boolean consented, int retryCount) {
        return consented && retryCount > 0;
    }
}
