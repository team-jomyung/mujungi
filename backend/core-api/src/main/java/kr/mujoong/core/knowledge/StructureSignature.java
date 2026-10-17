package kr.mujoong.core.knowledge;

import java.util.Set;

/** BR3: 다섯 구조 값은 장면 변형에서도 보존한다. */
public record StructureSignature(String targetSkill, String requiredInference,
        String perspectiveStructure, String decidableByEvidence, String directCheckAllowed) {
    public StructureSignature {
        if (!Set.of("RECOGNIZE", "PRODUCE", "REPAIR").contains(targetSkill)
                || requiredInference == null || requiredInference.isBlank()
                || !Set.of("상대의 의도 1층", "상대의 지식 상태 1층", "상대가 나를 어떻게 봤을지 2층").contains(perspectiveStructure)
                || !Set.of("YES", "NO").contains(decidableByEvidence)
                || !Set.of("YES", "NO").contains(directCheckAllowed)) {
            throw new IllegalArgumentException("Invalid structure signature");
        }
    }
}
