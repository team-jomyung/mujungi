package kr.mujoong.core.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.identity.SeedIds;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;

/**
 * U8 승인 발화(APPROVED_SPEECH) 시험 행(시험 소스 전용, U8 Step 11 · 13).
 *
 * <p>공용 시험 DB에서는 {@link #insertApprovedSpeech}만 쓴다: dev 시드와 같은 행을 넣고 HELP_OFFER 종류만 바꿀 뿐, 002의 v1 DRAFT 상태는
 * 건드리지 않는다(U2SchemaIT가 그 DRAFT 수를 센다). 004 SQL 전체({@link #applyU8Seed})는 따로 띄운 DB에서만 적용한다.
 */
public final class LiveContentFixtures {

    /** 시드 행 하나(SeedIds와 같은 값). */
    public record SpeechRow(UUID contentId, String code, UUID versionId, int version, String text) {
    }

    /** 004 · DevSeedRunner와 같은 승인 발화 5종. */
    public static final List<SpeechRow> SPEECH_ROWS = List.of(
            new SpeechRow(SeedIds.CONTENT_HELP_OFFER, ApprovedContent.CODE_HELP_OFFER, SeedIds.VERSION_HELP_OFFER_V2, 2,
                    SeedIds.HELP_OFFER_TEXT),
            new SpeechRow(SeedIds.CONTENT_D_REST_ACK_START, ApprovedContent.CODE_D_REST_ACK_START,
                    SeedIds.VERSION_D_REST_ACK_START, 1, SeedIds.D_REST_ACK_START_TEXT),
            new SpeechRow(SeedIds.CONTENT_D_REST_ACK, ApprovedContent.CODE_D_REST_ACK, SeedIds.VERSION_D_REST_ACK, 1,
                    SeedIds.D_REST_ACK_TEXT),
            new SpeechRow(SeedIds.CONTENT_COACH_SWITCH, ApprovedContent.CODE_COACH_SWITCH, SeedIds.VERSION_COACH_SWITCH, 1,
                    SeedIds.COACH_SWITCH_TEXT),
            new SpeechRow(SeedIds.CONTENT_PEER_RETURN, ApprovedContent.CODE_PEER_RETURN, SeedIds.VERSION_PEER_RETURN, 1,
                    SeedIds.PEER_RETURN_TEXT));

    private LiveContentFixtures() {
    }

    /** 승인 발화 5종을 mujoong_owner로 멱등하게 넣는다(넣기 · HELP_OFFER 종류 바꾸기만, 다른 시드 행 상태는 그대로). */
    public static void insertApprovedSpeech(MujoongPostgres postgres) {
        Timestamp at = Timestamp.from(SeedIds.U8_SEED_TIME);
        try (Connection owner = postgres.connect(Role.OWNER)) {
            try (PreparedStatement parent = owner.prepareStatement("""
                    insert into core.core_approved_content (id, content_code, content_type, created_at)
                    values (?, ?, ?, ?) on conflict do nothing
                    """)) {
                for (SpeechRow row : SPEECH_ROWS) {
                    parent.setObject(1, row.contentId());
                    parent.setString(2, row.code());
                    parent.setString(3, ApprovedContent.TYPE_APPROVED_SPEECH);
                    parent.setTimestamp(4, at);
                    parent.executeUpdate();
                }
            }
            try (PreparedStatement type = owner.prepareStatement(
                    "update core.core_approved_content set content_type = ? where content_code = ? and content_type <> ?")) {
                type.setString(1, ApprovedContent.TYPE_APPROVED_SPEECH);
                type.setString(2, ApprovedContent.CODE_HELP_OFFER);
                type.setString(3, ApprovedContent.TYPE_APPROVED_SPEECH);
                type.executeUpdate();
            }
            try (PreparedStatement version = owner.prepareStatement("""
                    insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path, status, created_at)
                    select ?, content.id, ?, ?, null, 'APPROVED', ? from core.core_approved_content content
                     where content.content_code = ?
                    on conflict do nothing
                    """)) {
                for (SpeechRow row : SPEECH_ROWS) {
                    version.setObject(1, row.versionId());
                    version.setInt(2, row.version());
                    version.setString(3, row.text());
                    version.setTimestamp(4, at);
                    version.setString(5, row.code());
                    version.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("U8 승인 발화 행 넣기 실패", e);
        }
    }

    /** {@code infra/seed/<파일>}을 mujoong_owner로 적용한다(따로 띄운 DB에서만). */
    public static void applySeed(MujoongPostgres postgres, String seedFile) {
        try (Connection owner = postgres.connect(Role.OWNER); Statement statement = owner.createStatement()) {
            statement.execute(Files.readString(postgres.repoRoot().resolve("infra/seed/" + seedFile), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("참조 시드 적용 실패: " + seedFile, e);
        }
    }

    /** 004 SQL 전체(따로 띄운 DB에서만, 002 v1 DRAFT를 RETIRED로 바꾼다). */
    public static void applyU8Seed(MujoongPostgres postgres) {
        applySeed(postgres, "004-u8-reference.sql");
    }
}
