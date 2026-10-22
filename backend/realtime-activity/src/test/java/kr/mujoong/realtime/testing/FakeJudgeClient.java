package kr.mujoong.realtime.testing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import kr.mujoong.realtime.judgment.JudgeClient;
import kr.mujoong.realtime.judgment.JudgeOutcome;
import kr.mujoong.realtime.judgment.JudgeRequest;

/**
 * 시험용 판정기(U11): 요청마다 약속을 하나 만들고 시험이 손으로 푼다. Luna를 부르지 않는다(R-F1). 요청 본문은 시험에서만 읽는다.
 */
public final class FakeJudgeClient implements JudgeClient {

    /** 받은 요청과 그 약속. */
    public record Call(JudgeRequest request, UUID turnId, CompletableFuture<JudgeOutcome> future) {
    }

    private final List<Call> calls = new ArrayList<>();
    private boolean available = true;

    @Override
    public synchronized boolean available() {
        return available;
    }

    public synchronized void available(boolean value) {
        this.available = value;
    }

    @Override
    public synchronized CompletionStage<JudgeOutcome> judge(JudgeRequest request, UUID turnId) {
        Call call = new Call(request, turnId, new CompletableFuture<>());
        calls.add(call);
        return call.future();
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized Call last() {
        return calls.getLast();
    }

    /** 마지막 요청에 확정 결과를 준다. */
    public void confirm(String first, double firstP, String second, double secondP) {
        last().future().complete(JudgeOutcome.confirmed(first, firstP, second, secondP, Duration.ofMillis(900), "luna-test", "prompt-v1"));
    }

    /** 마지막 요청에 확률이 남는 보류(LOW_CONFIDENCE · LOW_MARGIN)를 준다. */
    public void abstainUnsure(kr.mujoong.realtime.judgment.AbstainReason reason, String first, double firstP, String second,
            double secondP) {
        last().future().complete(JudgeOutcome.abstainedWithScores(reason, first, firstP, second, secondP, Duration.ofMillis(900),
                "luna-test", "prompt-v1"));
    }

    /** 마지막 요청에 확률 없는 보류를 준다. */
    public void abstain(kr.mujoong.realtime.judgment.AbstainReason reason) {
        last().future().complete(JudgeOutcome.abstained(reason, Duration.ofMillis(900), "luna-test", "prompt-v1"));
    }
}
