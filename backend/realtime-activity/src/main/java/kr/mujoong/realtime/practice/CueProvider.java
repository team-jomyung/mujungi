package kr.mujoong.realtime.practice;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 실제 공급자는 별도 연결한다. 운영 기본은 도움 생성 불가다. */
@FunctionalInterface
public interface CueProvider {
    CompletionStage<Result> generate(CueBrief brief);
    record Result(String text, boolean approved, boolean containsAnswerSentence) {
        @Override public String toString() { return "CueResult[approved=" + approved + "]"; }
    }
    CueProvider UNAVAILABLE = brief -> CompletableFuture.failedFuture(new IllegalStateException("도움 공급자 미연결"));
}
