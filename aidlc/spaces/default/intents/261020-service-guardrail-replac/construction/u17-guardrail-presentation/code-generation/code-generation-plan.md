# U17 사실 기반 화면·기기 웹 구현 계획

## Summary

- Builds: 이전 요청의 늦은403이 새 성공의 세션 데이터·사실 신뢰를 지우는 경합 수정
- Touches: frontend 공용 liveUpdates 오류 적용 경계·기존 회귀 시험·U17 검증 기록, 완료된 U16 유지
- Tests: 실패 재현3개 보존, 취소/현재403/CSRF/401 반례, 기능·전체 프런트 검사와 production build

## Loop-back 2 — 늦은403 오류의 현재성

출처: construction/build-and-test/test-results.md의 F-R04/Loop-back 2 및 독립 검토 review-02.md의 R-04. 인증 세대 교체·앞선 요청 순번·동일 세션 이탈/재진입의 세 경우에서 새 ACTIVE 성공 이후 이전403을 반환하면 현재 캐시가 undefined가 된다. 원래14단계와 이전 수정은 완료 이력으로 보존하고 이번 시도에서 다시 구현하지 않는다. 이번 보완은 새 시도의 승인 이후 동일 시도 수정이며, 보완 문서 자체가 사전 승인됐다고 표현하지 않는다.

- [x] Repair 1 — 실제 diff/실행기와 F-R04 재현3개를 확인한다. 기존 단언/전체·기능 LINE80%를 유지한다.
- [x] Repair 2 — liveUpdates.ts의 거절 결과에도 generation/sessionId/sequence/AbortSignal 검사를 부작용보다 먼저 적용한다. 지난 결과는 aborted로 끝내고 현재403만 해당 세션 데이터를 제거한다. 공개 응답/인증·CSRF·401 정책과 폴링 주기를 유지한다.
- [x] Repair 3 — 구현 직후 기존 실패3개를 실행하고 취소된 요청의 늦은403·현재403 제거·다른 세션/계정 보존·늦은 성공 복구 금지·CSRF 갱신/401 회귀를 보강/실행한다.
- [x] Repair 4 — 실제 수정 표면을 기능 범위와 대조하고 기존 U17 기능 명령, 전체 lint·coverage·production build를 실행한다. 백엔드 기준선을 다시 측정하지 않는다. 체크포인트 명령은 등록된 원문을 유지한다.
- [x] Repair 5 — code-summary/verification-results/traceability/source-manifest를 현재 결과·지문으로 갱신하고 독립 검토에 넘긴다. 실제 음성·실브라우저·verify.sh 미검증과 이전 실패 기록을 보존한다.

추정 영향: 구현·회귀20–40분 및 검증 시간, 공통 인증/조회 캐시 경합에 중간 위험. 추가 공급자 비용0. 새 의존성·서버 코드·공개 wire 변경 없음. 이번 실행에서 Chromium/GUI/Next 서버를 시작하지 않는다. 지연 최적화 파이프라인 변경은 포함하지 않는다.

## 입력·소유·영향

현재 브랜치 feat/service-guardrail-replacement의 U16 구현과 기존 변경을 보존한다. 현재 U17 functional-design의 F1–F5/BR17.1–BR17.10, nfr-requirements의15개 상세 기준, nfr-design의 P1–P3/S1–S3, 상위 C1–C3/RP-1–RP-4를 따른다. 새 서버 권한/DB/공개 wire/의존성/배포/실호출은 범위 밖이다. frontend 앱/시험/기능 범위 파일은 U17 담당이며 이미 변경된 backend/contracts/infra 및 다른 기록을 되돌리지 않는다.

예상 운영 표면:
- parent/common/api/types.ts, live.ts, 필요한 현재 응답 적용 helper, queries.ts 및 hooks/useLivePolling.ts.
- parent/features/live/useLiveScreen.ts·LiveScreen.tsx, common/activity/livePresentation.ts·NoticeDialog의 필요한 표시/초점 경계.
- parent/features/done/presentation.ts·DoneScreen.tsx·useDoneScreen.ts.
- shared/copy/{ko,activity}.ts 및 안전한 실제 인용의 필요한 순수 helper.
- 현재 로그인 세대/로그아웃·401·세션 전환의 조회 귀속 무효화가 필요하면 parent/common/{ParentProviders,navigation} 및 api/{apiClient,account}의 해당 연결만 수정한다. 인증/동의/계정 정책·URL·CSRF 의미는 유지한다.
- device/ws/deviceSession.ts, audio/player.ts, 필요한 runtime/deviceRuntime.ts·StatusView 소비. 이미 충족된 구현은 변경하지 않고 관련 회귀로 보존한다.

