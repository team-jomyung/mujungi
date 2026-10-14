package kr.mujoong.core.practice;
import java.util.UUID;
import org.springframework.stereotype.Service;
/** U6 재판정에는 상대역 지시를 전달하지 않는다. */
@Service
public class ScenarioLookup {
    private final ScenarioRepository repository;
    public ScenarioLookup(ScenarioRepository repository){this.repository=repository;}
    public java.util.Optional<Content> get(UUID id,UUID guardian,UUID session){
        return repository.find(id).filter(stored->stored.guardianId().equals(guardian)&&stored.sessionId().equals(session))
            .map(stored->new Content(stored.scenario().scenarioText(),stored.scenario().triggerScript()));
    }
    public java.util.List<Snapshot> get(UUID guardian,java.util.Collection<UUID> ids){
        return ids.stream().distinct().flatMap(id->repository.find(id).filter(stored->stored.guardianId().equals(guardian)).map(stored->{
            var s=stored.scenario();return new Snapshot(id,stored.goalSelectionId(),stored.behaviorKey(),stored.behaviorVersion(),s.scenarioText(),s.triggerScript(),s.triggerScript());
        }).stream()).toList();
    }
    public record Snapshot(UUID scenarioId,UUID goalSelectionId,String behaviorKey,int behaviorVersion,String situation,String partnerUtterance,String triggerScript){@Override public String toString(){return "Snapshot[id="+scenarioId+"]";}}
    public record Content(String scenarioText,String triggerScript){@Override public String toString(){return "Content[redacted]";}}
}
