package kr.mujoong.realtime.practiceflow;

import java.util.List;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;

/**
 * GPT-Live가 만드는 역할 발화의 지시문 템플릿 (U12 BR3.7 · BR5.2 · D6). 모든 문장은 서버가 만든 고정 템플릿이다.
 *
 * <ul>
 *   <li>판정 라벨 · 선택지 코드 · 확률 · 판정 기준 문자열은 어디에도 넣지 않는다(BR3.7, NFR1). 이 클래스는 판정 값을 받는 자리조차 없다.
 *       판정 결과가 드러나는 이유('일부만 말했으니' 같은 말)도 쓰지 않는다(AI 동작 명세 §1.3).
 *   <li>아이 발화 · 모델 출력 원문은 섞지 않는다(자리가 없다). 들어가는 대본 조각은 승인된 대본의 상황 · 요점 · 표현 예시 · 빈칸 예문뿐이다.
 *   <li>아이를 잘했다 · 맞았다로 평가하거나 칭찬하지 않게 한다(명세 §1.1 · §2.1). 친구는 칭찬 · 채점 대신 역할 안에서 아이 말에 반응한다.
 *   <li>친구 지시문은 정답 · 모범 문장을 미리 말하지 말고 아이 대신 대답하지 말라고 막고, 코치 지시문은 표현 예시를 동의 뒤에만 허용한다
 *       ({@link RolePolicy}). 되묻기는 아이가 원하는 것 · 할 말의 낱말을 상황에 나와 있어도 먼저 말하거나 그 낱말로 확인하지 않는다(실측 F4).
 *   <li>도움 제안은 친구가 아니라 무중이 역할 밖에서 한다(명세 §3.1 D_OFFER_HELP). 승인 문장이 있으면 그 문장을 쓰고 이 템플릿은 쓰지 않는다.
 *       변형 제안도 무중이 한다: 판 v6 대본은 변형 설명 + {@link #VARIANT_OFFER_QUESTION} 승인 발화이고, 옛 대본만 이 클래스의 생성형 질문을 쓴다.
 *   <li>생성형 말은 음성으로 나가므로 모든 지시문이 이모지 · 기호 · 괄호를 막는다({@link #VOICE_RULE}, 실측 F4).
 *   <li>지시문 낱말이 아이에게 들리지 않게 한다: 코치 첫 도움의 빈칸 예문을 '말틀' 같은 지시문 낱말로 부르지 않고, '빈칸 예문'이라는 말도 소리 내지
 *       않게 한다(실측 F5).
 *   <li>친구 역할 이름은 대본 partnerRole을 {@link RolePolicy#peerName}으로 거른 짧은 이름이다. 금지 규칙은 이 템플릿이 맡는다.
 *   <li>공급자 지시 길이 상한({@value #MAX_LENGTH}자) 안에 맞춘다. 대본 조각은 {@value #FIELD_MAX}자에서 자르고, 칸이 모두 상한 길이여도 끝의 금지
 *       규칙이 잘리지 않는 길이로 문장을 짠다(시험이 가장 긴 칸으로 확인한다).
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

    /** 대본 조각 하나의 지시문 안 상한(코드 포인트). */
    static final int FIELD_MAX = 80;

    /** 빈칸 예문에서 아이가 채울 자리(core 생성 판 v7과 같은 낱말). */
    static final String BLANK = "뭐뭐";

    private static final String PEER_RULE = "정답이나 모범 문장을 먼저 말하지 말고, 아이의 말을 고쳐 주거나 평가하지 않는다. 한두 문장으로 말한다. "
            + "아이 대신 대답하지 않는다.";
    private static final String COACH_RULE = "아이를 평가하거나 칭찬하거나 꾸짖지 않고 따뜻하게 말한다.";

    /** 생성형 말은 음성으로 나간다: 소리 내어 읽을 말만 쓰게 한다. 실측에서 텍스트 대리가 «…딸기 우유! 🥛»처럼 이모지를 냈다(F4). */
    static final String VOICE_RULE = "이모지, 기호, 괄호 없이 말로만 한다.";

    /**
     * 되묻기 금지(실측 F4). 대본 설명에 아이가 원한 것이 있어 친구가 알고 말해 버렸고(«파란색 크레용이 필요해?» · «딸기 우유가 필요해?»), 장면을
     * 벗어난 새 질문(«너는 무슨 그림 그리고 있어?»)도 했다.
     */
    private static final String REASK_RULE = "아이가 원하는 것이나 아이가 할 말의 낱말은 상황에 나와 있어도 먼저 말하지 않고, 그 낱말을 넣어 맞는지 묻지도 "
            + "않는다. 지금 장면과 상관없는 새 질문은 하지 않는다.";

    /** 코치 첫 도움이 비지 않게 하고 지시문 낱말이 들리지 않게 한다(실측 F5: «네가 떠올린 말을 먼저 들려줘.» · «말틀은 …야»). */
    private static final String FRAME_RULE = "요점과 빈칸 예문을 꼭 넣고, '빈칸 예문'이라는 말은 소리 내어 하지 않는다.";

    private PracticeInstructions() {
    }

    /**
     * 친구 되묻기(D_REASK, 기회 문장 뒤 J2 일부 전달). 친구 역할 그대로, 아이가 자기 말로 채워 답할 열린 질문 하나로 묻고 아이가 할 말은 알려 주지
     * 않는다.
     */
    static String peerReask(Scene scene, boolean again) {
        return fit("너는 아이의 " + RolePolicy.peerName(scene.partnerRole()) + " 역할이다. 상황: " + field(scene.sceneDescription())
                + (again ? " 같은 질문을 같은 말투로 한 번 더 천천히 물어본다. "
                        : " 친구 역할 그대로 아이 말에서 더 알고 싶은 것을, 아이가 자기 말로 채워 답할 열린 질문 하나로 짧게 되묻는다. ")
                + REASK_RULE + " " + VOICE_RULE + " " + PEER_RULE);
    }

    /**
     * 친구 반응(D_CONTINUE · D_RESUME). 장면을 닫은 아이 말에 친구 역할 그대로 반응해 장면을 한두 마디로 마무리한다. 칭찬 · 채점 대신 하는 반응이라
     * 평가 · 교정을 막고, 뒤에 아이 대답을 기다리지 않으므로 새 질문을 하지 않게 한다.
     */
    static String peerReaction(Scene scene) {
        return fit("너는 아이의 " + RolePolicy.peerName(scene.partnerRole()) + " 역할이다. 상황: " + field(scene.sceneDescription())
                + " 친구 역할 그대로 아이가 방금 한 말에 자연스럽게 반응해 장면을 한두 마디로 마무리한다. 칭찬하거나 고쳐 주지 않고, 새 질문을 하지 않는다. "
                + VOICE_RULE + " " + PEER_RULE);
    }

    /**
     * 도움 제안(D_OFFER_HELP)을 승인 문장 없이 생성형으로 낼 때. 무중이 친구 역할에서 잠깐 나와 도와줘도 되는지 묻는다(강제 아님). 말하는 역할은
     * 코치 슬롯(제브 PRACTICE_COACH)이다.
     */
    static String mujoongHelpOffer(boolean again) {
        return fit("너는 무중이다. 친구 역할에서 잠깐 나와, 무중이가 조금 도와줘도 되는지 한 문장으로 짧게 묻는다. 예: 무중이가 조금 도와줄까? "
                + (again ? "같은 말로 한 번 더 부드럽게 묻는다. " : "")
                + "힌트나 아이가 할 말은 말하지 않고, 싫다고 하면 그대로 받아들인다. " + VOICE_RULE + " " + COACH_RULE);
    }

    /**
     * 친구 첫 대사가 없는 옛 대본(생성 판 v5까지)의 변형 제안(D_VARIANT_OFFER). 판 v5의 변형 설명은 기회 문장을 글자 그대로 담아 제안에 넣으면
     * 아이가 단서를 미리 듣게 되므로, 무중이 역할 밖에서 비슷한 다른 장면으로 이어서 해 볼지만 묻는다. 변형 설명은 수락 뒤 장면 도입으로 들려준다.
     * 말하는 역할은 코치 슬롯(제브 PRACTICE_COACH)이다.
     */
    static String mujoongVariantOffer(boolean again) {
        return fit("너는 무중이다. 친구 역할에서 잠깐 나와, 비슷한 다른 장면으로 이어서 해 볼지 한 문장으로 짧게 묻는다. 예: 이번엔 다른 장면으로 이어서 해 볼까? "
                + (again ? "같은 말로 한 번 더 부드럽게 묻는다. " : "")
                + "새 장면의 내용이나 아이가 할 말은 말하지 않고, 싫다고 하면 그대로 받아들인다. " + VOICE_RULE + " " + COACH_RULE);
    }

    /**
     * 코치 도움 발화(D_FOCUS). 아이가 동의 · 요청한 뒤에만 표현 예시를 든다({@link RolePolicy#example}).
     *
     * <ul>
     *   <li>첫 도움(FRAME), 판 v7 대본(빈칸 예문이 있다): 요점 → 글자 그대로의 빈칸 예문 + '처럼 말할 수 있어' → '네 말로 해 볼래?' 세 가지를 이
     *       순서로 빠짐없이 말하게 한다(실측 F5, 기준 연습 대화의 코치 줄). 빈칸은 core가 만든 것이라 GPT-Live가 정답 낱말을 고르지 않는다.
     *   <li>첫 도움(FRAME), 옛 대본(빈칸 예문 없음): 요점 하나, 표현 예시의 핵심 낱말 자리를 '뭐뭐'로 비운 빈칸 예문 하나를 GPT-Live가 만들어 들어
     *       준다(표현 예시를 그대로 말하지 않는다), 마지막에 아이 말로 해 보자고 한다.
     *   <li>다시 해 보기 뒤(FULL): 표현 예시 하나. 동의가 없거나(NONE) 대본에 표현 예시가 없으면 단서만 준다.
     * </ul>
     */
    static String coachHelp(Scene scene, RolePolicy.Example example) {
        StringBuilder text = new StringBuilder("너는 아이를 돕는 코치 역할이다. 상황: ").append(field(scene.sceneDescription())).append(' ');
        String focus = first(scene.focusPoints());
        String sample = first(scene.expressionExamples());
        String frame = frame(scene);
        boolean framed = example == RolePolicy.Example.FRAME && !sample.isEmpty();
        if (framed && !frame.isEmpty()) {
            text.append("이 순서로 꼭 말한다. ");
            if (!focus.isEmpty()) {
                text.append("첫째, 요점을 쉬운 말 한 문장으로 말한다: ").append(focus).append(". ");
            }
            text.append("둘째, 이 빈칸 예문을 '뭐뭐'까지 그대로 말하고 '처럼 말할 수 있어'를 붙인다: ").append(frame).append(". ")
                    .append("셋째, 네 말로 해 볼래? 하고 묻는다. ").append(FRAME_RULE).append(' ');
        } else {
            if (!focus.isEmpty()) {
                text.append("이 요점 하나만 쉬운 말로 짧게 알려 준다: ").append(focus).append(". ");
            }
            if (framed) {
                text.append("그다음 이 표현 예시를 그대로 말하지 말고, 핵심 낱말 자리를 '뭐뭐'로 비운 빈칸 예문으로 바꿔 하나만 들어 준다: ")
                        .append(sample).append(". ");
            } else if (example == RolePolicy.Example.FULL && !sample.isEmpty()) {
                text.append("이 방향의 표현 예시를 하나만 들어도 된다: ").append(sample).append(". ");
            } else {
                text.append("표현 예시나 정답 문장은 말하지 말고, 상황을 다시 떠올려 스스로 말해 보도록 단서만 준다. ");
            }
            text.append("마지막에 아이 말로 해 보자고 한다. ");
            if (framed) {
                text.append(FRAME_RULE).append(' ');
            }
        }
        return fit(text.append("따라 하라고 다그치지 않는다. ").append(VOICE_RULE).append(' ').append(COACH_RULE).toString());
    }

    /** 코치 발화가 실제로 표현 예시(빈칸 예문 또는 예시)를 담는가: 동의 뒤이고 대본에 표현 예시가 있을 때다. 기록의 예시 여부와 같은 판단이다. */
    static boolean coachGivesExample(Scene scene, RolePolicy.Example example) {
        return example != RolePolicy.Example.NONE && !first(scene.expressionExamples()).isEmpty();
    }

    /** 코치가 다시 해 보기를 권하는 재안내(무응답 · 판독 불가 뒤). */
    static String coachRetryPrompt(Scene scene) {
        return fit("너는 아이를 돕는 코치 역할이다. 상황: " + field(scene.sceneDescription())
                + " 아이가 편할 때 자기 말로 다시 한 번 해 보자고 같은 말로 부드럽게 권한다. 새 예시나 정답은 말하지 않는다. 한두 문장으로 말한다. "
                + VOICE_RULE + " " + COACH_RULE);
    }

    /** 역할 전환 · 복귀 알림을 승인 문장 없이 생성형으로 낼 때(BR5.4). {@code toRole}은 PEER 또는 COACH. */
    static String roleNotice(String toRole, Scene scene) {
        if ("COACH".equals(toRole)) {
            return fit("친구 목소리에서 아이를 돕는 코치 목소리로 바뀐다고 한 문장으로 알린다. 곧 도와주겠다고만 말하고 예시나 정답은 말하지 않는다. "
                    + VOICE_RULE + " " + COACH_RULE);
        }
        return fit("다시 아이의 " + RolePolicy.peerName(scene.partnerRole()) + " 목소리로 돌아왔다고 한 문장으로 알린다. 새 질문이나 정답은 말하지 않는다. "
                + VOICE_RULE + " " + PEER_RULE);
    }

    /**
     * 첫 빈칸 예문(판 v7 대본). 제어 문자를 지운 한 줄이 칸 상한 안이고 '뭐뭐'가 있을 때만 쓴다. 상한에서 자르면 글자 그대로가 아니므로 쓰지 않고, 그때는
     * 옛 대본처럼 GPT-Live가 표현 예시에서 빈칸을 만든다.
     */
    static String frame(Scene scene) {
        String single = scene.firstFrame().replaceAll("\\p{Cntrl}+", " ").trim();
        boolean fits = single.codePointCount(0, single.length()) <= FIELD_MAX;
        return fits && single.contains(BLANK) ? single : "";
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
