package kr.mujoong.realtime.practicelegacy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.session.*;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.testsupport.db.MujoongPostgres;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;

/** U5b 전용 폐기 DB. 배포와 같은 V1~V4 마이그레이션으로 저장 제약을 시험한다. */
final class PracticeDb {
    static final MujoongPostgres DB = MujoongPostgres.builder().start();
    static final JdbcTemplate JDBC = new JdbcTemplate(DB.dataSource(MujoongPostgres.Role.REALTIME_APP));
    static final TransactionTemplate TX = new TransactionTemplate(new DataSourceTransactionManager(DB.dataSource(MujoongPostgres.Role.REALTIME_APP)));
    final JdbcPracticeRepository repository = new JdbcPracticeRepository(JDBC, TX);
    final UUID session = UUID.randomUUID();
    final UUID guardian = UUID.randomUUID();
    final SessionGoal goal = new SessionGoal(UUID.randomUUID(), session, guardian, UUID.randomUUID(),
            1, "차례", "turn", 1, "고정 기준", GoalStatus.PENDING, null, 0, null);
    final PracticeAttempt attempt = new PracticeAttempt(UUID.randomUUID(), goal.id(), AttemptType.ORIGINAL,
            AttemptState.EXPOSED, UUID.randomUUID(), null, PracticeModelTest.T, null);
    PracticeDb() {
        this(true);
    }
    PracticeDb(boolean withAttempt) {
        Instant t = PracticeModelTest.T;
        JDBC.update("insert into realtime.realtime_activity_session (id,guardian_id,child_id,device_id,activity_type,"
                + "status,status_version,start_idempotency_key,consent_checked_at,consent_snapshot_json,policy_version,"
                + "data_collection_allowed,transcript_storage_allowed,created_at,updated_at)"
                + " values (?,?,?,?,'PRACTICE','ACTIVE',1,?,?,'{\"policyVersion\":\"1\",\"voiceAndText\":true,"
                + "\"guardianView\":true,\"reportSharing\":false,\"rawAudio\":false}'::jsonb,'1',true,true,?,?)",
                session, guardian, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(),
                java.sql.Timestamp.from(t), java.sql.Timestamp.from(t), java.sql.Timestamp.from(t));
        repository.initialize(List.of(goal)); if (withAttempt) repository.saveAttempt(attempt);
    }
    PracticeResponse pending() { return repository.accept(PracticeModelTest.pending(attempt, ResponseType.FIRST)); }
    Evaluation evaluation(PracticeResponse p) {
        return new Evaluation(UUID.randomUUID(), p.id(), Label.functional, null, p.utteranceSeq(), false,
                p.work().attemptsStarted(), "fixture", "fixture");
    }
}
