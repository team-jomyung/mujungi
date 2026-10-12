package kr.mujoong.core.textai;
import java.time.Duration;
/** Future adapters must honor interruption/timeout and classify only transient failures retryable. */
@FunctionalInterface
public interface TextAiProvider {
    GeneratedScenario generateScenario(ScenarioPrompt prompt, Duration timeout);
}
