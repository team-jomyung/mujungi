# 제브 어댑터 선 형식 정정 — Build and Test 요약

> 작업 기록 `261017-jev-adapter-format` · 범위 `bugfix` · 시험 전략 Standard · 브랜치 `unit/u13-jev-guardrail`(검증 기준 커밋 `46ee0e5`).
> 증거 구분(R-M11): 합격은 **테스트 대역**과 **합성 대화**의 결과다. **실제 제브 API · 실제 기기 증거는 없다.**

## 1. 전체 상태

| 항목 | 상태 |
|---|---|
| 빌드 | 성공 — 백엔드 · 프런트 · tools · 이미지 5종 · compose 스택 기동 |
| 시험 | 백엔드 3,574건 중 실패 0 · 건너뜀 3(기존 `test-support` LOCAL 사례) · u13 태그 188건(앞 169건 +19) 통과 |
| 끝-끝 회귀 | `bash scripts/verify.sh --mode compose` 12단계 모두 PASS(335초), 기기 시뮬레이터 한 턴 C1 ~ C12 통과 |
| 이번 단계 발견 | 코드 · 시험 결함 없음. 첫 `verify.sh`의 T 단계는 실행 중 제가 문서를 써서 실패했고(작업 트리 변경), 다시 돌려 통과. 상세는 `test-results.md` 4절 |
| 준비 상태 | build-ready: 예 · test-ready: 예 · **deployment-ready: 아니오**(5절) |

## 2. 만든 시험 지시서

| 파일 | 내용 |
|---|---|
| `build-instructions.md` | 전제 · 환경 · 빌드 명령 · 문제 해결 |
| `integration-test-instructions.md` | 경계별 통합 시험(Standard 필수), 덮지 못하는 것 |
| `performance-test-instructions.md` | 측정 목표 없음 — 확인 가능한 시간 값과 하지 않은 것을 정직하게 적음 |
| `security-test-instructions.md` | 변경분 중심 STRIDE 위협 → 통제 → 증거, R-F15 미확인 |
| `test-results.md` | 실행 결과 · 발견 기록 |
| `cross-unit-traceability.md` | FR · NFR 최종 추적 확인 |

## 3. 유닛 커버리지

| 대상 | 측정 | 하한 |
|---|---|---|
| U13 범위(`backend/coverage-scope/u13.txt`) | 86.3% (1,781 / 2,063줄) | 80% 줄 |
| `:realtime-activity` 모듈 | `jacocoTestCoverageVerification` 통과 | 80% 줄 |
| 프런트 | 98.55% (변경 없음) | 80% 줄 |

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-COV-U13 | `team.md` Testing Posture · Testing Contract(유닛 범위 줄 커버리지), `backend/coverage-scope/u13.txt` | 80% 줄 이상 | 86.3% (1,781/2,063) | `-PincludeTags=u13 -PcoverageScope=u13` jacoco XML, `test-results.md` 3절 | build-and-test | Met |
| T-COV-MOD | `team.md` Testing Posture(모듈 전체 줄 80%) | 80% 줄 이상 | 하한 검증 통과 | `verify.sh` A1.tests(`JaCoCo 80% LINE(check)`) | build-and-test | Met |
| T-REG | Testing Contract scope floor(`bugfix`: 기존 시험 green) | 기존 시험 실패 0 | 3,574건 중 실패 0 · 건너뜀 3(기존) | `build/verify/report.txt` A1.tests | build-and-test | Met |
| T-BUGREG | Testing Contract scope floor(`bugfix`: 이 버그의 표적 회귀) | 경로 · 본문 최상위 키 · 판정 경계를 단언하는 시험이 존재하고 통과 | `JevGuardrailClientTest`가 `/v1/systemone` · 키 집합 `model`/`state`/`questions` · 옛 키 없음 · 0.5 직전/정확히/직후 · 동률을 단언, 통과 | u13 188건 통과 | build-and-test | Met |
| T-STD | Testing Contract strategy volume(Standard: 컴포넌트당 5~8개 + 핵심 경계 통합) | 어댑터 · 정책 리소스 · 기동 점검에 단위 시험, 경계 통합 시험 존재 | `JevGuardrailClientTest`(가짜 제브 서버 루프백), `GuardrailPolicyV2Test`, `GuardrailModeStartupChecksTest`, 계약 대조, u13 +19 | `integration-test-instructions.md` 1절 | build-and-test | Met |
| T-E2E | `team.md` Walking Skeleton(회귀 기준 `bash scripts/verify.sh`) | 통과 | 12단계 PASS, C1 ~ C12 | `build/verify/report.txt`(revision `46ee0e57d4ca`, 두 번째 실행) | build-and-test | Met |
| T-GUARD | `project.md` R-F1 · R-F2 · R-F3 · R-F14(금지 규칙 준수) | 실제 공급자 호출 0, 하한 · 단언 약화 0, 비밀 값 0, 원문 로그 0 | 실제 제브 호출 0, 약화 0(기존 단언 변경은 새 형식 사실 반영뿐), 비밀 스캔 걸림 0(`aidlc/` 기록 포함), 로그 위생 P2 PASS | `verify.sh` A4 · P2 · P3, 코드 생성 검토(`reviews/review-01.md`) | build-and-test | Met |

