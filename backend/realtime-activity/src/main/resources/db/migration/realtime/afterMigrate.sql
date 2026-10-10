-- Flyway afterMigrate 콜백 — realtime 위치 (U1 3.5 Step 11 · J116 · SD-13 · BR8.1)
-- 앱 역할 realtime_app이 자기 이력 표(flyway.realtime_schema_history)만 읽게 한다. 기동 점검 S2가 적용된 최신 버전을 읽는다.
-- 이력 표는 init(01-roles.sql) 때 아직 없으므로 여기서 준다. GRANT는 멱등이라 migrate를 몇 번 돌려도 결과가 같다.
-- core_app에는 주지 않는다(상대 이력 표 SELECT 거부, SchemaPrivilegeIT).
GRANT SELECT ON TABLE flyway.realtime_schema_history TO realtime_app;
