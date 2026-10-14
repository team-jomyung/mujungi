package kr.mujoong.core.knowledge;
import java.util.List;
import org.springframework.stereotype.Service;
/** U6가 지식의 공개 읽기 경계로 접근한다. */
@Service
public class BehaviorDefinitionLookup {
    private final PragmaticsKnowledge knowledge;
    private final KnowledgeJdbcRepository repository;
    public BehaviorDefinitionLookup(PragmaticsKnowledge knowledge,KnowledgeJdbcRepository repository){this.knowledge=knowledge;this.repository=repository;}
    public BehaviorDefinition get(String key,int version){return knowledge.requireVersion(key,version);}
    public List<BehaviorDefinition> active(){return repository.activeDefinitions();}
}
