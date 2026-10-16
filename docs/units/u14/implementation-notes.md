# U14 구현 경계와 호환성

## 승인 설계의 구현상 해석

- 새 후보는 CoreLunaClient의 GOAL_CANDIDATES 계약 스키마로 직접 부른다. 기존 U6 ResultTextProvider 진입점/가짜 공급자·레거시 결과 생성 경로는 유지한다. 실제 Luna 클라이언트가 연결된 생성 경로는 고정 행동 목록을 후보 입력으로 사용하지 않는다. 후보 이름·화면 근거·목표 표현·내부 근거를 기존 OutputChecker/classify 한 묶음으로 검사한다.
- ResultTiming의 운영 기본값은 최초 포함4회·5/15/30초다. 명시된 기존 U6 3회 설정은 읽기 호환을 위해 여전히 유효하며 기존 시험 설정·단언을 바꾸지 않았다. 새 설정은3개 재시도 간격이 필수다. DB 허용 범위는4까지다.
- result.job-attempts/job-retry-backoff는 contracts/timing.md의 Core 전용 키다. 실제 realtime application.yml과 frontend timing-defaults.json에 없으며 각 설정은 소유키만 둔다. NFR5의 정본 동기화 목적을 지키기 위해 Core 기본값·바인더·시간/AI 정본을 같은 변경으로 맞추고, 비소유 앱에는 사용하지 않는 운영 키를 새로 복제하지 않았다. U14TimingContractTest가 값 일치와 두 비소유 파일의 부재를 확인한다. 기존 공유 시간 값은 바꾸지 않았다.
- C-DS10 SavedGoalReader.find는 활성 선택만 읽는다. 기록용 내부 historical 읽기는 소유권·동의 확인 뒤 soft-delete 선택의 고정 버전도 읽는다. 결과가 새로 생성돼 이전 후보를 무효화해도 이미 담은 목표 스냅샷은 유지된다.
- REJUDGMENT는 저장 sceneRunId를 직접 전송하지 않고 호출마다 임시 UUID 참조를 만들어 스키마 형식을 지킨 뒤 복원한다. liveOutcome이 없거나 ABSTAINED이면 관측 차이를 추정하지 않는다. NONE은 실제 전달 시점이 없다는 관측으로만 비교한다. 라벨/확률 원값을 만들지 않는다.

## 공통 DB 계약 기준선 정합

첫 전체 backend check는 core 1546개 중9개 실패(rc1,391초)였다. 새 V9를 적용했지만 공통 스키마/기동 시험이 V8 목록과 fixture를 고정한 차이였다. 부모 검토에 따라 아래5파일만 정확한 새 DB 계약에 맞췄다. 이는 신규 사용자 승인을 받았다는 기록이 아니라, '다른 유닛 단언 불변' 원칙 중 공통 DB 계약 기준선 유지의 좁은 구현상 예외다.

- CoreSchemaIT: 새3표·부분 유일 인덱스·성공 이력9를 정확 목록에 추가했다. containsExactly와 소유 역할 검사는 그대로다.
- FlywayConfIT: 적용 수8→9, 성공 이력9, core표42→45/전체58→61, core 이력8→9다. 멱등0·권한42501·clean/체크섬/낮은판 거절은 그대로다.
- SchemaPrivilegeIT: 성공 최고판8→9다. 교차 스키마/이력 접근 거절은 그대로다.
- SchemaVersionCheckTest: 현재9·더 높은10 통과, 바로 이전8 거절을 검증한다. 빈 이력/기준선/읽기 실패/낮은3의 거절과 숫자 비교를 유지했다.
- CoreStartupSequenceIT: 모든1~9를 실제로 낮추고 복원하며, 앞선10/실패11 fixture로 기동 경계를 보존했다. 운영 SchemaVersionCheck를 바꾸지 않았다.

## 검증 증거의 범위

시험은 합성 기록과 가짜 공급자/포트만 사용한다. 실제 공급자·실제 아동·실제 기기 성공을 주장하지 않는다. V9의 사람 승인과 PostgreSQL upgrade 검증은 migration-review.md에 별도로 기록한다. 최종 개수·커버리지·전체검증 결과는 report.md와 Code Generation 요약에 실제 측정치로 기록한다.
