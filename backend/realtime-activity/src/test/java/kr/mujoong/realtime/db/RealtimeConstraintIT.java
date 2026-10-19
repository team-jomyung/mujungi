package kr.mujoong.realtime.db;

import static kr.mujoong.realtime.db.RealtimeDbFixtures.T0;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.VALID_SNAPSHOT;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.expectSqlState;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.insertInbox;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.insertOutbox;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.insertSession;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.query;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.randomTraceId;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.single;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.update;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.upsertTranscript;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.db.RealtimeDbFixtures.SessionRow;
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
 * Realtime V1 제약 통합 시험(U1 3.5 Step 14). 실제 init 스크립트 + 두 마이그레이션 위치로 만든 DB에 앱 역할 realtime_app으로 접속해
 * 진행 중 세션 유일 · 동의 스냅샷 검사 · 전사 유일과 upsert · Inbox RECEIVED 조건 · trace_id 형식을 확인한다. 시험마다 되돌린다.
 *
 * <p>증명: BR6.3(보호자 · 기기당 진행 중 세션 하나, Realtime 2차 방어) · BR6.6 ④(ON CONFLICT (id) DO NOTHING) · BR6.8(전이 조건부
 * UPDATE 승자 하나) · 스냅샷 CHECK(entities ActivitySession) · BR7.5(transcript (session_id, sequence_no) 유일과 PARTIAL → FINAL
 * 덮어쓰기) · BR5.6(Inbox RECEIVED는 next_attempt_at 필수) · BR5.11 · A3(trace_id) · 연결 상태 값(E2).
 */
@Tag("skeleton")
@Tag("db")
class RealtimeConstraintIT {

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
        connection = postgres.connect(Role.REALTIME_APP);
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void aGuardianAndADeviceEachHaveOneInProgressSession() throws SQLException {
        SessionRow running = SessionRow.preparing().withStatus("ACTIVE", 4);
        assertThat(insertSession(connection, running)).isEqualTo(1);

        // 같은 보호자 · 같은 기기의 두 번째 진행 중 세션은 23505(BR6.3, Core가 SESSION_IN_PROGRESS로 번역)
        expectSqlState(connection, UNIQUE_VIOLATION,
                () -> insertSession(connection, SessionRow.preparing().withGuardian(running.guardianId())));
        expectSqlState(connection, UNIQUE_VIOLATION,
                () -> insertSession(connection, SessionRow.preparing().withDevice(running.deviceId())));

        // 종료 상태는 색인 밖: 끝난 세션이 있어도 새 세션을 시작한다
        assertThat(update(connection,
                "update realtime.realtime_activity_session set status = 'ENDED', status_version = status_version + 1,"
                        + " row_version = row_version + 1, updated_at = ? where id = ? and status = 'ACTIVE'",
                T0, running.id())).isEqualTo(1);
        assertThat(insertSession(connection, SessionRow.preparing()
                .withGuardian(running.guardianId()).withDevice(running.deviceId()))).isEqualTo(1);
    }

