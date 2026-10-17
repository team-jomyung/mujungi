-- U10: 선택 확장만 추가한다. V9 자료·구버전 INSERT·기존 제약을 보존한다.
ALTER TABLE core.core_scenario ADD COLUMN script_version varchar(80);
ALTER TABLE core.core_scenario ADD COLUMN scenes_json jsonb;
ALTER TABLE core.core_scenario ADD COLUMN self_review_json jsonb;
ALTER TABLE core.core_scenario ADD COLUMN generation_json jsonb;
ALTER TABLE core.core_scenario ADD COLUMN goal_context_json jsonb;
