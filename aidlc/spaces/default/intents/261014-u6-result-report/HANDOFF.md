# U6 인수인계 — 2026-10-14

## 0. 후속 진행 상태 — 2026-10-14 구현 재개

아래 1~6절은 Functional Design 도중 남긴 이전 인수인계다. 현재 상태는 승인 기록과 최신 산출물을 기준으로 읽는다.

- Functional Design은 완료·승인되었고, `construction/functional-design/`의 다섯 산출물이 있다. 새 표는 최종 설계에서 **10개**다.
- 현재 단계는 **Code Generation 진행 중**이다. `construction/code-generation/code-generation-plan.md`의 31단계 계획과 `unit-test-instructions.md`가 승인되어 있다. 계획을 새로 만들거나 이미 답한 질문을 반복하지 않는다.
- 재개 시 브랜치 `unit/u6-results-report`를 확인했다. U6 구현 커밋은 없으며, 작업 트리의 C5 계약 변경부터 이어받았다. 사람이 별도로 요청하기 전까지 커밋하지 않는다.
- 재개 당시 C5 응답 스키마 일부만 있었고 실제 API 경로·AI 입출력·시간 설정·편차 정리가 남아 있었다. 이후 **Step 1~23(계약·설정·데이터 모델·저장소·실행기·접수·AI 경계·세션 처리기·삭제·결과 API·리포트·PDF와 시험)**을 마쳤고, **Step 24~26 프런트 화면**을 구현 중이다. 체크박스뿐 아니라 실제 소스·시험 결과로 완료 범위를 확인한다.
- 검증 환경: JDK 21(`/tmp/jdk-21.0.12.1+1`), Gradle 캐시 `/tmp/u6-gradle`, Docker DB. 임시 경로는 다음 세션에 존재하는지 다시 확인한다. 프런트 기준선은 **56개 파일·803개 시험 통과**다. 백엔드 최초 전체 검증은 계약 선작성에 따른 오류·경로 차이로 실패했으므로 깨끗한 기준선으로 보지 않는다. 이후 U5a·U6 태그 시험과 U6SchemaIT 8개 시험이 통과했으며 최종 전체 회귀는 남아 있다.
- 기존 시험의 추가 변경 승인: 사용자가 **「34→44 변경 허용」**으로 `FlywayConfIT`의 전체 테이블 수 기대값 변경을 허용했다. U6 신규 표 10개를 반영하는 해당 단언만의 승인이다. 별도로 `ContractConformanceIT`의 새 `{reportId}` 치환은 단언을 바꾸지 않는 요청 입력 준비 보완이다.
- 후속 검증: 저장소와 U5a 읽기 확장을 포함한 U5a·U6 통합 실행이 통과했고, 마이그레이션 버전·이력 건수 4→5와 승인된 표 개수를 반영한 `FlywayConfIT` 5개 시험도 통과했다. 최종 전체 회귀·커버리지 측정 완료를 뜻하지 않는다.
- Step 8~13 검증: 실행기 시도·시한·재시도·스레드 경합, AFTER_COMMIT·롤백, 동시 재시도, 오래된 UNDECIDED 100건 뒤의 접수, 후보 등록 복구를 시험했다. AI 경계·출력 검사 신규 시험 28개를 포함한 U6 시험 실행은 통과했다(36초). `result`에서 `report` 저장소를 직접 참조하지 않도록 `ReportJobState` 포트와 어댑터를 사용한다. UNDECIDED 만료는 `ResultEligibilityExpired` 코드 로그를 남긴다.
- Step 14~19 검증: 세션 처리기 통합 시험 8개를 포함한 U6 시험 실행이 통과했다(41초). INSUFFICIENT AI 0회, 공급자 UNAVAILABLE 3회 실패, 후보 등록 실패 시 DONE 유지와 등장인물만 CHILD_RECALL인 FULL 경계를 확인했다. 삭제 참가자 시험도 통과했다. 결과 API 8개·재시작 FAILED 노출 5개 HTTP 시험은 시험 데이터의 스냅숏 해시 형식을 고친 뒤 모두 통과했다(47초). 기존 `DbTestSupport`의 정리 도우미에 U6 삭제 참가자를 먼저 실행하도록 추가했다.
- Step 20~23 검증: 리포트 인용 선택·집계·서비스·처리기 28개 시험이 통과했다. 활동 20개·AI 호출당 2초·첫 호출 실패 후 5초 재시도 대기에서 READY까지 11,297ms였다. 느린 세션 3개와 리포트 전용 스레드 경합에서는 READY까지 4,126ms였다. PDF·리포트 API 13개 시험도 통과했으며 한글 추출·임시 파일 0개·소유권·CSRF·409를 확인했다. PDF 표본은 22,607바이트·166ms였다. 컨테이너 한도 검증과 최종 전체 회귀·커버리지는 아직 남아 있다.
- 승인 계획에 반영된 추가 조건: `result.undecided-limit` 1시간을 넘긴 UNDECIDED 기록을 NOT_ELIGIBLE로 확정하여 누락 점검의 100건이 막히지 않게 한다. INSUFFICIENT 결과는 AI 호출 없이 DONE, 생활 팁은 1차 금지 사전만 적용한다.
- 구현·시험·아키텍처 리뷰와 Code Generation 완료 승인은 아직 끝나지 않았다. 다음 단계로 자동 진행하지 않는다.

