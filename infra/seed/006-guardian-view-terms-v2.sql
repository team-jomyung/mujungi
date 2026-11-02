-- 보호자 열람 약관 v2 (2026-11-02): 생활 팁 기능을 뺐으므로(FT-1102-h) '볼 수 있는 것'에서 '생활 팁'을 지운다.
-- v1(…b2)은 그대로 두고 v2를 더한다. 앱은 코드별 APPROVED 최고 판을 보여 주며, 동의 유효성은 정책 판(CONSENT_POLICY) 기준이라
-- 기존 동의는 그대로 유효하고 새 동의부터 v2 판 id를 남긴다. 001~005 뒤 mujoong_owner로 적용한다(멱등, ON CONFLICT DO NOTHING).
INSERT INTO core.core_approved_content_version (id, content_id, version, content_text, audio_path, status, created_at)
SELECT seed.id, content.id, seed.version, seed.content_text, NULL, 'APPROVED', seed.created_at
FROM (
    VALUES
        ('00000000-0000-4000-8000-0000000000d1'::uuid, 'POLICY_TERMS_GUARDIAN_VIEW', 2,
            '{"title":"보호자 열람","sections":[{"heading":"보는 사람","body":"이 계정으로 로그인한 보호자"},{"heading":"볼 수 있는 것","body":"활동 기록 · 대화 글 · 연습 결과"},{"heading":"이용 목적","body":"아이의 대화와 연습을 보호자가 확인"},{"heading":"생성형 AI 안내","body":"무중은 생성형 AI가 아이와 대화하는 서비스예요. 아이가 듣는 말은 사람이 아니라 AI가 만들어요."},{"heading":"동의하지 않으면","body":"자유대화 · 연습을 시작할 수 없어요"}]}',
            TIMESTAMPTZ '2026-11-02 00:00:00+00')
) AS seed (id, content_code, version, content_text, created_at)
JOIN core.core_approved_content AS content ON content.content_code = seed.content_code
ON CONFLICT DO NOTHING;
