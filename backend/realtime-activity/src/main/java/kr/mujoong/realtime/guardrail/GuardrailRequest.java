package kr.mujoong.realtime.guardrail;

import java.util.List;
import java.util.Objects;

/**
 * 제브 검사 요청의 내부 형식 (U13 FR1.1 · FR2.2 · BR1.1 · BR2.4). {@code contracts/schemas/guardrail-request.schema.json}과 같은 모양이다
 * (닫힌 객체). 아동 · 보호자 · 세션 · 기기 식별자와 키를 담는 칸이 없다(NFR3.1). 공급자 실제 요청은 어댑터 안에서만 이 형식에서 바꾼다.
 * 후보 문장과 맥락 안의 지시문은 이 값의 문자열 데이터일 뿐이고 시스템 지시에는 섞이지 않는다(BR2.5).
 *
 * @param source 요청 출처. {@value #SOURCE} 하나뿐
 * @param mode 검사 모드. 지금 운영에서 실제로 부르는 것은 {@link Mode#FREE_TALK}뿐이다(BR1.1)
 * @param candidate 검사할 후보 문장 = 봉인된 구간의 전사(1 ~ {@value #CANDIDATE_MAX}자, 아이 이름은 '아이'로 치환된 값)
 * @param policyVersion 세션에 고정된 정책 판(1 ~ {@value GuardrailEventRecord#POLICY_VERSION_MAX}자)
 * @param recentContext 익명화한 최근 맥락 {@value #CONTEXT_MAX_TURNS}턴 이하(오래된 것부터)
 */
public record GuardrailRequest(String source, Mode mode, String candidate, String policyVersion, List<ContextTurn> recentContext) {

    /** 요청 출처 상수. */
    public static final String SOURCE = "GPT_LIVE_GENERATED";
    /** 후보 문장 길이 상한(스키마 maxLength). */
    public static final int CANDIDATE_MAX = 4000;
    /** 맥락 턴 수 상한(스키마 maxItems). */
    public static final int CONTEXT_MAX_TURNS = 3;
    /** 맥락 한 줄 길이 상한(스키마 maxLength). */
    public static final int CONTEXT_TEXT_MAX = 2000;

    /** 검사 모드. */
    public enum Mode {
        FREE_TALK,
        PRACTICE_PEER,
        PRACTICE_COACH
    }

    /** 맥락의 말한 쪽. */
    public enum Speaker {
        CHILD,
        PARTNER,
        COACH,
        MUJOONG
    }

    /**
     * 맥락 한 줄.
     *
     * @param text 1 ~ {@value #CONTEXT_TEXT_MAX}자, 아이 이름은 '아이'로 치환된 값
     */
    public record ContextTurn(Speaker speaker, String text) {

        public ContextTurn {
            Objects.requireNonNull(speaker, "speaker");
            Objects.requireNonNull(text, "text");
            if (text.isEmpty() || text.length() > CONTEXT_TEXT_MAX) {
                throw new IllegalArgumentException("맥락 한 줄의 길이가 올바르지 않다");
            }
        }

        /** 문자열 표현에 맥락 원문을 내지 않는다(R-F14). */
        @Override
        public String toString() {
            return "ContextTurn[speaker=" + speaker + ", chars=" + text.length() + "]";
        }
    }

    public GuardrailRequest {
        if (!SOURCE.equals(source)) {
            throw new IllegalArgumentException("source는 " + SOURCE + " 하나뿐이다");
        }
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.isEmpty() || candidate.length() > CANDIDATE_MAX) {
            throw new IllegalArgumentException("candidate의 길이가 올바르지 않다");
        }
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (policyVersion.isEmpty() || policyVersion.length() > GuardrailEventRecord.POLICY_VERSION_MAX) {
            throw new IllegalArgumentException("policyVersion의 길이가 올바르지 않다");
        }
        recentContext = recentContext == null ? List.of() : List.copyOf(recentContext);
        if (recentContext.size() > CONTEXT_MAX_TURNS) {
            throw new IllegalArgumentException("맥락은 " + CONTEXT_MAX_TURNS + "턴 이하여야 한다");
        }
    }

    /** 요청 한 건을 만드는 짧은 길(출처 상수 고정). */
    public static GuardrailRequest of(Mode mode, String candidate, String policyVersion, List<ContextTurn> recentContext) {
        return new GuardrailRequest(SOURCE, mode, candidate, policyVersion, recentContext);
    }

    /** 문자열 표현에 후보 문장 · 맥락 원문을 내지 않는다(R-F14). */
    @Override
    public String toString() {
        return "GuardrailRequest[mode=" + mode + ", candidateChars=" + candidate.length() + ", contextTurns=" + recentContext.size() + "]";
    }
}
