package kr.mujoong.core.activity;

import static kr.mujoong.core.common.db.ClockParams.text;
import static kr.mujoong.core.common.db.ClockParams.uuid;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 08 자유대화 배경 {@code core.core_activity_context} 데이터 접근 (U4a ActivityCoordinator · AC3.1.2 · AC3.1.7 · NFR3 · U4a-J1).
 *
 * <p>원문은 결과 구조화(U6)에서만 읽는다. 대화 전 참고에는 시작 요청에서 유도한 원문 없는 주제 코드만 쓰며 이 저장소의 원문을 Realtime에 보내지 않는다.
 * 네 칸이 모두 비면 행을 쓰지 않는다. 트랜잭션은 호출자가 연다(시작 서비스가 사본 행과 한 트랜잭션에 쓴다).
 */
@Repository
public class ActivityContextRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ActivityContextRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 배경 한 행을 쓴다. 네 칸이 모두 비었으면 아무것도 하지 않는다.
     *
     * @return 썼으면 true
     */
    public boolean insert(UUID sessionId, UUID guardianId, UUID childId, StartActivityService.Context context, Instant now) {
        if (context == null || isEmpty(context)) {
            return false;
        }
        MapSqlParameterSource params = ClockParams.at(Objects.requireNonNull(now, "now"));
        uuid(params, "id", UUID.randomUUID());
        uuid(params, "sessionId", Objects.requireNonNull(sessionId, "sessionId"));
        uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        uuid(params, "childId", Objects.requireNonNull(childId, "childId"));
        text(params, "knownContent", blankToNull(context.knownContent()));
        text(params, "situationType", context.situationType());
        text(params, "sourceType", context.sourceType());
        text(params, "question", blankToNull(context.question()));
        return jdbc.update("""
                insert into core.core_activity_context (id, session_id, guardian_id, child_id, known_content, situation_type,
                    source_type, question, created_at)
                values (:id, :sessionId, :guardianId, :childId, :knownContent, :situationType, :sourceType, :question, :now)
                """, params) == 1;
    }

    /** 세션의 배경(U6 결과 구조화가 읽는다). */
    public Optional<StoredContext> findBySession(UUID sessionId) {
        List<StoredContext> rows = jdbc.query("""
                select session_id, known_content, situation_type, source_type, question
                  from core.core_activity_context where session_id = :sessionId
                """, uuid(new MapSqlParameterSource(), "sessionId", Objects.requireNonNull(sessionId, "sessionId")),
                (rs, rowNum) -> new StoredContext(ClockParams.uuid(rs, "session_id"), rs.getString("known_content"),
                        rs.getString("situation_type"), rs.getString("source_type"), rs.getString("question")));
        return rows.stream().findFirst();
    }

    /** 보호자의 배경을 모두 지운다(계정 삭제 참가자 전용, 호출자 트랜잭션). */
    public int deleteAllByGuardian(UUID guardianId) {
        return jdbc.update("delete from core.core_activity_context where guardian_id = :guardianId",
                uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")));
    }

    static boolean isEmpty(StartActivityService.Context context) {
        return blankToNull(context.knownContent()) == null && context.situationType() == null
                && context.sourceType() == null && blankToNull(context.question()) == null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 저장된 배경. 원문은 문자열 표현에 내지 않는다. */
    public record StoredContext(UUID sessionId, String knownContent, String situationType, String sourceType, String question) {

        @Override
        public String toString() {
            return "StoredContext[sessionId=" + sessionId + "]";
        }
    }
}
