package kr.mujoong.core.db;

import static kr.mujoong.core.db.CoreDbFixtures.T0;
import static kr.mujoong.core.db.CoreDbFixtures.query;
import static kr.mujoong.core.db.CoreDbFixtures.single;
import static kr.mujoong.core.db.CoreDbFixtures.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 참조 데이터 시드 {@code infra/seed/001-reference.sql} 통합 시험(U1 3.5 Step 14 · R4-15 · J114 · J117 · J102).
 * mujoong_owner로 한 트랜잭션에 두 번 적용하고(멱등), 끝에 되돌려 공용 인스턴스에 남기지 않는다.
 *
 * <p>증명: 두 번 적용 → 정책 1 · 승인 문구 2(R4-15), 현재 정책 버전 '1.0'(J114), 승인 음성 파일이 저장소에 있다(J102), 고정 UUID(J117),
 * 다른 id로 먼저 들어온 같은 content_code에도 실패하지 않음, 앱 역할 realtime_app으로는 적용할 수 없음(BR8.1). 3.6 수정 분량: 시드를 다시
 * 적용해도 운영자가 나중에 승인한 버전 · 은퇴 처리를 되돌리지 않는다(ON CONFLICT DO NOTHING — 배포마다 다시 돌려도 안전).
 */
@Tag("u1")
@Tag("db")
class SeedReferenceSqlIT {

    private static final UUID CLOSING_VERSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000041");
    private static final UUID WAITING_FILLER_VERSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000042");
    private static final UUID POLICY_VERSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000043");

    /** J114: 현재 정책 버전 = CONSENT_POLICY의 최고 APPROVED 버전 content_text. */
    private static final String CURRENT_POLICY_VERSION_SQL =
            "select v.content_text from core.core_approved_content c"
                    + " join core.core_approved_content_version v on v.content_id = c.id"
                    + " where c.content_code = 'CONSENT_POLICY' and v.status = 'APPROVED'"
                    + " order by v.version desc limit 1";

