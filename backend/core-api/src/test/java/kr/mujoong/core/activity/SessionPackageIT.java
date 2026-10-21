package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.activity.StartActivityService.Context;
import kr.mujoong.core.activity.StartActivityService.StartRequest;
import kr.mujoong.core.activity.StartActivityService.Started;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.core.support.LiveContentFixtures;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.JsonNode;

/**
 * 세션 패키지 (U1 3.5 Step 28 · AC3.1.7 · BR6.5 · NFR3.1 · SD-20 · J117). 계약 대역이 받은 {@code POST /internal/sessions} 본문을 계약 스키마로
 * 검사하고 내용을 단언한다: 기본 종료 표현 한 건, 승인 문구 두 종(DB의 APPROVED 최고 버전 · {@code /media/approved/} 상대 경로),
 * childContext 세 필드뿐, 08 입력 원문 표식 0건. 3.6 수정 분량(J343): 아이 행을 계약 경계(이름 1 · 10자, 나이 6 · 12, 관심 10개 · 20자,
 * 공백 · 빈 항목 제외)로 바꿔도 패키지가 스키마에 맞고, 계약 밖 값이면 사본 · Realtime 호출 없이 시작이 멈춘다.
 */
@Tag("skeleton")
@Tag("db")
class SessionPackageIT {

    private static final String START = "/internal/sessions";
    private static final String KNOWN_MARKER = "표식-아는내용-7Q2";
    private static final String QUESTION_MARKER = "표식-질문-9K4";

    private static MujoongPostgres postgres;

    private final MutableClock clock = new MutableClock(Instant.parse("2001-04-02T00:00:00Z"));
    private final String token = TestSecrets.internalServiceToken();
    private DbTestSupport db;
    private BusinessFixtures fixtures;
    private ContractStubServer realtime;

