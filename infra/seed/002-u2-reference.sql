-- 무중 참조 데이터 시드 002 — U2 약관 본문 4종 · 세션 승인 문구 자리 7종 (docs/units/u2/design.md 3절 · R7 · U2-J7 · U2-J17)
--
-- 실행: 001-reference.sql 뒤에 같은 자격(mujoong_owner)으로 한 번. 몇 번 실행해도 결과가 같다(멱등, ON CONFLICT DO NOTHING).
--   psql -X -v ON_ERROR_STOP=1 --single-transaction -f infra/seed/002-u2-reference.sql
-- 트랜잭션 제어 · psql 메타 명령을 두지 않는다(JDBC로도 한 트랜잭션에 적용, U2 시험).
--
-- 내용(합성 참조 데이터, 개인정보 없음)
--   - POLICY_TERMS_<동의 항목> 4종(APPROVED): 02-1 약관 본문. content_text는 {"title", "sections":[{"heading","body"}]} JSON이고
--     글은 화면 설계 v1.5j 문구표 C.S-02-1.35 · 41 · 42 · 43 원문 + mockups F2 '생성형 AI 안내'(동의하지 않으면 바로 앞).
--   - INTRO_FREE_TALK · INTRO_PRACTICE · RE_PROMPT · DECLINE_ALTERNATIVE · SILENCE_WARNING · STOP_CONFIRM · HELP_OFFER(DRAFT, 음성 없음):
--     계약 5절 approvedContent 키의 자리만 만든다. 글은 interaction-spec 5절에서 옮긴 임시 글이고 승인(NFR1 · NFR2 검사 · 음성 파일)은
--     U4 · U5와 사람이 한다. CLOSING · WAITING_FILLER · CONSENT_POLICY는 001이 넣는다.
-- 고정 UUID: 종류 행 00000000-0000-4000-8000-0000000000a1 ~ …ab, 버전 행 …b1 ~ …bb. 시각은 고정 값이다(R2).

INSERT INTO core.core_approved_content (id, content_code, content_type, created_at)
VALUES
    ('00000000-0000-4000-8000-0000000000a1'::uuid, 'POLICY_TERMS_VOICE_AND_TEXT', 'POLICY_TERMS', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000a2'::uuid, 'POLICY_TERMS_GUARDIAN_VIEW', 'POLICY_TERMS', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000a3'::uuid, 'POLICY_TERMS_REPORT_SHARING', 'POLICY_TERMS', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000a4'::uuid, 'POLICY_TERMS_RAW_AUDIO', 'POLICY_TERMS', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000a5'::uuid, 'INTRO_FREE_TALK', 'APPROVED_AUDIO', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000a6'::uuid, 'INTRO_PRACTICE', 'APPROVED_AUDIO', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000a7'::uuid, 'RE_PROMPT', 'APPROVED_AUDIO', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000a8'::uuid, 'DECLINE_ALTERNATIVE', 'APPROVED_AUDIO', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000a9'::uuid, 'SILENCE_WARNING', 'APPROVED_AUDIO', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000aa'::uuid, 'STOP_CONFIRM', 'APPROVED_AUDIO', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000000ab'::uuid, 'HELP_OFFER', 'APPROVED_AUDIO', TIMESTAMPTZ '2026-10-12 00:00:00+00')
ON CONFLICT DO NOTHING;