types/LiveView는 공통 화면·fixture에 영향이 있으므로 구버전/null 기본을 보수적으로 처리한다. 같은 캐시를 갱신하는 폴링·P-01 충돌 재조회·직접 Done 진입을 모두 대조한다. copy 변경은 자유대화 사실 표시에 한정하고 연습·기록 정리·종료 확인 경계를 보존한다. 실제 수정 파일 전체를 Step14 manifest와 기능 범위에 반영한다.

## 적용할 층과 시험 순서

이번 UI에는 새 DB/엔티티 저장·repository·서버 API 구현이 없다. 기존 브라우저 응답/조회 층과 표시·기기 소비 층에 test-after를 적용한다. 실행기는 첫 실행 시험 전에 확인하고, 층을 구현한 뒤 해당 시험을 쓰고 실행한 다음 다음 층으로 간다. 세부 명령/fixture/모킹/coverage는 unit-test-instructions.md를 따른다.

## 실행 단계

- [ ] Step 1 — 커밋/실제 diff로 진행을 확인하고 기존 U16/문서 변경을 보존한다. package/runner/config·실행 가능한 기존 단위 명령을 확인한다. 앱 변경 전에 전체 프런트 lint·기존998개 coverage 시험·build 기준선을 실행하고 baseline-results.md에 실제 수치/실패를 기록한다. 직전 U16 결과를 이번 기준선 실행으로 대체하지 않는다.
- [ ] Step 2 — API parser/타입을 구현한다. v1 필수 값·양의 안전한 정수/null 참조·구조 실패 null·미래 문자열 enum UNKNOWN·모르는 속성 무시를 적용하고, 기본 응답/세션 ID·인증 오류를 완화하지 않는다.
- [ ] Step 3 — parser 컴포넌트의 약6개 경계 시험을 쓰고 실행한다. 유효/누락/null/지원 밖 판·필드/타입·미래 enum·잘못된 seq를 묶음 예로 검증한다.
- [ ] Step 4 — 현재 응답 적용/병합을 구현한다. 로그인 세대·sessionId·요청 발행 순서/취소·낮은 statusVersion을 적용 경계에서 검증하고 같은 판의 최신 facts는 수용한다. 모든 캐시 갱신 경로에 공통 규칙을 적용한다. 상태+facts는 한 응답, items만 같은 세션 seq 병합이다.
- [ ] Step 5 — 조회/병합 컴포넌트의 약8개 핵심 경계 시험을 쓰고 실행한다. 폴링과 수동 충돌 재조회 경합·직접 Done·세션 전환/로그아웃 뒤 늦은 응답·취소 불이행·같은 판 재생 변화·null 리셋·모드 경계를 검증한다.
- [ ] Step 6 — liveOutcome/P-03/해당 hook·copy를 구현한 직후 참여 표시 컴포넌트의 약6개 시험을 쓰고 실행한다. REFUSED·NO_RESPONSE·미확인·기술 FAILED의 제목/이동, 최신 제목의 불필요한 재초점 없음, 일반 대화 요청0을 검증한다. 기존 P-01은 유지한다.
- [ ] Step 7 — 종료 순수 표시/실제 인용을 구현한 직후 약8개 시험을 쓰고 실행한다. 정확한 현재 공개 CHILD final/uncertain=false 참조만 text/해당 행 시각을 표시하고 없으면 생략한다. 기본 등록 표현 대체/추가 전사 조회를 제거한다. 일반 사유 제목·인용·인사 줄을 분리한다.
- [ ] Step 8 — DoneScreen/hook의 현재 인사 신뢰 조건·로딩/오류를 구현한 직후 약6개 화면/통합 시험을 쓰고 실행한다. CLOSING+현재 PLAYING만 조건부 표시하고 stale/오프라인/조회 오류·terminal/중단/실패/null은 숨긴다. 같은 판 변화·기기 confirmed와 결과 정리를 구분한다. 아이 종료 추가 확인0을 지킨다.
- [ ] Step 9 — 기기 세션 소비의 실제 C3 현재 세션/연결/재생·중복 제거·종료 정리를 확인하고 필요한 부분만 구현한다. 바로 약6개 기기 세션 경계 시험을 쓰고 실행한다. 실제 재생 전/ACK만으로 COMPLETED0, 과거 세션/연결 콜백의 현재 적용0, 현재 정리 뒤 VOICE_SESSION_CLOSED를 검증한다.
- [ ] Step 10 — 플레이어 중단/실패/끊김 뒤 늦은 onEnded의 완료 금지를 확인하고 필요한 부분만 구현한다. 바로 약6개 재생기 시험을 쓰고 실행한다. 정상 실제 종료·STOPPED·FAILED·늦은 콜백·중복·재연결/즉시 끄기를 구분하며 기존 큐/시한·barge-in 동작을 유지한다.
- [ ] Step 11 — 필요한 접근성/copy·긴 문자열 표시를 마무리하고 바로 약5개 접근성/변경 표면 시험을 쓰고 실행한다. 기존 P-01 취소·P-03 확인 초점/Tab·제목 갱신·동일 상태 반복 낭독 방지·안전한 텍스트·연습 회귀를 확인한다. 가능한320px/200%·긴 한국어/인용·기기 조작/회전 점검은 가상 환경과 실제 브라우저 결과를 구분한다.
- [ ] Step 12 — 기존 vitest.config.ts를 유지하고 frontend/coverage-scope/guardrail-presentation.json 및 .tests를 작성한다. 실제 수정 운영 파일 전체·존재하는 정확한 시험 파일을 열거하고 빈/누락 실패·LINE80%를 유지한다. 기능 명령을 실행해 부족한 경계를 보강한다. 대형 수정 파일의 일부 함수만 제외하거나 기존 단언/계약/import/skip을 낮추지 않는다.
- [ ] Step 13 — 바꾼 쪽 전체 lint·Vitest coverage·production build를 실행한다. 기존 전체/기능 줄80%와 연습·기기·기존 계약을 유지하고, 실패 시 원인/수정/재실행을 구분한다. backend/공개 계약 변경이 추가로 필요하면 이 계획의 frontend 소유를 넘어 독단 수정하지 말고 구체적인 차이를 부모에게 반환한다.
- [ ] Step 14 — code-summary.md·traceability.json·source-manifest.json과 실제 verification-results.md를 작성한다. 할당 AC25개·BR10개·상세 NFR15개의 UI 책임/해당 파일·범위 밖2개를 빠짐없이 추적한다. 변경 파일/삭제 파일/범위와 지문을 대조한다. 외부 검토 전 모든 산출물을 완성하고 앱 편집을 멈춘다. 부모가 검토·단위 완료/체크포인트를 진행한다.

