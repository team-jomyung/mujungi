package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.activity.ApprovedContentCatalog.SpeechItem;
import kr.mujoong.core.activity.RealtimeSessionClient.StartKind;
import kr.mujoong.core.activity.SessionPackageBuilder.SessionPackage;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.internalhttp.InternalHttpClient;
import kr.mujoong.core.internalhttp.InternalHttpClient.Response;
import kr.mujoong.core.internalhttp.InternalHttpClient.Target;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Core → Realtime 시작 본문의 선택 속성 approvedSpeech (U8 BR1.2 · NFR6.1 · 편차 U8-b). 새 오버로드가 계약 스키마에 맞는 본문을 보내고, 승인
 * 발화가 비면 옛 본문과 같은 바이트를 보내는지 본다.
 */
@Tag("u8")
class U8RealtimeStartBodyTest {

    private static final Duration START = Duration.ofSeconds(3);
    private static final String SCHEMA = "internal-session-start-request.schema.json";

    private final InternalHttpClient http = mock(InternalHttpClient.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
    private final RealtimeSessionClient client = new RealtimeSessionClient(http,
            new SessionStartBreaker(clock, CoreMetrics.inMemory(), 3, Duration.ofSeconds(5)), JsonCodec.shared(), START, 1,
            Duration.ofSeconds(2), Duration.ofSeconds(1));
    private final Map<String, SpeechItem> speech = new LinkedHashMap<>();

    U8RealtimeStartBodyTest() throws Exception {
        when(http.send(eq(Target.SESSION_START), any())).thenReturn(new Response(202, "{\"status\":\"PREPARING\"}"));
        speech.put("D_REST_ACK_START", new SpeechItem(UUID.randomUUID(), "잠깐 쉬고 싶구나. 괜찮아. 준비되면 말해 줘."));
        speech.put("D_REST_ACK", new SpeechItem(UUID.randomUUID(), "잠깐 쉬고 싶구나. 천천히 해도 괜찮아."));
    }

    @Test
    void approvedSpeechIsSentAfterApprovedContentAndPassesTheContractSchema() throws Exception {
        SessionPackage pkg = sessionPackage();

        assertThat(client.start(pkg, speech).kind()).isEqualTo(StartKind.ACCEPTED);

        String body = sentBodies(1).get(0);
        assertThat(WebFixtures.schemaErrors(SCHEMA, body)).isEmpty();
        JsonNode json = JsonMapper.builder().build().readTree(body);
        List<String> fields = new ArrayList<>();
        json.propertyNames().forEach(fields::add);
        assertThat(fields.indexOf("approvedSpeech")).isEqualTo(fields.indexOf("approvedContent") + 1);
        List<String> codes = new ArrayList<>();
        json.path("approvedSpeech").propertyNames().forEach(codes::add);
        assertThat(codes).containsExactly("D_REST_ACK_START", "D_REST_ACK");
        assertThat(json.path("approvedSpeech").path("D_REST_ACK").path("contentVersionId").asString())
                .isEqualTo(speech.get("D_REST_ACK").contentVersionId().toString());
    }

    @Test
    void anEmptySpeechMapSendsExactlyTheOldBody() throws Exception {
        SessionPackage pkg = sessionPackage();

        client.start(pkg);
        client.start(pkg, Map.of());

        List<String> bodies = sentBodies(2);
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0)).doesNotContain("approvedSpeech");
    }

    @Test
    void practiceCarriesApprovedSpeechBeforeThePracticeBlock() throws Exception {
        SessionPackage pkg = sessionPackage().forPractice();
        Map<String, Object> practice = Map.of("goals", List.of());

        client.startPractice(pkg, practice, speech);
        client.startPractice(pkg, practice, Map.of());

        List<String> bodies = sentBodies(2);
        assertThat(WebFixtures.schemaErrors(SCHEMA, bodies.get(0))).isEmpty();
        assertThat(bodies.get(0).indexOf("\"approvedSpeech\"")).isLessThan(bodies.get(0).indexOf("\"practice\""));
        assertThat(bodies.get(1)).doesNotContain("approvedSpeech").contains("\"practice\"");
        assertThatThrownBy(() -> client.startPractice(sessionPackage(), practice, speech))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> sentBodies(int count) throws Exception {
        ArgumentCaptor<InternalHttpClient.Call> calls = ArgumentCaptor.forClass(InternalHttpClient.Call.class);
        verify(http, times(count)).send(eq(Target.SESSION_START), calls.capture());
        return calls.getAllValues().stream().map(InternalHttpClient.Call::body).toList();
    }

    private static SessionPackage sessionPackage() {
        ChildProfileService.ChildContext child = new ChildProfileService.ChildContext(UUID.randomUUID(), "하늘", 8, List.of("공룡"),
                List.of(new ChildProfileService.StopExpression(SessionPackageBuilder.DEFAULT_STOP_EXPRESSION, true)));
        Map<String, ApprovedItem> approved = new LinkedHashMap<>();
        approved.put("CLOSING", new ApprovedItem(UUID.randomUUID(), "안녕!", "/media/approved/closing-v1.wav"));
        approved.put("WAITING_FILLER", new ApprovedItem(UUID.randomUUID(), "음.", "/media/approved/waiting-filler-v1.wav"));
        return new SessionPackageBuilder().build(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                child, new ConsentPolicyService.Snapshot("1.0", true, true, true, true), approved);
    }
}
