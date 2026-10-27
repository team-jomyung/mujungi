package kr.mujoong.realtime.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.practiceflow.PracticeFlowRepository;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.Fact;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.InternalApi.Reply;
import kr.mujoong.realtime.testing.InternalApi.Token;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.usage.AiUsageRecord;
import kr.mujoong.realtime.usage.AiUsageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * JIRA-280 관리자 조회용 Realtime 내부 API(실제 서버 · 실제 DB). 응답은 contracts/schemas로 대조하고, 원문 없이 분류 코드 · 횟수 · 토큰만 나가는지
 * 본다.
 */
@Tag("admin")
@Tag("db")
class AdminFactsInternalIT {

    private static final String FACTS = "/internal/sessions/admin-facts";
    private static final String USAGE = "/internal/sessions/admin-usage";
    private static final Instant AT = Instant.parse("2019-05-14T03:00:00Z");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    private RealtimeItEnvironment env;
    private InternalApi api;
    private UUID session;
    private UUID guardian;
    private UUID run;

    @BeforeEach
    void start() {
        env = RealtimeItEnvironment.start();
        api = env.api();
        guardian = UUID.randomUUID();
        NewActivitySession row = new NewActivitySession(UUID.randomUUID(), guardian, UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.PRACTICE, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("합성", 8, List.of()), null, null);
        env.db.inTransaction(() -> new ActivitySessionRepository(env.db.jdbc()).insert(row, AT));
        session = row.id();
        run = UUID.randomUUID();
        PracticeFlowRepository flow = new PracticeFlowRepository(env.db.jdbc());
        env.db.inTransaction(() -> {
            flow.insertSceneRun(new SceneRunRecord(run, session, guardian, UUID.randomUUID(), 0, UUID.randomUUID(),
                    SceneKind.BASE, AT, AT.plusSeconds(60), LiveTiming.AFTER_HELP, null, 1, 0,
                    List.of(new Fact(FactKind.HELP_ACCEPTED, AT.plusSeconds(10)), new Fact(FactKind.FOCUS_HELP, AT.plusSeconds(20)))));
            flow.insertHelp(new HelpRecord(UUID.randomUUID(), run, guardian, HelpKind.ROLE_SWITCH, FlowRole.COACH, false,
                    ConsentAnswer.ACCEPT, 0, AT.plusSeconds(11)));
            flow.insertHelp(new HelpRecord(UUID.randomUUID(), run, guardian, HelpKind.FOCUS_HELP, FlowRole.COACH, true,
                    ConsentAnswer.ACCEPT, 42, AT.plusSeconds(20)));
        });
        AiUsageRepository usage = new AiUsageRepository(env.db.jdbc());
        env.db.inTransaction(() -> {
            usage.write(new AiUsageRecord(UUID.randomUUID(), AiUsageRecord.Source.VOICE, session, null, "synthetic-session", null,
                    "gpt-live-1", null, null, null, null, 120.0, AiUsageRecord.Status.COMPLETE, 1, AT.plusSeconds(60)), id -> true);
            usage.write(new AiUsageRecord(UUID.randomUUID(), AiUsageRecord.Source.LUNA, session, UUID.randomUUID(), null,
                    "synthetic-1", "gpt-6-luna", 100L, 20L, 10L, 0L, null, AiUsageRecord.Status.COMPLETE, 1, AT.plusSeconds(30)),
                    id -> true);
            usage.write(new AiUsageRecord(UUID.randomUUID(), AiUsageRecord.Source.LUNA, session, UUID.randomUUID(), null,
                    "synthetic-2", "gpt-6-luna", null, null, null, null, null, AiUsageRecord.Status.UNKNOWN, 1, AT.plusSeconds(40)),
                    id -> true);
            usage.write(new AiUsageRecord(UUID.randomUUID(), AiUsageRecord.Source.LUNA, session, UUID.randomUUID(), null,
                    "synthetic-3", "gpt-6-luna", 40L, null, null, null, null, AiUsageRecord.Status.PARTIAL, 1, AT.plusSeconds(50)),
                    id -> true);
        });
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void factsReturnSceneRunsWithFactsAndHelpCodesOnlyForSessionsThatHaveThem() {
        Reply reply = post(FACTS, "{\"sessionIds\":[\"" + session + "\",\"" + UUID.randomUUID() + "\"]}");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(InternalApi.schemaErrors("internal-admin-facts-response.schema.json", reply.body())).isEmpty();
        JsonNode sessions = reply.json().get("sessions");
        assertThat(sessions.size()).isEqualTo(1);
        JsonNode scene = sessions.get(0).get("sceneRuns").get(0);
        assertThat(scene.get("sceneRunId").asString()).isEqualTo(run.toString());
        assertThat(scene.get("liveTiming").asString()).isEqualTo("AFTER_HELP");
        assertThat(scene.get("retryCount").asInt()).isEqualTo(1);
        assertThat(scene.get("facts").toString()).isEqualTo("[\"HELP_ACCEPTED\",\"FOCUS_HELP\"]");
        assertThat(scene.get("helps").size()).isEqualTo(2);
        assertThat(scene.get("helps").get(1).get("exampleGiven").asBoolean()).isTrue();
        assertThat(reply.body()).doesNotContain("contentChars").doesNotContain("합성");
    }

    @Test
    void usageSumsOnlyMeasuredRowsAndKeepsTheUnmeasuredCallVisible() {
        Reply reply = post(USAGE, "{\"sessionIds\":[\"" + session + "\"]}");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(InternalApi.schemaErrors("internal-admin-usage-response.schema.json", reply.body())).isEmpty();
        Map<String, JsonNode> bySource = new java.util.HashMap<>();
        reply.json().get("usage").forEach(row -> bySource.put(row.get("source").asString(), row));
        JsonNode luna = bySource.get("LUNA");
        assertThat(luna.get("calls").asInt()).isEqualTo(3);
        assertThat(luna.get("measuredCalls").asInt()).isEqualTo(1);
        assertThat(luna.get("knownCalls").asInt()).as("입력만 아는 호출도 안다").isEqualTo(2);
        assertThat(luna.get("inputTokens").asLong()).as("표시용: 입력만 아는 호출의 40 포함").isEqualTo(140);
        assertThat(luna.get("measuredInputTokens").asLong()).as("금액용: 입력 · 출력 모두 아는 호출만").isEqualTo(100);
        assertThat(luna.get("measuredOutputTokens").asLong()).isEqualTo(20);
        assertThat(luna.get("cachedInputTokens").asLong()).isEqualTo(10);
        assertThat(bySource.get("VOICE").get("voiceSeconds").asDouble()).isEqualTo(120.0);
        assertThat(bySource.get("VOICE").has("inputTokens")).isFalse();
    }

    @Test
    void usageByRangeUsesTheObservedTimeHalfOpen() {
        Reply inside = post(USAGE, "{\"from\":\"2019-05-14T03:00:30Z\",\"to\":\"2019-05-14T03:00:40Z\"}");
        Reply outside = post(USAGE, "{\"from\":\"2019-05-15T00:00:00Z\",\"to\":\"2019-05-16T00:00:00Z\"}");

        assertThat(inside.status()).isEqualTo(200);
        assertThat(inside.json().get("usage").size()).as("03:00:30 LUNA 한 행만(03:00:40은 끝 미포함)").isEqualTo(1);
        assertThat(inside.json().get("usage").get(0).get("measuredCalls").asInt()).isEqualTo(1);
        assertThat(outside.json().get("usage").size()).isZero();
    }

    @Test
    void malformedRequestsAre400AndAMissingTokenIs401() {
        String ids = "\"sessionIds\":[\"" + session + "\"]";
        assertThat(post(USAGE, "{" + ids + ",\"from\":\"2019-05-14T00:00:00Z\",\"to\":\"2019-05-15T00:00:00Z\"}").status())
                .isEqualTo(400);
        assertThat(post(USAGE, "{}").status()).isEqualTo(400);
        assertThat(post(USAGE, "{\"from\":\"2019-05-15T00:00:00Z\",\"to\":\"2019-05-14T00:00:00Z\"}").status()).isEqualTo(400);
        assertThat(post(FACTS, "{\"sessionIds\":[]}").status()).isEqualTo(400);
        String tooMany = String.join(",", Collections.nCopies(501, "\"" + session + "\""));
        assertThat(post(FACTS, "{\"sessionIds\":[" + tooMany + "]}").status()).isEqualTo(400);
        assertThat(api.send("POST", FACTS, "{" + ids + "}", Token.MISSING, TRACE).status()).isEqualTo(401);
    }

    private Reply post(String path, String body) {
        return api.send("POST", path, body, Token.VALID, TRACE);
    }
}
