# U5a 연습 Core 작업 보고

> 브랜치 unit/u5a-practice-core · main(U1 · U2 · U3 · U4a)을 합친 상태 · U6 제외

## 구현

- 행동 정의와 생활 팁: V4의 PostgreSQL 원장, 참조용 정의 3개, 첫 고정 때 한 번 찍는 사용 시각·불변 제약(고정은 FOR KEY SHARE 읽기). 사용 중 RETIRED 버전도 보존한다. Neo4j 조회 포트는 기본 비활성이다.
- 텍스트 AI 출입구: 최소 DTO, 공급자 포트, 금지 7범주 검사, 모델·프롬프트 버전 기록, 한 번의 기술 재시도 및 전체/개별 시한. 운영 기본값은 공급자 미연결이며 시험 대역을 운영 성공 응답으로 쓰지 않는다.
- 후보와 목표: 실제 근거 확인 포트, 후보 자동 등록과 보호자 담기 분리, 같은 후보 한 행, 즐겨찾기 우선/담은 순서, 소프트 삭제·정확한 5초 복구 경계, 재담기 시 id/즐겨찾기/순서 보존. C4 API와 오류/JSON Schema를 제공한다.
- 상황: 세션에 고정된 목표/정의 버전으로 C10 제공, 기회·트리거·관계 검사, 한 번 재생성 후 422, 가상 상황 고지와 서버 고정 역할 문장(공급자 출력에 역할 칸 없음). 동일 논리 요청의 성공 재전송, 영속 선점/만료 회수, 늦은 결과 차단, 아이별 기존 발급/노출 상황 지문 재사용 차단.
- 노출·삭제: 실제 활동 기록 포트로만 ISSUED→EXPOSED 반영. 계정 삭제 참가자(@Order 150, 아이 300보다 먼저)가 U5a 개인 행을 멱등 삭제하며 공유 정의/생활 팁은 보존한다.
- 화면 18: 목록·빈 상태·독립 선택/즐겨찾기·삭제 취소·조회/변경 실패 재시도. 초점을 가진 취소 토스트 동안 DELETE를 보내지 않는다. 전송 결과 미확인은 실제 목록 재조회/시한 내 복구로 확인한다. 실제 연습 시작은 U5b 연결 전 비활성으로 표시한다.

## 연결 경계와 후속 작업

| 연결 | 이번 상태 | 병합 뒤 할 일 |
|---|---|---|
| U2 계정/아이 | U2PracticeAccountAdapter(GuardianWriteLock · ChildProfileService) | — |
| U2 계정 삭제 | 지휘가 아이보다 먼저 부름, DeletionCompleted 뒤 완료까지 통합 시험(PracticeAccountIT) | — |
| U4 기록/메타/근거 | 확인 불가를 null/거절로 반환, 가짜 개인화 없음 | 실제 증거 검증 및 출처/횟수 조회 연결 |
| U4/U5b 세션/노출 | 고정 스냅숏/실제 기록 포트 기본 503 | 실제 어댑터 및 ActivityRecorded 후 reconcileExposures 연결 |
| U5b 시작/진행 | 구현 제외 | 시작·가중 랜덤 순서 고정·단계 진행·중복 재생 방지·Cue/판정 연결 |
| 실공급자/Neo4j | 미연결·기본 비활성 | 공급자 통신 취소/시한, 승인 fixture, 의미·난도·정답 암시 검토 |
| U6 | 구현 제외 | 후보 생산 파이프라인/화면 27/리포트 PDF/작업 실행기 별도 유닛 |

문자열 검사와 지문은 의미적으로 같은 모든 재서술을 검출하지 않는다. 현재 구현을 실공급자 음성 연습 전체 완료로 간주하지 않는다.

## 검증

### 구현 시점(d25124e, main 병합 전) 실행 결과

아래 결과는 Codex 구현 커밋에서 실제로 돌린 결과다. 리뷰 반영 뒤의 결과는 아래 '리뷰 반영' 절에 있다. 시험용 PostgreSQL은 별도 DB이며 기존 데모 DB에 V4를 적용하지 않았다. JDK 21, Docker/Testcontainers, Node 22를 사용한다. scripts/verify.sh는 지시서에 따라 실행하지 않는다.

| 검사 | 결과 |
|---|---|
| Core U5a 태그/줄 커버리지 | 74/74 통과, 실패/오류/건너뜀 0, 533/544줄 = 97.98%, 분기 310/370 = 83.78% |
| 공용 계약 | 286/286 통과 |
| Core 전체 회귀 | 순차 실행 685건 중 684 통과·1건 앱 초기화 실패. 실패 묶음 단독 재실행 7/7 통과. 서로 다른 시험 691건 모두 통과 결과 확보(한 번의 전체 실행 통과와 구분) |
| Realtime 전체 회귀 | 538/538 통과, 실패/오류/건너뜀 0 |
| 시험 지원 | 27건 중 24 통과·로컬 PostgreSQL 방식 3건 건너뜀, 실패 0. Docker 방식은 실행 |
| 프런트 U5a/줄 커버리지 | 57/57 통과, 352/378줄 = 93.12%, 분기 82.47%, 함수 85.48% |
| 프런트 U1 회귀 | 514/514 통과 (34개 파일) |
| TypeScript/ESLint | 둘 다 종료 코드 0, 경고/오류 없음 |

