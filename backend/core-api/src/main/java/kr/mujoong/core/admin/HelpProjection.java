package kr.mujoong.core.admin;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.mujoong.core.admin.RealtimeAdminClient.HelpFact;
import kr.mujoong.core.admin.RealtimeAdminClient.SceneRunFacts;

/**
 * 장면 하나의 도움 제안 · 반응 판정 (JIRA-280). Realtime이 저장한 기록만으로 판단하고, 기록으로 가를 수 없으면 확인 불가로 둔다.
 *
 * <p>근거(realtime practiceflow FlowTransitions · PracticeConversation):
 * <ul>
 *   <li>도움 제안 자체는 기록되지 않는다. 반응은 수락 · 요청 · 불명확이면 역할 전환(ROLE_SWITCH) 행과 첫 초점 도움(FOCUS_HELP) 행에 같은 값이
 *       함께 저장되고, 거절은 도움 행 없이 장면 사실 HELP_DECLINED로만 남는다. 그래서 행을 세지 않고 장면당 한 번만 판정한다.
 *   <li>IMMEDIATE · AFTER_REASK는 장면 안 도움 제안 전의 수행이다. PREPRACTICE_PROVIDED가 있으면 사전 교육 뒤 수행이며,
 *       NOT_OFFERED가 모든 교육·도움이 없었다는 뜻은 아니다. 사전 연습을 아이가 요청한 도움이나 무응답으로 세지 않는다.
 *   <li>그 밖(시간 · 종료 요청으로 끝남, 판정 보류, 장면 실행 기록 없음)은 제안 전에 끝났는지 제안 뒤 반응이 없었는지 기록으로 가를 수 없다.
 * </ul>
 */
public final class HelpProjection {

    /** 제안 여부. */
    public enum Offer {
        /** 시스템이 도움을 제안했고 반응이 기록됐다. */
        OFFERED,
        /** 아이가 먼저 도움을 청했다. */
        REQUESTED,
        /** 장면 안 도움 제안 단계 전에 끝났다. 사전 연습 여부와 독립적이다. */
        NOT_OFFERED,
        /** 기록으로 가를 수 없다. */
        UNKNOWN
    }

    /** 판정 결과. {@code answer}는 ACCEPT · DECLINE · UNCLEAR · REQUESTED 또는 null. */
    public record Result(Offer offer, String answer) {
    }

    /** 실제 도움 실행으로 세는 종류(역할 전환 · 복귀는 도움이 아니다). */
    public static final Set<String> EXECUTED_KINDS = Set.of("FOCUS_HELP", "SITUATION_EXPLAIN", "EXPRESSION_EXAMPLE");

    private static final Set<String> BEFORE_OFFER_TIMINGS = Set.of("IMMEDIATE", "AFTER_REASK");
    private static final Set<String> ANSWERS = Set.of("ACCEPT", "DECLINE", "UNCLEAR", "REQUESTED");

    private HelpProjection() {
    }

    /** 장면 실행 기록이 없으면(레거시 흐름 · 기록 실패) 확인 불가다. */
    public static Result of(SceneRunFacts run) {
        if (run == null) {
            return new Result(Offer.UNKNOWN, null);
        }
        Optional<String> recorded = consent(run.helps(), "ROLE_SWITCH").or(() -> consent(run.helps(), "FOCUS_HELP"));
        if (recorded.isPresent()) {
            String answer = recorded.get();
            return new Result("REQUESTED".equals(answer) ? Offer.REQUESTED : Offer.OFFERED, answer);
        }
        List<String> facts = run.facts();
        if (facts.contains("HELP_DECLINED")) {
            return new Result(Offer.OFFERED, "DECLINE");
        }
        if (facts.contains("HELP_ACCEPTED")) {
            return new Result(Offer.OFFERED, "ACCEPT");
        }
        if (run.liveTiming() != null && BEFORE_OFFER_TIMINGS.contains(run.liveTiming())) {
            return new Result(Offer.NOT_OFFERED, null);
        }
        return new Result(Offer.UNKNOWN, null);
    }

    private static Optional<String> consent(List<HelpFact> helps, String kind) {
        return helps.stream()
                .filter(help -> kind.equals(help.kind()) && help.consentAnswer() != null && ANSWERS.contains(help.consentAnswer()))
                .map(HelpFact::consentAnswer)
                .findFirst();
    }
}