## Standard 컴포넌트 범위

| 컴포넌트 | 계획 시험 수 | 주요 경계 |
|---|---:|---|
| guardrail parser/타입 | 6 | 선택 객체·판·구조·미래 enum·참조 정수 |
| 조회/병합·요청 귀속 | 8 | 모든 호출 경로/취소/인증 세대/상태판/현재 facts |
| live 미참여 표시 | 6 | 사유3종/기술 실패/이동/초점 |
| done 순수 표시·인용 | 8 | 정확한 행·원문/시각·정보 없음·동작 분리 |
| done 화면/hook | 6 | 실제 인사 신뢰/로딩/오류/미확인·결과 |
| 접근성/연습 변경 표면 | 5 | 키보드/초점/반복 낭독/긴 문구/모드 |
| 기기 세션 확인 소비 | 6 | 현재 세션·연결·중복·실제 종료 확인 |
| 플레이어 재생 수명 | 6 | 실제 완료/중단·실패·늦은 콜백 |

약51개는 구현 단언을 그대로 복제하는 시험이 아니라 관측 가능한 권한/표시/전송 경계의 신규·강화 회귀다. 5–8개/컴포넌트와 핵심 통합은 동시에 적용한다. 시나리오가 늘어나면 시험을 추가하되 하한·기존 단언을 낮추지 않는다.

## 위험·제외·최종 인계

기존 잘못된 ‘DECLINED=무응답’·기본 종료 표현 인용·‘CLOSING=인사 중’ fixture/기대는 승인된 UI 동작에 맞춰 강화한다. 기존 확인/권한/숫자/전송/실패 단언은 유지하고 필요한 실제 facts를 명시한 예만 사용한다. 모든 fixture에 임의 성공 facts를 주입해 통과시키지 않는다.

