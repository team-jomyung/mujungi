**Collaborator:** aidlc-quality-agent

## Contribution

> 관점: 품질 · 시험(quality). 기준은 브랜치 `ops/local-run-readiness`(origin/main `72d7034`)이고, 소스와 리드 초안은 고치지 않고 읽기만 했다.
> 이 PC에는 JDK 21이 없고 `gh`도 쓸 수 없어 시험을 다시 돌리거나 main CI 실행 결과를 조회하지는 못했다. 아래 판단은 모두 저장소 파일과 앞선 작업 기록에서 나왔다.

### 1. 실제로 해 온 시험 방식 (Methodology · Ordering)

- 리드 초안의 **test-after**와 「층 하나 구현 → 그 층 시험 작성 · 실행」 순서는 근거와 맞는다. U1 · U4b · U5b · U6 Code Generation 계획과 Build and Test 요약이 모두 그렇게 적었다(예: `261014-u6-result-report/construction/build-and-test/build-and-test-summary.md` 「Standard·test-after 계약을 따랐다」).
- 다만 이번 작업은 **이미 있는 코드에 통합 시험을 더하는 일**이다. 새로 더할 끝-끝 시나리오는 실제로는 기존 동작을 고정하는 **특성 시험(characterization)이자 회귀 시험**이라서, test-after인지 시나리오 먼저인지를 고르는 것이 이번 작업의 결과를 크게 바꾸지 않는다. 그러므로 팀 규칙으로는 test-after를 확정하고, 이번 작업에서 시험 순서를 따로 정하는 질문은 짧게 끝내자고 제안한다.
- 팀이 org 기본값보다 **더 엄격하게** 해 온 점이 있다. 이 내용을 `team.md` Testing Posture에 명시해야 한다.
  - 유닛 범위 커버리지: 모듈 전체 80%에 더해, 유닛이 새로 만들었거나 바꾼 파일만 따로 80%를 잰다(`backend/coverage-scope/*.txt` · `frontend/coverage-scope/*.json`, `vitest.config.ts`는 범위 목록이 비어 있거나 없는 파일이 들어 있으면 **실행을 멈춘다** — 빈 측정으로 통과하지 않는다).
  - 음성 대조(negative control): 관문이 실제로 막는지 위반 샘플로 확인한다(`ci.yml` `images` 「관문 음성 대조」, `secrets` 「양성 대조 — 가짜 키를 gitleaks가 잡아야 한다」, `infra-validate` 「위반 샘플이 거부되는지」).
  - 미검증을 통과로 보고하지 않는다: `verify.sh`에는 단계 건너뛰기 옵션이 없고, 전제가 빠지면 종료 코드 2로 끝나며 「통과로 보고하지 않음」. host 모드에서 확인하지 못한 항목은 `NOT_VERIFIED(host)`로 남긴다. Build and Test 산출물도 건너뛴 시험을 `skipped-tests.md`에 따로 적었다.
  - 운영 jar에 시험 클래스 · 시험 라이브러리가 0건이어야 한다(`verifyBootJarHasNoTestClasses`).

### 2. 커버리지 도구 · 하한 — 리드 초안 정정 1건

- 백엔드: JaCoCo `LINE` `COVEREDRATIO` `0.80`, 대상은 `coverageFloorApplies`가 켜진 모듈(contract-model · core-api · realtime-activity)이다. `test-support`는 제외된다(`mujoong.java-conventions.gradle.kts`). `check`가 이 검증에 의존한다.
- 프런트: Vitest v8, `thresholds.lines: 80`.
- **tools(가짜 공급자 · 기기 시뮬레이터 · C안 시나리오)에는 커버리지 하한이 없다**(`ci.yml` `tools` 작업은 `vitest run tests`만 돈다). 시험 도구 자체의 결함이 끝-끝 결과를 흔들 수 있으므로 공백으로 기록해 둔다. 하한을 새로 둘지는 사람이 정한다.
- **정정**: 리드 초안은 「유닛별 범위 파일 … 80% 이상. CI에서 막는다」라고 적었다. 그러나 CI가 범위 하한을 실제로 거는 것은 **U5b(`u5b` · `u5b-core`)와 U7뿐이다**(`u5b-scope` 작업, `frontend` 작업의 U5b · U7 단계). U1 ~ U6 범위 파일은 각 유닛의 Build and Test에서만 쟀고, 지금 CI는 모듈 전체 80%만 막는다. 그러니 「CI는 모듈 80% + U5b · U7 범위 80%, 나머지 유닛 범위 80%는 유닛 작업 때 Build and Test에서 확인」으로 고쳐 적어야 한다.

### 3. CI 품질 관문 — 확인해야 할 사실

