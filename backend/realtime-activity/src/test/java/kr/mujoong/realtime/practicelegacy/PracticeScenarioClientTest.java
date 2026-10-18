package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("u5b")
class PracticeScenarioClientTest {
    @Test void sendsOnlyC10FieldsAndChecksValidationBeforeAcceptingScene() throws Exception {
        var http = mock(InternalHttpClient.class);
        UUID scene = UUID.randomUUID(), session = UUID.randomUUID(), selection = UUID.randomUUID();
        when(http.send(any(), any())).thenReturn(new InternalHttpClient.Response(200,
                "{\"scenarioId\":\"" + scene + "\",\"kind\":\"ORIGINAL\",\"scenarioText\":\"상황\","
                + "\"partnerRoleText\":\"친구\",\"triggerScript\":\"내 차례야.\","
                + "\"validation\":{\"goalIncluded\":true,\"triggerIncluded\":true,\"forbiddenFound\":false}}"));
        var client = new PracticeScenarioClient(http, Runnable::run, Duration.ofSeconds(15));
        var goal = new SessionGoal(UUID.randomUUID(), session, UUID.randomUUID(), selection, 1,
                "목표", "key", 1, "비공개 판정 기준", PracticeTypes.GoalStatus.PENDING, null, 0, null);
        assertThat(client.prepare(session, goal, null).toCompletableFuture().join().id()).isEqualTo(scene);
        var call = ArgumentCaptor.forClass(InternalHttpClient.Call.class);
        verify(http).send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), call.capture());
        assertThat(call.getValue().path()).isEqualTo("/internal/practice/scenarios");
        assertThat(call.getValue().body()).contains(session.toString(), selection.toString(), "ORIGINAL")
                .doesNotContain("비공개", "judgment", "displayName");
    }
    @Test void nonSuccessAndInvalidScenarioFailWithoutFabricatingAnId() throws Exception {
        var http = mock(InternalHttpClient.class);
        when(http.send(any(), any())).thenReturn(new InternalHttpClient.Response(503, "private"),
                new InternalHttpClient.Response(200, "{}"));
        var client = new PracticeScenarioClient(http, Runnable::run, Duration.ofSeconds(15));
        var goal = new SessionGoal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "목표", "key", 1, "기준", PracticeTypes.GoalStatus.PENDING, null, 0, null);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.prepare(goal.sessionId(), goal, null).toCompletableFuture().join())
                    .hasCauseInstanceOf(IllegalStateException.class).hasMessageNotContaining("private");
        }
    }
}
