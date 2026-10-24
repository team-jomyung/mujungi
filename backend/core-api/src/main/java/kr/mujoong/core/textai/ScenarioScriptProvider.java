package kr.mujoong.core.textai;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** U10: 별도 생성/검토 단일 호출 능력만 제공한다. 재시도는 생성 흐름이 소유한다. */
public interface ScenarioScriptProvider {
    /**
     * 생성 프롬프트 판. 연습 대화 C안의 새 칸 다섯 개를 채우는 판이다(docs/practice-direct-ask.md 4.1). 공급자가 보내는 판과 생성 흐름이 대본에 남기는
     * 판이 같도록 한곳에 둔다.
     */
    String GENERATION_PROMPT_VERSION = "scenario-generation.v3";
    /** 검토 프롬프트 판(새 칸을 기존 사유 코드로 검토한다). */
    String REVIEW_PROMPT_VERSION = "scenario-review.v2";
    /** 위 두 판으로 만든 대본의 판(계약 practice-scenario.schema.json scriptVersion). */
    String SCRIPT_VERSION = "scenario-script.v2";

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
