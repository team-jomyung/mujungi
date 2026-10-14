package kr.mujoong.core.practice;
import java.util.UUID;
import org.springframework.stereotype.Service;
/** U6 재판정에는 상대역 지시를 전달하지 않는다. */
@Service
public class ScenarioLookup {
    private final ScenarioRepository repository;
    public ScenarioLookup(ScenarioRepository repository){this.repository=repository;}
    public Content get(UUID id,UUID guardian,UUID session){
        var stored=repository.require(id);
        if(!stored.guardianId().equals(guardian)||!stored.sessionId().equals(session))
            throw kr.mujoong.core.common.error.CoreApiException.of(kr.mujoong.contract.error.ErrorCode.NOT_FOUND);
        var s=stored.scenario();return new Content(s.scenarioText(),s.triggerScript());
    }
    public java.util.List<Snapshot> get(UUID guardian,java.util.Collection<UUID> ids){
        return ids.stream().map(id->{var stored=repository.require(id);
            if(!stored.guardianId().equals(guardian))throw kr.mujoong.core.common.error.CoreApiException.of(kr.mujoong.contract.error.ErrorCode.NOT_FOUND);
            var s=stored.scenario();return new Snapshot(id,stored.goalSelectionId(),stored.behaviorKey(),stored.behaviorVersion(),s.scenarioText(),s.triggerScript(),s.triggerScript());
        }).toList();
    }
    public record Snapshot(UUID scenarioId,UUID goalSelectionId,String behaviorKey,int behaviorVersion,String situation,String partnerUtterance,String triggerScript){@Override public String toString(){return "Snapshot[id="+scenarioId+"]";}}
    public record Content(String scenarioText,String triggerScript){@Override public String toString(){return "Content[redacted]";}}
}
