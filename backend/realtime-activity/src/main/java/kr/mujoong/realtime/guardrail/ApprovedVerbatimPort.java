package kr.mujoong.realtime.guardrail;

import java.util.Optional;
import java.util.UUID;

/**
 * 승인 원문 일치를 묻는 좁은 포트 (U13 BR7.1 ~ BR7.4). 제브 패키지가 콘텐츠 레지스트리 전체에 기대지 않도록 '이 출력이 서버가 고정한 승인 발화나
 * 등록된 대본 문장과 원문 그대로인가'만 묻는다. 정규화는 ASCII 공백 제거뿐이다(BR7.3) — 그 규칙은 구현({@code LiveContentRegistry#matchApproved})이
 * 쥐고 이 포트는 섞지 않는다. 일치를 확인할 수 없으면(UNKNOWN · NOT_MATCHED) 비어 있다 — 생략하지 않고 새 발화로 검사한다(BR7.2).
 */
@FunctionalInterface
public interface ApprovedVerbatimPort {

    /** 원문 일치를 확인하지 않는다 — 어떤 출력도 생략하지 않는다(U13 전 모양 · 시험 기본값). */
    ApprovedVerbatimPort NONE = (sessionId, outputText) -> Optional.empty();

    /**
     * 출력 전사가 승인 원문과 일치하면 그 출처의 사유 코드({@code APPROVED_SPEECH} · {@code SESSION_SCRIPT}), 아니면 비어 있다. 전사는 저장하지
     * 않는다.
     */
    Optional<String> matchedSource(UUID sessionId, String outputText);

    /** 콘텐츠 레지스트리의 원문 일치를 좁은 포트로 잇는다. MATCHED만 생략 근거고 UNKNOWN · NOT_MATCHED는 비어 있다(BR7.2). */
    static ApprovedVerbatimPort of(kr.mujoong.realtime.content.LiveContentRegistry registry) {
        return (sessionId, outputText) -> {
            var match = registry.matchApproved(sessionId, outputText);
            return match.allowsGuardrailSkip() ? Optional.of(match.source().name()) : Optional.empty();
        };
    }
}
