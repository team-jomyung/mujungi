# U13 제브 게이트 — Build and Test 결과

> 실행일 2026-10-17 · 브랜치 `unit/u13-jev-guardrail` · 마지막 커밋 `421e1ad` · 이 PC(WSL Ubuntu, JDK 21, Docker는 `sg docker -c`).
> **증거 구분(R-M11):** 아래 모든 합격은 **테스트 대역**(가짜 제브 · 가짜 공급자 · 루프백 서버)과 **합성 대화**(`verify.sh` C안 기기 시뮬레이터 한 턴, 가짜 공급자)의 결과다. **실제 제브 API · 실제 기기 증거는 없다.** 제브는 한 번도 부르지 않았다(R-F1).

## 1. 빌드 상태

| 항목 | 결과 |
|---|---|
| 백엔드 컴파일 · 정적 검사 · 단위 · 통합 · 모듈 JaCoCo 80% 줄 하한 | 통과 (`verify.sh` A1, 213 ~ 217초) |
| 운영 `bootJar` 두 개에 시험 클래스 | 0개 |
| 프런트엔드(`eslint` · `vitest` 80% · `next build`) | 통과, 줄 커버리지 98.55% (이 단위는 프런트를 바꾸지 않음) |
| tools(`npm ci` · `vitest`) | 통과 |

## 2. 시험 결과

| 범주 | 총 | 통과 | 실패 | 건너뜀 | 증거 종류 |
|---|---|---|---|---|---|
| 백엔드 전체(`verify.sh` A1) | 3,555 | 3,552 | 0 | 3 | 테스트 대역 |
| 그중 `:realtime-activity:check` | 1,641 | 1,641 | 0 | 0 | 테스트 대역 (묶음 5 보고, 같은 명령 재실행에서도 통과) |
| 그중 새 `@Tag("u13")` 시험 | 169 (클래스 19) | 169 | 0 | 0 | 테스트 대역 |
| 인프라 `infra-validate` 8건 · `test-infra-validate.sh` 46건 · `test-host-tools.sh` 42건 | 96 | 96 | 0 | 0 | 정적 · 대역 |
| 이미지 점검(`image-checks.sh`) ① ② ④ ⑤ ⑥ ⑦ ⑧ | 7 | 7 | 0 | ③ 건너뜀 | ④가 제브 키 없는 demo realtime의 기동 거부를 확인 |
| 기기 시뮬레이터 한 턴 C1 ~ C12 | 12 | 12 | 0 | 0 | 합성 대화(가짜 공급자) |
| `verify.sh` 단계 A1 ~ A4 · F · B · C · P1 ~ P3 · T | 12단계 | 12 | 0 | 0 | 합성 대화 + 정적 |

- 건너뜀 3건은 `test-support`의 `MujoongPostgresTest [2] backend = LOCAL`(LOCAL DB 모드 전용 매개변수 사례)이다. 이 단위와 무관한 기존 건너뜀이다.
- 이미지 점검 ③ 건너뜀은 `trivy`가 이 PC에 없어서다(CI `images` 작업이 돌린다).
- `A4.gitleaks` 노트: `gitleaks`가 이 PC에 없어 이력 스캔은 CI `secrets` 작업 몫이다. 작업 트리는 키 형식 9종 grep으로 봤고 걸림 0이다.

## 3. 커버리지

| 대상 | 측정 | 하한 | 판정 |
|---|---|---|---|
| U13 유닛 범위(`backend/coverage-scope/u13.txt`, 운영 소스 31개) | **86.1%** (1,727 / 2,006줄) — `-PincludeTags=u13 -PcoverageScope=u13`, 이 문서 작성 중 독립 재측정 | 80% 줄 | 충족 |
| `:realtime-activity` 모듈 전체 | `check`의 `jacocoTestCoverageVerification` 통과 | 80% 줄 | 충족 |

주의: 범위 목록은 `SessionExecutor`(앞 유닛 코드가 대부분, 이 단위가 +338줄)를 뺀다(목록 머리에 사유). 그 변경 줄은 `GuardrailExecutorTest` 20개 · `GuardrailTurnIT` 4개가 실행하지만 이 86.1%에는 들어 있지 않다(코드 생성 검토 R-03). 하한이나 범위를 낮춘 것은 아니다. 낮은 파일은 `SessionMailbox` 67.1% · `PracticeSessionExtension` 68.6% · `InstructionTemplates` 69.2% · `RealtimeMetrics` 75.4% · `OutputGeneration` 77.4%이며 모두 앞 유닛 코드가 섞인 공통 파일이다.

