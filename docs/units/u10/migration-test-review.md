# U10 V10에 따른 공통 시험 기준선 변경 제안

## 변경이 필요한 이유

U10 지시서는 core V10 추가를 요구하면서 다른 단위 시험 단언은 변경하지 않도록 정한다. 현재 공통 시험에는 최신 버전 V9·이력 9건·시나리오 열 22개가 정확한 값으로 고정돼 있어, V10을 정상 추가해도 아래 네 시험 파일의 기대값/자료가 맞지 않는다. 운영 코드를 옛 시험에 맞춰 V10 없이 실행하거나 시험을 건너뛰는 방안은 사용하지 않는다.

## 한정된 변경 범위

| 파일 | 현재 | 제안 |
|---|---|---|
| backend/core-api/src/test/java/kr/mujoong/core/db/CoreSchemaIT.java | 성공 이력 V1~V9, core_scenario 22열 | 정확 이력에 V10 추가, 선택 컬럼 5개를 포함한 정확 27열 |
| backend/core-api/src/test/java/kr/mujoong/core/db/FlywayConfIT.java | 적용 횟수/이력 건수 9, 정확 이력 V1~V9 | 적용 횟수/이력 건수 10, 정확 이력 V1~V10. 전체 표 수 61은 그대로 |
| backend/core-api/src/test/java/kr/mujoong/core/startup/SchemaVersionCheckTest.java | 최신 기대 V9, 앞선 버전 V10 | 최신 V10, 앞선 V11. 기존 미적용·낮은 버전·권한/숫자 비교·비로그 거절을 유지하며 이전 V9 거절 추가 |
| backend/core-api/src/test/java/kr/mujoong/core/startup/CoreStartupSequenceIT.java | 낮은 이력 fixture V1~V9→0.6~0.14, 미래 성공 V10/실패 V11 | 낮은 이력 fixture에 V10→0.15 포함, 미래 성공 V11/실패 V12. 실패한 미래 이력 제외와 S2 거절 단언 유지 |

단언을 범위 비교나 느슨한 포함 관계로 바꾸지 않는다. 모든 기존 시험과 커버리지 하한·ArchUnit을 유지한다. V1~V9 마이그레이션 및 운영 DB 자료는 변경하지 않는다. 신규 U10 시험에서 V9 자료 보존·구버전 INSERT·선택 컬럼 null·V10 왕복을 별도로 검증한다. 이 제안은 실제 시험 통과의 증거가 아니며, 허용 후 실행 결과를 남긴다.

## 결정

[Answer]: 공통 시험 네 파일의 기대값·자료 갱신 허용

선택: 공통 시험 네 파일의 위 기대값/자료 갱신만 허용 / 기존 시험 유지 및 범위 재검토.
