package kr.mujoong.core.knowledge;
import java.util.List;
/** Optional Neo4j read adapter; only public catalog keys, never child data. */
public interface KnowledgeGraphLookup {
    List<String> findRelatedBehaviorKeys(String behaviorKey);
}
