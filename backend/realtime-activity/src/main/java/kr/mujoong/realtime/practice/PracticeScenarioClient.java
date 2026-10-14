package kr.mujoong.realtime.practice;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;

public final class PracticeScenarioClient implements PracticePorts.ScenarioPort {
    private final InternalHttpClient http;
    private final Executor calls;
    private final Duration timeout;
    public PracticeScenarioClient(InternalHttpClient http, Executor calls, Duration timeout) {
        this.http = http; this.calls = calls; this.timeout = timeout;
    }
    record Request(UUID sessionId, UUID goalSelectionId, String kind, UUID previousScenarioId) { }
    record Validation(boolean goalIncluded, boolean triggerIncluded, boolean forbiddenFound) { }
    public record Scenario(UUID scenarioId, String kind, String scenarioText, String partnerRoleText,
            String triggerScript, Validation validation) {
        public PracticePorts.Scene scene(String expectedKind) {
            if (scenarioId == null || !expectedKind.equals(kind) || validation == null || !validation.goalIncluded()
                    || !validation.triggerIncluded() || validation.forbiddenFound()
                    || scenarioText == null || scenarioText.length() > 8000 || partnerRoleText == null
                    || partnerRoleText.length() > 200 || triggerScript == null || triggerScript.length() > 2000) {
                throw new IllegalStateException("상황 응답 검증 실패");
            }
            return new PracticePorts.Scene(scenarioId, scenarioText, partnerRoleText, triggerScript);
        }
        @Override public String toString() { return "Scenario[id=" + scenarioId + "]"; }
    }
    @Override public CompletionStage<PracticePorts.Scene> prepare(UUID sessionId, SessionGoal goal, UUID previous) {
        return CompletableFuture.supplyAsync(() -> {
            String kind = previous == null ? "ORIGINAL" : "NEW_SCENARIO";
            try {
                var reply = http.send(InternalHttpClient.Target.PRACTICE_SCENARIO,
                        new InternalHttpClient.Call("POST", "/internal/practice/scenarios",
                                JsonCodec.shared().write(new Request(sessionId, goal.goalSelectionId(), kind, previous)),
                                timeout, 1, sessionId, null));
                if (!reply.isSuccess()) throw new IllegalStateException("상황 준비 불가");
                var scene = JsonCodec.shared().read(reply.body(), Scenario.class).scene(kind);
                if (scene.id().equals(previous)) throw new IllegalStateException("새 상황 식별자 중복");
                return scene;
            } catch (RuntimeException | InternalHttpClient.CallException failure) {
                throw new IllegalStateException("상황 준비 실패");
            }
        }, calls);
    }
}
