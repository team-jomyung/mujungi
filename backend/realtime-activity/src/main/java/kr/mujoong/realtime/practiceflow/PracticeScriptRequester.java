package kr.mujoong.realtime.practiceflow;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.practiceflow.PracticeScriptException.Reason;

/**
 * 다음 목표 대본의 비동기 요청 (U12 FR1.3 · BR1.3). 첫 목표 뒤 목표의 대본은 시작 패키지에 없으므로 core의 내부 호출
 * {@code POST /internal/practice/scenarios}(kind {@code ORIGINAL}, {@code internal-practice-scenario-request.schema.json})로 받는다.
 * 변형 장면은 같은 응답의 VARIANT {@code scenes[]}라 별도 호출이 없다.
 *
 * <p>호출은 주어진 실행기(가상 스레드)에서 한 번만 한다 — 안쪽 재시도를 두지 않는다(바깥 상황 생성 예산 15초를 두 겹으로 쓰지 않는다, 교정
 * 규칙). 결과 대본은 {@link PracticeScriptReader}가 검사하고, 어떤 실패든 {@link PracticeScriptException}(사유 코드만)로 완료한다 — 호출한 확장은
 * 이 단계의 결과를 {@code post}로 실행기에 넘기고 그 목표를 '미실시'로 둔다. 요청 · 응답 본문은 로그에 쓰지 않는다(R-F14).
 */
public final class PracticeScriptRequester {

    /** 내부 호출 경로(계약 ports.md). */
    static final String PATH = "/internal/practice/scenarios";
    /** 다음 목표 대본은 처음 받는 원 대본이다. */
    static final String KIND_ORIGINAL = "ORIGINAL";

    private final InternalHttpClient http;
    private final Executor calls;
    private final Duration timeout;

    /**
     * @param calls 호출을 돌릴 실행기
     * @param timeout 호출 한 번의 응답 제한(상황 생성 예산을 넘지 않는 값)
     */
    public PracticeScriptRequester(InternalHttpClient http, Executor calls, Duration timeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.calls = Objects.requireNonNull(calls, "calls");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** 요청 본문(스키마 필수 sessionId · goalSelectionId · kind). previousScenarioId는 ORIGINAL이라 보내지 않는다. */
    record Request(UUID sessionId, UUID goalSelectionId, String kind) {
    }

    /**
     * 그 목표의 대본을 요청한다.
     *
     * @return 검사를 통과한 대본, 실패면 {@link PracticeScriptException}으로 완료
     */
    public CompletionStage<PracticeScript> request(UUID sessionId, UUID goalSelectionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(goalSelectionId, "goalSelectionId");
        return CompletableFuture.supplyAsync(() -> {
            String body = JsonCodec.shared().write(new Request(sessionId, goalSelectionId, KIND_ORIGINAL));
            InternalHttpClient.Response reply;
            try {
                reply = http.send(InternalHttpClient.Target.PRACTICE_SCENARIO,
                        new InternalHttpClient.Call("POST", PATH, body, timeout, 1, sessionId, null));
            } catch (InternalHttpClient.CallException e) {
                throw new PracticeScriptException(Reason.REQUEST_FAILED);
            }
            if (!reply.isSuccess()) {
                throw new PracticeScriptException(Reason.REQUEST_FAILED);
            }
            return PracticeScriptReader.read(reply.body(), KIND_ORIGINAL);
        }, calls);
    }
}
