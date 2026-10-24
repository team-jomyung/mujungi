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
 * U8 승인 발화(APPROVED_SPEECH) 시험 행(시험 소스 전용, U8 Step 11 · 13)과 연습 대화 C안 고정 승인 발화(006).
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

    /** 006 · DevSeedRunner와 같은 연습 대화 C안 고정 승인 발화 6종(docs/practice-direct-ask.md 5장). */
    public static final List<SpeechRow> DIRECT_ASK_ROWS = List.of(
            new SpeechRow(SeedIds.CONTENT_VARIANT_OFFER, ApprovedContent.CODE_VARIANT_OFFER, SeedIds.VERSION_VARIANT_OFFER, 1,
                    SeedIds.VARIANT_OFFER_TEXT),
            new SpeechRow(SeedIds.CONTENT_RETRY_PROMPT, ApprovedContent.CODE_RETRY_PROMPT, SeedIds.VERSION_RETRY_PROMPT, 1,
                    SeedIds.RETRY_PROMPT_TEXT),
            new SpeechRow(SeedIds.CONTENT_INPUT_CONFIRM, ApprovedContent.CODE_INPUT_CONFIRM, SeedIds.VERSION_INPUT_CONFIRM, 1,
                    SeedIds.INPUT_CONFIRM_TEXT),
            new SpeechRow(SeedIds.CONTENT_SCENE_CLOSE, ApprovedContent.CODE_SCENE_CLOSE, SeedIds.VERSION_SCENE_CLOSE, 1,
                    SeedIds.SCENE_CLOSE_TEXT),
            new SpeechRow(SeedIds.CONTENT_WAIT_LEAD, ApprovedContent.CODE_WAIT_LEAD, SeedIds.VERSION_WAIT_LEAD, 1,
                    SeedIds.WAIT_LEAD_TEXT),
            new SpeechRow(SeedIds.CONTENT_ASK_DEFAULT, ApprovedContent.CODE_ASK_DEFAULT, SeedIds.VERSION_ASK_DEFAULT, 1,
                    SeedIds.ASK_DEFAULT_TEXT));

    private LiveContentFixtures() {
    }

    /**
     * 승인 발화 5종(004)과 C안 6종(006)을 mujoong_owner로 멱등하게 넣는다(넣기 · HELP_OFFER 종류 바꾸기만, 다른 시드 행 상태는 그대로). dev 시드와
     * 같은 행이라 이 뒤의 dev 시드는 승인 발화 행을 더 넣지 않는다.
     */
    public static void insertApprovedSpeech(MujoongPostgres postgres) {
        insertSpeech(postgres, SPEECH_ROWS, SeedIds.U8_SEED_TIME, true);
        insertSpeech(postgres, DIRECT_ASK_ROWS, SeedIds.DIRECT_ASK_SEED_TIME, false);
    }

    private static void insertSpeech(MujoongPostgres postgres, List<SpeechRow> rows, java.time.Instant seededAt, boolean helpOffer) {
        Timestamp at = Timestamp.from(seededAt);
        try (Connection owner = postgres.connect(Role.OWNER)) {
            try (PreparedStatement parent = owner.prepareStatement("""
                    insert into core.core_approved_content (id, content_code, content_type, created_at)
                    values (?, ?, ?, ?) on conflict do nothing
                    """)) {
                for (SpeechRow row : rows) {
                    parent.setObject(1, row.contentId());
                    parent.setString(2, row.code());
                    parent.setString(3, ApprovedContent.TYPE_APPROVED_SPEECH);
                    parent.setTimestamp(4, at);
                    parent.executeUpdate();
                }
            }
            if (helpOffer) {
                try (PreparedStatement type = owner.prepareStatement(
                        "update core.core_approved_content set content_type = ? where content_code = ? and content_type <> ?")) {
                    type.setString(1, ApprovedContent.TYPE_APPROVED_SPEECH);
                    type.setString(2, ApprovedContent.CODE_HELP_OFFER);
                    type.setString(3, ApprovedContent.TYPE_APPROVED_SPEECH);
                    type.executeUpdate();
                }
            }
            try (PreparedStatement version = owner.prepareStatement("""
                    insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path, status, created_at)
                    select ?, content.id, ?, ?, null, 'APPROVED', ? from core.core_approved_content content
                     where content.content_code = ?
                    on conflict do nothing
                    """)) {
                for (SpeechRow row : rows) {
                    version.setObject(1, row.versionId());
                    version.setInt(2, row.version());
                    version.setString(3, row.text());
                    version.setTimestamp(4, at);
                    version.setString(5, row.code());
                    version.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("승인 발화 행 넣기 실패", e);
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

    /** 006 SQL 전체(따로 띄운 DB에서만, C안 고정 승인 발화 6종). */
    public static void applyDirectAskSeed(MujoongPostgres postgres) {
        applySeed(postgres, "006-practice-direct-ask-reference.sql");
    }
}