`Not Met` · `Unverified` 대상은 없다. 단, 실제 제브 응답 · 지연 · 임시값(문턱 0.5 · 확률 합 오차 0.001)은 **목표값이 정해진 대상이 아니라 실호출로만 확정되는 열린 항목**이라 행에 넣지 않았다(5절).

## 4. 코드 생성 검토에서 넘어온 Minor 5건 (열린 항목)

R-01 비숫자 `JEV_PASS_THRESHOLD`는 Spring 바인딩이 기동 점검보다 먼저 실패할 수 있음(검토자가 확인하지 못함) · R-02 고정 버전 모델 이름을 제브가 받는지 미확인 · R-03 정책 문안은 명세 §10.2 · 인계서 §3.2 · §3.3을 옮겼고 격리 문장의 필드 이름만 바꿈 · R-04 `deviations` (U13-b)와 (U13-c)의 판 이름이 상호 참조 없이 읽힘 · R-05 `U8RealtimeStartupChecksTest` 단독 실행 순서 의존(이번 변경과 무관). 모두 Minor이며 이번 단계에서 고치지 않았다(검토 영수증을 보존하기 위해). 승인 관문에서 사람이 처리 여부를 정한다. 요구사항 검토의 Minor(R-03 확률 합 오차, R-05 `jev-latest` 충돌, R-06 FR3 출처)는 계획 D7 · D10 · D11에서 반영했다.

## 5. 배포 준비도와 한계 (deployment-ready: 아니오)

1. **실제 제브 미확인.** 정정한 선 형식이 실제 제브에서 통하는지(특히 `model`에 고정 버전 이름, 한국어 `criteria` 8개), 오류 본문 모양, `confidence` 의미, 지연 · 분류 품질은 사람이 `jevLiveTest`로 확인해야 한다. 형식이 어긋나면 모든 검사가 막힘이 되어 아이가 대화를 못 듣는다(fail-closed). 제브 **R-F15 확인 기록**(`infra/RUNBOOK.md` 단계 4)이 비어 있다.
2. **임시값.** 문턱 0.5(`mujoong.guardrail.jev.pass-threshold`)와 확률 합 허용 오차 0.001은 근거 없는 임시 시험값이다. 문턱은 설정으로 조정하고, 합 오차는 코드 상수라 바꾸려면 코드 변경이 필요하다.
3. **demo 배포 순서.** 이 SHA는 키 · 주소 · 모델이 없으면(그리고 문턱이 범위 밖이면) realtime이 기동을 거부한다. ① R-F15 확인 기록 → ② Parameter Store 입력 → ③ 배포.
4. **병합 순서.** 이 정정은 U13 PR에 함께 들어간다(U13이 아직 `main`에 병합 전). U13 → U12 순서는 그대로다.
5. 이 PC에는 `trivy` · `gitleaks`가 없어 이미지 취약점 스캔(③)과 이력 비밀 스캔은 CI가 돌린다.
