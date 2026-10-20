package kr.mujoong.core.db;

import static kr.mujoong.core.db.CoreDbFixtures.T0;
import static kr.mujoong.core.db.CoreDbFixtures.expectSqlState;
import static kr.mujoong.core.db.CoreDbFixtures.insertActivity;
import static kr.mujoong.core.db.CoreDbFixtures.insertChild;
import static kr.mujoong.core.db.CoreDbFixtures.insertDevice;
import static kr.mujoong.core.db.CoreDbFixtures.insertGuardian;
import static kr.mujoong.core.db.CoreDbFixtures.insertHousehold;
import static kr.mujoong.core.db.CoreDbFixtures.insertInbox;
import static kr.mujoong.core.db.CoreDbFixtures.insertOutbox;
import static kr.mujoong.core.db.CoreDbFixtures.randomHash;
import static kr.mujoong.core.db.CoreDbFixtures.randomTraceId;
import static kr.mujoong.core.db.CoreDbFixtures.single;
import static kr.mujoong.core.db.CoreDbFixtures.update;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import kr.mujoong.core.db.CoreDbFixtures.ActivityRow;
import kr.mujoong.core.db.CoreDbFixtures.DeviceRow;
import kr.mujoong.core.db.CoreDbFixtures.Household;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Core V1 제약 통합 시험(U1 3.5 Step 14). 실제 init 스크립트 + 두 마이그레이션 위치로 만든 PostgreSQL 16에 앱 역할 core_app으로 접속해
 * 유일 · 부분 유일 · 검사 · 외래 키 제약을 확인한다. 시험마다 한 트랜잭션에서 행을 만들고 끝에 되돌린다.
 *
 * <p>증명: BR1.2 · BR1.4(UNREGISTERED 코드 중복 금지 · EXPIRED 재사용) · AC2.2.7 · BR2.4 · BR2.5(보호자당 REGISTERED 하나) ·
 * RELEASED · REGISTERED 검사 · AC3.1.8 · BR6.2 · BR6.3(활동 유일) · local_failed 검사(BR6.6 · BR6.7 · BR6.12 ①) ·
 * trace_id 형식(A3 · OD-7) · BR5.6 · BR5.11 · BR2.10 · J58(외래 키 RESTRICT 23503).
 */
@Tag("skeleton")
@Tag("db")
class CoreConstraintIT {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static MujoongPostgres postgres;
    private Connection connection;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = postgres.connect(Role.CORE_APP);
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    // ------------------------------------------------------------------ core_device

    @Test
    void unregisteredCodesAreUniqueButAnExpiredRowFreesItsCode() throws SQLException {
        DeviceRow first = DeviceRow.unregistered();
        insertDevice(connection, first);

        // 발급 중 코드끼리 겹치지 않는다(BR1.2 재추첨의 근거)
        expectSqlState(connection, UNIQUE_VIOLATION, () -> insertDevice(connection, DeviceRow.unregistered()
                .withCode(first.code())));

        // 만료되면 같은 코드를 새 발급에서 다시 써도 된다(BR1.4). 만료 행의 코드 값은 그대로 남는다
        assertThat(update(connection,
                "update core.core_device set registration_status = 'EXPIRED', updated_at = ?, row_version = row_version + 1"
                        + " where id = ? and registration_status = 'UNREGISTERED'",
                T0, first.id())).isEqualTo(1);
        DeviceRow reissued = DeviceRow.unregistered().withCode(first.code());
        insertDevice(connection, reissued);
        assertThat(single(connection, "select count(*) from core.core_device where registration_code = ?", first.code()))
                .isEqualTo(2L);
    }

    @Test
    void aGuardianHasAtMostOneRegisteredDeviceAndReplacementReleasesTheOldRowFirst() throws SQLException {
        UUID guardianId = insertGuardian(connection);
        DeviceRow active = DeviceRow.registered(guardianId);
        insertDevice(connection, active);

        // 계정당 활성 기기 하나(AC2.2.7 · BR2.4): 부분 유일 색인 위반은 23505
        expectSqlState(connection, UNIQUE_VIOLATION, () -> insertDevice(connection, DeviceRow.registered(guardianId)));

        // 교체 등록 순서(BR2.5): 옛 행을 먼저 RELEASED로 바꾸면 새 행을 REGISTERED로 넣을 수 있다
        assertThat(update(connection,
                "update core.core_device set registration_status = 'RELEASED', released_at = ?, release_reason = 'REREGISTERED',"
                        + " device_token_hash = null, updated_at = ?, row_version = row_version + 1"
                        + " where id = ? and registration_status = 'REGISTERED'",
                T0, T0, active.id())).isEqualTo(1);
        insertDevice(connection, DeviceRow.registered(guardianId));
        assertThat(single(connection,
                "select count(*) from core.core_device where guardian_id = ? and registration_status = 'REGISTERED'",
                guardianId)).isEqualTo(1L);
    }

