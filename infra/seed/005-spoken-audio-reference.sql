-- 사용자 청취 승인 2026-10-21: 새 음성 판 8종. 이전 파일·본문·버전은 보존한다.
-- 001~004 뒤 mujoong_owner로 --single-transaction 적용. 기존 세션의 버전 고정은 유지한다.
-- 승인 근거/파일 해시/상황 매핑: infra/approved-audio/approval-20261021.json
DO $$ BEGIN
    IF (SELECT count(*) FROM core.core_approved_content_version WHERE id IN ('00000000-0000-4000-8000-000000000041'::uuid,'00000000-0000-4000-8000-0000000000c8'::uuid,'00000000-0000-4000-8000-0000000000c5'::uuid,'00000000-0000-4000-8000-0000000000c6'::uuid,'00000000-0000-4000-8000-0000000000c7'::uuid,'00000000-0000-4000-8000-0000000000c9'::uuid,'00000000-0000-4000-8000-0000000000ca'::uuid,'00000000-0000-4000-8000-000000000042'::uuid)) <> 8 THEN
        RAISE EXCEPTION '승인 음성의 원본 콘텐츠 8종이 없다';
    END IF;
END $$;

INSERT INTO core.core_approved_content_version (id,content_id,version,content_text,audio_path,status,created_at)
SELECT seed.id,content.id,seed.version,seed.content_text,seed.audio_path,'APPROVED',seed.created_at
FROM (VALUES
        ('ef3ed73e-7aba-58bf-b30c-fdd59fd36d9b'::uuid, 'CLOSING', 2, '오늘 이야기 즐거웠어. 다음에 또 만나!', 'closing-v2-spoken-20261021.wav', TIMESTAMPTZ '2026-10-21T07:49:30.408077+00:00'),
        ('9086a211-3354-5b22-b6c4-47f81c28dc45'::uuid, 'DECLINE_ALTERNATIVE', 3, '괜찮아. 그럼 오늘 있었던 일 하나만 이야기해 볼래? 하기 싫으면 안 해도 돼.', 'decline-alternative-v3-spoken-20261021.wav', TIMESTAMPTZ '2026-10-21T07:49:30.408077+00:00'),
        ('7dfe7883-ecdc-555d-b369-b66edde15377'::uuid, 'INTRO_FREE_TALK', 3, '안녕, 무중이야. 나는 사람이 아니라 AI야. 오늘은 나랑 자유롭게 이야기하는 시간이야. 우리가 나눈 말은 글로 남아서 너를 돌봐 주는 어른이 볼 수 있어. 그만하고 싶으면 "무중아 그만할래"라고 말해 줘. 오늘 나랑 이야기할래?', 'intro-free-talk-v3-spoken-20261021.wav', TIMESTAMPTZ '2026-10-21T07:49:30.408077+00:00'),
        ('c5741639-ef4d-5a2e-a565-52023edb7d9d'::uuid, 'INTRO_PRACTICE', 3, '안녕, 무중이야. 나는 사람이 아니라 AI야. 오늘은 나랑 같이 말하기 연습을 해 보는 시간이야. 우리가 나눈 말은 글로 남아서 너를 돌봐 주는 어른이 볼 수 있어. 그만하고 싶으면 "무중아 그만할래"라고 말해 줘. 오늘 나랑 같이 연습해 볼래?', 'intro-practice-v3-spoken-20261021.wav', TIMESTAMPTZ '2026-10-21T07:49:30.408077+00:00'),
        ('e3ed0bae-5569-50fe-bdf4-4a19e559f003'::uuid, 'RE_PROMPT', 3, '무중이 여기 있어. 이야기하고 싶으면 말해 줘.', 're-prompt-v3-spoken-20261021.wav', TIMESTAMPTZ '2026-10-21T07:49:30.408077+00:00'),
        ('d33cc0b0-45a5-5be8-a8b5-fe98acedd557'::uuid, 'SILENCE_WARNING', 3, '무중이 기다리고 있어. 이야기하고 싶으면 말해 줘.', 'silence-warning-v3-spoken-20261021.wav', TIMESTAMPTZ '2026-10-21T07:49:30.408077+00:00'),
        ('b9875cb6-6082-50d3-ae7d-d377e0f17ab0'::uuid, 'STOP_CONFIRM', 3, '이제 그만할까?', 'stop-confirm-v3-spoken-20261021.wav', TIMESTAMPTZ '2026-10-21T07:49:30.408077+00:00'),
        ('57ab36a7-15d6-5b84-ac2b-4e2cc9de809a'::uuid, 'WAITING_FILLER', 2, '음, 잠깐만 생각해 볼게.', 'waiting-filler-v2-spoken-20261021.wav', TIMESTAMPTZ '2026-10-21T07:49:30.408077+00:00')
) AS seed(id,content_code,version,content_text,audio_path,created_at)
JOIN core.core_approved_content content ON content.content_code=seed.content_code
ON CONFLICT DO NOTHING;

DO $$ BEGIN
    IF (SELECT count(*) FROM core.core_approved_content_version WHERE id IN ('ef3ed73e-7aba-58bf-b30c-fdd59fd36d9b'::uuid,'9086a211-3354-5b22-b6c4-47f81c28dc45'::uuid,'7dfe7883-ecdc-555d-b369-b66edde15377'::uuid,'c5741639-ef4d-5a2e-a565-52023edb7d9d'::uuid,'e3ed0bae-5569-50fe-bdf4-4a19e559f003'::uuid,'d33cc0b0-45a5-5be8-a8b5-fe98acedd557'::uuid,'b9875cb6-6082-50d3-ae7d-d377e0f17ab0'::uuid,'57ab36a7-15d6-5b84-ac2b-4e2cc9de809a'::uuid) AND status='APPROVED') <> 8 THEN
        RAISE EXCEPTION '새 음성 승인 판 등록이 충돌했다';
    END IF;
END $$;

UPDATE core.core_approved_content_version
SET status='RETIRED' WHERE id IN ('00000000-0000-4000-8000-000000000041'::uuid,'00000000-0000-4000-8000-0000000000c8'::uuid,'00000000-0000-4000-8000-0000000000c5'::uuid,'00000000-0000-4000-8000-0000000000c6'::uuid,'00000000-0000-4000-8000-0000000000c7'::uuid,'00000000-0000-4000-8000-0000000000c9'::uuid,'00000000-0000-4000-8000-0000000000ca'::uuid,'00000000-0000-4000-8000-000000000042'::uuid) AND status='APPROVED';