-- 버전 행은 content_code로 종류 행을 찾아 붙인다(001과 같은 규칙). 같은 (content_id, version)이 이미 있으면 아무것도 하지 않는다.
INSERT INTO core.core_approved_content_version (id, content_id, version, content_text, audio_path, status, created_at)
SELECT seed.id, content.id, seed.version, seed.content_text, NULL, seed.status, seed.created_at
FROM (
    VALUES
        ('00000000-0000-4000-8000-0000000000b1'::uuid, 'POLICY_TERMS_VOICE_AND_TEXT', 1,
            '{"title":"음성 수집 · 글로 옮겨 저장","sections":[{"heading":"수집하는 것","body":"아이가 기기에서 말한 음성"},{"heading":"이용 목적","body":"대화를 진행하고, 음성을 글로 옮겨 기록으로 저장"},{"heading":"보관","body":"글 기록과 음성 원본 모두 계정을 삭제할 때까지"},{"heading":"생성형 AI 안내","body":"무중은 생성형 AI가 아이와 대화하는 서비스예요. 아이가 듣는 말은 사람이 아니라 AI가 만들어요."},{"heading":"동의하지 않으면","body":"자유대화 · 연습을 시작할 수 없어요"}]}',
            'APPROVED', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000b2'::uuid, 'POLICY_TERMS_GUARDIAN_VIEW', 1,
            '{"title":"보호자 열람","sections":[{"heading":"보는 사람","body":"이 계정으로 로그인한 보호자"},{"heading":"볼 수 있는 것","body":"활동 기록 · 대화 글 · 연습 결과 · 생활 팁"},{"heading":"이용 목적","body":"아이의 대화와 연습을 보호자가 확인"},{"heading":"생성형 AI 안내","body":"무중은 생성형 AI가 아이와 대화하는 서비스예요. 아이가 듣는 말은 사람이 아니라 AI가 만들어요."},{"heading":"동의하지 않으면","body":"자유대화 · 연습을 시작할 수 없어요"}]}',
            'APPROVED', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000b3'::uuid, 'POLICY_TERMS_REPORT_SHARING', 1,
            '{"title":"리포트 생성 · 외부 전달","sections":[{"heading":"만드는 것","body":"고른 기간의 기록으로 상담용 리포트(PDF)"},{"heading":"전달","body":"보호자가 내용을 확인한 뒤 직접 전달해요. 앱이 자동으로 보내지 않아요"},{"heading":"보관","body":"보호자가 지울 때까지"},{"heading":"생성형 AI 안내","body":"무중은 생성형 AI가 아이와 대화하는 서비스예요. 아이가 듣는 말은 사람이 아니라 AI가 만들어요."},{"heading":"동의하지 않으면","body":"서비스를 시작할 수 없어요"}]}',
            'APPROVED', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000b4'::uuid, 'POLICY_TERMS_RAW_AUDIO', 1,
            '{"title":"원음 보관","sections":[{"heading":"보관하는 것","body":"아이 음성 원본"},{"heading":"이용 목적","body":"글로 옮긴 내용을 다시 확인할 때"},{"heading":"보관","body":"계정을 삭제할 때까지"},{"heading":"생성형 AI 안내","body":"무중은 생성형 AI가 아이와 대화하는 서비스예요. 아이가 듣는 말은 사람이 아니라 AI가 만들어요."},{"heading":"동의하지 않으면","body":"서비스를 시작할 수 없어요"}]}',
            'APPROVED', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000b5'::uuid, 'INTRO_FREE_TALK', 1,
            '안녕, 무중이야. 오늘 나랑 이야기할래?', 'DRAFT', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000b6'::uuid, 'INTRO_PRACTICE', 1,
            '안녕, 무중이야. 오늘 나랑 같이 연습해 볼래?', 'DRAFT', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000b7'::uuid, 'RE_PROMPT', 1,
            '무중이 여기 있어. 이야기하고 싶으면 말해 줘.', 'DRAFT', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000b8'::uuid, 'DECLINE_ALTERNATIVE', 1,
            '괜찮아. 다음에 이야기하고 싶을 때 불러 줘.', 'DRAFT', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000b9'::uuid, 'SILENCE_WARNING', 1,
            '무중이 기다리고 있어. 이야기하고 싶으면 말해 줘.', 'DRAFT', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000ba'::uuid, 'STOP_CONFIRM', 1,
            '이제 그만할까?', 'DRAFT', TIMESTAMPTZ '2026-10-12 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000000bb'::uuid, 'HELP_OFFER', 1,
            '같이 살펴볼까?', 'DRAFT', TIMESTAMPTZ '2026-10-12 00:00:00+00')
) AS seed (id, content_code, version, content_text, status, created_at)
JOIN core.core_approved_content AS content ON content.content_code = seed.content_code
ON CONFLICT DO NOTHING;