실행 명령은 brief 5절을 따랐다. Core 커버리지 옵션은 Core 작업에만 적용하며 U1 범위나 하한을 줄이지 않았다. 신규 Core 소스 전부와 변경된 공통 오류 타입, 프런트의 신규/변경 운영 파일을 U5a 범위 목록에 포함했다.

첫 백엔드 전체 병렬 실행은 1,533건 중 14건 실패였다. V4/새 설정 기대값 5건과 Windows SQL 경로 탐색 1건을 위 근거로 보강했다. 나머지 8건은 기존 Core/Realtime의 연결·기기·종료 시험에서 발생한 지연/연결 실패로, 단언·제한 시간을 바꾸지 않고 --no-parallel --max-workers=1로 재검증했다. 이 순차 실행에서도 이전에 통과한 CoreInternalApiIT의 앱 기동 시 JDBC 메타데이터 취득 실패가 나타났다. 이 묶음은 코드·설정 변경 없는 별도 실행에서 7/7 통과했다. 운영 제한 값을 늘리거나 기존 검증을 낮추어 통과시키지 않았다. 일괄 실행의 간헐적 기동 실패는 통합 시 재확인할 항목으로 남긴다. Realtime 전체 순차 재실행은 538/538 통과하여 첫 실행의 기존 기기·세션·종료 실패가 재현되지 않았다. 공용 계약 286건과 시험 지원 27건(로컬 PG 방식 3건 건너뜀)은 첫 실행부터 실패가 없다. 마지막 분리 재검증 명령은 종료 코드 0으로 끝났다.

기존 내부 API 묶음과 남은 모듈의 마지막 분리 재검증 명령(backend 폴더, JDK 21):

```text
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --continue :core-api:test --tests kr.mujoong.core.web.CoreInternalApiIT :realtime-activity:test :test-support:test
```

프런트 시험은 시스템 설치를 바꾸지 않고 임시 Node 22.23.3 런타임으로 수행했다. 390px 모바일 실제 브라우저에서도 합성 데이터로 확인했다: 가로 넘침 없음, 즐겨찾기/삭제 44×44, 초점 중 4.2초 뒤에도 취소 유지 및 원래 줄 복원. 화면 하단 안내가 고정 탭에 가려진 문제는 화면 18에만 box-sizing을 적용해 수정했다. 시험용 화면 데이터/브라우저 보조 파일은 운영 코드에 포함하지 않는다.

## 계약·가정·공용 변경

계약 추가는 deviations.md의 U5a-a~d이며 가정은 design.md의 U5a-J1~J24에 기록했다. 신규 오류 ALREADY_SELECTED/RESTORE_EXPIRED/SCENARIO_GEN_FAILED를 코드·문서·JSON Schema·프런트 문구에 함께 추가했고, 삭제 중 오류의 내부 노출 대상을 더했다.

공용 프런트 변경은 API client의 PATCH/DELETE 지원, Toast의 선택적 종료 이유 콜백, 5초 조회 지연 안내 시간 키, 신규 오류 문구뿐이다. U1의 자리 페이지 리다이렉트 기대값은 실제 화면 렌더링 검사로 교체했다. 기존 디자인/네비게이션 구조를 사용한다.

백엔드 공용 운영 변경은 ErrorCode 추가뿐이며 보안 체인과 identity/realtime 구현은 변경하지 않았다. 기존 시험의 마이그레이션 기대값은 V1~V4(이력 [1,2,3,4])이고, V3까지만 있는 DB는 기동을 거절한다. 모든 계약 경로와 실행 경로의 일치 검사, 익명 요청 거절 검사, 시간 설정의 값/타입 검사에 새 유닛도 포함했다.

기존 구조 시험에서 Windows 경로 구분자로 누락되던 SQL 탐색을 수정했으며 양 OS 형식과 빌드 산출물 제외 검증을 추가했다. 설정에서 읽은 두 TimeUnit 식만 허용 목록에 넣고 숫자 시간 상수 거부 검증은 유지했다. Realtime에는 설정 소유권 검사만 추가했고 운영 소스는 바꾸지 않았다.

마이그레이션은 V1 · V2(U2) · V3(U4a) · V4(U5a) 순서다. 권한 검사 probe는 시험 위치의 V90을 그대로 쓴다.

## 리뷰 반영 (리뷰 1차 · `review-1.md`)

