package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.account.DeletionCommander;
import kr.mujoong.core.account.DeletionCommander.Step;
import kr.mujoong.core.account.DeletionRequestRepository;
import kr.mujoong.core.child.ChildProfileDeletionParticipant;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.U2Fixtures;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.OpenApiContracts;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연습 계정 포트의 운영 구현과 계정 삭제 순서 통합 시험(U5a 리뷰 A · B · 3 · 4).
 *
 * <p>실제 앱에서 {@link U2PracticeAccountAdapter}(U2 GuardianWriteLock · ChildProfileService)가 쓰이는지, 아이 없는 보호자의 18 목록이 빈
 * 상태인지, 그리고 목표 선택 · 상황이 있는 보호자의 계정 삭제가 아이 단계에서 외래 키에 막히지 않고 DeletionCompleted 뒤 완료되는지 본다. 지휘
 * 주기는 1시간으로 두어 배경 실행이 끼지 않게 하고 시험이 {@link DeletionCommander#process}를 직접 부른다.
 */
@Tag("u5a")
@Tag("db")
class PracticeAccountIT {

    private static final Duration TICK = Duration.ofHours(1);

    private static DbTestSupport db;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static OpenApiContracts contracts;

    private final List<UUID> sessionsOf = new ArrayList<>();
    private final List<UUID> practiceOwners = new ArrayList<>();

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        BusinessFixtures.applyReferenceSeed(db.postgres());
        U2Fixtures.applyU2Seed(db.postgres());
        contracts = OpenApiContracts.load();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl())
                .property("mujoong.timing.account.deletion-tick", "1h")
                .property("mujoong.timing.account.deletion-session-wait", "2h")
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

    @AfterEach
    void cleanUp() {
        for (UUID owner : practiceOwners) {
            app.bean(PracticeDeletionParticipant.class).deleteByGuardian(owner, UUID.randomUUID());
        }
        WebFixtures.deleteSessions(db, sessionsOf.toArray(UUID[]::new));
    }

    @Test
    void theAppWiresTheU2AdapterAndItReadsGuardianAndChildThroughU2() {
        PracticeAccountPort accounts = app.bean(PracticeAccountPort.class);
        assertThat(accounts).isInstanceOf(U2PracticeAccountAdapter.class);

        UUID guardian = db.insertGuardian("ACTIVE");
        UUID child = db.insertChild(guardian);
        PracticeAccountPort.AccountContext context = accounts.requireActive(guardian, false);
        assertThat(context).isEqualTo(new PracticeAccountPort.AccountContext(guardian, child, 8, List.of("공룡")));
        assertThat(context.toString()).doesNotContain("공룡");
        assertThat(accounts.requireChild(child, false)).isEqualTo(context);
        assertThat(accounts.childrenForDeletion(guardian)).containsExactly(child);
        // 잠그는 호출은 트랜잭션 안에서만(보호자 행 잠금이 그 트랜잭션의 첫 잠금이다)
        assertThat(inAppTx(() -> accounts.requireActive(guardian, true))).isEqualTo(context);
        assertThatThrownBy(() -> accounts.requireActive(guardian, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts.lockForDeletion(guardian)).isInstanceOf(IllegalStateException.class);

        error(() -> accounts.requireActive(null, false), ErrorCode.UNAUTHENTICATED);
        error(() -> accounts.requireActive(UUID.randomUUID(), false), ErrorCode.UNAUTHENTICATED);
        error(() -> inAppTx(() -> accounts.requireActive(UUID.randomUUID(), true)), ErrorCode.UNAUTHENTICATED);
        error(() -> accounts.requireChild(null, false), ErrorCode.VALIDATION_FAILED);
        error(() -> accounts.requireChild(UUID.randomUUID(), false), ErrorCode.NOT_FOUND);

        // 관심사 · 나이를 모르는 아이: 빈 관심사 · 나이 null(상황 생성은 나이가 없으면 503으로 닫힌다)
        db.jdbc().update("update core.core_child set interests_json = null, age = null where id = :id",
                new MapSqlParameterSource("id", child));
        PracticeAccountPort.AccountContext unknown = accounts.requireActive(guardian, false);
        assertThat(unknown.interests()).isEmpty();
        assertThat(unknown.ageYears()).isNull();

        db.jdbc().update("update core.core_guardian set status = 'DELETION_REQUESTED' where id = :id",
                new MapSqlParameterSource("id", guardian));
        error(() -> accounts.requireGuardian(guardian), ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        error(() -> inAppTx(() -> accounts.requireActive(guardian, true)), ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        // 삭제 참가자는 상태와 관계없이 잠근다
        inAppTx(() -> {
            accounts.lockForDeletion(guardian);
            return null;
        });
    }

    @Test
    void aGuardianWithoutAChildSeesTheEmptyGoalListAndCannotAddGoals() {
        // U5a 리뷰 4: 아이를 아직 등록하지 않은 보호자의 18은 빈 상태다(예전에는 404)
        Guardian guardian = WebFixtures.guardian(app, db);
        sessionsOf.add(guardian.id());

        Result list = app.get("/api/goal-selections").session(guardian.session()).send();
        assertThat(contracts.violations("GET /api/goal-selections", list)).isEmpty();
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.json()).isEqualTo(Map.of("items", List.of()));

        Result add = app.post("/api/goal-selections").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"recommendationId\":\"" + UUID.randomUUID() + "\"}").send();
        assertThat(contracts.violations("POST /api/goal-selections", add)).isEmpty();
        assertThat(add.status()).isEqualTo(404);
    }

    @Test
    void practiceRunsBeforeTheChildParticipantSoAnAccountWithGoalsAndScenariosIsFullyDeleted() {
        // U5a 리뷰 B: 목표 선택 · 추천이 core_child를 ON DELETE RESTRICT로 가리킨다. 연습 참가자가 아이(300)보다 늦게 돌면 아이 단계에서 멈춘다
        List<DeletionParticipant> ordered = app.context().getBeanProvider(DeletionParticipant.class).orderedStream().toList();
        int practice = indexOf(ordered, PracticeDeletionParticipant.class);
        int child = indexOf(ordered, ChildProfileDeletionParticipant.class);
        assertThat(practice).as("연습 참가자가 아이 참가자보다 먼저").isLessThan(child);

        Guardian guardian = WebFixtures.guardian(app, db);
        sessionsOf.add(guardian.id());
        UUID childId = db.insertChild(guardian.id());
        UUID recommendation = db.inTx(status -> app.bean(PracticeGoalRepository.class).register(childId, UUID.randomUUID(),
                new CandidateRegistry.Candidate("ACCEPT_STOP", 1, "친구의 말에 대답한 기록",
                        List.of(new CandidateRegistry.Evidence(1, "CHILD_RECALL"))),
                "친구가 그만하자고 할 때 받아들이기", app.clock().now()));
        practiceOwners.add(guardian.id());
        Result added = app.post("/api/goal-selections").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"recommendationId\":\"" + recommendation + "\"}").send();
        assertThat(added.status()).isEqualTo(201);
        UUID goalSelection = UUID.fromString(String.valueOf(added.json().get("goalSelectionId")));
        insertScenario(guardian.id(), childId, goalSelection);

        Result accepted = app.call("DELETE", "/api/me").session(guardian.session()).csrf(guardian.csrf()).send();
        assertThat(accepted.status()).isEqualTo(202);
        UUID requestId = UUID.fromString(String.valueOf(accepted.json().get("deletionRequestId")));
        DeletionCommander commander = app.bean(DeletionCommander.class);

        assertThat(commander.process(requestId)).as("Core 참가자가 모두 지나간다(아이 단계 외래 키 오류 없음)")
                .isEqualTo(Step.REALTIME_REQUESTED);
        for (String table : List.of("core_practice_goal_selection", "core_scenario", "core_scenario_request", "core_child")) {
            assertThat(count("select count(*) from core." + table + " where guardian_id = :g", guardian.id())).as(table).isZero();
        }
        assertThat(count("select count(*) from core.core_goal_recommendation where child_id = :g", childId)).isZero();
        assertThat(count("select count(*) from core.core_recommendation_evidence where recommendation_id = :g",
                recommendation)).isZero();

        app.clock().advance(TICK);
        assertThat(commander.process(requestId)).as("DeletionCompleted 전에는 기다린다").isEqualTo(Step.WAITING);
        deliverDeletionCompleted(requestId, guardian.id());
        awaitRealtimeCompleted(requestId);
        assertThat(commander.process(requestId)).isEqualTo(Step.DONE);
        assertThat(app.bean(DeletionRequestRepository.class).findById(requestId).orElseThrow().status()).isEqualTo("DONE");
        assertThat(count("select count(*) from core.core_guardian where id = :g", guardian.id())).isZero();
    }

    // ------------------------------------------------------------------ 도움

    /** 앱의 트랜잭션 관리자로 연 트랜잭션(어댑터가 쓰는 연결과 같은 연결에서 보호자 행을 잠근다). */
    private static <T> T inAppTx(java.util.function.Supplier<T> body) {
        return new TransactionTemplate(app.bean(PlatformTransactionManager.class)).execute(status -> body.get());
    }

    private static void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CoreApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static int indexOf(List<DeletionParticipant> participants, Class<?> type) {
        for (int i = 0; i < participants.size(); i++) {
            if (type.isInstance(participants.get(i))) {
                return i;
            }
        }
        throw new AssertionError(type.getSimpleName() + " 참가자가 빈으로 없다");
    }

    /** 발급된 상황 한 개와 그 요청 행(C10 결과 모양 그대로, 외래 키 없음). */
    private static void insertScenario(UUID guardianId, UUID childId, UUID goalSelectionId) {
        UUID scenarioId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource("id", scenarioId).addValue("guardian", guardianId)
                .addValue("child", childId).addValue("session", sessionId).addValue("goal", goalSelectionId)
                .addValue("fingerprint", UUID.randomUUID().toString().replace("-", ""))
                .addValue("key", UUID.randomUUID().toString().replace("-", ""))
                .addValue("now", kr.mujoong.core.common.db.ClockParams.timestamptz(app.clock().now()));
        db.jdbc().update("""
                insert into core.core_scenario(id, guardian_id, child_id, session_id, goal_selection_id, behavior_key,
                  behavior_version, kind, relation_type, opportunity_condition, scenario_text, partner_role_text, trigger_script,
                  validation_json, content_fingerprint, status, model_version, prompt_version, created_at)
                values (:id, :guardian, :child, :session, :goal, 'ACCEPT_STOP', 1, 'ORIGINAL', 'PEER', '기회', '상황', '역할',
                  '대사', '{}'::jsonb, :fingerprint, 'ISSUED', 'm1', 'p1', :now)
                """, params);
        db.jdbc().update("""
                insert into core.core_scenario_request(request_key, guardian_id, child_id, session_id, goal_selection_id, kind,
                  claim_id, lease_until, status, result_scenario_id, created_at, updated_at)
                values (:key, :guardian, :child, :session, :goal, 'ORIGINAL', :id, :now, 'READY', :id, :now, :now)
                """, params);
    }

    private static long count(String sql, UUID id) {
        Long value = db.jdbc().queryForObject(sql, new MapSqlParameterSource("g", id), Long.class);
        return value == null ? 0 : value;
    }

    private void deliverDeletionCompleted(UUID requestId, UUID guardianId) {
        JsonCodec json = JsonCodec.shared();
        UUID eventId = db.trackInbox(UUID.randomUUID());
        String body = json.encodeEventEnvelope(json.envelopeFor(eventId,
                new DeletionCompleted(requestId, guardianId, app.clock().now(), new DeletionCompleted.Counts(0, 0, 0, 0)),
                app.clock().now(), null));
        Result result = app.post("/internal/events").internalToken().json(body).send();
        assertThat(result.status()).isEqualTo(200);
    }

    private void awaitRealtimeCompleted(UUID requestId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (app.bean(DeletionRequestRepository.class).findById(requestId).orElseThrow().realtimeCompletedAt() != null) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("DeletionCompleted가 10초 안에 반영되지 않았다");
    }
}
