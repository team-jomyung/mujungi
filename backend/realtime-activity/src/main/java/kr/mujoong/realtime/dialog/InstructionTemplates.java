package kr.mujoong.realtime.dialog;

import java.util.Objects;
import kr.mujoong.contract.status.ActivityType;

/**
 * 서버 발화 지시문 템플릿 (U11 설계 개정 A2 · A4.5 · A6). 공급자에는 응답 요청 type이 없어 서버의 모든 발화가 지시({@code session.instructions.append})
 * 하나로 시작한다. 추가한 지시는 이후 응답에도 남고 지울 방법이 없으므로(실호출 관측) 모든 지시문이 스스로 범위를 닫는다 — "이번 한 번만 …, 그 뒤에는
 * 평소처럼 …". 새 말의 첫 낱말이 오디오 시계 여유로 잘릴 수 있어 자유 발화 지시는 아주 짧은 맞장구로 시작하라고 시킨다. 역할 지시문
 * ({@link DialogInstructions#role})과 달리 아이 정보를 받을 입구가 없다(고정 문장 + 호출자가 검증한 본문만).
 */
public final class InstructionTemplates {

    /** 새 말의 첫 낱말이 오디오 시계 여유로 잘려도 문제가 없게 모든 발화 지시가 시키는 아주 짧은 맞장구(설계 개정 A4.5). */
    public static final String INTERJECTION_LEAD = "먼저 '음,'이라고 짧게 말한 뒤";

    /**
     * 지시의 범위를 스스로 닫는 문장(설계 개정 A6): 추가한 지시는 이후 응답에도 남고 지울 방법이 없으므로 모든 지시문 끝에 붙는다. "이번 한 번만 …,
     * 그 뒤에는 평소처럼 …" 모양이다.
     */
    public static final String SCOPE_CLOSE = "이번 한 번만 위 지시를 따르고, 그 뒤에는 평소처럼 아이 말을 끝까지 듣고 한두 문장으로 답한다.";

    /** 발언권을 돌려주는 지시(응답 요청 type이 없어 지시가 응답을 시작한다, 설계 개정 A2). */
    public enum ResponseStart {
        /** 참여 확인 뒤 첫 응답. */
        FIRST(INTERJECTION_LEAD + " 관심사나 일상 질문 하나로 아이와 이야기를 시작한다. "),
        /** 확인 질문 뒤 이어 가기 · 연습의 응답 요청. */
        CONTINUE(INTERJECTION_LEAD + " 아이가 마지막으로 한 말에 한두 문장으로 답한다. ");

        private final String lead;

        ResponseStart(String lead) {
            this.lead = lead;
        }

        /** 공급자에 보낼 문장(범위를 닫는 문장 포함). */
        public String content() {
            return scoped(lead);
        }

        public String content(ActivityType mode) {
            return content(mode, null);
        }

        /** 자유대화 첫 응답에는 대화 전 보호자 참고(선택, {@link DialogInstructions#conversationGuide})를 덧붙인다. 연습에는 싣지 않는다. */
        public String content(ActivityType mode, kr.mujoong.contract.context.ConversationGuide guide) {
            if (mode == ActivityType.PRACTICE) return content();
            if (this == FIRST) return scoped("서버가 현재 안내 전달과 참여 수락을 확인했다. 현재 연결에서 검증된 시작 안내 녹음의 실제 재생이 완료됐다. "
                    + "시작 인사·자기소개·안내 대본·참여 질문을 다시 시작하거나 반복하지 않는다. "
                    + "아이가 마지막으로 한 질문·경험·정정을 먼저 따르고 바로 이어 답한다. 아이가 누구인지나 이름을 명시적으로 물으면 그 질문에만 답한다. "
                    + "새 질문·맞장구를 강제하지 않고 아이에게 발언권을 돌려준다." + DialogInstructions.conversationGuide(guide));
            return scoped("서버가 현재 안내 전달과 참여 수락을 확인했다. 같은 안내나 참여 질문은 반복하지 않는다. "
                    + "아이가 이미 말한 질문·경험·정정을 먼저 따르고, 답 뒤 새 질문을 자동으로 붙이지 않는다. "
                    + "아직 아이가 말하지 않았다면 짧게 참여를 받아 주고 발언권을 돌려준다.");
        }
    }

    /**
     * 판정 턴이 보내는 다음 행동 지시 (U11 FR3.3 · FR4.2 · FR6.4 · BR3.5 · BR4.2). 고정 문장뿐이라 판정 라벨 · 확률 · 기준 · 아이 원문이 들어갈
     * 자리가 없다(채울 값이 없고 {@link #content()}가 인자를 받지 않는다). 모두 맞장구로 시작해 범위를 닫는다.
     */
    public enum NextAction {
        /** 판정 보류 뒤: 교정 없이 짧게 다시 말해 달라고 한다(BR4.2). */
        HOLD_REASK(INTERJECTION_LEAD + " 방금 말을 잘 못 알아들었다고 가볍게 말하고, 한 번만 더 짧게 말해 달라고 다정하게 부탁한다. "),
        /** 쉬고 싶다는 뜻을 받아 주고 곧 이어서 하자고 한다(BR6.4). */
        REST_ACK_AND_RETURN(INTERJECTION_LEAD + " 쉬고 싶다는 마음을 받아 주고, 잠깐 숨을 고른 뒤 이어서 해 보자고 다정하게 말한다. "),
        /** 쉬고 싶다는 뜻만 받아 주고 아이가 다시 말할 때까지 기다린다(BR6.4). */
        REST_ACK_ONLY(INTERJECTION_LEAD + " 쉬고 싶다는 마음을 받아 주고, 편할 때 말해 달라고 다정하게 말한다. ");

