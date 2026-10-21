package kr.mujoong.realtime.session;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 현재 세션/사실 타입만 읽으며 revision이 저장 순서의 권위다. */
@Repository
public class SessionGuardrailFactsRepository {

    public static final String EVENT_TYPE = "SESSION_GUARDRAIL_FACTS";
    static final String LATEST = "select payload_json from realtime.realtime_session_event"
            + " where session_id = :sessionId and event_type = 'SESSION_GUARDRAIL_FACTS'"
            + " order by (payload_json -> 'revision') desc limit 2";

    private final NamedParameterJdbcTemplate jdbc;
    private final SessionEventRepository events;
    private final JsonCodec json = JsonCodec.shared();

    public SessionGuardrailFactsRepository(NamedParameterJdbcTemplate jdbc, SessionEventRepository events) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.events = Objects.requireNonNull(events, "events");
    }

    public Optional<SessionGuardrailFacts> latest(UUID sessionId) {
        MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "sessionId", sessionId);
        List<String> rows = jdbc.query(LATEST, params, (rs, index) -> rs.getString("payload_json"));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SessionGuardrailFacts latest = decode(rows.getFirst(), sessionId);
        if (rows.size() > 1) {
            SessionGuardrailFacts previous = decode(rows.get(1), sessionId);
            if (previous.revision() == latest.revision() && !previous.equals(latest)
                    || previous.revision() < latest.revision() - 1) {
                throw new IllegalStateException("세션 사실의 개정이 충돌한다");
            }
        }
        return Optional.of(latest);
    }

    /** 부르는 쪽이 같은 트랜잭션에서 세션 행 FOR UPDATE를 소유해야 한다. */
    public SessionGuardrailFacts append(ActivitySessionRow locked, SessionGuardrailFacts proposed) {
        Transactions.requireActive("SessionGuardrailFactsRepository.append");
        if (!locked.id().equals(proposed.sessionId()) || locked.statusVersion() != proposed.statusVersion()) {
            throw new IllegalStateException("세션 사실의 상태 버전이 일치하지 않는다");
        }
        Optional<SessionGuardrailFacts> previous = latest(locked.id());
        if (previous.isPresent() && previous.get().revision() == proposed.revision()) {
            if (!previous.get().equals(proposed)) {
                throw new IllegalStateException("동일 사실 개정의 payload가 다르다");
            }
            return previous.get();
        }
        long next = previous.map(value -> Math.addExact(value.revision(), 1)).orElse(1L);
        if (proposed.revision() != next) {
            throw new IllegalStateException("세션 사실의 다음 개정이 아니다");
        }
        events.insert(new SessionEvent(UUID.randomUUID(), locked.id(), EVENT_TYPE, null, json.write(proposed),
                proposed.recordedAt()));
        return proposed;
    }

    private SessionGuardrailFacts decode(String payload, UUID sessionId) {
        try {
            SessionGuardrailFacts facts = json.read(payload, SessionGuardrailFacts.class);
            if (!facts.sessionId().equals(sessionId)) {
                throw new IllegalArgumentException("세션 귀속 불일치");
            }
            return facts;
        } catch (RuntimeException e) {
            // 오류 문구/원문 payload는 조회나 일반 로그로 내보내지 않는다.
            throw new IllegalStateException("세션 사실의 형식이 올바르지 않다");
        }
    }
}
