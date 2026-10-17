package kr.mujoong.realtime.dialog;

import java.util.Objects;

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
}
