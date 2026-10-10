-- 무중 참조 데이터 시드 001 — 현재 정책 버전 1 · 승인 문구 2 (U1 3.5 Step 11 · R4-15 · J114 · J117 · J60 · J102 · infra-spec 5-b)
--
-- 실행: mujoong_owner(migrate와 같은 자격)로 Flyway 적용 뒤 한 번. 몇 번 실행해도 결과가 같다(멱등, ON CONFLICT DO NOTHING).
--   psql -X -v ON_ERROR_STOP=1 --single-transaction -f infra/seed/001-reference.sql
-- 트랜잭션 제어 · psql 메타 명령을 두지 않는다. 같은 파일을 JDBC로도 한 트랜잭션에 적용할 수 있다(SeedReferenceSqlIT).
--
-- 내용(합성 참조 데이터, 개인정보 없음 · mujoong_db.data_class = synthetic)
--   - CONSENT_POLICY: 현재 정책 버전 자리. 최고 APPROVED 버전 행의 content_text '1.0'을 현재 정책 버전으로 읽는다(J114, 임시 · U2 OQ7)
--   - CLOSING · WAITING_FILLER: 승인 문구(임시, J117)와 승인 음성 파일 이름(/media/approved/ 아래, infra/seed-audio, J102)
-- 고정 UUID(J117): dev 시드(DevSeedRunner · SeedIds)도 같은 값을 쓴다. 바꾸면 두 곳을 함께 바꾼다.
--   core_approved_content          CLOSING 00000000-0000-4000-8000-000000000031 · WAITING_FILLER …032 · CONSENT_POLICY …033
--   core_approved_content_version  CLOSING v1 00000000-0000-4000-8000-000000000041 · WAITING_FILLER v1 …042 · CONSENT_POLICY v1 …043
-- 시각은 DB 시각 함수가 아니라 고정 값(참조 데이터 정의 시각)이다(R2 · NFR6.4).

INSERT INTO core.core_approved_content (id, content_code, content_type, created_at)
VALUES
    ('00000000-0000-4000-8000-000000000031'::uuid, 'CLOSING', 'APPROVED_AUDIO', TIMESTAMPTZ '2026-10-10 00:00:00+00'),
    ('00000000-0000-4000-8000-000000000032'::uuid, 'WAITING_FILLER', 'APPROVED_AUDIO', TIMESTAMPTZ '2026-10-10 00:00:00+00'),
    ('00000000-0000-4000-8000-000000000033'::uuid, 'CONSENT_POLICY', 'POLICY_VERSION', TIMESTAMPTZ '2026-10-10 00:00:00+00')
ON CONFLICT DO NOTHING;

-- 버전 행은 content_code로 종류 행을 찾아 붙인다. 종류 행이 다른 id로 먼저 들어와 있어도(같은 content_code) 그 행에 붙고,
-- 같은 (content_id, version)이 이미 있으면 아무것도 하지 않는다.
INSERT INTO core.core_approved_content_version (id, content_id, version, content_text, audio_path, status, created_at)
SELECT seed.id, content.id, seed.version, seed.content_text, seed.audio_path, 'APPROVED', seed.created_at
FROM (
    VALUES
        ('00000000-0000-4000-8000-000000000041'::uuid, 'CLOSING', 1,
            '오늘 이야기 즐거웠어. 다음에 또 만나!', 'closing-v1.wav', TIMESTAMPTZ '2026-10-10 00:00:00+00'),
        ('00000000-0000-4000-8000-000000000042'::uuid, 'WAITING_FILLER', 1,
            '음, 잠깐만 생각해 볼게.', 'waiting-filler-v1.wav', TIMESTAMPTZ '2026-10-10 00:00:00+00'),
        ('00000000-0000-4000-8000-000000000043'::uuid, 'CONSENT_POLICY', 1,
            '1.0', NULL, TIMESTAMPTZ '2026-10-10 00:00:00+00')
) AS seed (id, content_code, version, content_text, audio_path, created_at)
JOIN core.core_approved_content AS content ON content.content_code = seed.content_code
ON CONFLICT DO NOTHING;
