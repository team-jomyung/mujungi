package kr.mujoong.core.textai;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** U10: 별도 생성/검토 단일 호출 능력만 제공한다. 재시도는 생성 흐름이 소유한다. */
public interface ScenarioScriptProvider {
    Generated generate(Call request);
    Reviewed review(Call request);
    record Call(Map<String,Object> input,Map<String,String> aliases,Instant deadline) {
        public Call { input=Map.copyOf(input);aliases=Map.copyOf(aliases); }
        @Override public String toString(){return "ScenarioCall[redacted]";}
    }
    record Generated(ScenarioScript script,String model) {
        @Override public String toString(){return "GeneratedScript[redacted]";}
    }
    record Reviewed(ScenarioReview review,String model) {}
    final class Failure extends RuntimeException {
        public enum Kind { TRANSIENT,CONTENT,PERMANENT }
        private final Kind kind;
        private final Optional<Duration> retryAfter;
        public Failure(Kind kind,Optional<Duration> retryAfter){super("Scenario provider failure: "+kind,null,false,false);this.kind=kind;this.retryAfter=retryAfter;}
        public Kind kind(){return kind;}
        public Optional<Duration> retryAfter(){return retryAfter;}
    }
}