        private final String lead;

        NextAction(String lead) {
            this.lead = lead;
        }

        /** 공급자에 보낼 문장(범위를 닫는 문장 포함, 500자 이하). */
        public String content() {
            return scoped(lead);
        }

        public String content(ActivityType mode) {
            if (mode == ActivityType.PRACTICE) return content();
            return switch (this) {
                case HOLD_REASK -> scoped("판정이 보류되었다. 이해 실패를 꾸미거나 새 질문을 강제하지 말고, "
                        + "확인된 아이 말을 존중하며 기다린다.");
                case REST_ACK_AND_RETURN, REST_ACK_ONLY -> scoped("쉬거나 생각하겠다는 선택을 짧게 한 번 수용한다. "
                        + "새 질문이나 다시 이어 하자는 권유 없이 아이가 새로 말할 때까지 기다린다.");
            };
        }
    }

    /**
     * 제브가 막은 뒤 서버 지시 없는 자유 응답을 한 번 다시 받는 지시 (U13 BR6.2). 방금 말은 쓰지 말라고만 하고 정책 내용 · 판정 사유 · 후보 문장은
     * 담지 않는다(채울 값이 없고 {@link #content()}가 인자를 받지 않는다).
     */
    public enum Retry {
        RETRY_FREE_TURN(INTERJECTION_LEAD + " 방금 한 말은 쓰지 말고, 같은 이야기 흐름에서 다른 말로 짧게 한 번만 이어 말한다. ");

        private final String lead;

        Retry(String lead) {
            this.lead = lead;
        }

        /** 공급자에 보낼 문장(범위를 닫는 문장 포함). */
        public String content() {
            return scoped(lead);
        }

        public String content(ActivityType mode) {
            return mode == ActivityType.PRACTICE ? content() : scoped("전달하지 않은 방금 응답은 이어서 말하지 않는다. "
                    + "확인된 현재 아이 문맥만으로 짧게 답한다. 이해 실패·도움 요청을 꾸미거나 질문·맞장구를 강제하지 않는다.");
        }
    }

    private InstructionTemplates() {
    }

    /** 지시 본문 끝에 범위를 닫는 문장을 붙인다(이미 붙어 있으면 그대로). */
    public static String scoped(String body) {
        Objects.requireNonNull(body, "body");
        String trimmed = body.strip();
        return trimmed.endsWith(SCOPE_CLOSE) ? trimmed : trimmed + " " + SCOPE_CLOSE;
    }

    /** 게이트 폐기 뒤 교정 지시 전문: 짧은 맞장구로 시작해 범주 지침대로 다시 답하고 범위를 닫는다. */
    public static String correctionContent(DialogInstructions.Correction correction) {
        Objects.requireNonNull(correction, "correction");
        return scoped(INTERJECTION_LEAD + " 아래 지침에 따라 다시 답한다. 지침: " + correction.text());
    }

    /** 기존 금지 범주는 유지하면서 교정이 새 질문/의무 맞장구를 요구하지 않게 한다. */
    public static String correctionContent(DialogInstructions.Correction correction, ActivityType mode) {
        if (mode == ActivityType.PRACTICE) return correctionContent(correction);
        Objects.requireNonNull(correction, "correction");
        String constraint = switch (correction) {
            case DIAGNOSIS -> "상태를 진단하거나 이름 붙이지 않는다.";
            case TREATMENT -> "치료나 처치를 권하거나 지시하지 않는다.";
            case SCORE -> "점수·등급·남과의 비교로 평가하지 않는다.";
            case CAUSE -> "원인을 단정하거나 원인 확인 질문을 강제하지 않는다.";
            case GENERALIZATION -> "늘·절대로 일반화하지 않는다.";
            case EMOTION_CLAIM -> "감정을 단정하거나 감정 확인 질문을 강제하지 않는다.";
            case PRESSURE -> "재촉하지 않고 아이가 선택한 만큼 기다린다.";
            case PROFANITY -> "욕설·비속어·놀리는 말을 쓰지 않고, 욕설처럼 들리는 이름은 부르지 않고 친구라고 부른다.";
            case INCOMPLETE -> "확인된 문맥에서 완결된 한두 문장만 사용한다.";
        };
        return scoped("방금 응답은 아이에게 전달하지 않았다. " + constraint
                + " 취소된 질문을 잇지 않고 이해 실패를 꾸미거나 새 질문·맞장구를 강제하지 않는다.");
    }
}