- 필수 검사는 `ci-gate` 하나다. 이 검사는 건너뜀은 허용하고, 실패와 취소는 허용하지 않는다.
- **끝-끝 검증 `skeleton`(C안)은 PR에서 돌지 않는다.** 경로 필터가 `push` → `main`일 때와 `workflow_dispatch`일 때만 `skeleton=true`로 둔다(J103, 시간이 길어서). 그래서 PR의 `ci-gate` 통과는 끝-끝 통과를 뜻하지 않고, 두 앱을 함께 띄웠을 때의 회귀는 **병합한 뒤에야** 드러난다. 그래도 배포는 main 실행의 `ci-gate`(skeleton 포함)가 막는다.
- 사람의 요청 「모든 개발이 완료된 후 merge된 것으로 알고 있음. 확인 후…」에서 **「확인」의 품질 근거**는 `72d7034`에 대한 main `ci` 실행이 skeleton을 포함해 성공했다는 증거다. 이 증거는 저장소 안에 없다(`integration-2026-10-14.md`도 「실행 번호와 최종 결과는 통합 PR에 기록한다」고만 적었다). 실행 번호를 받거나, `workflow_dispatch`로 한 번 다시 돌리거나, 현장 PC에서 `verify.sh`를 직접 돌려 확보해야 한다.

### 4. 「개발 완료」 전제와 맞지 않는 품질 기록 — 인터뷰에서 반드시 확인

- U5b Build and Test 결과는 **FAIL(Met 11 / Not Met 0 / Unverified 12)**이고, 사람이 「Accept failure」를 골랐다. 전체 완료에는 후속 통합 INT-1 ~ INT-5(실제 Realtime 연결, Core 시작 준비 · API, 실제 어댑터 · 기록 · 노출, 보호자 화면 18 → 12 연결, V3 → V4 마이그레이션 · 종합 검증)가 필요하다고 적혀 있다(`261013-u5b-practice/construction/code-generation/code-generation-plan.md` 70 ~ 76 · 94행).
- 그 뒤 main에 `feat(u5b): persist practice preparation and connect core start`, `integrate practice runtime playback and settlement`, `fix(integration): map all practice preparation failures`가 들어왔다. INT 일부가 통합 브랜치에서 처리된 것으로 보이지만, **INT-1 ~ INT-5가 모두 실제 통합 시험으로 닫혔다는 기록은 찾지 못했다**. 연습(U5a · U5b) 흐름이 현장 시연 범위에 들어가는지를 정하기 전에 이 점부터 확인해야 한다.
- U6에서는 계정 삭제 경로의 연결 풀 고갈(동기 `AFTER_COMMIT` → `REQUIRES_NEW`) 위험을 사람이 **수용**했고, 간헐적으로 다시 생길 위험을 유지한다고 적었다. 시험 문맥만 풀을 3으로 늘렸고 운영 풀은 10이다. 현장에서 오래 띄워 두면 이 위험이 드러날 수 있다.

### 5. 시험 종류와 패턴 — 리드 초안에 동의, 보충

- 피라미드 모양은 건강하다. 단위 테스트가 가장 많고, 통합 테스트는 앱 하나 + 실제 PostgreSQL(Testcontainers) + 상대 앱 `ContractStubServer` · `FakeProviderWsServer` · `DeviceSimulatorClient`로 이루어지며, 끝-끝 테스트는 C안 하나다.
- 계약 대조(`ContractConformanceIT` · `FlywayConfIT` · 시드 대조 IT · `ConfigKeyContractTest`)가 앱 사이 이음새를 **한쪽씩** 지킨다. 실제로 함께 띄워 확인하는 이음새는 자유대화(IX-1) 하나뿐이다.
- ArchUnit과 ESLint 경계는 구조 회귀를 막는다. 이 둘은 품질 관점에서 유지해야 할 규칙이다.

### 6. 흔들리는 시험 (flaky) — 현장 기준선을 흔들 수 있음

| 항목 | 상태 | 근거 |
|---|---|---|
| `RealtimeInternalApiIT` 부하 시 202 대신 409 | 열림(CLOSING 시험 프로필 3초 · 고아 점검 1초) | `docs/units/README.md` 「알려진 흔들림」 |
| `SessionLifecycleIT` DEVICE_ACK 2000ms 경계 | 열림 | `code-quality-assessment.md` 「시험 체계」 |
| `RealtimeDeletionHandlerIT` 원음 파일 생성 대기 | 열림 | 같은 곳 |
| 지표 시험 수동 `tick` ↔ 주기 작업 경합, 기록 발행 `AFTER_COMMIT` 대기, 무작위 traceId에 `bad`가 들어간 경우 | 10/14 고침(`092f909` · `cf19eaa` · `1c5d596`) | `integration-2026-10-14.md` 「통합 중 보완」 |
| 프런트 U7 설정 시험 worker 기동 시간 초과(로컬 1건, Linux CI는 통과) | 열림 — **WSL · Windows 로컬에서만** | `integration-2026-10-14.md` 「검증」 |
| `MujoongPostgresTest` LOCAL 3건 건너뜀(PG 16 없음) | PG 18.6에서는 통과, PG 16 기준은 미검증 | U6 `skipped-tests.md` |

