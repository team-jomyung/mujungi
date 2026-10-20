-- 기존 숫자 나이는 구 버전 및 생년월일 없는 기존 시드 호환용으로 유지한다.
ALTER TABLE core.core_child ADD COLUMN birth_date date;
