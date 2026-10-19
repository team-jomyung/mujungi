package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.core.activity.RealtimeSessionClient;
import kr.mujoong.core.activity.SessionPackageBuilder;
import kr.mujoong.core.activity.SessionStartBreaker;
import kr.mujoong.core.internalhttp.InternalHttpClient;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("practice-legacy")
class PracticeRealtimeClientTest {
    final InternalHttpClient http=mock(InternalHttpClient.class);
    final RealtimeSessionClient client=new RealtimeSessionClient(http,
            new SessionStartBreaker(new MutableClock(Instant.EPOCH),CoreMetrics.inMemory(),3,Duration.ofSeconds(5)),
            JsonCodec.shared(),Duration.ofSeconds(3),1,Duration.ofSeconds(2),Duration.ofSeconds(1));
    SessionPackageBuilder.SessionPackage packet() {
        return new SessionPackageBuilder.SessionPackage(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),ActivityType.FREE_TALK,
                new SessionPackageBuilder.ConsentSnapshot("1.0",true,true,true,true),
                new SessionPackageBuilder.ChildContext("하늘",8,List.of()),List.of(),Map.of());
    }
    @Test void practicePayloadAddsFrozenGoalsWithoutChangingTheCommonPacketShape() throws Exception {
        var packet=packet().forPractice();var goals=Map.<String,Object>of("goals",List.of(Map.of("goalSelectionId",UUID.randomUUID())));
        when(http.send(eq(InternalHttpClient.Target.SESSION_START),any())).thenReturn(new InternalHttpClient.Response(202,"{\"status\":\"PREPARING\"}"));
        assertThat(client.startPractice(packet,goals).kind()).isEqualTo(RealtimeSessionClient.StartKind.ACCEPTED);
        var call=ArgumentCaptor.forClass(InternalHttpClient.Call.class);
        verify(http).send(eq(InternalHttpClient.Target.SESSION_START),call.capture());
        Map<?,?> body=JsonCodec.shared().read(call.getValue().body(),Map.class);
        assertThat(body.get("activityType")).isEqualTo("PRACTICE");
        assertThat(body.get("sessionId")).isEqualTo(packet.sessionId().toString());
        assertThat((List<?>)((Map<?,?>)body.get("practice")).get("goals")).hasSize(1);
        assertThat(body.containsKey("context")).isFalse();assertThat(body.containsKey("guardianContact")).isFalse();
        assertThat(JsonCodec.shared().read(JsonCodec.shared().write(packet),Map.class).containsKey("practice")).isFalse();
    }
    @Test void timeoutRetriesTheSameFrozenPayloadAndSession() throws Exception {
        when(http.send(eq(InternalHttpClient.Target.SESSION_START),any()))
                .thenThrow(new InternalHttpClient.CallException(InternalHttpClient.Target.SESSION_START,InternalHttpClient.Outcome.TIMEOUT,"HttpTimeoutException"))
                .thenReturn(new InternalHttpClient.Response(202,"{\"status\":\"PREPARING\"}"));
        assertThat(client.startPractice(packet().forPractice(),Map.of("goals",List.of())).afterTimeoutRetry()).isTrue();
        var call=ArgumentCaptor.forClass(InternalHttpClient.Call.class);
        verify(http,times(2)).send(eq(InternalHttpClient.Target.SESSION_START),call.capture());
        assertThat(call.getAllValues().get(0).body()).isEqualTo(call.getAllValues().get(1).body());
        assertThat(call.getAllValues()).extracting(InternalHttpClient.Call::attempt).containsExactly(1,2);
    }
    @Test void freeTalkCannotUseThePracticeSender() {
        assertThatThrownBy(()->client.startPractice(packet(),Map.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
    }
}
