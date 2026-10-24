package kr.mujoong.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.common.db.ClockParams;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * dev 시드의 U8 승인 발화 문장 · 값(U8 Step 11 · BR1.1, DB 없이 도는 단위 시험). DB 결과는 {@code LiveContentDevSeedRunnerIT}가 본다.
 *
 * <p>DevSeedRunner가 승인 발화 5종을 004 · SeedIds와 같은 UUID · 판 · 글 · 종류(APPROVED_SPEECH) · 음성 없음 · U8 시드 시각으로 넣고,
 * HELP_OFFER 종류만 바꾸며, 002 v1 DRAFT를 RETIRED로 바꾸는 문장은 보내지 않는지 본다. 연습 대화 C안 고정 승인 발화 6종은 006과 같은 값 ·
 * 006 시각으로 004 뒤에 넣는다(docs/practice-direct-ask.md 5장).
 */
@Tag("live-content")
class DevSeedRunnerStatementsTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

    @Test
    void theDevSeedSendsTheSame004SpeechRowsAndNeverRetiresSeed002Drafts() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        int rows = new DevSeedRunner(jdbc, mock(PlatformTransactionManager.class)).seed();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), params.capture());
        List<String> statements = sql.getAllValues();
        assertThat(rows).isEqualTo(statements.size());
        assertThat(statements).noneMatch(statement -> statement.contains("RETIRED"));

        List<UUID> contentIds = new ArrayList<>();
        List<String> versionRows = new ArrayList<>();
        List<Object> speechTimes = new ArrayList<>();
        String typeUpdate = null;
        for (int i = 0; i < statements.size(); i++) {
            MapSqlParameterSource source = (MapSqlParameterSource) params.getAllValues().get(i);
            if (statements.get(i).contains("insert into core.core_approved_content (")
                    && ApprovedContent.TYPE_APPROVED_SPEECH.equals(source.getValue("type"))) {
                contentIds.add((UUID) source.getValue("id"));
                assertThat(source.getValue("now")).as("시드 시각").isNotNull();
                speechTimes.add(source.getValue("now"));
            }
            if (statements.get(i).contains("insert into core.core_approved_content_version") && source.hasValue("version")) {
                versionRows.add(source.getValue("code") + "|" + source.getValue("versionId") + "|" + source.getValue("version")
                        + "|" + source.getValue("text"));
                assertThat(statements.get(i)).contains("null, 'APPROVED'");
            }
            if (statements.get(i).contains("update core.core_approved_content")) {
                typeUpdate = source.getValue("code") + "|" + source.getValue("type");
            }
        }
        assertThat(contentIds).containsExactly(SeedIds.CONTENT_HELP_OFFER, SeedIds.CONTENT_D_REST_ACK_START,
                SeedIds.CONTENT_D_REST_ACK, SeedIds.CONTENT_COACH_SWITCH, SeedIds.CONTENT_PEER_RETURN,
                SeedIds.CONTENT_VARIANT_OFFER, SeedIds.CONTENT_RETRY_PROMPT, SeedIds.CONTENT_INPUT_CONFIRM,
                SeedIds.CONTENT_SCENE_CLOSE, SeedIds.CONTENT_WAIT_LEAD, SeedIds.CONTENT_ASK_DEFAULT);
        assertThat(versionRows).containsExactly(
                "HELP_OFFER|" + SeedIds.VERSION_HELP_OFFER_V2 + "|2|" + SeedIds.HELP_OFFER_TEXT,
                "D_REST_ACK_START|" + SeedIds.VERSION_D_REST_ACK_START + "|1|" + SeedIds.D_REST_ACK_START_TEXT,
                "D_REST_ACK|" + SeedIds.VERSION_D_REST_ACK + "|1|" + SeedIds.D_REST_ACK_TEXT,
                "COACH_SWITCH|" + SeedIds.VERSION_COACH_SWITCH + "|1|" + SeedIds.COACH_SWITCH_TEXT,
                "PEER_RETURN|" + SeedIds.VERSION_PEER_RETURN + "|1|" + SeedIds.PEER_RETURN_TEXT,
                "VARIANT_OFFER|" + SeedIds.VERSION_VARIANT_OFFER + "|1|" + SeedIds.VARIANT_OFFER_TEXT,
                "RETRY_PROMPT|" + SeedIds.VERSION_RETRY_PROMPT + "|1|" + SeedIds.RETRY_PROMPT_TEXT,
                "INPUT_CONFIRM|" + SeedIds.VERSION_INPUT_CONFIRM + "|1|" + SeedIds.INPUT_CONFIRM_TEXT,
                "SCENE_CLOSE|" + SeedIds.VERSION_SCENE_CLOSE + "|1|" + SeedIds.SCENE_CLOSE_TEXT,
                "WAIT_LEAD|" + SeedIds.VERSION_WAIT_LEAD + "|1|" + SeedIds.WAIT_LEAD_TEXT,
                "ASK_DEFAULT|" + SeedIds.VERSION_ASK_DEFAULT + "|1|" + SeedIds.ASK_DEFAULT_TEXT);
        assertThat(typeUpdate).isEqualTo("HELP_OFFER|APPROVED_SPEECH");
        assertThat(speechTimes.subList(0, 5)).as("004 행은 U8 시드 시각")
                .containsOnly(ClockParams.timestamptz(SeedIds.U8_SEED_TIME));
        assertThat(speechTimes.subList(5, 11)).as("006 행은 C안 시드 시각")
                .containsOnly(ClockParams.timestamptz(SeedIds.DIRECT_ASK_SEED_TIME));
    }

    @Test
    void theSpeechTextsMatchTheCanonicalWordingAndTheContractLength() {
        assertThat(DevSeedRunner.SPEECH_SEEDS).extracting(DevSeedRunner.SpeechSeed::text).containsExactly(
                "무중이가 조금 도와줄까?", "잠깐 쉬고 싶구나. 괜찮아. 준비되면 말해 줘.", "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.",
                "잠깐, 무중이 도와줄게", "다시 친구 역할로 돌아갈게");
        assertThat(DevSeedRunner.SPEECH_SEEDS).allMatch(seed -> seed.text().codePointCount(0, seed.text().length()) <= 200);
        assertThat(DevSeedRunner.SPEECH_SEEDS).extracting(DevSeedRunner.SpeechSeed::code).doesNotContain("D_REPROMPT");
    }

    @Test
    void theDirectAskTextsAreTheDesignTableWordingWithOnlyPeriodCommaAndQuestionMark() {
        assertThat(DevSeedRunner.DIRECT_ASK_SEEDS).extracting(DevSeedRunner.SpeechSeed::code).containsExactly("VARIANT_OFFER",
                "RETRY_PROMPT", "INPUT_CONFIRM", "SCENE_CLOSE", "WAIT_LEAD", "ASK_DEFAULT");
        assertThat(DevSeedRunner.DIRECT_ASK_SEEDS).extracting(DevSeedRunner.SpeechSeed::text).containsExactly(
                "비슷한 이야기로 한 번 더 해 볼까?", "네 말로 한 번 말해 볼래?", "잘 못 들었어. 한 번 더 말해 줄래?",
                "알겠어. 이 이야기는 여기까지 할게.", "천천히 생각해도 돼.", "너라면 친구한테 뭐라고 말할래?");
        assertThat(DevSeedRunner.DIRECT_ASK_SEEDS).allSatisfy(seed -> {
            assertThat(seed.version()).isEqualTo(1);
            assertThat(seed.text().codePoints().allMatch(c -> Character.isLetterOrDigit(c) || " .,?".indexOf(c) >= 0))
                    .as(seed.code() + " 원문 대조용 문장부호").isTrue();
        });
        assertThat(DevSeedRunner.DIRECT_ASK_SEEDS).extracting(DevSeedRunner.SpeechSeed::code)
                .doesNotContainAnyElementsOf(DevSeedRunner.SPEECH_SEEDS.stream().map(DevSeedRunner.SpeechSeed::code).toList());
    }

    @Test
    void aSecondRunThatInsertsNothingReportsZeroRows() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

        assertThat(new DevSeedRunner(jdbc, mock(PlatformTransactionManager.class)).seed()).isZero();
    }
}