- 현장 PC가 Windows + WSL이면 프런트 worker 시간 초과가 A 단계를 실패시킬 수 있다. 「다시 돌려 통과하면 인정」할지, 그 횟수를 몇 번까지 허용할지를 미리 정해야 검증 결과를 두고 다투지 않는다. 품질 관점의 권고는 이렇다: 재실행은 최대 1회, 재실행 사실과 실패한 시험 이름을 `report.txt` 옆에 기록, 시험을 끄거나 `@Disabled` 처리하는 것은 금지.

### 7. 로컬 현장 실행에 필요한 통합 시험 — 우선순위 제안

`code-quality-assessment.md` G1 ~ G7과 M1 ~ M13을 「현장 실행」 기준으로 다시 묶었다. 사람이 「로컬 현장」의 모습을 정해야 범위가 확정된다.

**P0 — 현장 실행 준비의 합격선. 대역(test double)이 없어도 된다**

1. **현장 PC에서 C안 기준선**: JDK 21을 설치한 뒤 `bash scripts/verify.sh --mode compose`가 종료 코드 0으로 끝나고 `report.txt`가 남아야 한다. host 모드는 PG 16 전제(M2)를 충족하지 못하므로 compose로 고정할 것을 권장한다.
2. **G2 결과 상태 단언**: C안 시나리오 끝에 `GET /api/sessions/{id}/result`가 `DONE` 또는 `FAILED`로 끝까지 가는지 단언을 하나 더한다. 공급자가 가짜라서 AI 없이 끝나는 1단계 경로(INSUFFICIENT → DONE)를 확인할 수 있다. 통합 중 연결 풀 고갈 이력이 있는 이음새라서 가치가 가장 크다.
3. **G7 시드 001 → 002 → 003 적용 확인**: 승인 문구 6종이 시작 패키지에 실리는지 직접 단언한다(M3 · M4 문서 누락과 짝을 이룬다).
4. **재기동 · 지속성 시험(M11)**: 현장 상시 기동을 목표로 한다면, 스택을 내렸다 올린 뒤에도 비밀 · env · DB 볼륨이 유지되고 3종 호출과 C 한 턴이 다시 통과해야 한다. 지금 `KEEP=1` 스택은 재기동에 실패하는 것으로 알려져 있다.
5. **(태블릿을 LAN으로 붙이는 경우) LAN 접속 스모크(M7)**: 다른 기기에서 `https://<현장 호스트>/device`가 Secure Context로 열리는지(인증서 · CA 신뢰), WSS 101이 나는지를 수동 점검표로 확인한다.

**P1 — 시연 범위를 넓힐 때만**

6. **G4 계정 삭제 ↔ Realtime 삭제 처리**: 대역 없이도 할 수 있다. 수용된 풀 고갈 위험을 실제 스택에서 한 번은 확인해야 한다.
7. **G5 리포트 PDF 내려받기**: 실행 이미지(read_only · tmpfs)에서 확인한다. 대역 없이도 할 수 있다.
8. **G1 연습 흐름**: 실행 스택에 텍스트 AI · 판정 · Cue 대역 프로필을 넣는 **코드 변경**이 필요하다(M9). 4절의 INT 상태를 확인하기 전에는 착수하지 않기를 권한다.
9. **G3 웹 푸시**: VAPID 값의 생성 · 분배 경로가 없다(M10). 시연에 푸시가 필요할 때만 진행한다.

**수동 점검표로 남길 것 (자동화 도구를 새로 들이지 않음)**

- **G6 실제 브라우저 기기**(마이크 워클릿 · 오디오 재생 · 권한 팝업): 지금 브라우저 E2E 도구가 없다. 현장 준비 작업에서 Playwright 같은 도구를 새로 들이는 것은 비용 대비 효과가 낮다. 실제 태블릿에서 확인하는 짧은 점검표(10항목 안팎)를 권한다.
- **장시간 기동(soak)**: 현장에서 시연 시간 동안 띄워 둔다면, 기기 시뮬레이터로 세션을 N회 반복하며 메모리 · DB 연결 수가 늘어나지 않는지 보는 가벼운 반복 실행을 고려한다. 부하 시험 도구(`compose.loadtest.yml`)는 없다(M12).

