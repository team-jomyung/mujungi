package kr.mujoong.realtime.practiceflow;

import java.util.List;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;

/**
 * GPT-Live가 만드는 역할 발화의 지시문 템플릿 (U12 BR3.7 · BR5.2 · D6). 모든 문장은 서버가 만든 고정 템플릿이다.
 *
 * <ul>
 *   <li>판정 라벨 · 선택지 코드 · 확률 · 판정 기준 문자열은 어디에도 넣지 않는다(BR3.7, NFR1). 이 클래스는 판정 값을 받는 자리조차 없다.
 *   <li>아이 발화 · 모델 출력 원문은 섞지 않는다(자리가 없다). 들어가는 대본 조각은 승인된 대본의 상황 · 단서 · 표현 예시뿐이다.
 *   <li>친구 지시문은 정답 · 모범 문장을 미리 말하지 말라고 막고, 코치 지시문은 표현 예시를 동의 뒤에만 허용한다({@link RolePolicy}).
 *   <li>공급자 지시 길이 상한({@value #MAX_LENGTH}자) 안에 맞춘다.
 * </ul>
 */
final class PracticeInstructions {

    /** 공급자 지시문 상한(코드 포인트)과 같은 값({@code PracticeInstruction.MAX_LENGTH}). */
    static final int MAX_LENGTH = 500;

    private static final int FIELD_MAX = 80;

    private static final String PEER_RULE = "정답이나 모범 문장을 먼저 말하지 말고, 아이의 말을 고쳐 주거나 평가하지 않는다. 한두 문장으로 말한다.";
    private static final String COACH_RULE = "아이를 평가하거나 꾸짖지 않고 한두 문장으로 따뜻하게 말한다.";

    private PracticeInstructions() {
    }

    /** 친구 되묻기(OPPORTUNITY 뒤 부분적으로만 전해졌을 때). */
    static String peerReask(Scene scene, boolean again) {
        return fit("너는 아이의 " + field(scene.partnerRole()) + " 역할이다. 상황: " + field(scene.sceneDescription())
                + (again ? " 같은 질문을 같은 말투로 한 번 더 천천히 물어본다." : " 방금 아이의 대답에서 빠진 부분이 있어 자연스럽게 한 번만 되묻는다.")
                + " " + PEER_RULE);
    }

    /** 도움 제안(강제 아님). */
    static String peerHelpOffer(Scene scene, boolean again) {
        return fit("너는 아이의 " + field(scene.partnerRole()) + " 역할이다. 상황: " + field(scene.sceneDescription())
                + (again ? " 도와줘도 되는지 같은 말로 한 번 더 부드럽게 묻는다." : " 아이가 어려워 보이면 도와줘도 되는지 부드럽게 묻는다.")
                + " 싫다고 하면 그대로 받아들인다. " + PEER_RULE);
    }

    /** 변형 제안(같은 연습을 새 상황으로 해 볼지). */
    static String peerVariantOffer(Scene scene, boolean again) {
        return fit("너는 아이의 " + field(scene.partnerRole()) + " 역할이다. 방금 연습을 잘 마쳤다고 짧게 말하고, "
                + (again ? "새 상황으로 한 번 더 해 볼지 같은 말로 다시 묻는다." : "비슷한 새 상황으로 한 번 더 해 볼지 가볍게 묻는다.")
                + " 하기 싫다고 하면 그대로 받아들인다. " + PEER_RULE);
    }

    /**
     * 코치 도움 발화. 첫 도움은 단서만 주고, 표현 예시는 {@code mayGiveExample}일 때만 허용한다. 예시는 대본의 표현 예시 하나로 한정한다.
     */
    static String coachHelp(Scene scene, boolean mayGiveExample) {
        StringBuilder text = new StringBuilder("너는 아이를 돕는 코치 역할이다. 상황: ").append(field(scene.sceneDescription())).append(' ');
        String focus = first(scene.focusPoints());
        if (!focus.isEmpty()) {
            text.append("떠올려 볼 점: ").append(focus).append(". ");
        }
        String example = first(scene.expressionExamples());
        if (mayGiveExample && !example.isEmpty()) {
            text.append("아이가 도움을 원했으니 이 방향의 표현 예시를 하나만 들어도 된다: ").append(example).append(". 그런 뒤 아이가 자기 말로 다시 해 보게 한다. ");
        } else {
            text.append("표현 예시나 정답 문장은 말하지 말고, 상황을 다시 떠올려 스스로 말해 보도록 단서만 준다. 마지막에 다시 해 볼 수 있게 권한다. ");
        }
        return fit(text.append(COACH_RULE).toString());
    }

    /** 코치가 다시 해 보기를 권하는 재안내(무응답 · 판독 불가 뒤). */
    static String coachRetryPrompt(Scene scene) {
        return fit("너는 아이를 돕는 코치 역할이다. 상황: " + field(scene.sceneDescription())
                + " 아이가 편할 때 자기 말로 다시 한 번 해 보자고 같은 말로 부드럽게 권한다. 새 예시나 정답은 말하지 않는다. " + COACH_RULE);
    }

    /** 역할 전환 · 복귀 알림을 승인 문장 없이 생성형으로 낼 때(BR5.4). {@code toRole}은 PEER 또는 COACH. */
    static String roleNotice(String toRole, Scene scene) {
        if ("COACH".equals(toRole)) {
            return fit("친구 목소리에서 아이를 돕는 코치 목소리로 바뀐다고 한 문장으로 알린다. 곧 도와주겠다고만 말하고 예시나 정답은 말하지 않는다. "
                    + COACH_RULE);
        }
        return fit("다시 아이의 " + field(scene.partnerRole()) + " 목소리로 돌아왔다고 한 문장으로 알린다. 새 질문이나 정답은 말하지 않는다. "
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
