# 시험 결과 — 구조 · 이름 리팩토링

> 측정: 2026-10-19, 브랜치 `claude/refactor-structure`(소스는 리비전 `53a4d2d5e63b` 위의 미커밋 변경 포함), 이 PC(WSL Ubuntu, JDK 21, Node 22, Docker). 모든 결과는 **테스트 대역(가짜 공급자) · 합성 대화** 기준이다. **실제 API · 실제 기기 성공 증거는 없다**(R-M11).

## 결과 요약

| 항목 | 결과 |
|---|---|
| 빌드(백엔드 · 프런트 · tools · bootJar) | 성공 |
| 백엔드 시험 | 3,941건 · 실패 0 · 건너뜀 3 (기준선 3,937건 + 특성화 4건) |
| 프런트 시험 | 74개 파일 · 994건 통과, 줄 커버리지 98.56% |
| tools 시험 | 12개 파일 · 133건 통과 |
| 전체 `verify.sh --mode compose` | **두 번째 실행 `RESULT PASS`** (첫 실행은 A1 실패, 아래) |
| 합격 기준 AC-1 ~ AC-9 | 8개 충족, **AC-9 미확인(Unverified)** |

## 실행 이력

| 실행 | 결과 | 비고 |
|---|---|---|
| 1. `verify.sh --mode compose` | **A1 FAIL**, `RESULT FAIL rc=1` (305초) | 시험 5건 실패: `ReportPdfTest.twentyActivityReportCreatesNoTemporaryFiles`(`/tmp/runc-process2708658930`이 남음) 1건, `RealtimeLogHygieneIT` 4건(`기기가 기대한 메시지를 받지 못했다(제한 PT10S)` 1건, `entityManagerFactory` → Hikari `dataSource` 생성 실패 3건) |
| 진단 | — | 같은 PC에 `mujoong-live-*` 스택 5개 컨테이너가 31분째 떠 있었다. 그 컨테이너의 healthcheck가 `/tmp`에 `runc-process…` 파일을 만들고 자원을 나눠 쓴 것이 원인으로 **추정**한다. 이 추정은 실패 시점에 직접 확인하지 못했고, 코드 결함이 아니라는 증거는 다음 두 줄이다 |
| 2. 실패한 5건만 단독 재실행 | 통과 (`BUILD SUCCESSFUL in 17s`) | `./gradlew :core-api:test --tests "*ReportPdfTest" :realtime-activity:test --tests "*RealtimeLogHygieneIT" -Pmujoong.test.db=docker` |
| 3. `verify.sh --mode compose` 다시 | **`RESULT PASS rc=0 total=405s`** | A1 278s · A2 34s · A3 10s · A4 1s · B 58s · C 20s · P1 2s · P2 0s · P3 1s · T 0s |

코드 · 시험 · 임계값은 첫 실행과 두 번째 실행 사이에 바꾸지 않았다(소스 지문 `sha256:386abd66…`이 두 실행에서 같다). `mujoong-live-*` 스택은 건드리지 않았다(R-F12).

## 단계별 결과(두 번째 `verify.sh`)

- **A1 backend**: bootJar 두 개 시험 클래스 0, 시험 3,941건 · 실패 0 · 건너뜀 3(`MujoongPostgresTest`의 `backend = LOCAL` 매개변수 3건, 기준선과 같음), 모듈 전체 JaCoCo 줄 80% 통과.
- **A2 frontend**: eslint · vitest(80% 줄) · next build 통과, 줄 98.56%.
- **A3 tools** · **A4 secrets**: 통과(gitleaks 없음 — 이력 스캔은 CI 몫).
- **B 기동**: 이미지 5종 빌드, 병합 설정 유효, 포트는 nginx 127.0.0.1:8443 하나, migrate · 시드 멱등, 프로필 가드 4건, 3종 호출 · 보안 응답 통과.
- **C 한 턴**: C1 ~ C12 통과(음성 1턴 12 items, 금지 문장은 출력 0 · WAITING_FILLER 1, 끝내기 CLOSE_TRANSPORT 1회).
- **P1 ~ P3 · T**: 통과.

## 시험 수 대조(AC-5)

| 대상 | 기준선(`main` 570a625) | 지금 | 차이 |
|---|---|---|---|
| contract-model | 322 | 322(합계에 포함) | 0 |
| core-api | 1,722 | 1,722 | 0 |
| realtime-activity | 1,862 | 1,866 | +4 (`SessionClocksCharacterizationTest`, 계획 Step 2의 특성화 시험) |
| test-support | 31 | 31 | 0 |
| **백엔드 합계** | **3,937** | **3,941** | +4 |
| 프런트 | 74개 파일 · 994 | 74개 파일 · 994 | 0 |
| tools | 12개 파일 · 133 | 12개 파일 · 133 | 0 |

## 합격 기준 확인

| AC | 확인 명령 · 값 | 결과 |
|---|---|---|
| AC-1 | 화면 번호 · 유닛 번호가 든 프런트 파일 0개, `parent/screens` 없음 | 0 / 0 충족 |
| AC-2 | ① 경로 · 파일 이름(Flyway 제외) 0 ② `@Tag("u…")` 파일 0 ③ `ci.yml`의 작업 · 태그 · 범위 이름 0 | 충족. `ci.yml` 2번째 줄의 머리 주석에 옛 설계 번호 `U1 3.5 Step 50`이 남아 있다(이름이 아니라 주석) |
| AC-3 | 대상 9개 `*Screen.tsx`의 `router.` · `useRouter` · `catch` · `await` · `fetch` · `endSession` | 8개 0건. `LiveScreen.tsx:93`의 `"fetch"`는 문자열 값(`issue` 속성)이지 호출이 아니다 → 충족 |
| AC-4 | `SessionExecutor.java` 3,090줄(상한 3,100), 분리한 구역 표식 0, 협력 객체 파일 존재 | 충족 |
| AC-5 | `verify.sh` 통과, 시험 수 동일(+특성화 4) | 충족 |
| AC-6 | ESLint 경계 · ArchUnit · `PracticeFlowArchTest` 통과(규칙이 빈 채로 통과하지 않음) | 충족 |
| AC-7 | 이 기록이 건드린 파일의 결정 ID 줄 540 → 10(남은 10은 모두 코드) | 충족 |
| AC-8 | `practice-legacy` 3개 파일 32건, `web-push` 6개 파일 89건(이번에 직접 실행), 목록 줄 수 3 · 6 = 파일 수, 없는 경로 0 | 충족 |
| AC-9 | 이름 변경 뒤 **첫 CI 실행**에서 `hashFiles('frontend/coverage-scope/web-push.json')` 조건 단계가 `success` | **미확인** — 이 브랜치는 푸시하지 않았고 CI 실행 기록이 없다. `ci.yml`에는 해당 조건 단계가 있다(`:200`, `:205`) |

## 실행하지 않은 것

- `infra/bin/infra-validate`, 인프라 시험(`test-host-tools.sh`)은 이번 단계에서 돌리지 않았다. 이 PC에 `/usr/bin/gnudate`가 없다. CI 이름을 바꾼 Step 23의 검증 항목이었고, 이번 단계에서는 다시 확인하지 않았다.
- 원격 `main`과의 병합 따라잡기는 단계 5 확인 때 끝냈고, 그 뒤의 새 `main` 변경은 확인하지 않았다.
- 실제 AI 공급자 · 실제 기기 · 배포 환경 확인은 이 작업의 범위가 아니다.
