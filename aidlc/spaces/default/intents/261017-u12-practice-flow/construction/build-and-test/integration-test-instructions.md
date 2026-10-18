# U12 새 연습 흐름 — 통합 시험 지침

> Standard 전략이라 통합 시험 지침을 만든다. 입력: `unit-test-instructions.md`의 시험 범위표, `code-generation-plan.md` 묶음 D. 모든 통합 시험은 가짜 공급자 · 가짜 제브 · 가짜 판정이며 실제 API · 실제 기기가 아니다(R-F1 · R-M11).

## 핵심 경계와 시험

| 경계 | 시험 | 확인하는 것 |
|---|---|---|
| 시작 경로 ↔ 대본 읽기 | `RealtimeInternalApiIT` · `SessionInternalControllerU4bTest` | `PRACTICE` + `practice` 본문 202, `practiceJson` · 승인 발화 지도 도달, 자유대화 단언 무변경 |
| 저장소 ↔ V7 | `PracticeFlowSchemaIT` · `PracticeFlowRepositoryIT` · `PracticeFlowRecorderIT` | 새 표 · 제약 · 색인, 원문 칸 없음, 삭제 순서(190), 정산 복구, `record.practice`가 `practice-record-v2` 스키마 통과 |
| 실행부 ↔ 제브 | `PracticeGeneratedSpeechTest` · `PracticeFlowIT` | ALLOW 뒤 재생, BLOCK 0바이트, 제브 비활성 · 실패 폐기, 첫 막힘 재지시, 종료 사건 한 번 |
| 끝-끝(대역) | `PracticeFlowIT` | 시작 요청 → 도입 → J2 PARTIAL → 되묻기 → J3 DELIVERED → 정리 → 정산 → `record.practice` |
| 두 앱 ↔ 계약 | core-api `FlywayConfIT` · `SchemaPrivilegeIT`, `ConfigKeyContractTest`, `TimeLiteralScanTest` | V7 사실, 새 설정 키 두 개 등록, 숫자 시간 값 없음 |
| 구조 | `PracticeFlowArchTest` | 새 패키지가 `practice/`를 모름, 금지 능력 · 라벨 · 확률 지시문 없음, 실행부 → 확장 한 방향 |
| 전체 스택 | `bash scripts/verify.sh` C안 | 기기 시뮬레이터 한 턴(C1 ~ C12), 교차 스키마 쓰기 거절(P1), 로그 위생(P2) |

## 실행 방법

- 통합 시험은 Testcontainers PostgreSQL이 필요하다: `sg docker -c 'cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'`, `sg docker -c 'cd backend && ./gradlew :core-api:check -Pmujoong.test.db=docker'`.
- 전체 스택 확인은 `sg docker -c 'bash scripts/verify.sh --mode compose'` 한 번이다. 상대 앱은 스텁이고 공급자는 `fake-provider`다.
- 시험 데이터는 빌더로 시험마다 격리한다. 시각은 시험용 `MujoongClock`(수동 시계)으로만 움직인다(R-F6).

## 알려진 범위 밖 항목

- 기기 시뮬레이터 대본에는 연습 시작이 아직 없어, 이 연습 흐름은 `verify.sh` C 단계에서 돌지 않는다. 연습 흐름의 끝-끝 증거는 `PracticeFlowIT`(대역)뿐이다. 시뮬레이터 대본 확장은 후속 과제다.
- 실제 제브 · 실제 공급자 · 실제 기기로 한 확인은 없다. R-F15(아동 데이터 전달 허용 범위 확인)가 먼저다.