## 1. 지금 상태

| 항목 | 값 |
|---|---|
| 브랜치 | `unit/u6-results-report` (origin/main `7a22b67`에서 땀. **아직 커밋 없음**, 모든 변경이 작업 트리에만 있음) |
| 작업 기록 | `aidlc/spaces/default/intents/261014-u6-result-report/` (refactor, Depth/Test Standard, 배포 2단계 뺌) |
| 진행 | Reverse Engineering ✅ → Requirements Analysis ✅ → **Functional Design 진행 중** → Code Generation → Build and Test |
| 기준 문서 | `docs/units/u6/brief.md` (처음 요청이 "Unit6 개발 시작" 한 줄이라 지시서를 정본으로 삼음) |
| stash | `u6-switch: registry + u4b audit + mode bits` — 필요한 것은 다 옮겼음. 확인 후 `git stash drop` 가능 |

**주의:** AI-DLC 승인 증빙은 PC(클론)별이다. 같은 PC · 같은 클론이면 `/aidlc --resume`으로 이어 갈 수 있다. 다른 PC면 이 기록을 이어 가지 말고 산출물을 참고해 새 기록을 만든다(`docs/units/README.md`).

## 2. Functional Design에서 남은 일

끝난 것
- 질문 Q1 ~ Q7 모두 A(추천) 답, 요약 확인 **Looks correct** 기록됨: `construction/functional-design/functional-design-questions.md`
- `construction/functional-design/entities.md` 작성 완료(새 표 9개 + support_item.title)

