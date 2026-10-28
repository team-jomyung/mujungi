package kr.mujoong.core.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BR1·BR5: 모든 아동 자료 접근은 childId를 필수로 전달한다. */
public interface ChildKnowledgeGraphPort {
    default void prepareSchema() {}
    List<KnowledgeSeed> seeds(UUID childId);
    /** 사용 가능한 씨앗과 그 씨앗에 쌓인 관찰 · 참고 기록. */
    List<SeedProfile> profiles(UUID childId);
    String begin();
    void replaceBase(String transaction, UUID childId, SeedCatalog catalog);
    void writeGeneratedSeed(String transaction, UUID childId, KnowledgeSeed seed);
    /** 이미 있는 씨앗의 관찰 기록만 덮어쓴다. 목록에 없는 씨앗은 0회로 되돌린다. 씨앗을 새로 만들지 않는다. */
    void writeObservations(String transaction, UUID childId, Map<String, SeedObservation> observations);
    /** 이미 있는 씨앗의 마지막 참고 시점만 바꾼다. 씨앗을 새로 만들지 않는다. */
    void markReferenced(String transaction, UUID childId, String seedId, Instant referencedAt);
    void deleteSeeds(String transaction, UUID childId, List<String> seedIds);
    void deleteChild(String transaction, UUID childId);
    void commit(String transaction);
    /** 종료를 서버가 확인했을 때만 true. 클라이언트 취소는 종료 증거가 아니다. */
    boolean terminate(String transaction);
}