## 4. 이번 단계에서 발견해 고친 실패 (실패 사다리 1단: 단계 안 수정 2회 중 2회 사용)

모듈별 점검(`:realtime-activity:check`)은 통과했지만 전체 `verify.sh`가 모듈 밖 회귀 두 가지를 잡았다.

| # | 실행 | 실패 | 원인 | 수정 | 재실행 |
|---|---|---|---|---|---|
| 1 | `verify.sh` A1 (217초) | `:core-api`의 `FlywayConfIT.confFilesMigrateAnEmptyDatabaseInOrderAndAreIdempotent` (기대 5, 실제 6) · `SchemaPrivilegeIT.eachAppReadsOnlyItsOwnFlywayHistoryTable` (기대 "5", 실제 "6") | core-api 시험이 realtime 마이그레이션 설정 파일도 돌려 realtime 최대 버전 V5를 기대했다. U13의 V6이 표 1개를 더했다. | 단언을 V6 사실로 갱신: 적용 수 5→6, 이력 행 6→7, realtime 표 17→18, 합계 62→63, 최대 버전 "5"→"6". U11이 V5를 넣을 때 같은 갱신을 한 선례(`3861291`)를 따랐다. 단언을 지우거나 느슨하게 하지 않았다(R-F2). 커밋 `a619120`. | `:core-api:check` 통과(2분 59초) |
| 2 | `verify.sh` A4 (재실행) | `A4.patterns`: 키 형식 문자열이 든 파일 1개 `JevHostsTest.java` | 사용자 정보가 든 주소를 거절하는 시험의 리터럴(https 주소에 사용자 이름과 비밀번호를 콜론과 @로 이은 모양)이 비밀 스캔의 URL 자격 증명 패턴과 겹쳤다(실제 비밀 아님, 시험 입력). | 스캐너 패턴과 `.gitleaks.toml`은 그대로 두고(R-F9) 시험 문자열을 조각으로 조립. 같은 단언 유지. 커밋 `421e1ad`. | `JevHostsTest` 통과, 이어서 `verify.sh` 전체 통과 |

두 수정 모두 시험 코드에 대한 것이며 운영 코드는 바꾸지 않았다. 코드 생성의 `source-manifest.json`에는 `FlywayConfIT` · `SchemaPrivilegeIT`(core-api)가 없다. 이 단계의 수정으로 추가된 시험 변경이다.

**교훈(다음 단위에 쓸 것):** realtime 마이그레이션 버전을 올리면 `:realtime-activity`뿐 아니라 `:core-api`의 `FlywayConfIT` · `SchemaPrivilegeIT`와 `scripts/verify` 쪽 버전 기대값도 함께 갱신해야 한다. U12(다음 V7)도 같다. 계획 단계 시험 안내가 `:realtime-activity:check`만 적어 모듈 밖 회귀를 놓쳤으므로, 마이그레이션을 더하는 단위는 `:core-api:check`도 돌려야 한다.

## 5. 대상 검증 결과 요약

`build-and-test-summary.md`의 Target Verification Matrix 참조. 요약: 측정 대상 6개 모두 `Met`, `Not Met` · `Unverified` 0개.

## 6. 하지 않은 것 (정직한 한계)

- **실제 제브 API:** 응답 형식 · 지연 · 분류 품질은 모른다. `jevLiveTest`는 키 없이 `SKIPPED_NO_KEY`까지만 확인했다. 사람 몫 열린 항목이다.
- **실제 기기 · 실제 아이 음성:** 체감 대기에 제브가 더하는 값은 측정하지 않았다.
- **demo 배포:** 이 단위는 배포를 다루지 않는다(Q5-A). 이 SHA를 demo에 배포하려면 키 · 주소 · 모델을 Parameter Store에 먼저 넣어야 한다(기동 거부).
- **`DeviceGatewayU3IT`:** 코드 생성 중 부하로 보이는 일시 실패가 1회 있었다(재실행 통과). 이번 Build and Test의 세 번의 `verify.sh` 실행에서는 재발하지 않았다.
- **trivy · gitleaks 이력 스캔:** 이 PC에 도구가 없어 CI에서 돈다.
- **부하 · 성능 시험:** 측정 목표가 정해진 NFR이 없어 하지 않았다(`performance-test-instructions.md`).

## Loop-Back Log

(없음 — 실패 사다리 1단에서 해결했고 코드 생성으로 되돌아가지 않았다.)
