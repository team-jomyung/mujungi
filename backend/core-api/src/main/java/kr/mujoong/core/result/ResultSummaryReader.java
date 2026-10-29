package kr.mujoong.core.result;

import static kr.mujoong.core.result.ResultData.text;
import static kr.mujoong.core.result.ResultData.transcript;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 26 기록 목록 · 28 리포트 활동 고르기의 한 줄 요약.
 *
 * <p>정리가 끝난(DONE) 현재 결과의 결과 문장(자유대화 1~2문장, 연습은 목표마다)을 저장 순서대로 이어 붙인다.
 * 리포트 6항목과 같은 글이고 새로 만들지 않는다. 결과가 없거나 정리 중 · 실패 · 무효 · 문장 없음이면 요약이 없다.
 * 동의 철회로 끝난 활동은 부르는 쪽이 빼고 넘긴다(리포트 {@code ReportInput#contentAllowed}와 같은 규칙).
 */
@Component
public class ResultSummaryReader {

    private final NamedParameterJdbcTemplate jdbc;

    public ResultSummaryReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 세션별 요약. 요약이 없는 세션은 지도에 없다. */
    public Map<UUID, String> summaries(UUID guardianId, Collection<UUID> sessionIds) {
        Map<UUID, String> summaries = new HashMap<>();
        if (sessionIds.isEmpty()) {
            return summaries;
        }
        var params = new MapSqlParameterSource("guardian", guardianId).addValue("sessions", sessionIds);
        jdbc.query("select s.session_id, r.sentences_json from core.core_session_result_state s"
                + " join core.core_activity_result r on r.id = s.current_result_id"
                + " where s.guardian_id = :guardian and s.session_id in (:sessions)"
                + " and s.job_status = 'DONE' and r.invalidated_at is null", params, row -> {
                    String summary = join(row.getString("sentences_json"));
                    if (!summary.isEmpty()) {
                        summaries.put(row.getObject("session_id", UUID.class), summary);
                    }
                });
        return summaries;
    }

    private static String join(String sentencesJson) {
        return String.join(" ", transcript(sentencesJson).stream()
                .map(sentence -> text(sentence, "text").strip())
                .filter(sentence -> !sentence.isEmpty())
                .toList());
    }
}
