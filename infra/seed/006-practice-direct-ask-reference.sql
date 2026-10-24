-- 무중 참조 데이터 시드 006 — 연습 대화 C안(무중이 직접 묻기) 고정 승인 발화(APPROVED_SPEECH) 6종
--   (docs/practice-direct-ask.md 5장 · contracts/ai-io.md 10.6 · 10.17 · 편차 FT-1024-a)
--
-- 실행: 001 ~ 005 뒤에 같은 자격(mujoong_owner)으로 한 번. 몇 번 실행해도 결과가 같다(멱등).
--   psql -X -v ON_ERROR_STOP=1 --single-transaction -f infra/seed/006-practice-direct-ask-reference.sql
-- 트랜잭션 제어 · psql 메타 명령을 두지 않는다(JDBC로도 한 트랜잭션에 적용, SeedLiveContentReferenceSqlIT).
--
-- 내용(합성 참조 데이터, 개인정보 없음)
--   - C안에서 장면 안 무중의 말은 모두 승인 문장이다. 대본에 없는 말(변형 제안 · 다시 해 보기 무응답 · 확인 · 장면 닫기 · 무응답 다시 묻기
--     앞머리 · 옛 대본의 묻기 대체)을 고정 문장으로 둔다. 음성 파일 없이 GPT-Live 원문 읽기로 낸다(audio_path NULL).
--     core는 연습 시작 패키지 approvedSpeech에 싣는다.
--   - 새 code 6종(부모 · v1 APPROVED): VARIANT_OFFER · RETRY_PROMPT · INPUT_CONFIRM · SCENE_CLOSE · WAIT_LEAD · ASK_DEFAULT.
--   - HELP_OFFER(004)는 그대로 쓴다. COACH_SWITCH · PEER_RETURN(004)은 연습 시작 허용 목록에서만 빠지고 행은 그대로다(004는 고치지 않는다).
--   - 문구 정본: docs/practice-direct-ask.md 5장 표(초안, 팀 승인 기록은 AI 동작 명세 관례로 따로 남긴다). 원문 대조가 문장부호까지
--     엄격하므로 마침표 · 쉼표 · 물음표만 쓴다(설계 4.3).
-- 고정 UUID(SeedIds와 같다): 종류 행 00000000-0000-4000-8000-0000000008a5 ~ …8aa, 판 행 …8b6 ~ …8bb. 시각은 고정 값이다(R2).

-- 1. 종류 행
INSERT INTO core.core_approved_content (id, content_code, content_type, created_at)
VALUES
    ('00000000-0000-4000-8000-0000000008a5'::uuid, 'VARIANT_OFFER', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000008a6'::uuid, 'RETRY_PROMPT', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000008a7'::uuid, 'INPUT_CONFIRM', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000008a8'::uuid, 'SCENE_CLOSE', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000008a9'::uuid, 'WAIT_LEAD', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
    ('00000000-0000-4000-8000-0000000008aa'::uuid, 'ASK_DEFAULT', 'APPROVED_SPEECH', TIMESTAMPTZ '2026-10-24 00:00:00+00')
ON CONFLICT DO NOTHING;

-- 2. 승인 판(음성 없음). 버전 행은 content_code로 종류 행에 붙는다(001 · 004와 같은 규칙). 같은 (content_id, version)이 있으면 그대로
INSERT INTO core.core_approved_content_version (id, content_id, version, content_text, audio_path, status, created_at)
SELECT seed.id, content.id, seed.version, seed.content_text, NULL, 'APPROVED', seed.created_at
FROM (
    VALUES
        ('00000000-0000-4000-8000-0000000008b6'::uuid, 'VARIANT_OFFER', 1,
            '비슷한 이야기로 한 번 더 해 볼까?', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000008b7'::uuid, 'RETRY_PROMPT', 1,
            '네 말로 한 번 말해 볼래?', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000008b8'::uuid, 'INPUT_CONFIRM', 1,
            '잘 못 들었어. 한 번 더 말해 줄래?', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000008b9'::uuid, 'SCENE_CLOSE', 1,
            '알겠어. 이 이야기는 여기까지 할게.', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000008ba'::uuid, 'WAIT_LEAD', 1,
            '천천히 생각해도 돼.', TIMESTAMPTZ '2026-10-24 00:00:00+00'),
        ('00000000-0000-4000-8000-0000000008bb'::uuid, 'ASK_DEFAULT', 1,
            '너라면 친구한테 뭐라고 말할래?', TIMESTAMPTZ '2026-10-24 00:00:00+00')
) AS seed (id, content_code, version, content_text, created_at)
JOIN core.core_approved_content AS content ON content.content_code = seed.content_code
ON CONFLICT DO NOTHING;

-- 3. 확인: 여섯 판이 위 UUID · code · 글 · 음성 없음으로 있어야 한다. 같은 code나 판 번호의 다른 행과 부딪혀 넣지 못했으면 조용히 넘어가지 않고
--    멈춘다. 뒤 시드가 이 판을 RETIRED로 바꿔도 다시 돌릴 수 있게 상태는 APPROVED · RETIRED를 모두 받는다
DO $$ BEGIN
    IF (SELECT count(*)
          FROM core.core_approved_content_version AS v
          JOIN core.core_approved_content AS c ON c.id = v.content_id
          JOIN (VALUES
                    ('00000000-0000-4000-8000-0000000008b6'::uuid, 'VARIANT_OFFER', '비슷한 이야기로 한 번 더 해 볼까?'),
                    ('00000000-0000-4000-8000-0000000008b7'::uuid, 'RETRY_PROMPT', '네 말로 한 번 말해 볼래?'),
                    ('00000000-0000-4000-8000-0000000008b8'::uuid, 'INPUT_CONFIRM', '잘 못 들었어. 한 번 더 말해 줄래?'),
                    ('00000000-0000-4000-8000-0000000008b9'::uuid, 'SCENE_CLOSE', '알겠어. 이 이야기는 여기까지 할게.'),
                    ('00000000-0000-4000-8000-0000000008ba'::uuid, 'WAIT_LEAD', '천천히 생각해도 돼.'),
                    ('00000000-0000-4000-8000-0000000008bb'::uuid, 'ASK_DEFAULT', '너라면 친구한테 뭐라고 말할래?')
               ) AS expected (id, content_code, content_text)
            ON expected.id = v.id AND expected.content_code = c.content_code AND expected.content_text = v.content_text
         WHERE c.content_type = 'APPROVED_SPEECH' AND v.audio_path IS NULL AND v.version = 1
           AND v.status IN ('APPROVED', 'RETIRED')) <> 6 THEN
        RAISE EXCEPTION '연습 C안 승인 발화 6종 등록이 다른 행과 충돌했다';
    END IF;
END $$;
