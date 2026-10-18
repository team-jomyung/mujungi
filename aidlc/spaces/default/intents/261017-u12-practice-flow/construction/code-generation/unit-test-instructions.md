# U12 새 연습 흐름 — 단위 시험 지침

> 작업 기록 `261017-u12-practice-flow` · 시험 전략 Standard · 방법론 test-after(층마다 구현 뒤 그 층의 시험을 쓰고 돌린다). 모든 명령은 이 단위(태그 `u12`)로 한정한다. 시험 · CI · 검증에서는 가짜 공급자 · 가짜 제브 · 가짜 판정만 쓴다(R-F1).

## 시험 틀과 실행 명령

백엔드는 JUnit 5 · AssertJ · Mockito, 통합은 Spring Boot + Testcontainers PostgreSQL이다. 새 시험은 태그 `@Tag("u12")`(DB가 필요하면 `@Tag("db")` 추가)를 단다. 이름은 단위 `*Test`, 통합 `*IT`다. 도커 접근이 필요한 명령은 `sg docker -c '…'`로 돌린다. 운영 코드가 시험 코드를 참조하지 않는다(R-F8).

- **이 단위 시험만(실행기 확인용, 층마다 반복)**
  `sg docker -c 'cd backend && ./gradlew :realtime-activity:test -PincludeTags=u12 -Pmujoong.test.db=docker'`
- **이 단위 범위 커버리지(줄 80%)**
  `sg docker -c 'cd backend && ./gradlew :realtime-activity:test :realtime-activity:jacocoTestReport -PincludeTags=u12 -PcoverageScope=u12 -Pmujoong.test.db=docker'`
  범위 파일은 `backend/coverage-scope/u12.txt`다. 빈 목록 · 없는 파일이면 측정이 실패한다(J137).
- **묶음마다 확인(마이그레이션 · 계약 키를 더하므로 둘 다)**
  `sg docker -c 'cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'`
  `sg docker -c 'cd backend && ./gradlew :core-api:check -Pmujoong.test.db=docker'`
- **회귀 시험 지정 실행(필요할 때)**
  `sg docker -c 'cd backend && ./gradlew :realtime-activity:test --tests "kr.mujoong.realtime.arch.*" --tests "*ConfigKeyContractTest" --tests "*TimeLiteralScanTest" -Pmujoong.test.db=docker'`
- 전체 `bash scripts/verify.sh`는 Build and Test에서 한 번 돌린다. 도는 동안에는 저장소 안에 파일을 쓰지 않는다.

## 시험 범위 (구현 층 → 시험)

| 층 | 시험 | 핵심 단언 |
|---|---|---|
| V7 데이터 | `RealtimeSchemaIT` · `RealtimeDb` 갱신, `PracticeFlowSchemaIT` | 표 · 제약 · 색인, 원문 칸 없음, 레거시 V4 표 불변. core-api `FlywayConfIT` · `SchemaPrivilegeIT`는 V7 사실로만 갱신 |
| 저장소 | `PracticeFlowRepositoryIT` | 저장 · 조회 · 삭제 참여자 순서(190, 세션 200 앞) · 정산 복구 |
| 시작 경로 | `RealtimeInternalApiIT` · `SessionInternalControllerU4bTest` 갱신 + 신규 | PRACTICE + `practice` 202, `practiceJson` · 승인 발화 지도 도달, 자유대화 단언 무변경 |
| 대본 읽기 | `PracticeScriptReaderTest` | 필수 6필드, 선택 필드 정규화, `scriptVersion` 없음 |
| 전이표(순수) | `FlowTransitionsTest` | §3.3 행마다 입력 → 다음 단계 · 사실 · 사유, J2 6 · J3 4 · J4 3 · J5 7 코드 전부, 경계(9초/10초 · 90초 · 재시도 2회 · 목표 10분), 변형 4조건 거짓 각각 · 종료 원인별 사유 |
| 상태 기계 | `PracticeConversationTest` | AC1.2.3(되묻기로 해결, AFTER_REASK, 집중 도움 0건), 판정 거절 · 보류, 지시문에 라벨 · 확률 없음, 중복 종료 사건 무시, 레거시 포트 실패에도 끝까지 |
| 라우터 | `PracticeFlowRouterTest` | `scriptVersion` 있음 · 없음 분기, `controlsOutput` 항상 참 |
| 실행부 출력 | `PracticeGeneratedSpeechTest`(+ IT) | 제브 ALLOW 재생, BLOCK 0바이트, 제브 비활성 · 실패 폐기, 첫 막힘 재지시 = 같은 지시문, 종료 원인별 사건 한 번, 레거시 폐기 단언 무변경 |
| 기록 · 진행 | `PracticeFlowRecordTest` · `PracticeProgressMappingTest` | `practice-record-v2` 스키마 통과, 모든 단계 → 6값, 도입 실패 · 미참여 행 없음, **표식 문자열이 로그 · 기록 · 지표 · 이벤트 어디에도 없음** |
| 끝-끝(대역) | `PracticeFlowIT` | 시작 요청 → 도입 → J2 PARTIAL → 되묻기 → J3 DELIVERED → 정리 → 정산 → `record.practice` |
| 구조 | `PracticeFlowArchTest` | 새 패키지가 `practice/` 미참조, 금지 능력 · 라벨 · 확률 지시문 금지 |
| 계약 대조 | `ConfigKeyContractTest` · `TimeLiteralScanTest` | 새 키 두 개 등록, 코드에 숫자 시간 값 없음 |

**시험 수 목표(Standard):** 구성요소마다 5 ~ 8개 + 핵심 경계 통합(시작 경로 · 제브 연결 · 기록)이다.

## 모의 · 시험 데이터 · 하한

- **모의:** `PracticeOutput`은 기록하는 가짜(능력 호출 · 슬롯 종료 사건을 순서대로 남긴다), 판정은 가짜 `JudgeClient`(선택지 코드를 시험이 주입), 제브는 `StubGuardrailClient`(ALLOW · BLOCK · 실패 주입), 시각은 시험용 `MujoongClock`(수동 시계, 시스템 시각 직접 읽기 금지 R-F6), 공급자는 가짜 공급자다.
- **시험 데이터:** 대본 · 장면 · 목표는 빌더로 만들고 시험마다 격리한다. 한국어 장면 문장은 가짜 값이고, 비밀 스캔 패턴과 겹치는 모양의 리터럴을 문서 · 시험에 그대로 쓰지 않는다(R-F3).
- **하한:** 모듈 전체 줄 80%(JaCoCo)와 `u12` 범위 줄 80%를 낮추지 않는다. 기존 단언 · 계약 대조 · ArchUnit · U5b 범위 80%를 낮추거나 `@Disabled` · `-PexcludeTags`로 끄지 않는다(R-F2). 모자라면 시험을 더하거나 차이를 보고한다.
- **증거 구분:** 이 시험이 통과해도 그것은 테스트 대역 · 합성 대화의 성공이다. 실제 API · 실제 기기 성공으로 적지 않는다(R-M11).