    @Test
    void sameSessionIdInsertIsANoOpAndTransitionsHaveOneWinner() throws SQLException {
        SessionRow session = SessionRow.preparing();
        assertThat(insertSession(connection, session)).isEqualTo(1);
        // BR6.6 ④: 시간 초과 재시도가 같은 id로 와도 실패하지 않고 0행
        assertThat(insertSession(connection, session)).isZero();

        // BR6.8: 같은 출발 상태의 전이는 승자 하나, 진 쪽은 0행
        String transition = "update realtime.realtime_activity_session set status = ?, status_version = status_version + 1,"
                + " row_version = row_version + 1, updated_at = ? where id = ? and status = ?";
        assertThat(update(connection, transition, "DEVICE_ACK", T0, session.id(), "PREPARING")).isEqualTo(1);
        assertThat(update(connection, transition, "FAILED", T0, session.id(), "PREPARING")).isZero();
        assertThat(query(connection,
                "select status, status_version, row_version from realtime.realtime_activity_session where id = ?",
                session.id())).containsExactly(List.of("DEVICE_ACK", 2, 1L));

        // status_version은 1부터(PREPARING = 1), 모르는 상태는 거부
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertSession(connection, SessionRow.preparing().withStatus("PREPARING", 0)));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertSession(connection, SessionRow.preparing().withStatus("REQUESTED", 1)));
    }

    @Test
    void consentSnapshotMustHoldFiveTypedValuesAndAgreeWithItsDerivedColumns() throws SQLException {
        // 정상: 다섯 값 + 파생 열 일치
        assertThat(insertSession(connection, SessionRow.preparing())).isEqualTo(1);
        assertThat(insertSession(connection, SessionRow.preparing().withSnapshot(
                VALID_SNAPSHOT.replace("\"voiceAndText\":true", "\"voiceAndText\":false"), "1.0", false, false)))
                .isEqualTo(1);

        // 키가 빠지면 비교가 NULL이 되지만 검사는 통과하지 않는다
        String missingRawAudio = VALID_SNAPSHOT.replace(",\"rawAudio\":false", "");
        expectSqlState(connection, CHECK_VIOLATION, () -> insertSession(connection,
                SessionRow.preparing().withSnapshot(missingRawAudio, "1.0", true, true)));
        // 형이 틀린 값(문자열 'true')
        expectSqlState(connection, CHECK_VIOLATION, () -> insertSession(connection,
                SessionRow.preparing().withSnapshot(VALID_SNAPSHOT.replace("\"guardianView\":true",
                        "\"guardianView\":\"true\""), "1.0", true, true)));
        // 파생 열이 스냅샷과 다름
        expectSqlState(connection, CHECK_VIOLATION, () -> insertSession(connection,
                SessionRow.preparing().withSnapshot(VALID_SNAPSHOT, "2.0", true, true)));
        expectSqlState(connection, CHECK_VIOLATION, () -> insertSession(connection,
                SessionRow.preparing().withSnapshot(VALID_SNAPSHOT, "1.0", false, true)));
        expectSqlState(connection, CHECK_VIOLATION, () -> insertSession(connection,
                SessionRow.preparing().withSnapshot(VALID_SNAPSHOT, "1.0", true, false)));
        // 객체가 아님
        expectSqlState(connection, CHECK_VIOLATION, () -> insertSession(connection,
                SessionRow.preparing().withSnapshot("[]", "1.0", true, true)));
    }

    @Test
    void transcriptSequenceIsUniquePerSessionAndPartialIsOverwrittenByFinal() throws SQLException {
        SessionRow session = SessionRow.preparing().withStatus("ACTIVE", 4);
        insertSession(connection, session);

        // BR7.5: 같은 번호의 PARTIAL은 덮어쓰고 FINAL로 닫는다(행은 하나)
        assertThat(upsertTranscript(connection, session.id(), 1, "CHILD", "공룡", "PARTIAL", true)).isEqualTo(1);
        assertThat(upsertTranscript(connection, session.id(), 1, "CHILD", "공룡 좋아", "FINAL", true)).isEqualTo(1);
        assertThat(upsertTranscript(connection, session.id(), 2, "AI", "그렇구나", "FINAL", false)).isEqualTo(1);
        assertThat(query(connection,
                "select sequence_no, text, assembly_status, approved_for_display from realtime.realtime_transcript"
                        + " where session_id = ? order by sequence_no",
                session.id())).containsExactly(
                        List.of(1, "공룡 좋아", "FINAL", true),
                        List.of(2, "그렇구나", "FINAL", false));

        // upsert가 아닌 같은 번호 INSERT는 23505, 번호는 1부터, 화자 · 조립 상태 값 검사, 없는 세션은 23503
        expectSqlState(connection, UNIQUE_VIOLATION, () -> update(connection,
                "insert into realtime.realtime_transcript (id, session_id, sequence_no, speaker, text, assembly_status,"
                        + " created_at) values (?, ?, 1, 'CHILD', 'x', 'FINAL', ?)",
                UUID.randomUUID(), session.id(), T0));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> upsertTranscript(connection, session.id(), 0, "CHILD", "x", "FINAL", true));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> upsertTranscript(connection, session.id(), 3, "GUARDIAN", "x", "FINAL", true));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> upsertTranscript(connection, session.id(), 3, "AI", "x", "DRAFT", false));
        expectSqlState(connection, FOREIGN_KEY_VIOLATION,
                () -> upsertTranscript(connection, UUID.randomUUID(), 1, "CHILD", "x", "FINAL", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSED", "IGNORED", "DEAD"})
    void inboxNeedsNextAttemptAtOnlyWhileReceived(String finishedStatus) throws SQLException {
        // BR5.6: RECEIVED는 폴러가 잡도록 next_attempt_at 필수, 처음부터 IGNORED 등 끝난 행은 비어도 된다(BR5.9)
        insertInbox(connection, UUID.randomUUID(), "RECEIVED", T0, randomTraceId());
        insertInbox(connection, UUID.randomUUID(), finishedStatus, null, null);
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertInbox(connection, UUID.randomUUID(), "RECEIVED", null, null));

        UUID eventId = UUID.randomUUID();
        insertInbox(connection, eventId, "RECEIVED", T0, null);
        expectSqlState(connection, UNIQUE_VIOLATION, () -> insertInbox(connection, eventId, "RECEIVED", T0, null));
        // 처리기 선점 UPDATE 모양(BR5.7): 상태 조건 + 도래 조건, 다시 비우면 검사 위반
        assertThat(update(connection,
                "update realtime.realtime_inbox_event set next_attempt_at = ? where event_id = ? and status = 'RECEIVED'"
                        + " and next_attempt_at <= ?",
                T0.plusSeconds(30), eventId, T0)).isEqualTo(1);
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_inbox_event set next_attempt_at = null where event_id = ?", eventId));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_inbox_event set source_service = 'REALTIME' where event_id = ?", eventId));
    }

    @Test
    void outboxTraceIdFormatAndPublishedPurgeAreEnforced() throws SQLException {
        insertOutbox(connection, UUID.randomUUID(), "PENDING", "{\"state\":\"CONNECTED\"}", null, randomTraceId());
        insertOutbox(connection, UUID.randomUUID(), "PUBLISHED", "{}", T0, null);
        // SessionRecordCaptured를 계정 삭제 참가자가 닫은 모양(BR5.14): FAILED + 비운 payload
        insertOutbox(connection, UUID.randomUUID(), "FAILED", "{}", T0, null);

        expectSqlState(connection, CHECK_VIOLATION, () -> insertOutbox(connection, UUID.randomUUID(), "PUBLISHED",
                "{\"state\":\"CONNECTED\"}", null, null));
        expectSqlState(connection, CHECK_VIOLATION, () -> insertOutbox(connection, UUID.randomUUID(), "PENDING", "{}", null,
                "0123456789ABCDEF0123456789ABCDEF"));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertInbox(connection, UUID.randomUUID(), "RECEIVED", T0, "z".repeat(32)));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertOutbox(connection, UUID.randomUUID(), "QUEUED", "{}", null, null));
    }

    @Test
    void deviceConnectionHasOneRowPerDeviceAndKnownStatesOnly() throws SQLException {
        UUID deviceId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        String upsert = "insert into realtime.realtime_device_connection (device_id, guardian_id, connection_state,"
                + " connection_id, connection_since, updated_at) values (?, ?, 'CONNECTED', ?, ?, ?)"
                + " on conflict (device_id) do update set connection_state = excluded.connection_state,"
                + " connection_id = excluded.connection_id, connection_since = excluded.connection_since,"
                + " updated_at = excluded.updated_at";
        // BR3.7: 첫 접속 upsert와 교체(행은 하나)
        assertThat(update(connection, upsert, deviceId, UUID.randomUUID(), connectionId, T0, T0)).isEqualTo(1);
        UUID replacement = UUID.randomUUID();
        assertThat(update(connection, upsert, deviceId, UUID.randomUUID(), replacement, T0, T0)).isEqualTo(1);
        assertThat(single(connection, "select count(*) from realtime.realtime_device_connection where device_id = ?",
                deviceId)).isEqualTo(1L);
        // 이후 기록은 connection_id 조건부: 옛 연결의 기록은 0행(BR4.8)
        assertThat(update(connection,
                "update realtime.realtime_device_connection set connection_state = 'DISCONNECTED', updated_at = ?"
                        + " where device_id = ? and connection_id = ?",
                T0, deviceId, connectionId)).isZero();

        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_device_connection set connection_state = 'ONLINE' where device_id = ?", deviceId));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_device_connection set recent_disconnects = -1 where device_id = ?", deviceId));
    }
}
