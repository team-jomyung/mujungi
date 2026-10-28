-- 사전 표현 연습의 안내 수신 · 실제 시도를 장면 안 코칭과 구분한다.
-- 현재 최댓값 V9 확인. V1~V9와 기존 facts 허용 값은 변경하지 않는 additive migration이다.
-- 이전 앱은 이 표를 읽거나 쓰지 않으므로 기존 장면 읽기 · 쓰기를 유지한다.
-- 새 앱은 기록 캡처 · 관리자 조회 때만 기존 facts와 합친다. Core reader를 Realtime writer보다 먼저 배포한다.
-- 시각은 앱 MujoongClock이 제공한다. 원문 · 대본 · 프롬프트 · 판정 입력을 담는 열은 없다.
-- 장면 삭제(보호자 삭제 포함)에 종속되므로 CASCADE로 같은 트랜잭션에서 제거한다.
CREATE TABLE realtime.realtime_practice_preparation (
    scene_run_id uuid PRIMARY KEY REFERENCES realtime.realtime_practice_scene_run(id) ON DELETE CASCADE,
    provided_at timestamptz,
    attempted_at timestamptz,
    CONSTRAINT realtime_practice_preparation_observed_ck CHECK (provided_at IS NOT NULL OR attempted_at IS NOT NULL)
);