    @Test
    void registrationStatusChecksRejectInconsistentRows() throws SQLException {
        UUID guardianId = insertGuardian(connection);

        // REGISTERED이면 보호자 · 등록 시각이 있어야 한다
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertDevice(connection, DeviceRow.registered(guardianId).withGuardian(null)));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertDevice(connection, DeviceRow.registered(guardianId).withRegisteredAt(null)));
        // RELEASED이면 해제 시각 · 알려진 사유가 있고 토큰 해시가 비어 있어야 한다(BR3.3)
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertDevice(connection, DeviceRow.released(guardianId).withRelease(null, "REREGISTERED")));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertDevice(connection, DeviceRow.released(guardianId).withRelease(T0, "STOLEN")));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertDevice(connection, DeviceRow.released(guardianId).withTokenHash(randomHash())));
        // 등록 전 · 만료 행에는 보호자가 없다
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertDevice(connection, DeviceRow.unregistered().withGuardian(guardianId)));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertDevice(connection, DeviceRow.unregistered().withStatus("EXPIRED").withGuardian(guardianId)));
        // 모르는 상태 값
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertDevice(connection, DeviceRow.unregistered().withStatus("PAIRED")));

        // 정상 행은 들어간다(같은 보호자의 해제 행 + 활성 행)
        insertDevice(connection, DeviceRow.released(guardianId));
        insertDevice(connection, DeviceRow.registered(guardianId));
    }

    @Test
    void bootstrapAndTokenHashesAreUnique() throws SQLException {
        DeviceRow first = DeviceRow.unregistered();
        insertDevice(connection, first);
        expectSqlState(connection, UNIQUE_VIOLATION,
                () -> insertDevice(connection, DeviceRow.unregistered().withBootstrapHash(first.bootstrapHash())));

        UUID guardianA = insertGuardian(connection);
        UUID guardianB = insertGuardian(connection);
        DeviceRow registered = DeviceRow.registered(guardianA);
        insertDevice(connection, registered);
        expectSqlState(connection, UNIQUE_VIOLATION,
                () -> insertDevice(connection, DeviceRow.registered(guardianB).withTokenHash(registered.tokenHash())));
    }

    // ------------------------------------------------------------------ core_activity

    @Test
    void sameIdempotencyKeyOfTheSameGuardianIsOneSession() throws SQLException {
        Household household = insertHousehold(connection);
        ActivityRow first = ActivityRow.requested(household.guardianId(), household.childId(), household.deviceId())
                .withStatus("ENDED");
        insertActivity(connection, first);

        // 같은 (guardian_id, start_idempotency_key)는 종료 뒤에도 다른 세션이 될 수 없다(BR6.2 · AC3.1.8)
        expectSqlState(connection, UNIQUE_VIOLATION,
                () -> insertActivity(connection, first.withNewSessionId().withStatus("REQUESTED")));

        // 다른 보호자는 같은 키 문자열을 써도 서로 막지 않는다
        Household other = insertHousehold(connection);
        insertActivity(connection, ActivityRow.requested(other.guardianId(), other.childId(), other.deviceId())
                .withKey(first.idempotencyKey()));
    }

    @Test
    void aGuardianHasOneInProgressSessionAtATime() throws SQLException {
        Household household = insertHousehold(connection);
        ActivityRow running = ActivityRow.requested(household.guardianId(), household.childId(), household.deviceId())
                .withStatus("ACTIVE");
        insertActivity(connection, running);

        // 진행 중 상태 여섯(REQUESTED ~ CLOSING) 중 하나가 있으면 두 번째는 23505(BR6.3 · AC3.1.8)
        for (String status : new String[] {"REQUESTED", "PREPARING", "CLOSING"}) {
            expectSqlState(connection, UNIQUE_VIOLATION, () -> insertActivity(connection,
                    ActivityRow.requested(household.guardianId(), household.childId(), household.deviceId())
                            .withStatus(status)));
        }

        // 종료 상태는 색인 밖이라 진행 중 세션과 함께 있을 수 있다
        insertActivity(connection, ActivityRow.requested(household.guardianId(), household.childId(), household.deviceId())
                .withStatus("ENDED"));
        assertThat(update(connection,
                "update core.core_activity set status = 'ENDED', status_version = 5, updated_at = ?"
                        + " where session_id = ? and status_version < 5 and not (status = 'FAILED' and local_failed)",
                T0, running.sessionId())).isEqualTo(1);
        insertActivity(connection, ActivityRow.requested(household.guardianId(), household.childId(), household.deviceId()));
    }

    @Test
    void localFailedRowsAreFailedWithoutFailureCodeAndWithStartResultCode() throws SQLException {
        Household household = insertHousehold(connection);
        ActivityRow base = ActivityRow.requested(household.guardianId(), household.childId(), household.deviceId());

        // 정상: 로컬 FAILED(start_result_code 503 · failure_code 비움)와 일반 행(start_result_code 비움)
        insertActivity(connection, base.withStatus("FAILED").withLocalFailed(true, 503));
        insertActivity(connection, ActivityRow.requested(household.guardianId(), household.childId(), household.deviceId())
                .withStatus("FAILED").withFailureCode("PROVIDER_UNAVAILABLE"));

        ActivityRow fresh = ActivityRow.requested(household.guardianId(), household.childId(), household.deviceId());
        // local_failed인데 FAILED가 아님
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertActivity(connection, fresh.withLocalFailed(true, 409)));
        // local_failed인데 start_result_code 없음(NULL 비교로 통과하지 않는다)
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertActivity(connection, fresh.withStatus("FAILED").withLocalFailed(true, null)));
        // local_failed인데 failure_code 있음
        expectSqlState(connection, CHECK_VIOLATION, () -> insertActivity(connection,
                fresh.withStatus("FAILED").withLocalFailed(true, 503).withFailureCode("SERVER_RESTART")));
        // 허용 밖 응답 코드
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertActivity(connection, fresh.withStatus("FAILED").withLocalFailed(true, 500)));
        // local_failed가 아닌데 start_result_code 있음
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertActivity(connection, fresh.withLocalFailed(false, 202)));
        // 모르는 상태
        expectSqlState(connection, CHECK_VIOLATION, () -> insertActivity(connection, fresh.withStatus("RUNNING")));
    }

    // ------------------------------------------------------------------ Outbox · Inbox

    @Test
    void outboxAndInboxTraceIdMustBeLowercaseHex32WhenPresent() throws SQLException {
        // 정상: 형식이 맞는 값과 없음(A3 trace_id는 선택 열)
        insertOutbox(connection, UUID.randomUUID(), "PENDING", "{\"deviceId\":\"x\"}", null, randomTraceId());
        insertOutbox(connection, UUID.randomUUID(), "PENDING", "{}", null, null);
        insertInbox(connection, UUID.randomUUID(), "RECEIVED", T0, randomTraceId());

        String upper = "0123456789abcdef0123456789abcdef".toUpperCase(Locale.ROOT);
        String nonHex = "g".repeat(32);
        String tooShort = randomTraceId().substring(0, 31);
        for (String bad : new String[] {upper, nonHex, tooShort}) {
            expectSqlState(connection, CHECK_VIOLATION,
                    () -> insertOutbox(connection, UUID.randomUUID(), "PENDING", "{}", null, bad));
            expectSqlState(connection, CHECK_VIOLATION,
                    () -> insertInbox(connection, UUID.randomUUID(), "RECEIVED", T0, bad));
        }
    }

    @Test
    void publishedOutboxRowsMustBePurgedAndRetryCountIsBounded() throws SQLException {
        // PUBLISHED는 payload를 비운 행만(BR5.11)
        insertOutbox(connection, UUID.randomUUID(), "PUBLISHED", "{}", T0, null);
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertOutbox(connection, UUID.randomUUID(), "PUBLISHED", "{\"deviceId\":\"x\"}", null, null));

        UUID eventId = UUID.randomUUID();
        insertOutbox(connection, eventId, "PENDING", "{\"deviceId\":\"x\"}", null, null);
        // 같은 eventId는 한 행(기본 키)
        expectSqlState(connection, UNIQUE_VIOLATION,
                () -> insertOutbox(connection, eventId, "PENDING", "{}", null, null));
        // retry_count 상한 20(BR5.3)
        assertThat(update(connection, "update core.core_outbox_event set retry_count = 20 where event_id = ?", eventId))
                .isEqualTo(1);
        expectSqlState(connection, CHECK_VIOLATION,
                () -> update(connection, "update core.core_outbox_event set retry_count = 21 where event_id = ?", eventId));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> update(connection, "update core.core_outbox_event set status = 'SENT' where event_id = ?", eventId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSED", "IGNORED", "DEAD"})
    void inboxNeedsNextAttemptAtOnlyWhileReceived(String finishedStatus) throws SQLException {
        // RECEIVED는 next_attempt_at 필수(BR5.6 · BR5.7), 처음부터 IGNORED 등 끝난 행은 비어도 된다(BR5.9)
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertInbox(connection, UUID.randomUUID(), "RECEIVED", null, null));
        insertInbox(connection, UUID.randomUUID(), finishedStatus, null, null);

        UUID duplicated = UUID.randomUUID();
        insertInbox(connection, duplicated, "RECEIVED", T0, null);
        // 같은 eventId는 한 행(BR5.6 중복 차단의 근거)
        expectSqlState(connection, UNIQUE_VIOLATION,
                () -> insertInbox(connection, duplicated, "RECEIVED", T0, null));
        // retry_count 상한 5(BR5.8)
        expectSqlState(connection, CHECK_VIOLATION,
                () -> update(connection, "update core.core_inbox_event set retry_count = 6 where event_id = ?", duplicated));
        // 수신 원천은 REALTIME뿐
        expectSqlState(connection, CHECK_VIOLATION,
                () -> update(connection, "update core.core_inbox_event set source_service = 'CORE' where event_id = ?",
                        duplicated));
    }

    // ------------------------------------------------------------------ 외래 키(계정 삭제 순서)

    @Test
    void foreignKeysRestrictDeletionOrderActivityDeviceThenGuardian() throws SQLException {
        Household household = insertHousehold(connection);
        ActivityRow activity = ActivityRow.requested(household.guardianId(), household.childId(), household.deviceId())
                .withStatus("ENDED");
        insertActivity(connection, activity);

        // 순서를 어기면 23503이고 행은 남는다(BR2.10 · J58)
        expectSqlState(connection, FOREIGN_KEY_VIOLATION,
                () -> update(connection, "delete from core.core_guardian where id = ?", household.guardianId()));
        expectSqlState(connection, FOREIGN_KEY_VIOLATION,
                () -> update(connection, "delete from core.core_device where id = ?", household.deviceId()));

        // core_activity(U4 참가자) → core_device(BR2.10) → (U2: 아이) → core_guardian 순서면 지워진다
        assertThat(update(connection, "delete from core.core_activity where session_id = ?", activity.sessionId()))
                .isEqualTo(1);
        assertThat(update(connection, "delete from core.core_device where guardian_id = ?", household.guardianId()))
                .isEqualTo(1);
        expectSqlState(connection, FOREIGN_KEY_VIOLATION,
                () -> update(connection, "delete from core.core_guardian where id = ?", household.guardianId()));
        assertThat(update(connection, "delete from core.core_child where guardian_id = ?", household.guardianId()))
                .isEqualTo(1);
        assertThat(update(connection, "delete from core.core_guardian where id = ?", household.guardianId()))
                .isEqualTo(1);
    }

    @Test
    void foreignKeysRejectRowsPointingAtMissingParents() throws SQLException {
        UUID missingGuardian = UUID.randomUUID();
        expectSqlState(connection, FOREIGN_KEY_VIOLATION,
                () -> insertDevice(connection, DeviceRow.registered(missingGuardian)));
        expectSqlState(connection, FOREIGN_KEY_VIOLATION, () -> insertChild(connection, missingGuardian));

        Household household = insertHousehold(connection);
        expectSqlState(connection, FOREIGN_KEY_VIOLATION, () -> insertActivity(connection,
                ActivityRow.requested(household.guardianId(), household.childId(), UUID.randomUUID())));
    }
}
