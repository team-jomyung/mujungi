# U13 제브 게이트 — Build and Test 요약

> 작업 기록 `261017-u13-jev-guardrail` · 범위 `refactor` · 시험 전략 Standard · 브랜치 `unit/u13-jev-guardrail`(마지막 커밋 `421e1ad`).
> 증거 구분(R-M11): 합격은 **테스트 대역**과 **합성 대화**의 결과다. **실제 제브 API · 실제 기기 증거는 없다.**

## 1. 전체 상태

| 항목 | 상태 |
|---|---|
| 빌드 | 성공 — 백엔드 · 프런트 · tools · 이미지 5종 · compose 스택 기동 |
| 시험 | 백엔드 3,555건 중 실패 0 · 건너뜀 3(기존 `test-support` LOCAL 사례) · 새 `@Tag("u13")` 169건 통과 |
| 끝-끝 회귀 | `bash scripts/verify.sh --mode compose` 12단계 모두 PASS(341초), 기기 시뮬레이터 한 턴 C1 ~ C12 통과 |
| 이번 단계 발견 | `core-api` 시험 2건이 realtime 최대 버전 V5를 기대 → V6 사실로 갱신(`a619120`), `JevHostsTest` 리터럴이 비밀 스캔 패턴과 겹침 → 조립(`421e1ad`). 상세는 `test-results.md` 4절 |
| 준비 상태 | build-ready: 예 · test-ready: 예 · **deployment-ready: 아니오**(아래 5절) |

## 2. 만든 시험 지시서

| 파일 | 내용 |
|---|---|
| `build-instructions.md` | 전제 · 환경 · 빌드 명령 · 문제 해결 |
| `integration-test-instructions.md` | 경계별 통합 시험(Standard 전략 필수), 덮지 못하는 것 |
| `performance-test-instructions.md` | 측정 목표 없음 — 확인 가능한 시간 값과 하지 않은 것을 정직하게 적음 |
| `security-test-instructions.md` | STRIDE 위협 → 통제 → 증거, R-F15 미확인 |
| `test-results.md` | 실행 결과 · 실패 수정 기록 |
| `cross-unit-traceability.md` | FR · NFR 최종 추적 확인 |

## 3. 유닛 커버리지

| 대상 | 측정 | 하한 |
|---|---|---|
| U13 범위(31개 파일) | 86.1% (1,727 / 2,006줄) | 80% 줄 |
| `:realtime-activity` 모듈 | `jacocoTestCoverageVerification` 통과 | 80% 줄 |
| 프런트 | 98.55% (변경 없음) | 80% 줄 |

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-COV-U13 | `team.md` Testing Posture · Testing Contract(유닛 범위 줄 커버리지), `backend/coverage-scope/u13.txt` | 80% 줄 이상 | 86.1% (1,727/2,006) | `-PincludeTags=u13 -PcoverageScope=u13` jacoco XML, `test-results.md` 3절 | build-and-test | Met |
| T-COV-MOD | `team.md` Testing Posture(모듈 전체 줄 80%) | 80% 줄 이상 | 하한 검증 통과 | `verify.sh` A1.tests(`JaCoCo 80% LINE(check)`) | build-and-test | Met |
| T-REG | Testing Contract scope floor(`refactor`: 기존 시험 green) | 기존 시험 실패 0 | 3,555건 중 실패 0 · 건너뜀 3(기존) | `build/verify/report.txt` A1.tests | build-and-test | Met |
| T-STD | Testing Contract strategy volume(Standard: 컴포넌트당 5~8개 + 핵심 경계 통합) | 컴포넌트당 5~8개 이상, 경계 통합 시험 존재 | u13 169건 / 19개 클래스, 통합 `GuardrailTurnIT` 4 · `GuardrailEventRepositoryIT` 6 · `GuardrailEventSchemaIT` 6 | `integration-test-instructions.md` 1절 | build-and-test | Met |
| T-E2E | `team.md` Walking Skeleton(회귀 기준 `bash scripts/verify.sh`) | 통과 | 12단계 PASS, C1 ~ C12 | `build/verify/report.txt`(revision `421e1ade0cb9`) | build-and-test | Met |
| T-GUARD | `project.md` R-F1 · R-F2 · R-F3 · R-F14(금지 규칙 준수) | 실제 공급자 호출 0, 하한 · 단언 약화 0, 비밀 값 0, 원문 로그 0 | 실제 제브 호출 0, 약화 0(기존 단언 변경은 V6 사실 반영뿐), 비밀 스캔 걸림 0, 로그 위생 P2 PASS | `verify.sh` A4 · P2 · P3, 코드 생성 검토(`reviews/review-01.md`) | build-and-test | Met |

`Not Met` · `Unverified` 대상은 없다. 단, 성능 · 실제 제브 응답은 **목표값이 정해진 대상이 아니라서 행에 넣지 않았다**(5절 · `performance-test-instructions.md`).

## 4. 코드 생성 검토에서 넘어온 Minor 7건 (열린 항목)

R-01 안전 타이머 2배 값이 `contracts/timing.md`에 없음 · R-02 `code-summary.md` 4절 낡음 · R-03 커버리지 분모에서 `SessionExecutor` 제외 · R-04 지표 라벨 허용 집합이 모든 지표에 공통으로 넓어짐 · R-05 재지시 ALLOW 뒤 아이 발화 없는 서버 응답의 첫 막힘이 재지시 없이 포기됨 · R-06 제브 장치 없는 호환 생성자가 OFF로 fail-open · R-07 프로필 없음의 기본 모드가 STUB. 모두 Minor이며 이번 단계에서 고치지 않았다(검토 영수증을 보존하기 위해). 승인 관문에서 사람이 처리 여부를 정한다.

## 5. 배포 준비도와 한계 (deployment-ready: 아니오)

1. **실제 제브 미확인.** 요청 경로 `/v1/guardrail/check`와 본문 · 응답 필드는 형식 가정이다. 응답 형식 · 지연(800ms가 충분한지) · 분류 품질(AC2.1.10 · AC2.1.16)은 사람이 `jevLiveTest`로 확인해야 한다. 그 전에 **제브 R-F15 확인 기록**(`infra/RUNBOOK.md` 단계 4)이 비어 있다.
2. **demo 배포 순서.** 이 SHA는 키 · 주소 · 모델이 없으면 realtime이 기동을 거부한다. ① R-F15 확인 기록 → ② Parameter Store 세 값 입력 → ③ 배포.
3. **병합 순서.** U13 → U12(`SessionExecutor`를 같이 고침). 다음 realtime 마이그레이션은 V7이고, 그 단위는 `:core-api`의 `FlywayConfIT` · `SchemaPrivilegeIT`도 함께 갱신해야 한다(`test-results.md` 4절 교훈). `team.md`의 "다음 번호 realtime V5"는 낡았다.
4. 이 PC에는 `trivy` · `gitleaks`가 없어 이미지 취약점 스캔(③)과 이력 비밀 스캔은 CI가 돌린다.
5. `DeviceGatewayU3IT` 일시 실패 1회(코드 생성 중)는 이번 단계 3회 실행에서 재발하지 않았다. CI에서 재발하면 이번 변경과의 관련성을 다시 본다.