### 8. 인터뷰에서 정해야 할 품질 질문 (리드 질문 6 · 7 보완)

1. `72d7034`의 main `ci` 실행(skeleton 포함)이 성공했다는 실행 번호를 줄 수 있는가? 없다면 현장 PC의 `verify.sh` 결과를 「병합 상태 확인」의 근거로 삼아도 되는가?
2. U5b의 Unverified 12건과 INT-1 ~ INT-5는 통합 브랜치에서 닫혔는가? 연습 흐름을 현장 시연 범위에 넣는가?
3. 위 P0 다섯 가지를 이번 작업의 합격선으로 삼을까? 2 · 3번(G2 · G7 단언)은 C안 시나리오에 더해 CI `skeleton`에도 들어가게 할까?
4. 흔들리는 시험을 다시 돌리는 것을 허용할까(허용한다면 최대 1회, 기록 필수)?
5. tools(시험 도구)에 커버리지 하한을 둘까, 지금처럼 하한 없이 둘까?
6. 실제 기기 · 브라우저 확인은 수동 점검표로 대신해도 되는가?

## Positions

- AGREE: Methodology = test-after, Ordering = 층 구현 뒤 그 층 시험 — U1 · U4b · U5b · U6 기록이 모두 그렇게 했다. 이번 작업의 새 시험은 특성 · 회귀 시험이라서 순서 선택의 영향이 작다.
- AGREE: 하한 · 기존 단언을 낮추지 않는다는 Forbidden 후보 — 통합 기록과 U6 계획이 실제로 지켰고, org 규칙과도 맞는다.
- AGREE: 시험 · CI · 검증에서 실제 유료 AI 공급자를 부르지 않는다는 Forbidden 후보 — 단, 이 규칙은 시험 · 검증에만 적용하고 현장 런타임에서 실공급자를 쓸지는 따로 정한다는 점을 문구에 남겨야 한다.
- AGREE: 검증 스택을 demo 호스트에서 돌리지 않는다는 후보 — RUNBOOK 17절. 현장 PC가 상시 운영 호스트가 된다면 같은 원칙(시험 로그인 · 가짜 공급자 프로필을 상시 스택과 분리)을 현장 PC에도 적용해야 한다.
- OBJECT: Testing Posture 「유닛별 범위 파일 … 80% 이상. CI에서 막는다」 — CI가 범위 하한을 거는 것은 U5b · U7뿐이고 U1 ~ U6 범위는 유닛 Build and Test에서만 쟀다. 사실에 맞게 고쳐야 한다.
- OBJECT: 끝-끝 시험의 위치를 적지 않았다 — `skeleton`은 PR에서 돌지 않고 main 병합 · 수동 실행에서만 돈다. 이 사실이 Testing Posture에 없으면 「PR의 `ci-gate` 통과 = 끝-끝 통과」로 잘못 읽힌다. 명시해야 한다.
- OBJECT: 「개발 완료」 전제를 그대로 받아들이고 있다 — U5b Build and Test가 FAIL(Unverified 12, INT-1 ~ INT-5 후속)이고 U6 연결 풀 위험은 수용된 상태다. 인터뷰 질문에 이 확인을 넣어야 한다.
- AGREE: 통합 시험 공백 G1 ~ G6을 인터뷰에서 정하자는 리드 제안 — 다만 대역 없이 할 수 있는 G2 · G7 · 재기동 시험을 P0로, 대역이나 코드 변경이 필요한 G1 · G3을 P1로 나눠 묻기를 권한다.
- AGREE: 실제 기기 음성 · 브라우저는 자동 시험으로 대체하지 않는다 — 브라우저 E2E 도구를 새로 들이지 않고 실제 태블릿 수동 점검표로 확인할 것을 권한다.
- OBJECT: discovered-rules에 빠진 Mandated 후보 — ALWAYS 새 관문 · 검사를 더할 때는 막혀야 할 위반 샘플(음성 대조)로 실제로 막히는지 함께 시험한다 — `ci.yml`의 관문 음성 대조 · gitleaks 양성 대조 관행.
- OBJECT: discovered-rules에 빠진 Forbidden 후보 — NEVER 전제 누락 · 건너뜀 · 미검증을 통과로 보고하지 않는다(`NOT_VERIFIED` · 종료 코드 2 · `skipped-tests.md`로 남긴다) — `verify.sh`와 Build and Test 기록이 이미 지키는 규칙이다.
- AGREE: 포매터 없음(Code Style 확인 필요 항목) — `code-quality-assessment.md` 「정적 품질」은 프런트에 Prettier가 있다고 적었지만 `frontend/package.json`에도, `.prettierrc*` 파일로도 확인되지 않는다. 포매터가 없다는 리드 초안이 맞고, codekb 문장을 고쳐야 한다.
