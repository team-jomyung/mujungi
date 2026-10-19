# Build and Test 요약 — 구조 · 이름 리팩토링

> 시험 전략 Minimal · 방법론 test-after. 이 작업은 동작을 바꾸지 않는 이동 · 이름 변경 · 분리다. 모든 증거는 **테스트 대역(가짜 공급자) · 합성 대화**이며 실제 API · 실제 기기 성공이 아니다(R-M11).

## 준비 상태와 시험 종류

| 항목 | 상태 |
|---|---|
| 빌드 준비 | 성공(`build-instructions.md`) |
| 시험 준비 | 성공(백엔드 3,941 · 프런트 994 · tools 133 통과) |
| 배포 준비 | 해당 없음 — 이 작업은 배포를 다루지 않는다(Q5-A). CI 첫 실행 확인(AC-9)이 남음 |

생성한 지침: `build-instructions.md`, `integration-test-instructions.md`. 성능 · 보안 지침은 새 NFR이 없어 "해당 없음 / 기존 검사 확인"으로만 적었다. 단위 시험은 코드 생성 단계의 `unit-test-instructions.md`가 맡는다. 유닛 없는 한 기록이라 유닛별 커버리지 기대는 모듈 전체 줄 80%(JaCoCo · Vitest)다.

## 측정 가능한 목표 목록

`nfr-requirements/` · `nfr-design/` 산출물은 이 기록에 없다. 목표는 승인된 설계의 합격 기준(AC)과 Testing Contract(기존 시험 통과, 새 시험 하한 없음)와 팀의 커버리지 하한에서 가져왔다.

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-contract | code-generation-plan Testing Contract | 기존 시험 통과(새 시험 하한 없음) | 백엔드 3,941 · 프런트 994 · tools 133 모두 통과 | test-results.md, `build/verify/report.txt` | build-and-test | Met |
| T-cov-be | team.md Testing Posture | 모듈 전체 줄 80%(JaCoCo) | `check` 통과(`A1.tests` PASS) | `build/verify/logs/A1.log` | build-and-test | Met |
| T-cov-fe | team.md Testing Posture | 줄 80%(Vitest v8) | 98.56% | A2 로그 | build-and-test | Met |
| AC-1 | functional-spec 5절 | 번호 · 유닛 번호 이름 0 | 0 | test-results.md | build-and-test | Met |
| AC-2 | functional-spec 5절 | 유닛 번호 이름 0 | 경로 0 · 태그 0 · `ci.yml` 이름 0 (머리 주석 1줄 잔존) | test-results.md | build-and-test | Met |
| AC-3 | functional-spec 5절 | 9개 화면 호출 0건 | 0건(`LiveScreen`의 `"fetch"`는 문자열 값) | test-results.md | build-and-test | Met |
| AC-4 | functional-spec 5절 | `SessionExecutor` ≤ 3,100줄 | 3,090줄 | `wc -l` | build-and-test | Met |
| AC-5 | functional-spec 5절 | `verify.sh` 통과 · 시험 수 같음 | RESULT PASS, 3,937 → 3,941(+특성화 4) | test-results.md | build-and-test | Met |
| AC-6 | functional-spec 5절 | 경계 규칙 통과 · 빈 규칙 없음 | ESLint · ArchUnit 통과 | A1 · A2 로그 | build-and-test | Met |
| AC-7 | functional-spec 5절 | 결정 ID 줄 감소 | 540 → 10 | code-summary.md | build-and-test | Met |
| AC-8 | functional-spec 5절 | 3개 파일 32건 · 6개 파일 89건 | 3 · 32, 6 · 89 | 직접 실행 | build-and-test | Met |
| AC-9 | functional-spec 5절 | 첫 CI 실행에서 `hashFiles` 조건 단계 `success` | CI 실행 기록 없음 | — | 없음(후속 검증 단계가 계획에 없음) | **Unverified** |

## 결과와 알려진 한계

- **Build and Test는 실패로 본다**: 적용되는 목표 AC-9가 `Unverified`다. 로컬 명령은 모두 통과했다.
- `verify.sh` 첫 실행은 A1에서 시험 5건이 실패했고(다른 Compose 스택이 같은 PC에 떠 있던 환경 간섭으로 **추정**), 코드 변경 없이 단독 재실행과 두 번째 전체 실행이 통과했다. 간헐 실패 위험은 남는다.
- gitleaks가 없어 이력 스캔은 CI `secrets` 작업 몫이다. `infra-validate`는 이번에 돌리지 않았다.
- `team.md`에 옛 규칙 줄과 대체 줄이 함께 있다(코드 생성 검토 R-01, 승인 때 수락한 위험).
