package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import kr.mujoong.contract.event.payload.SessionRecordCaptured;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.NewActivity;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import kr.mujoong.core.record.ActivityRecorded;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.support.ParentAccountFixtures;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Call;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.OpenApiContracts;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;

/**
 * U4a 활동 세션 Core 쪽 통합 시험(US3.1 · US3.2 · US1.4 · AC3.1.2 · AC3.1.7 · AC2.3.7 · AC3.2.17 · AC1.4.13 · AC5.1.8 · AC5.2.1 · 설계 R1 ~ R10).
 *
 * <p>실제 앱(Inbox 처리기 포함) + Realtime 계약 대역. 응답은 parent-api.yaml 연산 계약으로 대조한다. Realtime 쪽 상태 기계는 U4b라 세션
 * 상태 · 기록 이벤트는 대역 · 직접 넣은 사본 · {@code /internal/events}로 만든다.
 */
@Tag("activity-session")
@Tag("db")
class ActivityApiIT {

    private static final String KNOWN = "표식-배경-U4a-1";
    private static final String QUESTION = "표식-질문-U4a-2";

    private static DbTestSupport db;
    private static BusinessFixtures fixtures;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static OpenApiContracts contracts;
    private static final JsonCodec JSON = JsonCodec.shared();

    private final List<String> violations = new ArrayList<>();

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        BusinessFixtures.applyReferenceSeed(db.postgres());
        ParentAccountFixtures.applyU2Seed(db.postgres());
        fixtures = new BusinessFixtures(db);
        contracts = OpenApiContracts.load();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl())
                .source(RecordedListener.class)
                // 시험이 시계를 앞당기지 않지만 U1 PREPARING 점검이 시험의 REQUESTED 사본을 닫지 않게 넉넉히 둔다
                .property("mujoong.timing.session.preparing-timeout", "30d")
                .start();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (realtime != null) {
            realtime.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void reset() {
        realtime.clearRequests();
        app.bean(RecordedEvents.class).clear();
    }

    @AfterEach
    void cleanUp() {
        db.cleanUp();
        assertThat(violations).as("응답이 parent-api.yaml 연산 계약과 맞다").isEmpty();
    }

    // ------------------------------------------------------------------ 시작(08)

    @Test
    void startStoresTheBackgroundInCoreOnlyAndSendsTheStopExpressionAndConsentSnapshots() {
        Family family = family();
        addStopExpression(family.childId(), "이제 그만", 1);
        addStopExpression(family.childId(), "끝낼래", 2);
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());

        Result started = send("POST /api/activities/free", app.post("/api/activities/free").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"context\":{\"knownContent\":\""
                        + KNOWN + "\",\"situationType\":\"FRIEND\",\"sourceType\":\"SEEN\",\"question\":\"" + QUESTION + "\"}}"));

        assertThat(started.status()).isEqualTo(202);
        UUID sessionId = UUID.fromString(String.valueOf(started.json().get("sessionId")));
        assertThat(db.jdbc().queryForMap("""
                select known_content, situation_type, source_type, question, child_id from core.core_activity_context
                 where session_id = :s
                """, new MapSqlParameterSource("s", sessionId)))
                .as("08 배경은 Core에 저장된다(AC3.1.2)")
                .containsEntry("known_content", KNOWN).containsEntry("situation_type", "FRIEND")
                .containsEntry("source_type", "SEEN").containsEntry("question", QUESTION)
                .containsEntry("child_id", family.childId());

        var sent = realtime.requests("POST", WebFixtures.RT_START);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).schemaErrors()).as("패키지가 계약 스키마 안").isEmpty();
        String body = sent.get(0).body();
        assertThat(body).as("08 배경은 패키지에 없다(AC3.1.7)").doesNotContain(KNOWN).doesNotContain(QUESTION);
        JsonNode stops = sent.get(0).json().path("stopExpressions");
        assertThat(stops.size()).as("기본 + 추가 표현 스냅숏(AC2.3.7)").isEqualTo(3);
        assertThat(stops.get(0).path("text").asString()).isEqualTo("무중아 그만할래");
        assertThat(stops.get(0).path("isDefault").asBoolean()).isTrue();
        assertThat(stops.get(1).path("text").asString()).isEqualTo("이제 그만");
        assertThat(stops.get(2).path("text").asString()).isEqualTo("끝낼래");
        assertThat(stops.get(2).path("isDefault").asBoolean()).isFalse();
        JsonNode consent = sent.get(0).json().path("consentSnapshot");
        assertThat(consent.path("voiceAndText").asBoolean() && consent.path("guardianView").asBoolean()
                && consent.path("reportSharing").asBoolean() && consent.path("rawAudio").asBoolean()).isTrue();
        assertThat(fieldNames(sent.get(0).json().path("approvedContent")))
                .as("자유대화 문구는 승인 · 음성 있는 것만(시드 DRAFT라 필수 둘만, U4a-J2)").containsExactly("CLOSING", "WAITING_FILLER");
    }

    @Test
    void anEmptyBackgroundWritesNoRowAndAReplayDoesNotWriteASecondOne() {
        Family family = family();
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());
        String key = UUID.randomUUID().toString();

        Result empty = send("POST /api/activities/free", app.post("/api/activities/free").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"));
        assertThat(empty.status()).isEqualTo(202);
        assertThat(contexts(family.guardianId())).as("네 칸 모두 비면 행 없음(U4a-J1)").isZero();
        db.jdbc().update("update core.core_activity set status = 'ENDED', status_version = 9 where guardian_id = :g",
                new MapSqlParameterSource("g", family.guardianId()));

        String body = "{\"idempotencyKey\":\"" + key + "\",\"context\":{\"situationType\":\"SCHOOL\"}}";
        Result first = send("POST /api/activities/free", app.post("/api/activities/free").session(guardian.session())
                .csrf(guardian.csrf()).json(body));
        Result replay = send("POST /api/activities/free", app.post("/api/activities/free").session(guardian.session())
                .csrf(guardian.csrf()).json(body));

        assertThat(replay.json().get("sessionId")).isEqualTo(first.json().get("sessionId"));
        assertThat(contexts(family.guardianId())).as("멱등 재생은 배경을 다시 쓰지 않는다").isEqualTo(1);
    }

    @Test
    void withoutCurrentConsentTheStartIsRefusedByTheConsentGatePort() {
        Family family = family();
        fixtures.consent(family.guardianId(), family.childId(), "RAW_AUDIO", "WITHDRAWN", app.clock().now().minusSeconds(1));
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());

        Result refused = send("POST /api/activities/free", app.post("/api/activities/free").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("CONSENT_MISSING");
        assertThat(realtime.requests("POST", WebFixtures.RT_START)).isEmpty();
    }

    // ------------------------------------------------------------------ 지금 활동(04)

    @Test
    void currentSessionAndHomeShowOnlyAnInProgressSession() {
        Family family = family();
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());

        assertThat(send("GET /api/sessions/current", app.get("/api/sessions/current").session(guardian.session())).json())
                .as("없으면 {session: null}").containsEntry("session", null);
        assertThat(send("GET /api/home", app.get("/api/home").session(guardian.session())).json())
                .containsEntry("currentSession", null);

        UUID sessionId = copy(family, SessionStatus.REQUESTED);
        for (SessionStatus status : List.of(SessionStatus.REQUESTED, SessionStatus.PREPARING, SessionStatus.DEVICE_ACK,
                SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE, SessionStatus.CLOSING)) {
            setStatus(sessionId, status);
            Result current = send("GET /api/sessions/current", app.get("/api/sessions/current").session(guardian.session()));
            assertThat(current.json().get("session")).as(status.name()).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) current.json().get("session")).get("status")).isEqualTo(status.name());
            Result home = send("GET /api/home", app.get("/api/home").session(guardian.session()));
            assertThat(((Map<?, ?>) home.json().get("currentSession")).get("sessionId")).isEqualTo(sessionId.toString());
        }
        for (SessionStatus status : List.of(SessionStatus.ENDED, SessionStatus.ENDED_UNCONFIRMED, SessionStatus.ABORTED,
                SessionStatus.DECLINED, SessionStatus.FAILED)) {
            setStatus(sessionId, status);
            assertThat(send("GET /api/sessions/current", app.get("/api/sessions/current").session(guardian.session()))
                    .json()).as(status.name()).containsEntry("session", null);
        }
    }

    // ------------------------------------------------------------------ U4b 설계 8절과 맞춤(끝내기 · 상태 사건)

    @Test
    void aGuardianStopWhileWaitingForTheChildIsForwardedAndAnsweredClosing() {
        // U4b 8절 2 · AC3.3.8: WAITING_CHILD도 끝낼 수 있는 상태다(C.S-12.11.5 「이야기 끝내기」)
        Family family = family();
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());
        UUID sessionId = copy(family, SessionStatus.WAITING_CHILD);

        Result ended = send("POST /api/sessions/{sessionId}/end", app.post("/api/sessions/" + sessionId + "/end")
                .session(guardian.session()).csrf(guardian.csrf()).json("{\"requestId\":\"" + UUID.randomUUID() + "\"}"));

        assertThat(ended.status()).isEqualTo(202);
        assertThat(ended.json()).containsEntry("status", "CLOSING");
        var forwarded = realtime.requests("POST", WebFixtures.RT_END);
        assertThat(forwarded).hasSize(1);
        assertThat(forwarded.get(0).json().path("reason").asString()).isEqualTo("GUARDIAN_STOP");
    }

    @Test
    void stateEventsCarryTheClosingReasonAndTheFinalReasonsIntoTheCopy() {
        // U4b 8절 3: CLOSING에 마무리 사유가 실리고, ENDED_UNCONFIRMED는 UNCONFIRMED, DECLINED는 DECLINED다
        Family family = family();
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());
        // 사본은 status_version 1(시험 사본), 사건은 그보다 큰 버전으로 온다
        UUID unconfirmed = copy(family, SessionStatus.ACTIVE);

        deliver(stateChanged(family, unconfirmed, SessionStatus.CLOSING, 10, "CHILD_STOP"));
        awaitStatus(unconfirmed, "CLOSING");
        assertThat(endReason(unconfirmed)).isEqualTo("CHILD_STOP");
        Result current = send("GET /api/sessions/current", app.get("/api/sessions/current").session(guardian.session()));
        assertThat(map(current.json().get("session"))).containsEntry("status", "CLOSING").containsEntry("endReason", "CHILD_STOP");

        deliver(stateChanged(family, unconfirmed, SessionStatus.ENDED_UNCONFIRMED, 11, "UNCONFIRMED"));
        awaitStatus(unconfirmed, "ENDED_UNCONFIRMED");
        assertThat(endReason(unconfirmed)).isEqualTo("UNCONFIRMED");

        UUID declined = copy(family, SessionStatus.WAITING_CHILD);
        deliver(stateChanged(family, declined, SessionStatus.DECLINED, 10, "DECLINED"));
        awaitStatus(declined, "DECLINED");
        assertThat(endReason(declined)).isEqualTo("DECLINED");
    }

    private static SessionStateChanged stateChanged(Family family, UUID sessionId, SessionStatus status, long version,
            String endReason) {
        return new SessionStateChanged(sessionId, family.guardianId(), family.childId(), ActivityType.FREE_TALK, status, version,
                EndReason.valueOf(endReason), null, false, null, Instant.parse("2026-10-15T01:10:00Z"));
    }

    private void deliver(SessionStateChanged payload) {
        UUID eventId = db.trackInbox(UUID.randomUUID());
        String body = JSON.encodeEventEnvelope(JSON.envelopeFor(eventId, payload, app.clock().now(), null));
        assertThat(app.post("/internal/events").internalToken().json(body).send().status()).isEqualTo(200);
    }

    private static void awaitStatus(UUID sessionId, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(db.jdbc().queryForObject("select status from core.core_activity where session_id = :s",
                    new MapSqlParameterSource("s", sessionId), String.class))) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("사본이 10초 안에 " + expected + "가 되지 않았다");
    }

    private static String endReason(UUID sessionId) {
        return db.jdbc().queryForObject("select end_reason from core.core_activity where session_id = :s",
                new MapSqlParameterSource("s", sessionId), String.class);
    }

    // ------------------------------------------------------------------ 진행 조회(12)

    @Test
    void theLiveViewRelaysGapsPauseSilenceAndPracticeFilteredToTheContract() {
        Family family = family();
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());
        UUID sessionId = copy(family, SessionStatus.ACTIVE);
        realtime.route("GET", WebFixtures.RT_VIEW).respond(StubResponse.json(200, """
                {"sessionId":"%s","activityType":"FREE_TALK","status":"ACTIVE","endReason":null,"failureCode":null,
                 "confirmed":false,"requestedAt":"2026-10-15T09:00:00Z","startedAt":"2026-10-15T09:00:04Z","endedAt":null,
                 "statusVersion":4,"serverTime":"2026-10-15T09:05:00Z",
                 "technicalPause":{"kind":"DEVICE","since":"2026-10-15T09:04:50Z","secret":"x"},
                 "silenceWarning":{"silentSince":"2026-10-15T09:00:30Z","endsAt":"2026-10-15T09:06:30Z"},
                 "practice":{"goalIndex":1,"goalCount":2,"goalName":"같이 놀자고 말하기","stage":"CUE","label":"functional"},
                 "gaps":[{"from":"2026-10-15T09:03:00Z","to":"2026-10-15T09:03:40Z","kind":"DISCONNECT"},
                         {"from":"2026-10-15T09:04:50Z","to":null,"kind":"DISCONNECT"},
                         {"from":"not-a-time","kind":"DISCONNECT"},{"from":"2026-10-15T09:04:00Z","kind":"WHATEVER"}],
                 "items":[],"nextSeq":0}
                """.formatted(sessionId)));
        try {
            Result view = send("GET /api/sessions/{sessionId}",
                    app.get("/api/sessions/" + sessionId).session(guardian.session()));

            assertThat(view.status()).isEqualTo(200);
            assertThat(view.body()).as("계약 밖 필드(판정 label 등)는 걸러진다").doesNotContain("functional")
                    .doesNotContain("secret");
            assertThat((List<?>) view.json().get("gaps")).hasSize(2);
            assertThat(map(view.json().get("technicalPause"))).containsEntry("kind", "DEVICE");
            assertThat(map(view.json().get("silenceWarning"))).containsEntry("endsAt", "2026-10-15T09:06:30Z");
            assertThat(map(view.json().get("practice"))).containsEntry("stage", "CUE").containsEntry("goalCount", 2);
        } finally {
            realtime.route("GET", WebFixtures.RT_VIEW).respond(StubResponse.error(404,
                    kr.mujoong.contract.error.ErrorCode.NOT_FOUND));
        }
    }

    // ------------------------------------------------------------------ 기록 수신 · 26 · 27

    @Test
    void aCapturedRecordIsStoredOncePublishedOnceAndServedWithoutJudgmentsOrGuardianInput() {
        Family family = family();
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());
        UUID sessionId = copy(family, SessionStatus.ENDED);
        db.jdbc().update("""
                insert into core.core_activity_context (id, session_id, guardian_id, child_id, known_content, created_at)
                values (:id, :s, :g, :c, :k, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("s", sessionId)
                .addValue("g", family.guardianId()).addValue("c", family.childId()).addValue("k", KNOWN)
                .addValue("now", java.sql.Timestamp.from(app.clock().now())));
        Map<String, Object> record = practiceRecord();

        deliver(captured(family, sessionId, SessionStatus.ENDED, record));
        deliver(captured(family, sessionId, SessionStatus.ENDED, record));
        awaitProcessed(2);
        awaitRecordedEvent(sessionId);

        assertThat(count("select count(*) from core.core_activity_record where session_id = :g", sessionId)).isEqualTo(1);
        assertThat(app.bean(RecordedEvents.class).events()).as("ActivityRecorded는 처음 저장 때 한 번(커밋 뒤)").singleElement()
                .satisfies(event -> {
                    assertThat(event.sessionId()).isEqualTo(sessionId);
                    assertThat(event.finalStatus()).isEqualTo("ENDED");
                });

        Result detail = send("GET /api/records/{sessionId}", app.get("/api/records/" + sessionId).session(guardian.session()));
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body()).as("판정값 · Cue · 원음 경로 · 08 원문이 없다(FR4.3 · A10)")
                .doesNotContain("functional").doesNotContain("deliveredText").doesNotContain("storagePath")
                .doesNotContain("audio/").doesNotContain(KNOWN).doesNotContain("label");
        assertThat((List<?>) detail.json().get("utterances")).hasSize(2);
        assertThat((List<?>) detail.json().get("goals")).singleElement()
                .satisfies(goal -> assertThat(map(goal)).containsOnlyKeys("displayName", "order", "status"));
        assertThat(detail.json()).containsEntry("endConfirmed", true);

        String day = Instant.parse("2026-10-14T16:10:00Z").atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        Result month = send("GET /api/records", app.get("/api/records?month=2026-10").session(guardian.session()));
        assertThat((List<?>) month.json().get("days")).singleElement()
                .satisfies(entry -> assertThat(map(entry)).containsEntry("date", day).containsEntry("count", 1));
        Result date = send("GET /api/records", app.get("/api/records?date=" + day).session(guardian.session()));
        assertThat((List<?>) date.json().get("items")).singleElement().satisfies(item -> assertThat(map(item))
                .containsEntry("linkable", true).containsEntry("goalCount", 1).containsEntry("interrupted", true));
    }

    @Test
    void declinedKeepsOnlyTheHeaderFailedIsHiddenAndAWrongHashIsNotStored() {
        Family family = family();
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());
        UUID declined = copy(family, SessionStatus.DECLINED);
        deliver(captured(family, declined, SessionStatus.DECLINED, practiceRecord()));
        UUID wrongHash = UUID.randomUUID();
        insertCopy(family, wrongHash, SessionStatus.ENDED);
        SessionRecordCaptured tampered = captured(family, wrongHash, SessionStatus.ENDED, practiceRecord());
        deliver(new SessionRecordCaptured(tampered.sessionId(), tampered.guardianId(), tampered.childId(),
                tampered.activityType(), tampered.finalStatus(), tampered.endReason(), tampered.confirmed(),
                tampered.startedAt(), tampered.endedAt(), tampered.termination(), tampered.snapshotId(),
                tampered.snapshotVersion(), "sha256:" + "0".repeat(64), tampered.record()));
        awaitProcessed(1);

        assertThat(String.valueOf(db.jdbc().queryForObject(
                "select transcript_json::text from core.core_activity_record where session_id = :s",
                new MapSqlParameterSource("s", declined), String.class))).as("DECLINED는 머리 정보만(FR3.5)").isEqualTo("[]");
        Result detail = send("GET /api/records/{sessionId}", app.get("/api/records/" + declined).session(guardian.session()));
        assertThat(detail.json()).containsEntry("utterances", List.of()).containsEntry("goals", List.of())
                .containsEntry("recordScope", null);
        assertThat(count("select count(*) from core.core_activity_record where session_id = :g", wrongHash))
                .as("해시가 내용과 다르면 저장하지 않는다(U4a-J6)").isZero();
        Result date = send("GET /api/records", app.get("/api/records?date=2026-10-15").session(guardian.session()));
        assertThat((List<?>) date.json().get("items")).singleElement()
                .satisfies(item -> assertThat(map(item)).containsEntry("linkable", false)
                        .containsEntry("finalStatus", "DECLINED"));

        Guardian other = WebFixtures.guardian(app, db);
        assertThat(send("GET /api/records/{sessionId}", app.get("/api/records/" + declined).session(other.session())).status())
                .as("남의 기록은 404").isEqualTo(404);
        assertThat(send("GET /api/records", app.get("/api/records?month=2026-10&date=2026-10-15").session(guardian.session()))
                .status()).isEqualTo(400);
        assertThat(send("GET /api/records", app.get("/api/records?month=2026-13").session(guardian.session())).status())
                .isEqualTo(400);
    }

    @Test
    void afterTheDeletionRequestReadsAre403AndALateRecordIsNotStored() {
        // 리뷰 R1 · R2: 계약 2절(/api/me 외 403) · AC1.6.4 · AC1.6.8(삭제 접수 뒤 도착한 기록은 저장 · 발행하지 않는다)
        Family family = family();
        Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());
        UUID sessionId = copy(family, SessionStatus.ENDED);
        db.jdbc().update("update core.core_guardian set status = 'DELETION_REQUESTED' where id = :id",
                new MapSqlParameterSource("id", family.guardianId()));

        for (String[] read : new String[][] {
                {"GET /api/sessions/{sessionId}", "/api/sessions/" + sessionId},
                {"GET /api/sessions/current", "/api/sessions/current"},
                {"GET /api/records", "/api/records?month=2026-10"},
                {"GET /api/records/{sessionId}", "/api/records/" + sessionId}}) {
            Result refused = send(read[0], app.get(read[1]).session(guardian.session()));
            assertThat(refused.status()).as(read[1]).isEqualTo(403);
            assertThat(refused.json()).as(read[1]).containsEntry("code", "ACCOUNT_DELETION_IN_PROGRESS");
        }

        deliver(captured(family, sessionId, SessionStatus.ENDED, practiceRecord()));
        awaitProcessed(1);
        assertThat(count("select count(*) from core.core_activity_record where session_id = :g", sessionId))
                .as("삭제 접수 뒤 도착한 기록은 저장하지 않는다").isZero();
        assertThat(app.bean(RecordedEvents.class).events()).as("ActivityRecorded도 내지 않는다").isEmpty();
    }

    // ------------------------------------------------------------------ 계정 삭제 참가자

    @Test
    void deletionParticipantsRemoveBackgroundRecordAndCopyOfThatGuardianOnly() {
        Family mine = family();
        Family other = family();
        UUID session = copy(mine, SessionStatus.ENDED);
        UUID otherSession = copy(other, SessionStatus.ENDED);
        deliver(captured(mine, session, SessionStatus.ENDED, practiceRecord()));
        deliver(captured(other, otherSession, SessionStatus.ENDED, practiceRecord()));
        awaitProcessed(2);
        for (Family family : List.of(mine, other)) {
            db.jdbc().update("""
                    insert into core.core_activity_context (id, session_id, guardian_id, child_id, question, created_at)
                    select :id, session_id, guardian_id, child_id, '질문', created_at from core.core_activity where guardian_id = :g
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("g", family.guardianId()));
        }

        // 순번이 아니라 타입으로 집는다: U6(70) · U7(60) 참가자가 더 앞에 붙어도 이 시험은 그대로다(리뷰 R4)
        List<DeletionParticipant> participants = List.of(
                app.bean(kr.mujoong.core.record.ActivityRecordDeletionParticipant.class),
                app.bean(ActivityDeletionParticipant.class));
        Map<String, Integer> deleted = new LinkedHashMap<>();
        for (DeletionParticipant participant : participants) {
            deleted.putAll(participant.deleteByGuardian(mine.guardianId(), UUID.randomUUID()));
        }

        assertThat(deleted).containsEntry("core_activity_record", 1).containsEntry("core_activity_context", 1)
                .containsEntry("core_activity", 1);
        for (String table : List.of("core_activity_record", "core_activity_context", "core_activity")) {
            assertThat(count("select count(*) from core." + table + " where guardian_id = :g", mine.guardianId())).as(table)
                    .isZero();
            assertThat(count("select count(*) from core." + table + " where guardian_id = :g", other.guardianId())).as(table)
                    .isEqualTo(1);
        }
        assertThat(participants.get(0).deleteByGuardian(mine.guardianId(), UUID.randomUUID()))
                .as("멱등").containsEntry("core_activity_record", 0);
    }

    // ------------------------------------------------------------------ 도움

    private Family family() {
        Family family = fixtures.family(app.clock().now().minusSeconds(60));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, app.clock().now(), app.clock().now());
        return family;
    }

    private UUID copy(Family family, SessionStatus status) {
        UUID sessionId = UUID.randomUUID();
        insertCopy(family, sessionId, status);
        return sessionId;
    }

    private void insertCopy(Family family, UUID sessionId, SessionStatus status) {
        UUID device = db.jdbc().queryForObject("select id from core.core_device where guardian_id = :g limit 1",
                new MapSqlParameterSource("g", family.guardianId()), UUID.class);
        app.bean(ActivityCopyRepository.class).insertRequested(new NewActivity(sessionId, family.guardianId(),
                family.childId(), device, ActivityType.FREE_TALK, UUID.randomUUID().toString(), "hash",
                Instant.parse("2026-10-15T01:00:00Z")));
        setStatus(sessionId, status);
    }

    private void setStatus(UUID sessionId, SessionStatus status) {
        db.jdbc().update("update core.core_activity set status = :st, status_version = status_version + 1 where session_id = :s",
                new MapSqlParameterSource("st", status.name()).addValue("s", sessionId));
    }

    private void addStopExpression(UUID childId, String text, int order) {
        db.jdbc().update("""
                insert into core.core_stop_expression (id, child_id, expression_text, normalized_text, sort_order, created_at,
                    updated_at)
                values (:id, :c, :t, :n, :o, :now, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("c", childId).addValue("t", text)
                .addValue("n", text.replace(" ", "")).addValue("o", order)
                .addValue("now", java.sql.Timestamp.from(app.clock().now())));
    }

    private static Map<String, Object> practiceRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("utterances", List.of(
                Map.of("seq", 2, "speaker", "CHILD", "text", "블록 쌓았어", "uncertain", false,
                        "startedAt", "2026-10-14T16:11:00Z", "endedAt", "2026-10-14T16:11:02Z"),
                Map.of("seq", 1, "speaker", "AI", "text", "안녕 무중이야", "uncertain", false,
                        "startedAt", "2026-10-14T16:10:05Z", "endedAt", "2026-10-14T16:10:07Z")));
        record.put("gaps", List.of(Map.of("from", "2026-10-14T16:12:00Z", "to", "2026-10-14T16:12:30Z", "kind", "DISCONNECT")));
        record.put("recordScope", Map.of("from", "2026-10-14T16:10:00Z", "to", "2026-10-14T16:20:00Z"));
        record.put("practice", Map.of("goals", List.of(Map.of("goalSelectionId", UUID.randomUUID().toString(),
                "displayName", "같이 놀자고 말하기", "order", 1, "status", "DONE",
                "responses", List.of(Map.of("responseType", "FIRST", "label", "functional")),
                "cues", List.of(Map.of("consentResponse", "ACCEPTED", "deliveredText", "같이 살펴볼까?"))))));
        record.put("audio", List.of(Map.of("recordingId", "r1", "speaker", "CHILD", "storagePath", "audio/r1.wav")));
        return record;
    }

    private static SessionRecordCaptured captured(Family family, UUID sessionId, SessionStatus status, Map<String, Object> record) {
        String hash = "sha256:" + JSON.sha256OfCanonical(JSON.write(record));
        return new SessionRecordCaptured(sessionId, family.guardianId(), family.childId(), ActivityType.FREE_TALK, status,
                status == SessionStatus.DECLINED ? "DECLINED" : "GUARDIAN_STOP", true,
                status == SessionStatus.DECLINED ? null : Instant.parse("2026-10-14T16:10:00Z"),
                Instant.parse("2026-10-14T16:20:00Z"), Map.of("requestedAt", "2026-10-14T16:19:50Z"), UUID.randomUUID(), 1,
                hash, record);
    }

    private final List<UUID> delivered = new CopyOnWriteArrayList<>();

    private void deliver(SessionRecordCaptured payload) {
        UUID eventId = db.trackInbox(UUID.randomUUID());
        delivered.add(eventId);
        String body = JSON.encodeEventEnvelope(JSON.envelopeFor(eventId, payload, app.clock().now(), null));
        assertThat(app.post("/internal/events").internalToken().json(body).send().status()).isEqualTo(200);
    }

    /** 처리기가 끝난(PROCESSED) 이벤트가 {@code expected}개 이상이 되고, 나머지는 처리 실패로 재시도 대기일 때까지. */
    private void awaitProcessed(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer processed = db.jdbc().queryForObject(
                    "select count(*) from core.core_inbox_event where event_id = any(:ids) and status = 'PROCESSED'",
                    new MapSqlParameterSource("ids", delivered.toArray(UUID[]::new)), Integer.class);
            Integer pending = db.jdbc().queryForObject(
                    "select count(*) from core.core_inbox_event where event_id = any(:ids) and status = 'RECEIVED' and retry_count = 0",
                    new MapSqlParameterSource("ids", delivered.toArray(UUID[]::new)), Integer.class);
            if (processed != null && processed >= expected && pending != null && pending == 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Inbox 처리기가 10초 안에 끝나지 않았다");
    }

    /** DB 커밋 뒤 실행되는 구독자까지 도착했는지 별도로 기다린다. */
    private void awaitRecordedEvent(UUID sessionId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (app.bean(RecordedEvents.class).events().stream().anyMatch(event -> event.sessionId().equals(sessionId))) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("ActivityRecorded 구독자가 10초 안에 받지 못했다");
    }

    private int contexts(UUID guardianId) {
        return count("select count(*) from core.core_activity_context where guardian_id = :g", guardianId);
    }

    private static int count(String sql, Object value) {
        Integer counted = db.jdbc().queryForObject(sql, new MapSqlParameterSource("g", value), Integer.class);
        return counted == null ? -1 : counted;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private Result send(String operation, Call call) {
        Result result = call.send();
        violations.addAll(contracts.violations(operation, result));
        return result;
    }

    /** 시험 전용: ActivityRecorded를 커밋 뒤 모은다(U5 · U6 구독자 자리). */
    @TestConfiguration(proxyBeanMethods = false)
    static class RecordedListener {

        @Bean
        RecordedEvents recordedEvents() {
            return new RecordedEvents();
        }
    }

    /** 받은 ActivityRecorded. */
    static class RecordedEvents {

        private final List<ActivityRecorded> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(ActivityRecorded event) {
            events.add(event);
        }

        List<ActivityRecorded> events() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }
}
