# U12 새 연습 흐름 — Build and Test 요약

> 입력: `code-generation-plan.md`(Testing Contract: test-after · Standard · refactor) · `unit-test-instructions.md` · `code-summary.md`. 상세 결과는 `test-results.md`.

## 빌드와 준비 상태

- 빌드 상태: 성공. 전제(JDK 21 · Node 22 · Docker Compose)는 모두 충족했다. 명령은 `build-instructions.md`.
- 시험 종류: 단위 · 통합(`integration-test-instructions.md`) · 계약 대조 · ArchUnit · 끝-끝 대역(`PracticeFlowIT`, `verify.sh` C). 성능 · 보안 별도 묶음은 해당 목표가 없어 만들지 않았다(두 지침 파일에 이유 기록).
- 유닛별 커버리지 기대: 모듈 전체 줄 80%(CI가 막음) · 유닛 범위 줄 80%(`u12`, 로컬 근거).

## Target Verification Matrix

수치 목표의 출처는 NFR 문서가 아니라 Testing Contract · 팀 Testing Posture · 요구사항 NFR다. 이 작업은 NFR 요구 · 설계 단계를 건너뛰었으므로 `nfr-requirements/` · `nfr-design/` 산출물이 없다.

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-SUITE | Testing Contract `scope_floor`(기존 시험 초록 유지) | 실패 0 | 3,937건 · 실패 0 · 건너뜀 3(이전부터) | `build/verify/report.txt` A1.tests | build-and-test | Met |
| T-COV-MOD | team.md Testing Posture(모듈 전체 줄 80%) | 줄 80% 이상 | backend JaCoCo check 통과, frontend 98.55% | A1(check) · A2.coverage | build-and-test | Met |
| T-COV-U12 | `unit-test-instructions.md`(u12 범위 줄 80%) | 줄 80% 이상 | 96.2%(1,222/1,270줄) | 코드 생성 측정 + 검토자 재측정(이번 단계에서 재실행하지 않음) | code-generation | Met |
| T-COV-U5B | 같은 문서(U5b 범위 80% 유지) | 하한 유지 | 통과 | 코드 생성 단계 `-PincludeTags=u5b -PcoverageScope=u5b` 실행 | code-generation | Met |
| T-E2E | team.md Walking Skeleton(`verify.sh` 회귀 기준) | `RESULT PASS` | PASS rc=0, B · C · P1 ~ P3 · T 전부 통과 | `build/verify/report.txt` | build-and-test | Met |
| T-FAKE | R-F1 · ADR-002(가짜 공급자만) | 실제 공급자 호출 0 | realtime 공급자 주소 = 가짜 공급자 | B1.provider | build-and-test | Met |
| T-SECRET | R-F3(저장소에 비밀 값 0) | 걸림 0 | 파일 3,315개 걸림 0, 로그 일치 0 | A4 · P2 · P3 | build-and-test | Met |
| T-ISOLATE | R-F5(두 앱 DB 격리) | 교차 쓰기 거절 | 6건 모두 SQLSTATE 42501 | P1 | build-and-test | Met |
| T-NOWEAKEN | R-F2(하한 · 단언 · ArchUnit 비약화) | 약화 0 | 이번 변경에 `@Disabled` · 건너뛰기 추가 0, 하한 설정 무변경 | `git diff 17ba094..HEAD` 검색 + A1 통과 | build-and-test | Met |

`Pending` 행은 없다. 적용 대상 목표 9개 중 `Unverified`는 0개다.

## 준비 상태 평가

- build-ready: 예. test-ready: 예(대역 · 합성 대화 기준).
- deployment-ready: 이 작업은 배포를 다루지 않는다(Q5-A). PR의 `ci-gate`가 정적 · 단위 · 통합 · 보안 · 인프라 · 이미지를 다시 본다.

## 알려진 한계와 남은 일

- 실제 API · 실제 제브 · 실제 기기 성공은 없다. 모든 통과는 테스트 대역 · 합성 대화다.
- gitleaks 이력 스캔은 CI 몫이다. 연습 흐름은 시뮬레이터 대본에 없어 `verify.sh` C에서 돌지 않는다(후속 과제).
- 코드 검토의 경미 지적 R-01(UUID(0) 슬롯 표지 편차 번호 없음), R-02(`report.md` 없음), R-03(AC1.1.x 회귀 근거만)은 승인된 상태로 남아 있다. `docs/units/u12/report.md`는 이 단계에서도 만들지 않았다.
- `SessionExecutor` 제브 분기(약 +121줄)는 u12 범위 밖이라 모듈 전체 80%로만 본다. 파일 단위 줄 커버리지는 확인하지 않았다.