| 항목 | 반영 | 시험 |
|---|---|---|
| A 보호자 잠금 단일화 | 임시 JDBC 어댑터를 지우고 `U2PracticeAccountAdapter`(GuardianWriteLock · GuardianRepository · ChildRepository · ChildProfileService)로 바꿈 | PracticeAccountIT `theAppWiresTheU2Adapter…`, U2SingleSourceTest(보호자 잠금 SQL은 GuardianWriteLock 한 곳) |
| B 계정 삭제 순서 | `DeletionParticipant.ORDER_PRACTICE = 150`, 연습 참가자에 @Order | PracticeAccountIT `practiceRunsBeforeTheChildParticipant…`(목표 선택 · 상황이 있는 계정이 DONE까지) |
| 1 교착 | pin은 FOR KEY SHARE 읽기 + used_at이 null일 때만 UPDATE. 후보 등록은 정의를 (키, 버전) 순서로 고정 | KnowledgeJdbcRepositoryIT `pinningAPinnedDefinitionNeitherWaits…`, PragmaticsKnowledgeTest 3건 |
| 2 계약 밖 500 | C10에서 고정 실패(IllegalArgumentException) → 400 VALIDATION_FAILED | ScenarioServiceTest `anUnusableDefinitionVersion…` |
| 3 조용히 깨짐 | 포트의 default 메서드 · @FunctionalInterface 제거, 메서드 모두 추상 | 컴파일 시점(빠진 구현은 빌드 실패) |
| 4 빈 상태 404 | 목록은 `requireGuardian`(보호자 상태만) | PracticeCoreIT `aGuardianWithoutAChild…`, PracticeAccountIT `aGuardianWithoutAChildSeesTheEmptyGoalList…` |
| 5 설정 규칙 | 키를 application.yml로 옮기고 `.properties` 두 개와 @PropertySource 삭제. 시간 키는 CoreProperties.timing(practice · goal), 텍스트 AI · 지식 그래프는 TextAiProperties · KnowledgeProperties | ConfigKeyContractTest(yml ↔ 레코드 대조에 두 레코드 포함, `u5aKeysLiveInApplicationYml…`) |
| 6 키가 실패로 굳음 | 두 번 모두 내용 확인 실패일 때만 FAILED. 같은 내용 · 저장 경합은 선점을 풀어 다시 시도 | ScenarioServiceTest `alreadyIssuedContentTwiceIs422ButTheSameKeyCanBeRetried` |
| 7 멀쩡한 상황 거절 | GeneratedScenario에서 partnerRoleText 제거, Gateway 검사에서도 뺌(design.md J22) | TextAiGatewayTest(출력 레코드 칸 대조) |
| 8 잠금 중 외부 확인 | 근거 확인을 트랜잭션 전에 끝냄 | PracticeCoreIT `candidateEvidenceIsVerifiedBeforeTheTransactionOpens…` |
| 9 정리 | fingerprint의 trigger 인자 제거(본문에 트리거가 들어 있어야 하므로 본문만 본다), markUsed · requireActive 삭제 | ScenarioServiceTest 지문 대조 |
| 추가(반영 뒤 검토) | 화면 18의 401이 U2 전 임시 경로(/parent/dev-login, 운영에서는 404)로 가던 것을 다른 화면과 같은 goToLogin(안내 토스트 뒤 01, AC1.1.9)으로 바꿈 | tests/u5a/practice-18 401 세 건 |

`PragmaticsKnowledge.requireVersion`은 운영 호출이 아직 없지만 남긴다. U6 기록용 재판정이 고정된 옛 버전(RETIRED 포함)의 판정 기준을 읽을 자리다.

V4는 새 표만 만든다. 새 표의 외래 키는 기존 core_child · core_guardian을 가리키지만 이전 앱은 이 표에 쓰지 않으므로 이전 앱의 아이 · 보호자 삭제가 막히지 않는다. 그래서 `infra/migration-approvals.txt`에 승인 줄을 더하지 않았다(마이그레이션 검사도 승인 없이 통과한다).

### 실행 결과

작업 환경에서 Maven Central · Gradle 배포판 · Docker Hub · npm이 막혀 이번 반영분은 Gradle 빌드 · 시험을 돌리지 못했다. 로컬에서는 의존성이 필요 없는 두 검사만 돌렸고, 시험은 PR의 CI가 첫 검증이다.

| 검사 | 결과 |
|---|---|
| 마이그레이션 expand 검사(`infra/bin/infra-validate --only migrations`) | 통과(로컬) |
| 바꾼 자바 파일 26개 구문 · 프로젝트 안 참조(외부 라이브러리 없이 javac) | 오류 없음(로컬) |
| U5a 시험(`-PcoverageScope=u5a`, :core-api) | 미실행 — CI 확인 |
| core-api 전체 | 미실행 — CI 확인 |
| 프런트 tsc · eslint · vitest(tests/u5a · u1 · u2) | 미실행 — CI 확인 |
