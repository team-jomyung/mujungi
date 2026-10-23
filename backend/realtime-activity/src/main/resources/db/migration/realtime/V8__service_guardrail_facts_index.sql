-- 세션 사실 조회는 전체 사건을 재생하지 않고 현재 세션의 최신 개정 두 개만 읽는다.
-- 기존 행/열/제약을 바꾸지 않는 부분 색인이라 이전 앱의 쓰기와 호환된다.
CREATE INDEX realtime_session_guardrail_revision_idx
    ON realtime.realtime_session_event (session_id, (payload_json -> 'revision') DESC)
    WHERE event_type = 'SESSION_GUARDRAIL_FACTS';