    private static MujoongPostgres postgres;
    private static String seedSql;
    private Connection owner;

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = MujoongPostgres.sharedOrAbort();
        seedSql = Files.readString(postgres.repoRoot().resolve("infra/seed/001-reference.sql"), StandardCharsets.UTF_8);
    }

    @BeforeEach
    void openTransaction() throws SQLException {
        owner = postgres.connect(Role.OWNER);
        owner.setAutoCommit(false);
        // 다른 시험 클래스(dev 시드 등)가 공용 인스턴스에 커밋한 같은 코드 행이 있어도 이 트랜잭션 안에서는 빈 상태에서 시작한다
        update(owner,
                "delete from core.core_approved_content_version where content_id in (select id from core.core_approved_content"
                        + " where content_code in ('CONSENT_POLICY', 'CLOSING', 'WAITING_FILLER'))");
        update(owner,
                "delete from core.core_approved_content where content_code in ('CONSENT_POLICY', 'CLOSING', 'WAITING_FILLER')");
    }

    @AfterEach
    void rollback() throws SQLException {
        owner.rollback();
        owner.close();
    }

    @Test
    void applyingTheSeedTwiceLeavesOnePolicyAndTwoApprovedPhrases() throws SQLException {
        applySeed(owner);
        List<List<Object>> afterFirst = seededRows(owner);
        applySeed(owner);
        List<List<Object>> afterSecond = seededRows(owner);

        assertThat(afterSecond).isEqualTo(afterFirst);
        assertThat(single(owner,
                "select count(*) from core.core_approved_content where content_code = 'CONSENT_POLICY'")).isEqualTo(1L);
        assertThat(single(owner,
                "select count(*) from core.core_approved_content where content_code in ('CLOSING', 'WAITING_FILLER')"))
                .isEqualTo(2L);
        assertThat(single(owner,
                "select count(*) from core.core_approved_content_version v join core.core_approved_content c"
                        + " on c.id = v.content_id where c.content_code in ('CONSENT_POLICY', 'CLOSING', 'WAITING_FILLER')"
                        + " and v.status = 'APPROVED'"))
                .isEqualTo(3L);
        assertThat(single(owner, CURRENT_POLICY_VERSION_SQL)).isEqualTo("1.0");
    }

    @Test
    void seededPhrasesMatchTheContractAndPointAtCommittedAudioFiles() throws SQLException, IOException {
        applySeed(owner);
        assertThat(seededRows(owner)).containsExactly(
                List.of("CLOSING", "APPROVED_AUDIO", CLOSING_VERSION_ID, 1, "오늘 이야기 즐거웠어. 다음에 또 만나!",
                        "closing-v1.wav"),
                List.of("CONSENT_POLICY", "POLICY_VERSION", POLICY_VERSION_ID, 1, "1.0", "-"),
                List.of("WAITING_FILLER", "APPROVED_AUDIO", WAITING_FILLER_VERSION_ID, 1, "음, 잠깐만 생각해 볼게.",
                        "waiting-filler-v1.wav"));

        // audio_path는 /media/approved/ 아래 파일 이름이고 그 원본(infra/seed-audio)이 저장소에 있다(J102 · 계약 ApprovedAudioUrl)
        Pattern approvedAudioUrl = Pattern.compile("^/media/approved/[A-Za-z0-9._-]+\\.wav$");
        for (String fileName : List.of("closing-v1.wav", "waiting-filler-v1.wav")) {
            assertThat("/media/approved/" + fileName).matches(approvedAudioUrl);
            Path wav = postgres.repoRoot().resolve("infra/seed-audio").resolve(fileName);
            assertThat(wav).isRegularFile();
            assertThat(Files.size(wav)).isGreaterThan(44L);
        }
    }

    @Test
    void anExistingContentRowWithTheSameCodeButAnotherIdDoesNotBreakTheSeed() throws SQLException {
        // dev 시드나 손 작업이 다른 id로 CLOSING을 먼저 넣었어도 시드는 실패하지 않고 그 행에 버전을 붙인다
        UUID otherId = UUID.randomUUID();
        update(owner,
                "insert into core.core_approved_content (id, content_code, content_type, created_at)"
                        + " values (?, 'CLOSING', 'APPROVED_AUDIO', ?)",
                otherId, T0);
        applySeed(owner);
        applySeed(owner);
        assertThat(single(owner, "select count(*) from core.core_approved_content where content_code = 'CLOSING'"))
                .isEqualTo(1L);
        assertThat(single(owner, "select content_id from core.core_approved_content_version where id = ?",
                CLOSING_VERSION_ID)).isEqualTo(otherId);
    }

    @Test
    void theSeedHasNoTransactionControlOrPsqlMetaCommandsAndNeedsTheCoreSideRole() throws SQLException {
        // 실행기(psql --single-transaction · JDBC)가 트랜잭션을 정한다
        List<String> statements = seedSql.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .toList();
        assertThat(statements).noneMatch(line -> line.startsWith("\\"));
        assertThat(statements).noneMatch(line -> line.matches("(?i)^(begin|commit|rollback|start transaction)\\b.*"));

        // realtime_app은 core 스키마에 권한이 없다(BR8.1): 다른 앱 역할로는 적용되지 않는다
        try (Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            realtime.setAutoCommit(false);
            assertThatThrownBy(() -> applySeed(realtime))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            realtime.rollback();
        }
    }

    @Test
    void reapplyingTheSeedKeepsLaterApprovalsAndRetirementsInPlace() throws SQLException {
        applySeed(owner);
        UUID closingContent = (UUID) single(owner,
                "select id from core.core_approved_content where content_code = 'CLOSING'");
        // 운영자가 CLOSING v2를 승인하고 시드의 v1을 은퇴시켰다. 정책은 1.1로 올렸다
        UUID closingV2 = UUID.randomUUID();
        update(owner, "insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path,"
                + " status, created_at) values (?, ?, 2, '다음에 또 이야기하자!', 'closing-v2.wav', 'APPROVED', ?)",
                closingV2, closingContent, T0);
        update(owner, "update core.core_approved_content_version set status = 'RETIRED' where id = ?", CLOSING_VERSION_ID);
        update(owner, "insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path,"
                + " status, created_at) select ?, content_id, 2, '1.1', null, 'APPROVED', ?"
                + " from core.core_approved_content_version where id = ?", UUID.randomUUID(), T0, POLICY_VERSION_ID);
        List<List<Object>> beforeReapply = seededRows(owner);

        applySeed(owner);

        assertThat(seededRows(owner)).as("시드 재적용은 행을 더하거나 고치지 않는다").isEqualTo(beforeReapply);
        assertThat(single(owner, "select status from core.core_approved_content_version where id = ?", CLOSING_VERSION_ID))
                .as("은퇴시킨 시드 버전이 되살아나지 않는다").isEqualTo("RETIRED");
        assertThat(query(owner,
                "select v.id, v.version from core.core_approved_content_version v join core.core_approved_content c"
                        + " on c.id = v.content_id where c.content_code = 'CLOSING' and v.status = 'APPROVED'"
                        + " order by v.version desc limit 1"))
                .as("현재 CLOSING은 운영자의 v2").containsExactly(List.of(closingV2, 2));
        assertThat(single(owner, CURRENT_POLICY_VERSION_SQL)).as("정책 버전도 시드 값 '1.0'으로 돌아가지 않는다").isEqualTo("1.1");
    }

    private static void applySeed(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(seedSql);
        }
    }

    private static List<List<Object>> seededRows(Connection connection) throws SQLException {
        return query(connection,
                "select c.content_code, c.content_type, v.id, v.version, v.content_text, coalesce(v.audio_path, '-')"
                        + " from core.core_approved_content c join core.core_approved_content_version v on v.content_id = c.id"
                        + " where c.content_code in ('CONSENT_POLICY', 'CLOSING', 'WAITING_FILLER')"
                        + " order by c.content_code, v.version");
    }
}