    @BeforeAll
    static void database() {
        postgres = MujoongPostgres.sharedOrAbort();
        BusinessFixtures.applyReferenceSeed(postgres);
        // U8(편차 U8-b): dev 시드를 쓰는 다른 시험이 공용 DB에 승인 발화 행을 남기므로 시험 순서와 무관하게 늘 있게 둔다(넣기 전용)
        LiveContentFixtures.insertApprovedSpeech(postgres);
    }

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        fixtures = new BusinessFixtures(db);
        realtime = ContractStubServer.start().requireInternalToken(token);
        realtime.route("POST", START).requestSchema("internal-session-start-request.schema.json")
                .respond(StubResponse.json(202, "{\"status\":\"PREPARING\"}"));
    }

    @AfterEach
    void tearDown() {
        realtime.close();
        db.close();
    }

    @Test
    void packageCarriesOnlyContractFieldsAndNoGuardianInput() {
        Family family = fixtures.family(clock.now().minusSeconds(60));
        UUID deviceId = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            UUID key = UUID.randomUUID();
            Started started = context.getBean(StartActivityService.class).start(family.guardianId(),
                    new StartRequest(key, new Context(KNOWN_MARKER, "FRIEND", "HEARD_FROM_CHILD", QUESTION_MARKER)));

            RecordedRequest request = realtime.requests("POST", START).get(0);
            assertThat(request.schemaErrors()).isEmpty();
            assertThat(request.body()).doesNotContain(KNOWN_MARKER).doesNotContain(QUESTION_MARKER)
                    .doesNotContain("FRIEND").doesNotContain("HEARD_FROM_CHILD").doesNotContain("@")
                    .doesNotContainIgnoringCase("email");
            JsonNode body = request.json();
            // U8(편차 U8-b): 계약 필드에 선택 속성 approvedSpeech가 더해졌다. 자유대화는 허용 목록의 두 code만 싣는다
            assertThat(fieldNames(body)).containsExactlyInAnyOrder("sessionId", "idempotencyKey", "guardianId", "childId",
                    "deviceId", "activityType", "consentSnapshot", "childContext", "stopExpressions", "approvedContent",
                    "approvedSpeech");
            assertThat(fieldNames(body.path("approvedSpeech"))).containsExactly("D_REST_ACK_START", "D_REST_ACK");
            assertThat(body.path("sessionId").asString()).isEqualTo(started.sessionId().toString());
            assertThat(body.path("idempotencyKey").asString()).isEqualTo(key.toString());
            assertThat(body.path("guardianId").asString()).isEqualTo(family.guardianId().toString());
            assertThat(body.path("childId").asString()).isEqualTo(family.childId().toString());
            assertThat(body.path("deviceId").asString()).isEqualTo(deviceId.toString());
            assertThat(body.path("activityType").asString()).isEqualTo("FREE_TALK");
        });
    }

    @Test
    void childContextHasExactlyThreeFieldsAndConsentSnapshotUsesCurrentPolicy() {
        Family family = fixtures.family(clock.now().minusSeconds(60));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            context.getBean(StartActivityService.class).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));

            JsonNode body = realtime.requests("POST", START).get(0).json();
            JsonNode child = body.path("childContext");
            assertThat(fieldNames(child)).containsExactlyInAnyOrder("displayName", "age", "interests");
            assertThat(child.path("displayName").asString()).isEqualTo("하늘");
            assertThat(child.path("age").asInt()).isEqualTo(8);
            assertThat(child.path("interests").get(0).asString()).isEqualTo("공룡");
            JsonNode consent = body.path("consentSnapshot");
            assertThat(consent.path("policyVersion").asString()).isEqualTo(currentPolicyVersion());
            assertThat(consent.path("voiceAndText").asBoolean()).isTrue();
            assertThat(consent.path("rawAudio").asBoolean()).isTrue();
        });
    }

    @Test
    void stopExpressionIsTheSingleDefaultAndApprovedContentIsLatestApprovedVersions() {
        Family family = fixtures.family(clock.now().minusSeconds(60));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            context.getBean(StartActivityService.class).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));

            JsonNode body = realtime.requests("POST", START).get(0).json();
            JsonNode stops = body.path("stopExpressions");
            assertThat(stops.size()).isEqualTo(1);
            assertThat(stops.get(0).path("text").asString()).isEqualTo("무중아 그만할래");
            assertThat(stops.get(0).path("isDefault").asBoolean()).isTrue();
            JsonNode approved = body.path("approvedContent");
            assertThat(fieldNames(approved)).containsExactlyInAnyOrder("CLOSING", "WAITING_FILLER");
            for (String code : List.of("CLOSING", "WAITING_FILLER")) {
                JsonNode item = approved.path(code);
                Latest latest = latestApproved(code);
                assertThat(item.path("contentVersionId").asString()).isEqualTo(latest.id().toString());
                assertThat(item.path("text").asString()).isEqualTo(latest.text());
                assertThat(item.path("audioUrl").asString()).isEqualTo("/media/approved/" + latest.audioPath())
                        .startsWith("/media/approved/").doesNotContain("://");
            }
        });
    }

    @Test
    void childAtTheUpperContractLimitsIsSentStrippedAndPassesTheSchema() {
        Family family = fixtures.family(clock.now().minusSeconds(60));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        List<String> interests = new ArrayList<>();
        StringBuilder stored = new StringBuilder("[\"\", \"   \", null");
        for (int i = 0; i < 10; i++) {
            String interest = "가".repeat(19) + i;
            interests.add(interest);
            stored.append(", \"  ").append(interest).append("  \"");
        }
        stored.append(']');
        updateChild(family.childId(), "가나다라마바사아자차", 12, stored.toString());
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            context.getBean(StartActivityService.class).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));

            RecordedRequest request = realtime.requests("POST", START).get(0);
            assertThat(request.schemaErrors()).as("상한 값도 계약 스키마 안").isEmpty();
            JsonNode child = request.json().path("childContext");
            assertThat(child.path("displayName").asString()).isEqualTo("가나다라마바사아자차");
            assertThat(child.path("age").asInt()).isEqualTo(12);
            List<String> sent = new ArrayList<>();
            child.path("interests").forEach(node -> sent.add(node.asString()));
            assertThat(sent).as("앞뒤 공백을 걷고 빈 항목 · null은 세지 않는다(13칸 → 10개)").containsExactlyElementsOf(interests);
        });
    }

    @Test
    void continuingChildOverTwelveKeepsActualAgeInTheSessionPackage() {
        Family family = fixtures.family(clock.now().minusSeconds(60));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        updateChild(family.childId(), "하늘", 13, "[]");
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            context.getBean(StartActivityService.class).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));
            RecordedRequest request = realtime.requests("POST", START).get(0);
            assertThat(request.schemaErrors()).isEmpty();
            assertThat(request.json().path("childContext").path("age").asInt()).isEqualTo(13);
        });
    }

    @Test
    void childAtTheLowerContractLimitsWithoutInterestsSendsAnEmptyList() {
        Family family = fixtures.family(clock.now().minusSeconds(60));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        updateChild(family.childId(), "솔", 6, null);
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            context.getBean(StartActivityService.class).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));

            RecordedRequest request = realtime.requests("POST", START).get(0);
            assertThat(request.schemaErrors()).isEmpty();
            JsonNode child = request.json().path("childContext");
            assertThat(fieldNames(child)).containsExactlyInAnyOrder("displayName", "age", "interests");
            assertThat(child.path("displayName").asString()).isEqualTo("솔");
            assertThat(child.path("age").asInt()).isEqualTo(6);
            assertThat(child.path("interests").isArray()).as("관심 NULL은 빈 배열(필수 필드는 남긴다)").isTrue();
            assertThat(child.path("interests").size()).isZero();
        });
    }

    @Test
    void childOutsideTheContractStopsTheStartBeforeAnyCopyOrRealtimeCall() {
        Family family = fixtures.family(clock.now().minusSeconds(60));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        String elevenInterests = "[" + "\"공룡\", ".repeat(10) + "\"그림\"]";
        List<Object[]> outside = List.of(
                new Object[] {"하늘", -1, "[\"공룡\"]"},
                new Object[] {"가나다라마바사아자차카", 8, "[\"공룡\"]"},
                new Object[] {"하늘", 8, elevenInterests},
                new Object[] {"하늘", 8, "[\"" + "가".repeat(21) + "\"]"});
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            StartActivityService service = context.getBean(StartActivityService.class);
            for (Object[] child : outside) {
                updateChild(family.childId(), (String) child[0], (Integer) child[1], (String) child[2]);

                assertThatThrownBy(() -> service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null)))
                        .as("나이 %s · 이름 %s자", child[1], ((String) child[0]).length())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("childContext")
                        .hasMessageNotContaining((String) child[0]);
            }

            assertThat(realtime.requests("POST", START)).as("계약 밖 아이 정보는 Realtime에 나가지 않는다").isEmpty();
            assertThat(db.jdbc().queryForObject("select count(*) from core.core_activity where guardian_id = :g",
                    new MapSqlParameterSource("g", family.guardianId()), Integer.class))
                    .as("REQUESTED 사본이 남아 다음 시작을 막지 않는다").isZero();
        });
    }

    /** 이 시험이 만든 아이 행만 바꾼다(커밋, 보호자 정리가 지운다). */
    private void updateChild(UUID childId, String displayName, Integer age, String interestsJson) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", childId);
        ClockParams.text(params, "name", displayName);
        ClockParams.integer(params, "age", age);
        ClockParams.text(params, "interests", interestsJson);
        db.jdbc().update("""
                update core.core_child
                   set display_name = :name, age = :age, interests_json = cast(:interests as jsonb)
                 where id = :id
                """, params);
    }

    private String currentPolicyVersion() {
        return latestApproved("CONSENT_POLICY").text();
    }

    private Latest latestApproved(String code) {
        return db.jdbc().queryForObject("""
                select v.id, v.content_text, v.audio_path
                  from core.core_approved_content_version v
                  join core.core_approved_content c on c.id = v.content_id
                 where c.content_code = :code and v.status = 'APPROVED'
                 order by v.version desc limit 1
                """, new MapSqlParameterSource("code", code),
                (rs, n) -> new Latest(rs.getObject("id", UUID.class), rs.getString("content_text"), rs.getString("audio_path")));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private record Latest(UUID id, String text, String audioPath) {
    }
}
