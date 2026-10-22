package kr.mujoong.realtime.practiceflow;

import java.util.List;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;

/**
 * GPT-Live가 만드는 역할 발화의 지시문 템플릿 (U12 BR3.7 · BR5.2 · D6). 모든 문장은 서버가 만든 고정 템플릿이다.
 *
 * <ul>
 *   <li>판정 라벨 · 선택지 코드 · 확률 · 판정 기준 문자열은 어디에도 넣지 않는다(BR3.7, NFR1). 이 클래스는 판정 값을 받는 자리조차 없다.
 *       판정 결과가 드러나는 이유('일부만 말했으니' 같은 말)도 쓰지 않는다(AI 동작 명세 §1.3).
 *   <li>아이 발화 · 모델 출력 원문은 섞지 않는다(자리가 없다). 들어가는 대본 조각은 승인된 대본의 상황 · 단서 · 표현 예시뿐이다.
 *   <li>아이를 잘했다 · 맞았다로 평가하거나 칭찬하지 않게 한다(명세 §1.1 · §2.1). 친구는 칭찬 · 채점 대신 역할 안에서 아이 말에 반응한다.
 *   <li>친구 지시문은 정답 · 모범 문장을 미리 말하지 말고 아이 대신 대답하지 말라고 막고, 코치 지시문은 표현 예시를 동의 뒤에만 허용한다
 *       ({@link RolePolicy}).
 *   <li>도움 제안은 친구가 아니라 무중이 역할 밖에서 한다(명세 §3.1 D_OFFER_HELP). 승인 문장이 있으면 그 문장을 쓰고 이 템플릿은 쓰지 않는다.
 *       변형 제안도 무중이 한다: 판 v6 대본은 변형 설명 + {@link #VARIANT_OFFER_QUESTION} 승인 발화이고, 옛 대본만 이 클래스의 생성형 질문을 쓴다.
 *   <li>친구 역할 이름은 대본 partnerRole을 {@link RolePolicy#peerName}으로 거른 짧은 이름이다. 금지 규칙은 이 템플릿이 맡는다.
 *   <li>공급자 지시 길이 상한({@value #MAX_LENGTH}자) 안에 맞춘다.
 * </ul>
 */
final class PracticeInstructions {

    /** 공급자 지시문 상한(코드 포인트)과 같은 값({@code PracticeInstruction.MAX_LENGTH}). */
    static final int MAX_LENGTH = 500;

    /**
     * 변형 제안 승인 발화의 고정 꼬리(명세 §3.1 D_VARIANT_OFFER «{variant_intro} 이어서 해 볼까?»). 무중이 변형 장면 설명(승인 대본 문장)
     * 뒤에 붙여 역할 밖에서 묻는다. 평가 · 칭찬이 끼어들 자리가 없도록 생성형으로 만들지 않는다.
     */
    static final String VARIANT_OFFER_QUESTION = "이어서 해 볼까?";

    private static final int FIELD_MAX = 80;

    private static final String PEER_RULE = "정답이나 모범 문장을 먼저 말하지 말고, 아이의 말을 고쳐 주거나 평가하지 않는다. 한두 문장으로 말한다. "
            + "아이 대신 대답하지 않는다.";
    private static final String COACH_RULE = "아이를 평가하거나 칭찬하거나 꾸짖지 않고 따뜻하게 말한다.";

    private PracticeInstructions() {
    }

    /** 친구 되묻기(D_REASK, 기회 문장 뒤 J2 일부 전달). 친구 역할 그대로 묻고 아이가 할 말은 알려 주지 않는다. */
    static String peerReask(Scene scene, boolean again) {
        return fit("너는 아이의 " + RolePolicy.peerName(scene.partnerRole()) + " 역할이다. 상황: " + field(scene.sceneDescription())
                + (again ? " 같은 질문을 같은 말투로 한 번 더 천천히 물어본다."
                        : " 친구 역할 그대로 아이 말에서 더 알고 싶은 것을 자연스럽게 짧게 한 번 되묻는다.")
                + " " + PEER_RULE);
    }

    /**
     * 친구 반응(D_CONTINUE · D_RESUME). 장면을 닫은 아이 말에 친구 역할 그대로 반응해 장면을 한두 마디로 마무리한다. 칭찬 · 채점 대신 하는 반응이라
     * 평가 · 교정을 막고, 뒤에 아이 대답을 기다리지 않으므로 새 질문을 하지 않게 한다.
     */
    static String peerReaction(Scene scene) {
        return fit("너는 아이의 " + RolePolicy.peerName(scene.partnerRole()) + " 역할이다. 상황: " + field(scene.sceneDescription())
                + " 친구 역할 그대로 아이가 방금 한 말에 자연스럽게 반응해 장면을 한두 마디로 마무리한다. 칭찬하거나 고쳐 주지 않고, 새 질문을 하지 않는다. "
                + PEER_RULE);
    }

    /**
     * 도움 제안(D_OFFER_HELP)을 승인 문장 없이 생성형으로 낼 때. 무중이 친구 역할에서 잠깐 나와 도와줘도 되는지 묻는다(강제 아님). 말하는 역할은
     * 코치 슬롯(제브 PRACTICE_COACH)이다.
     */
    static String mujoongHelpOffer(boolean again) {
        return fit("너는 무중이다. 친구 역할에서 잠깐 나와, 무중이가 조금 도와줘도 되는지 한 문장으로 짧게 묻는다. 예: 무중이가 조금 도와줄까? "
                + (again ? "같은 말로 한 번 더 부드럽게 묻는다. " : "")
                + "힌트나 아이가 할 말은 말하지 않고, 싫다고 하면 그대로 받아들인다. " + COACH_RULE);
    }

