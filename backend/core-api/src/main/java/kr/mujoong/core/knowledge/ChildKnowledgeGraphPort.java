package kr.mujoong.core.knowledge;

import java.util.List;
import java.util.UUID;

/** BR1·BR5: 모든 아동 자료 접근은 childId를 필수로 전달한다. */
public interface ChildKnowledgeGraphPort {
    List<KnowledgeSeed> seeds(UUID childId);
    String begin();
    void replaceBase(String transaction, UUID childId, SeedCatalog catalog);
    void writeGeneratedSeed(String transaction, UUID childId, KnowledgeSeed seed);
    void deleteSeeds(String transaction, UUID childId, List<String> seedIds);
    void deleteChild(String transaction, UUID childId);
    void commit(String transaction);
    /** 종료를 서버가 확인했을 때만 true. 클라이언트 취소는 종료 증거가 아니다. */
    boolean terminate(String transaction);
}
