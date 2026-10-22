package kr.mujoong.core.textai;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** U10: 별도 생성/검토 단일 호출 능력만 제공한다. 재시도는 생성 흐름이 소유한다. */
public interface ScenarioScriptProvider {
    /** 대본 생성 프롬프트 판. 판을 올릴 때는 이 값과 자원 파일만 바꾼다. */
    String GENERATION_PROMPT_VERSION = "scenario-generation.v4";
    /** {@link #GENERATION_PROMPT_VERSION} 판의 지시 자원(첫 줄이 판 이름). */
    String GENERATION_INSTRUCTIONS = "prompts/scenario/generation.v4.txt";
    /** 별도 검토 프롬프트 판. 판을 올릴 때는 이 값과 자원 파일만 바꾼다(저장된 대본의 selfReview 판도 이 값이다). */
    String REVIEW_PROMPT_VERSION = "scenario-review.v2";
    /** {@link #REVIEW_PROMPT_VERSION} 판의 지시 자원(첫 줄이 판 이름). */
    String REVIEW_INSTRUCTIONS = "prompts/scenario/review.v2.txt";
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
