-- 무중 참조 데이터 시드 003 — U4a 세션 승인 문구 6종 승인 버전 v2 (docs/units/u4a/design.md 8절 U4a-J20 · U4b 설계 8절 1 · U2-J17)
--
-- 실행: 001-reference.sql · 002-u2-reference.sql 뒤에 같은 자격(mujoong_owner)으로 한 번. 몇 번 실행해도 결과가 같다(멱등).
--   psql -X -v ON_ERROR_STOP=1 --single-transaction -f infra/seed/003-u4a-reference.sql
-- 트랜잭션 제어 · psql 메타 명령을 두지 않는다(JDBC로도 한 트랜잭션에 적용, SeedActivitySessionReferenceSqlIT).
--
-- 내용(합성 참조 데이터, 개인정보 없음)
--   - 002가 DRAFT · 음성 없음으로 만든 세션 문구 중 참여 확인 · 확인 질문 · 공백 안내에 쓰는 6종의 승인 버전 v2(APPROVED, 음성 있음):
--     INTRO_FREE_TALK · INTRO_PRACTICE · RE_PROMPT · DECLINE_ALTERNATIVE · SILENCE_WARNING · STOP_CONFIRM. 시작 패키지가 이 문구를
--     싣지 않으면 Realtime(U4b)은 참여 확인 · 공백 안내를 소리 없이 시계만 돌린다(U4b-R15).
--   - 문구는 승인 카탈로그 규칙(버전은 바꾸지 않고 다음 번호 행 · 음성 파일 이름 형식 · 계약 text 200자 이하)과 NFR2 시드 금지 표현
--     사전 검사를 통과한다(SeedActivitySessionReferenceSqlIT). 시작 안내는 AC6.3.1 순서(AI와 대화한다는 사실 · 오늘 활동 · 기록과 보호자 열람 ·
--     "무중아 그만할래")를 지킨 뒤 화면 설계 C.S-12.11.4의 질문으로 묻는다. 대안 제안은 AC6.3.4대로 다른 선택지를 한 번 묻고 재촉하지
--     않는다. 나머지 셋은 002 v1 글 그대로다.
--   - 음성은 tools/seed/gen-session-wav.mjs가 만든 합성음 자리다(J102와 같은 임시). 실제 녹음 · 사람 승인은 D 담당이고, 그때 v3 행을
--     더하고 v2를 RETIRED로 둔다(U2-J17).
--   - 002의 v1 DRAFT 행은 승인된 적이 없는 앞 버전이라 RETIRED로 둔다(카탈로그에 살아 있는 줄을 하나로). HELP_OFFER는 U5 몫이라 그대로다.
-- 고정 UUID: 버전 행 00000000-0000-4000-8000-0000000000c5 ~ …ca(종류 행은 002의 …a5 ~ …aa). 시각은 고정 값이다(R2).

INSERT INTO core.core_approved_content_version (id, content_id, version, content_text, audio_path, status, created_at)
SELECT seed.id, content.id, seed.version, seed.content_text, seed.audio_path, 'APPROVED', seed.created_at
FROM (
    VALUES
        ('00000000-0000-4000-8000-0000000000c5'::uuid, 'INTRO_FREE_TALK', 2,
            '안녕, 무중이야. 나는 사람이 아니라 AI야. 오늘은 나랑 자유롭게 이야기하는 시간이야. 우리가 나눈 말은 글로 남아서 너를 돌봐 주는 어른이 볼 수 있어. 그만하고 싶으면 "무중아 그만할래"라고 말해 줘. 오늘 나랑 이야기할래?',
            'intro-free-talk-v2.wav', TIMESTAMPTZ '2026-10-13 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000c6'::uuid, 'INTRO_PRACTICE', 2,
            '안녕, 무중이야. 나는 사람이 아니라 AI야. 오늘은 나랑 같이 말하기 연습을 해 보는 시간이야. 우리가 나눈 말은 글로 남아서 너를 돌봐 주는 어른이 볼 수 있어. 그만하고 싶으면 "무중아 그만할래"라고 말해 줘. 오늘 나랑 같이 연습해 볼래?',
            'intro-practice-v2.wav', TIMESTAMPTZ '2026-10-13 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000c7'::uuid, 'RE_PROMPT', 2,
            '무중이 여기 있어. 이야기하고 싶으면 말해 줘.', 're-prompt-v2.wav', TIMESTAMPTZ '2026-10-13 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000c8'::uuid, 'DECLINE_ALTERNATIVE', 2,
            '괜찮아. 그럼 오늘 있었던 일 하나만 이야기해 볼래? 하기 싫으면 안 해도 돼.', 'decline-alternative-v2.wav',
            TIMESTAMPTZ '2026-10-13 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000c9'::uuid, 'SILENCE_WARNING', 2,
            '무중이 기다리고 있어. 이야기하고 싶으면 말해 줘.', 'silence-warning-v2.wav', TIMESTAMPTZ '2026-10-13 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000ca'::uuid, 'STOP_CONFIRM', 2,
            '이제 그만할까?', 'stop-confirm-v2.wav', TIMESTAMPTZ '2026-10-13 00:00:00+00')
) AS seed (id, content_code, version, content_text, audio_path, created_at)
JOIN core.core_approved_content AS content ON content.content_code = seed.content_code
ON CONFLICT DO NOTHING;

-- 승인된 적 없는 v1 DRAFT 행을 RETIRED로(같은 종류에 v2 APPROVED가 있을 때만, 다시 돌려도 결과가 같다)
UPDATE core.core_approved_content_version AS old
   SET status = 'RETIRED'
  FROM core.core_approved_content AS content
 WHERE old.content_id = content.id
   AND content.content_code IN ('INTRO_FREE_TALK', 'INTRO_PRACTICE', 'RE_PROMPT', 'DECLINE_ALTERNATIVE', 'SILENCE_WARNING',
                                'STOP_CONFIRM')
   AND old.version = 1
   AND old.status = 'DRAFT'
   AND EXISTS (SELECT 1 FROM core.core_approved_content_version AS approved
                WHERE approved.content_id = content.id AND approved.version = 2 AND approved.status = 'APPROVED');
