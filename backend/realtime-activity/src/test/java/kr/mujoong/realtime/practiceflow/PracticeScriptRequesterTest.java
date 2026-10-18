package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.practiceflow.PracticeScriptException.Reason;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 다음 목표 대본 비동기 요청 시험 (U12 FR1.3 · BR1.3). 내부 호출의 경로 · 본문(kind ORIGINAL, previousScenarioId 없음) · 시도 1회 · 소유 id,
 * 성공 응답의 대본 검사, 실패(연결 · 비 2xx · 모양 오류 · kind 불일치)가 사유 코드 예외로 완료되는지 본다. core는 부르지 않는 대역이다(테스트 대역 증거).
 */
@Tag("u12")
class PracticeScriptRequesterTest {

    private static final Duration BUDGET = Duration.ofSeconds(7);
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID GOAL = UUID.randomUUID();
    private static final UUID SCENARIO = UUID.randomUUID();

    private final InternalHttpClient http = mock(InternalHttpClient.class);
    private final PracticeScriptRequester requester = new PracticeScriptRequester(http, Runnable::run, BUDGET);

    private static InternalHttpClient.Response reply(int status, String body) {
        return new InternalHttpClient.Response(status, body);
    }

    private PracticeScript request() {
        return requester.request(SESSION, GOAL).toCompletableFuture().join();
    }

    private static void assertFailsWith(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOf(CompletionException.class).cause()
                .isInstanceOfSatisfying(PracticeScriptException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void aSuccessfulReplyIsReadAsAScriptAndTheCallCarriesTheOriginalKindWithoutAPreviousScenario() throws Exception {
        when(http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any())).thenReturn(reply(200, minimalScript(SCENARIO)));

        PracticeScript script = request();

        assertThat(script.scenarioId()).isEqualTo(SCENARIO);
        ArgumentCaptor<InternalHttpClient.Call> call = ArgumentCaptor.forClass(InternalHttpClient.Call.class);
        verify(http).send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), call.capture());
        assertThat(call.getValue().method()).isEqualTo("POST");
        assertThat(call.getValue().path()).isEqualTo("/internal/practice/scenarios");
        assertThat(call.getValue().timeout()).isEqualTo(BUDGET);
        assertThat(call.getValue().attempt()).as("안쪽 재시도 없음").isEqualTo(1);
        assertThat(call.getValue().owner()).isEqualTo(SESSION);
        Object body = JsonCodec.shared().read(call.getValue().body(), java.util.Map.class);
        assertThat(body).isEqualTo(java.util.Map.of("sessionId", SESSION.toString(), "goalSelectionId", GOAL.toString(),
                "kind", "ORIGINAL"));
    }

    @Test
    void aTransportFailureANon2xxAndAnUnreadableReplyCompleteWithReasonCodes() throws Exception {
        when(http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any())).thenThrow(
                new InternalHttpClient.CallException(InternalHttpClient.Target.PRACTICE_SCENARIO,
                        InternalHttpClient.Outcome.TIMEOUT, "HttpTimeoutException"));
        assertFailsWith(this::request, Reason.REQUEST_FAILED);

        when(http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any())).thenReturn(reply(422, "{}"));
        assertFailsWith(this::request, Reason.REQUEST_FAILED);

        when(http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any())).thenReturn(reply(200, "not json"));
        assertFailsWith(this::request, Reason.UNPARSABLE);

        when(http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any()))
                .thenReturn(reply(200, minimalScript(SCENARIO).replace("\"scriptVersion\":\"scenario-script.v1\",", "")));
        assertFailsWith(this::request, Reason.NO_SCRIPT_VERSION);
    }

    @Test
    void aReplyOfAnotherKindIsRejectedAndTheCompletionNeverCarriesSentences() throws Exception {
        when(http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any()))
                .thenReturn(reply(200, minimalScript(SCENARIO).replace("\"kind\":\"ORIGINAL\"", "\"kind\":\"NEW_SCENARIO\"")));

        assertFailsWith(this::request, Reason.VALIDATION_FAILED);
        assertThatThrownBy(this::request).hasMessageNotContaining("친구가");
    }
}