    /**
     * 친구 첫 대사가 없는 옛 대본(생성 판 v5까지)의 변형 제안(D_VARIANT_OFFER). 판 v5의 변형 설명은 기회 문장을 글자 그대로 담아 제안에 넣으면
     * 아이가 단서를 미리 듣게 되므로, 무중이 역할 밖에서 비슷한 다른 장면으로 이어서 해 볼지만 묻는다. 변형 설명은 수락 뒤 장면 도입으로 들려준다.
     * 말하는 역할은 코치 슬롯(제브 PRACTICE_COACH)이다.
     */
    static String mujoongVariantOffer(boolean again) {
        return fit("너는 무중이다. 친구 역할에서 잠깐 나와, 비슷한 다른 장면으로 이어서 해 볼지 한 문장으로 짧게 묻는다. 예: 이번엔 다른 장면으로 이어서 해 볼까? "
                + (again ? "같은 말로 한 번 더 부드럽게 묻는다. " : "")
                + "새 장면의 내용이나 아이가 할 말은 말하지 않고, 싫다고 하면 그대로 받아들인다. " + COACH_RULE);
    }

    /**
     * 코치 도움 발화(D_FOCUS). 요점 하나를 쉬운 말로 알려 주고, 아이가 동의 · 요청한 뒤에만 표현 예시를 든다: 첫 도움은 대본 표현 예시의 핵심
     * 낱말을 비운 말틀, 다시 해 보기 뒤의 도움은 표현 예시 하나다({@link RolePolicy#example}). 대본에 표현 예시가 없으면 단서만 준다.
     */
    static String coachHelp(Scene scene, RolePolicy.Example example) {
        StringBuilder text = new StringBuilder("너는 아이를 돕는 코치 역할이다. 상황: ").append(field(scene.sceneDescription())).append(' ');
        String focus = first(scene.focusPoints());
        if (!focus.isEmpty()) {
            text.append("이 요점 하나만 쉬운 말로 짧게 알려 준다: ").append(focus).append(". ");
        }
        String sample = first(scene.expressionExamples());
        if (example == RolePolicy.Example.FRAME && !sample.isEmpty()) {
            text.append("그다음 이 표현 예시를 그대로 말하지 말고, 아이가 채워 말할 핵심 낱말 자리를 '뭐뭐'로 비운 말틀로 바꿔 하나만 들어 준다: ")
                    .append(sample).append(". ");
        } else if (example == RolePolicy.Example.FULL && !sample.isEmpty()) {
            text.append("이 방향의 표현 예시를 하나만 들어도 된다: ").append(sample).append(". ");
        } else {
            text.append("표현 예시나 정답 문장은 말하지 말고, 상황을 다시 떠올려 스스로 말해 보도록 단서만 준다. ");
        }
        return fit(text.append("마지막에 아이 말로 해 보자고 한다. 따라 하라고 다그치지 않는다. 세 문장 이내로 말한다. ").append(COACH_RULE)
                .toString());
    }

    /** 코치 발화가 실제로 표현 예시(말틀 또는 예시)를 담는가: 동의 뒤이고 대본에 표현 예시가 있을 때다. 기록의 예시 여부와 같은 판단이다. */
    static boolean coachGivesExample(Scene scene, RolePolicy.Example example) {
        return example != RolePolicy.Example.NONE && !first(scene.expressionExamples()).isEmpty();
    }

    /** 코치가 다시 해 보기를 권하는 재안내(무응답 · 판독 불가 뒤). */
    static String coachRetryPrompt(Scene scene) {
        return fit("너는 아이를 돕는 코치 역할이다. 상황: " + field(scene.sceneDescription())
                + " 아이가 편할 때 자기 말로 다시 한 번 해 보자고 같은 말로 부드럽게 권한다. 새 예시나 정답은 말하지 않는다. 한두 문장으로 말한다. "
                + COACH_RULE);
    }

    /** 역할 전환 · 복귀 알림을 승인 문장 없이 생성형으로 낼 때(BR5.4). {@code toRole}은 PEER 또는 COACH. */
    static String roleNotice(String toRole, Scene scene) {
        if ("COACH".equals(toRole)) {
            return fit("친구 목소리에서 아이를 돕는 코치 목소리로 바뀐다고 한 문장으로 알린다. 곧 도와주겠다고만 말하고 예시나 정답은 말하지 않는다. "
                    + COACH_RULE);
        }
        return fit("다시 아이의 " + RolePolicy.peerName(scene.partnerRole()) + " 목소리로 돌아왔다고 한 문장으로 알린다. 새 질문이나 정답은 말하지 않는다. "
                + PEER_RULE);
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? "" : field(values.getFirst());
    }

    /** 대본 조각을 한 줄로 줄인다(제어 문자 제거 · 길이 상한). */
    private static String field(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\p{Cntrl}+", " ").trim();
        return cut(single, FIELD_MAX);
    }

    private static String fit(String text) {
        return cut(text, MAX_LENGTH);
    }

    private static String cut(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }
}