남은 것(모두 **파일 쓰기 도구로** 저장. 셸로 쓰면 산출물로 인정 안 됨)
1. `construction/functional-design/rules.md` — fenced ```yaml 블록, `BRx.y` ID, 각 규칙 statement · category · applies_to · trigger · logic · violation · source(FR/NFR ID). 묶음 제안: BR1 실행기, BR2 접수 · 정리 대상, BR3 구조화, BR4 재판정 · 칩 · 사상표(Q3), BR5 결과 문장 · 수준, BR6 후보, BR7 팁, BR8 API 노출 · 접근, BR9 리포트 생성 · 60초 · 항목 조립(Q4 · Q5), BR10 저장 · 버림 · PDF(Q6), BR11 화면, BR12 U5a 확장, BR13 삭제 70, BR14 재시작 FAILED 노출(Q7). FR16(계약 · 문서 정비)은 BR을 만들지 않는다.
2. `construction/functional-design/functional-spec.md` — 워크플로 · 상태 기계의 정본(ResultJob 상태, Report 상태 GENERATING→READY/FAILED→SAVED/DISCARDED), ER mermaid(entities 파생), 규칙 요약(rules 파생), 인터페이스(C5 9경로, U5a 확장 포트, 삭제 참가자), 오류 목록, 시험 · 문서 정비, **요구사항 검토 지적 R-01 ~ R-09 반영 절**(아래 4절). 구성은 U4b의 같은 파일 참고: `aidlc/spaces/default/intents/261013-u4b-activity-realtime/construction/functional-design/functional-spec.md`
3. `construction/functional-design/traceability.json` — `{"stage":"functional-design","upstream_ids":[요구사항 FR/NFR ID 전부],"coverage":[{id,status,target}],"reverse":[...]}`. 유닛 폴더 없음, 상위 ID는 이 기록의 FR/NFR. FR16.x는 `N/A`(작업 방식 요구사항)로.
4. `construction/functional-design/frontend-components.md` — 17 · 26 · 27 · 28 · 30 화면 구성요소, 상태, 폴링, API 연결, data-testid.
5. 리뷰(adversarial, 최대 2회): `aidlc engine log review --stage functional-design --reviewer aidlc-architecture-reviewer-agent --iteration 1` → 아키텍처 리뷰어 에이전트 → `--verdict` 기록
6. 작업 일지 `construction/functional-design/memory.md`에 항목 추가 → learnings 의식(`aidlc engine learnings surface/persist`) → `report --result awaiting-approval` → 승인 질문(Approve / Request Changes)

## 3. 정해진 결정 (다시 묻지 말 것)

요구사항 단계(`inception/requirements-analysis/requirements-analysis-questions.md`)
- Q1 A 종료 상태 기록은 모두 정리, DECLINED · 발화 0건 제외, 미확인 종료는 FULL 불가
- **Q2 B** 서버 재시작 FAILED(failure_code SERVER_RESTART, `core_activity`)도 발화 있으면 정리하고 26 · 27 · 28에 노출 → U4a 계약 변경 편차(records 스키마 FAILED 추가, U4a-J9 해제, AC5.1.8 · 5.3.5 변경). 바뀌는 U4a 시험 목록을 설계에 적어야 함
- Q3 A 리포트 60초 = 서버 절대 마감
- **Q4 B** 27에 08 배경 안 보임(AC5.2.2 일부 편차)
- Q5 A 자유대화 결과 문장은 저장 · API만, 27엔 안 그림
- Q6 A PDF = 요청마다 메모리 렌더, OpenHTMLtoPDF + Noto Sans KR
- Q7 A AI 빠진 내용은 U6 초안(가정 U6-J)
- Q8 A 연습은 계약 6절 예시 대역, practice null → INSUFFICIENT로 DONE
- Q9 A 후보 근거 = 아이 회상(전사)만, U5a에 읽기 메서드 추가
- Q10 A 후보 두 활동 모두, 세션당 최대 3, 의미 검토 = 서버 검사
- Q11 A 팁 태그 = behavior_key, support_item.title 추가
- 가정 27건(C1 · C2 · C7 · C11 · C13 · C17~C19 · C21~C24 · G2 · G4~G17) 추천안 그대로 — 질문 파일 맨 아래

Functional Design 단계(`construction/functional-design/functional-design-questions.md`)
- Q1 A 실행기 = DB 표 + SKIP LOCKED 선점 + SmartLifecycle 주기 실행기(`AccountDeletionScheduler` · `DeletionCommander` 방식), AI 호출 트랜잭션 밖, 스레드 2
- Q2 A `result.job-tick` 1s · `result.attempt-timeout` 30s · `result.reconcile-interval` 5m(+기동) · `report.unsaved-retention` 24h · `text-ai.result.request-timeout` 10s, 리포트 시도는 60초 남은 예산 · `result.job-attempts` 3 · `result.job-retry-backoff` [5s,15s]
- Q3 A 사상표: NOT_REACHED→ACTIVITY_ENDED, SKIPPED+상황 실패→SCENARIO_FAILED, NOT_PERFORMED+시간→TIME_OVER / +무응답→NO_RESPONSE, 장면2 미노출→ACTIVITY_ENDED, 모르는 값→ACTIVITY_ENDED+코드값 로그 (U5b와 맞출 안건)
- Q4 A 리포트 AI 항목마다 인용 후보 최대 30, 활동당 최대 5
- Q5 A 집계 · 전사 인용은 모든 활동, 결과 값 항목(3 · 9 칩, 5 출처 문장, 결과 문장)은 DONE 활동만, 기다리지 않음
- Q6 A Noto Sans KR 한글 11,172자 + 기본 라틴 서브셋(Regular · Bold)
- Q7 A 재시작 FAILED: 26 "중간에 끊김", 27 끝난 이유 '기기 응답 없이 끝냈어요' + 종료 확인 "확인 안 됨"

## 4. 요구사항 검토 지적 (승인 때 감수한 위험 — 설계에서 해소할 것)

- R-01 FR8.2 · 9.1 · 9.2 · 10.1 · 10.2 · 11.2 · 11.3 · 12.1 · 12.2에 완료 기준 · AC 번호 없음 → 설계에서 AC 번호(AC5.1.x · 5.2.x · 5.3.1~14 · 5.4.1~8 · 5.5.x)로 시험 기준 명시
- R-02 NFR1 60초 시험에 지연 값 없음 → 설계에서 가짜 공급자 지연 값(예: 호출당 고정 지연 + 실패 1회 후 성공 경로) 정하고, Q3 A(60초 마감) 편차를 편차 목록에 추가
- R-03 인용 N → FD Q4로 해소(30 · 활동당 5)
- R-04 FR12.1 "칩 3개"인데 이름 4개 → 원 AC5.3.1 · S-28 기준 4종으로 정정
- R-05 AC5.4.6 이동(저장 전 「←」=28 범위 유지, 「나중에」=26, 저장 후 「←」 · 「기록으로」=26) 반영
- R-06 접근성 AC5.1.10(날 칸 44px, 점 낭독 "m월 d일 · 활동 n개") · AC5.3.14 반영
- R-07 안드로이드 크롬 PDF 저장 · 공유는 Build and Test 수동 확인 항목으로
- R-08 FR15.1 근거(사람 결정 Q2 B, 지시서 예외 확대)와 바뀌는 U4a 단언 목록을 설계 필수 산출로
- R-09 → FD Q5로 해소

## 5. 핵심 사실 (코드 · 계약)

- U4b는 main에 병합됨(PR #8). U6 지시서는 PR #7.
- ActivityRecorded(Spring 앱 이벤트, AFTER_COMMIT) 구독자 없음. `SessionRecordCapturedHandler`가 새로 넣을 때만 냄.
- `CandidateEvidencePort` 기본 = 늘 false → U6 실제 어댑터 필요. `ScenarioLookup` · `BehaviorDefinitionLookup` 없음. `TextAiProvider`는 상황 생성만.
- `ConfigKeyContractTest`: timing.md · application.yml · CoreProperties 세 곳 동시 일치 강제. ai-io 6.1의 `mujoong.ai.*` ↔ 코드 `mujoong.text-ai.*`(가정 C1: text-ai 하위로).
- `DeletionParticipant`(`core/common/deletion/`) 순서 90 · 100 · 150 · 200 · 250 · 300 · 900 → U6 `ORDER_RESULT = 70` 추가.
- core-api 컨테이너: 메모리 1.6g, `/tmp` 64m, 읽기 전용 루트 → PDF 파일 저장 금지, 메모리 렌더.
- 「오늘 기록 보기」 감춤 단언: `frontend/tests/u1/parent/done-17.test.tsx:109-112`, `frontend/tests/u4a/parent/screens-u4a.test.tsx:155`.
- 프런트: Next.js 16 App Router, 화면 `frontend/src/parent/screens/*`, 라우트 `frontend/src/app/parent/**/page.tsx`(26 `/parent/records`는 스텁).
- 코드 지식: `aidlc/spaces/default/codekb/mujungi/` (특히 `architecture.md` 「U6 접점 요약」, `code-quality-assessment.md` D1 ~ D12)
- 정본 대조 요약(부딪힘 C1~C24 · 빈틈 G1~G18 근거, 경로 · 줄 번호 포함): `inception/requirements-analysis/u6-canon-digest.md`

## 6. 뒤 단계 메모

- Code Generation 계획은 ResultPipeline → Report → 화면 순, 단계마다 `feat(u6): …` 커밋. 커밋 전 `git diff --cached --summary`로 실행 권한 변경 확인(WSL).
- Functional Design 결과를 `docs/units/u6/design.md`에 옮기고, 끝나면 `docs/units/u6/report.md`, `docs/units/README.md` 현황표(U4b 병합 · U6 진행) 갱신.
- 계약은 `contracts/` 먼저 고치고 편차는 `contracts/deviations.md`에 `(U6-a)`부터.
- 시험: 태그 `u6`, `backend/coverage-scope/u6.txt` · `frontend/coverage-scope/` 범위 80%, 시간 값 줄여 주입, 가짜 공급자는 시험 소스에만.
- 사람이 할 일: U5b 담당과 practice null 규칙 · 사상표 맞추기, D 담당에게 프롬프트 · 금지 사전 · 생활 팁 확인, 팀장에게 자유대화 결과 문장 27 노출 여부 확인.
