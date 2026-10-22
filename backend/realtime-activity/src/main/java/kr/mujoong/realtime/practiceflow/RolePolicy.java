package kr.mujoong.realtime.practiceflow;

import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;

/**
 * 역할별 발화 정책 (U12 BR5.2). 친구는 정답 · 모범 문장을 미리 말하지 않고, 코치는 아이가 동의한 뒤에만 표현 예시를 줄 수 있다. 레거시
 * CueProvider의 전역 금지를 이 역할별 정책으로 나눈 것이다. 정책의 문장은 {@link PracticeInstructions}가 지시문 템플릿에 담는다.
 *
 * <p>승인 발화의 말하는 사람 표시는 문장 종류로 정한다: 장면 설명 · 상황 다시 설명처럼 무중이 역할 밖에서 하는 설명은 {@link #NARRATOR_LABEL},
 * 친구 첫 대사 · 기회 문장처럼 장면 속 친구의 대사는 {@link #peerName}이다(docs/practice-dialogue-standard.md).
 */
final class RolePolicy {

    /** 무중이 역할 밖에서 하는 설명 · 제안의 말하는 사람 표시. */
    static final String NARRATOR_LABEL = "무중";
    /** 대본 partnerRole을 역할 이름으로 쓸 수 없을 때의 친구 표시. core 라벨 규칙의 대체값과 같다(contracts/ai-io.md 10.15). */
    static final String PEER_FALLBACK = "친구";
    /** 역할 이름 상한(코드 포인트). core 라벨 규칙과 같다. */
    static final int PEER_NAME_MAX = 20;

    private RolePolicy() {
    }

    /** {@code speakGenerated}의 역할 인자(제브 모드 PRACTICE_PEER · PRACTICE_COACH로 대응한다). */
    static String slotRole(FlowRole role) {
        return role.name();
    }

    /**
     * 대본 partnerRole을 친구 역할 이름으로 쓴다. 비었거나 상한을 넘거나 문장부호({@code .?!。}) · 제어 문자가 있으면 지시문으로 보고
     * '친구'로 바꾼다. 배포 전에 저장된 준비 패키지 · 옛 대본에 남은 지시문 값이 "너는 아이의 … 역할이다"에 끼어들지 않게 막는다.
     */
    static String peerName(String scenePartnerRole) {
        if (scenePartnerRole == null || scenePartnerRole.isBlank()) {
            return PEER_FALLBACK;
        }
        String name = scenePartnerRole.strip();
        boolean shortName = name.codePointCount(0, name.length()) <= PEER_NAME_MAX;
        boolean plain = name.codePoints().noneMatch(c -> Character.isISOControl(c) || ".?!。".indexOf(c) >= 0);
        return shortName && plain ? name : PEER_FALLBACK;
    }

    /** 코치 발화가 들어 줄 수 있는 표현 예시의 단계. */
    enum Example {
        /** 예시 없이 요점 · 단서만. */
        NONE,
        /** 핵심 낱말 자리를 비운 말틀 하나(첫 도움). */
        FRAME,
        /** 대본의 표현 예시 하나(다시 해 보기 뒤의 도움). */
        FULL
    }

    /**
     * 코치가 들어 줄 표현 예시의 단계(BR5.2: 아이가 도움에 동의했거나 직접 요청한 뒤에만 예시). 첫 도움은 요점 하나와 핵심 낱말을 비운 말틀,
     * 다시 해 보기 뒤의 도움은 완성된 표현 예시 하나다(AI 동작 명세 §3.1 D_FOCUS, docs/practice-dialogue-standard.md).
     *
     * @param consented 아이가 도움에 동의했거나 요청했다
     * @param retryCount 지금까지 다시 해 보기에 들어간 횟수
     */
    static Example example(boolean consented, int retryCount) {
        if (!consented) {
            return Example.NONE;
        }
        return retryCount == 0 ? Example.FRAME : Example.FULL;
    }
}