U16 R-01(안내 이전 전사 항목의 늦은 조각 재수락)은 미해결 서버 경계다. U17 표시로 해결했다고 주장하지 않고 최종 Build and Test에 실행기 회귀·수정 검증을 인계한다. 운영 승인 음성 부재/실호출·실기기·배포·migration·main 병합은 수행하지 않는다. 기존 전체 verify.sh는 Build and Test에서 실행하며 이번 UI 기준선/최종 프런트 결과와 구분한다.

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "team",
  "ordering": "시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.",
  "scope": "classic",
  "test_strategy": "standard",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: test-after\n- **Ordering**: 시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.\n- 계약에 닿는 변경도 같은 순서를 따른다(Q3-A). 다만 설계 문서 변경이 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고친다(`discovered-rules.md` R-M10).\n- 커버리지 하한(줄 기준만, 분기 · 함수 하한 없음):\n  - 모듈 전체 줄 80%를 CI가 막는다. 백엔드 JaCoCo는 `contract-model` · `core-api` · `realtime-activity`(`test-support`는 제외), 프런트 Vitest v8은 `src/**/*.{ts,tsx}`(`*.d.ts`만 제외).\n  - 범위 파일이 있는 기능에는 기능 범위 줄 80%를 적용한다(`backend/coverage-scope/<기능>.txt` · `frontend/coverage-scope/<기능>.json`, 시험 목록 `<기능>.tests`). 현재 CI가 막는 범위는 프런트 `practice-legacy` · `web-push`이며, 백엔드 범위 파일은 로컬 · 검토 근거다.\n  - `tools/`에는 커버리지 하한이 없다(시험 지원 코드, J107).\n  - 범위 측정은 빈 목록 · 없는 파일이면 실패한다(J137). 범위 파일을 새로 쓰면 이 방식을 그대로 따른다.\n  - `classic` 범위의 줄 80% 하한 · 병합 전 CI 실행과 같다.\n- 하한 · 기존 단언 · 대조 시험 · ArchUnit 시험은 시험을 통과시키려고 낮추거나 끄거나 건너뛰게 하지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.\n- 시험 종류:\n  - 단위: JUnit 5 · AssertJ · Mockito(백엔드), Vitest + Testing Library + jsdom(프런트), Vitest(tools).\n  - 통합: Spring Boot 통합 시험 + Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`), 상대 앱은 스텁.\n  - 계약 대조: `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `OpenApiContracts` · `frontend/tests/u1/shared/timing.test.ts` · `SeedDictionaryFileTest`가 `contracts/`와 코드를 대조한다.\n  - 아키텍처: ArchUnit(`core/arch` · `realtime/arch`), ESLint `/parent` ↔ `/device` ↔ `shared` import 경계.\n  - 끝-끝: `bash scripts/verify.sh` C안(기기 시뮬레이터 + 가짜 공급자).\n  - 인프라: `infra-validate`(Compose 불변식 · `nginx -t` · shellcheck · 마이그레이션 expand 검사), 이미지 점검, 관문 음성 대조.\n- 시험 클래스 이름은 단위 `*Test`, 통합 `*IT`를 기본으로 한다. 백엔드 태그는 기능 이름(`practice-legacy` · `web-push` · `skeleton` 등), DB 시험은 `db`를 쓴다. 프런트·tools 시험은 소스 구조 폴더(`tests/parent/features/…` · `tests/shared/…`)에 둔다.\n- 관문의 한계: PR `ci-gate`는 changes · backend · backend-deps · frontend · frontend-deps · tools · secrets · infra-validate를 집계한다. 정적·단위·통합·적용 범위 커버리지·보안·인프라는 경로 조건에 따라 실행된다. 이미지는 관문 통과 뒤 main에서 실행되며, 끝-끝 `skeleton`은 main 전용 임시 비차단이다. 이번 작업은 Build and Test의 전체 `verify.sh`로 병합 전 끝-끝을 확인한다.\n- 시험 · CI · 검증에서는 가짜 공급자만 쓴다(ADR-002).\n- 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공을 따로 적고, 대역 성공을 실공급자 성공으로 표시하지 않는다(`discovered-rules.md` R-M11, Q10-A).\n- 이번 작업 기록의 시험 전략은 Standard다(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험)."
    }
  ],
  "obligations": {
    "strategy": "standard",
    "strategy_volume": [
      "Five to eight tests per component.",
      "Unit tests plus integration tests for key boundaries.",
      "Add E2E, performance, or security tests when requirements demand them."
    ],
    "scope_floor": [
      "Keep the existing test suite green.",
      "This scope adds no extra new-test floor beyond the selected test strategy."
    ],
    "combination_rule": "Apply every selected-strategy obligation and every scope-floor obligation; neither replaces the other, and a targeted scope regression may add the narrowest necessary test type beyond the strategy default."
  },
  "plan_profile": {
    "methodology": "test-after",
    "runner_step": "Verify the existing test runner/configuration and record the exact unit-scoped command.",
    "runner_ready_before_first_test": true,
    "testable_layers": [
      "Data model / database behavior",
      "Repository / data access",
      "Business logic",
      "API / endpoint",
      "Frontend behavior"
    ],
    "steps": [
      "Project structure and production configuration skeleton.",
      "Verify the existing test runner/configuration and record the exact unit-scoped command.",
      "Data model / database behavior - implement.",
      "Data model / database behavior - write and run its tests after implementation.",
      "Repository / data access - implement.",
      "Repository / data access - write and run its tests after implementation.",
      "Business logic - implement.",
      "Business logic - write and run its tests after implementation.",
      "API / endpoint - implement.",
      "API / endpoint - write and run its tests after implementation.",
      "Frontend behavior - implement.",
      "Frontend behavior - write and run its tests after implementation.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:665dd09bf47887dda46a323f3ec4d9a3bec9d30c9bd22f0b892bce61f8fe2479",
  "contract_sha256": "sha256:231770ff8fc8e342a5163711626da88820010bbc0b413c96ec9fc94bb2241ce4"
}
```
