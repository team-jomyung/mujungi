package kr.mujoong.core.knowledge;

import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** BR5: 외부 삭제가 불명확하면 예외로 지휘의 재시도를 유지한다. */
@Component
@Order(DeletionParticipant.ORDER_KNOWLEDGE_GRAPH)
public class KnowledgeDeletionParticipant implements DeletionParticipant {
    private final ChildKnowledgeService knowledge;
    public KnowledgeDeletionParticipant(ChildKnowledgeService knowledge) { this.knowledge = knowledge; }
    @Override public Map<String, Integer> deleteByGuardian(UUID guardian, UUID request) {
        var children = knowledge.ledger.children(guardian);
        for (UUID child : children) knowledge.deleteChild(guardian, child);
        knowledge.transaction(() -> { knowledge.ledger.forgetDeletedChildren(guardian); return null; });
        return Map.of("knowledge_graph", children.size());
    }
}
