-- 무중 참조 데이터 시드 004 — U8 승인 발화(APPROVED_SPEECH) 5종 (U8 functional-spec 8.1 · BR1.1 · BR1.2 · 설계 검토 R-01) [U8 편차 (U8-e)]
--
-- 실행: 001 · 002 · 003 뒤에 같은 자격(mujoong_owner)으로 한 번. 몇 번 실행해도 결과가 같다(멱등).
--   psql -X -v ON_ERROR_STOP=1 --single-transaction -f infra/seed/004-u8-reference.sql
-- 트랜잭션 제어 · psql 메타 명령을 두지 않는다(JDBC로도 한 트랜잭션에 적용, SeedU8ReferenceSqlIT).
--
-- 내용(합성 참조 데이터, 개인정보 없음)
--   - content_type 새 값 APPROVED_SPEECH: 음성 파일 없이 GPT-Live 지시 + 공백 제거 완전 일치로 재생하는 승인 발화(audio_path NULL).
--     Core는 이 값이고 APPROVED 판이 있는 code만 시작 패키지 approvedSpeech에 싣는다(ai-io.md 10.6절).
--   - HELP_OFFER: 002가 APPROVED_AUDIO · v1 DRAFT('같이 살펴볼까?')로 만든 부모 행의 content_type을 APPROVED_SPEECH로 바꾸고(멱등 UPDATE,
--     이미 만들어진 DB에도 적용), v2 APPROVED를 넣은 뒤 v1 DRAFT를 RETIRED로 둔다. 002가 없는 DB에서도 부모 행을 같은 UUID로 만든다.
--   - 새 code 4종(부모 · v1 APPROVED): D_REST_ACK_START · D_REST_ACK · COACH_SWITCH · PEER_RETURN.
--   - 문구 정본: AI 동작 명세 v0.2(B) — HELP_OFFER는 §3.1 D_OFFER_HELP의 예시 문장, D_REST_ACK_START · D_REST_ACK는 §3.1 같은 ID의
--     예시 문장, COACH_SWITCH · PEER_RETURN은 §8.5의 역할 전환 표현을 글자 그대로 옮겼다. D_REPROMPT는 §3.1에서 '직전에 한 무중의 말을
--     그대로 다시 말하는' 지시라 고정 원문이 없어 넣지 않는다(정본에 없는 code는 시드하지 않음).
-- 고정 UUID(SeedIds와 같다): 종류 행 00000000-0000-4000-8000-0000000008a1 ~ …8a4(HELP_OFFER는 002의 …0ab),
--   버전 행 …8b1(HELP_OFFER v2) · …8b2 ~ …8b5. 시각은 고정 값이다(R2).

-- 1. 종류 행(HELP_OFFER는 002가 이미 만들었으면 그대로)
INSERT INTO core.core_approved_content (id, content_code, content_type, created_at)
VALUES
    ('00000000-0000-4000-8000-0000000000ab'::uuid, 'HELP_OFFER', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-16 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000008a1'::uuid, 'D_REST_ACK_START', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-16 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000008a2'::uuid, 'D_REST_ACK', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-16 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000008a3'::uuid, 'COACH_SWITCH', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-16 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000008a4'::uuid, 'PEER_RETURN', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-16 00:00:00+00')
ON CONFLICT DO NOTHING;

-- 2. HELP_OFFER 부모 행을 음성 없는 승인 발화로(002의 APPROVED_AUDIO를 바꾼다, 다시 돌려도 결과가 같다)
UPDATE core.core_approved_content
   SET content_type = 'APPROVED_SPEECH'
 WHERE content_code = 'HELP_OFFER'
   AND content_type <> 'APPROVED_SPEECH';

-- 3. 승인 판(음성 없음). 버전 행은 content_code로 종류 행에 붙는다(001과 같은 규칙). 같은 (content_id, version)이 있으면 그대로
INSERT INTO core.core_approved_content_version (id, content_id, version, content_text, audio_path, status, created_at)
SELECT seed.id, content.id, seed.version, seed.content_text, NULL, 'APPROVED', seed.created_at
FROM (
    VALUES
        ('00000000-0000-4000-8000-0000000008b1'::uuid, 'HELP_OFFER', 2,
            '무중이가 조금 도와줄까?', TIMESTAMPTZ '2026-10-16 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000008b2'::uuid, 'D_REST_ACK_START', 1,
            '잠깐 쉬고 싶구나. 괜찮아. 준비되면 말해 줘.', TIMESTAMPTZ '2026-10-16 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000008b3'::uuid, 'D_REST_ACK', 1,
            '잠깐 쉬고 싶구나. 천천히 해도 괜찮아.', TIMESTAMPTZ '2026-10-16 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000008b4'::uuid, 'COACH_SWITCH', 1,
            '잠깐, 무중이 도와줄게', TIMESTAMPTZ '2026-10-16 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000008b5'::uuid, 'PEER_RETURN', 1,
            '다시 친구 역할로 돌아갈게', TIMESTAMPTZ '2026-10-16 00:00:00+00')
) AS seed (id, content_code, version, content_text, created_at)
JOIN core.core_approved_content AS content ON content.content_code = seed.content_code
ON CONFLICT DO NOTHING;

-- 4. 승인된 적 없는 HELP_OFFER v1 DRAFT를 RETIRED로(v2 APPROVED가 있을 때만, 다시 돌려도 결과가 같다)
UPDATE core.core_approved_content_version AS old
   SET status = 'RETIRED'
  FROM core.core_approved_content AS content
 WHERE old.content_id = content.id
   AND content.content_code = 'HELP_OFFER'
   AND old.version = 1
   AND old.status = 'DRAFT'
   AND EXISTS (SELECT 1 FROM core.core_approved_content_version AS approved
                WHERE approved.content_id = content.id AND approved.version = 2 AND approved.status = 'APPROVED');
