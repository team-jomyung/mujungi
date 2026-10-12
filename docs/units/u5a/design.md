# U5a 연습 Core 설계

> 2026-10-12 · 설계 초안 / U2 병합 대기
> 작업 위치: `C:\Users\baikj\Desktop\mujoong-w5` · 브랜치: `unit/u5a-practice-core`
> 기준 main: `b9040da9fccd94088a2f0f8738e37714bc638efc`

## 1. 현재 상태와 구현 진입 조건

원격 main을 fetch하고 로컬 main과 같은 커밋임을 확인한 뒤 작업 브랜치에 fast-forward했다. 이 기준점의 `docs/units/u2/`에는 `brief.md`만 있고 `design.md`, `report.md`는 없다. [U5a 지시서](brief.md) 4절 및 사용자의 지시에 따라 **이번 산출물은 이 설계 문서뿐**이다. 지시서 2절 표의 U2 완료 표시는 구현 시작의 증거로 사용하지 않는다.

구현 재개 시 최신 main을 다시 가져와 U2 보고서와 실제 코드·시험을 읽고, 아래 가정 중 U2 포트·삭제 지휘·공용 프런트 관련 사항부터 대조한다. 구현·마이그레이션·계약 파일 변경·애플리케이션 시험은 아직 수행하지 않았다. 이 PC에서는 문서도 작업 브랜치에 커밋만 하고 푸시하지 않는다.

## 2. 포함 스토리와 경계

| 근거 | U5a에 포함 | 후속 통합 |
|---|---|---|
| US4.1 / AC4.1.1·2·5~9·11·13 | 화면 18 목록·빈 상태·복수 선택·즐겨찾기·담은 순서·삭제/복구·접근성 | AC4.1.3·4·10의 실제 시작, 가중 랜덤 순서 확정, 12 이동은 U5b |
| US4.2 / AC4.2.1·8, AC6.5.5·8 | 요청한 목표 하나의 상황 생성·확인·재생성, 새 상황 조건, 중복 방지, 보호자 응답에서 상황/판정 제외 | 단계 진행·Cue·반응 판정·중간 목표 미실시 처리는 U5b/Realtime |
| US4.3 / AC4.3.1~5 | 후보·근거 등록/조회 포트, 보호자 담기/취소, 동일 선택 자원과 담김 상태 | 후보 생성 파이프라인·화면 27은 U6 |
| AC4.1.12·AC4.2.9 / ADR-012·016 | 정의 버전 보존, 선택 변경과 분리된 상황 생성 입력, 노출 반영 수신 경계 | 세션 시작 시 목표·순서·판정 기준을 고정하는 Realtime 스냅숏은 U5b |
| FR6.3·7.4~7.6 / NFR1·3 | 4요소 카탈로그·조건/한계를 포함한 생활 팁, 최소 전송, 금지 표현·버전 기록 | 실공급자 검증과 정식 프롬프트/사전 본문은 AI 계약 후속 작업 |

U2 계정·아이·동의, U3 음성/기기, U4a 활동 관리·화면 08/12/17은 각각의 공개 포트로만 연결한다. `identity` 수정, Realtime 코드/스키마 접근, `aidlc/`·두 README 수정은 범위 밖이다.

## 3. 컴포넌트와 데이터

### 3.1 패키지와 기존 방식

- `kr.mujoong.core.knowledge`: 행동 정의 버전과 생활 팁 조회 서비스, 자기 저장소, 선택적인 Neo4j 조회 포트. PostgreSQL이 원장이고 Neo4j는 기본 비활성이다.
- `kr.mujoong.core.textai`: 공급자 포트, 목적별 최소 요청 DTO, 기술 재시도/시간 제한, 출력 검사, 모델·프롬프트 버전 반환. 자체 AI 원문/호출 이력 테이블은 만들지 않는다.
- `kr.mujoong.core.practice`: 후보 등록, 담은 목표 관리, 상황 생성·검증·발급 관리, 삭제 참가자. 보호자/C10 컨트롤러는 `practice.web`에 둔다.
- 의존 방향은 `practice → knowledge/textai/타 유닛 공개 포트`로 고정한다. 다른 컴포넌트의 저장소를 직접 주입하지 않는다.
- 기존 `DeviceJdbcRepository`의 JDBC 바인딩과 조건부 갱신, 서비스의 `TransactionTemplate`·`DbRetry`, `MujoongClock`·`ClockParams`를 따른다. 외부 AI 호출 중 DB 트랜잭션/행 잠금을 유지하지 않는다.
- 보호자 주체는 `GuardianPrincipal`, JSON/오류는 `ApiResponses`·`CoreApiException`·계약 오류 봉투를 재사용한다. 아이 정보는 병합된 U2 `ChildProfileService` 계열 포트의 확정 반환형을 따른다.

### 3.2 V4 데이터 설계

파일은 `backend/core-api/src/main/resources/db/migration/core/V4__u5a_practice_core.sql`이다. V1/V2를 고치거나 U4a의 V3을 사용하지 않는다. 아래 추가 필드·제약은 가정 목록과 함께 구현 전에 구체화한다.

| 표 / 소유 | 주요 내용과 제약 |
|---|---|
| `core_behavior_definition` / knowledge | id, behavior_key, version, display_name, 행동 정의·기회 조건·상황 생성 조건·판정 기준, status, created_at. `(behavior_key, version)` 유일. 4요소가 완전한 정의만 ACTIVE, 사용된 버전 본문 불변 |
| `core_support_item` / knowledge | behavior_key, condition_text, content_text, limitation_text, status, 시각. 승인된 항목만 조건과 한계를 함께 반환 |
| `core_goal_recommendation` / practice | child_id, source_session_id, structured_experience_id, behavior_key/version, reason_text, status, created_at/invalidated_at. 실제 기록의 근거 없는 후보 등록 금지 |
| `core_recommendation_evidence` / practice | recommendation_id, session_id, utterance_sequence_no, evidence_type, created_at. 근거 위치·출처만 저장하며 전사 원문을 복제하지 않음 |
| `core_practice_goal_selection` / practice | guardian_id, child_id, recommendation_id, behavior_key/version, display_name, is_favorite, sort_order, status, selected_at/deleted_at. 동일 아이·후보의 선택 자원은 삭제 후에도 하나(J1) |
| `core_scenario` / practice | child_id, goal_selection_id, behavior_key/version, scenario_kind, relation_type, opportunity_condition, scenario_text, partner_role_text, trigger_script, validation_status, status, exposed_session_id, model_version, prompt_version, created_at. 발급 예약·요청 식별·중복 지문은 J5/J6 |
| `core_scenario_request` / practice, 추가 제안 | guardian_id/child_id, 정규화한 요청 키, 상태, 완료 scenario_id, 처리 기한. 동시 C10 요청을 선점하고 성공 결과를 재조회하는 최소 발급 장부(J5). 공급자 원문 저장 없음 |

현재 존재하는 Core 자원과 U5a 내부 참조에는 FK/유일 제약을 사용한다. 아직 없는 U4/U6 테이블을 V4에서 임의로 만들지 않는다. 이들의 논리 참조·근거 확인은 J9에 따른다. Realtime UUID는 DB FK가 없는 논리 참조다. 시각은 UTC `timestamptz`, 앱 Clock의 값을 인자로 바인딩한다.

### 3.3 계정 삭제와 후속 유닛 포트

`PracticeDeletionParticipant`는 기존 `DeletionParticipant.deleteByGuardian(UUID guardianId, UUID deletionRequestId)`를 구현한다. 한 트랜잭션에서 자기 요청 장부 → 상황 → 선택 → 근거 → 후보를 삭제하며, 재호출 0건도 성공이다. 공용 행동 정의·생활 팁은 개인 데이터와 분리해 유지한다. 참가자 등록 및 U4 기록/아이 삭제와의 실행 순서는 U2 병합 후 실제 지휘 틀에 맞춘다.

삭제와 생성/담기가 경합할 때 U2의 계정 사용 가능 여부를 쓰기 완료 전에 다시 확인하고, 삭제된 계정에 AI 응답이 늦게 도착해 행을 되살리지 않도록 시험한다. 잠금/거래 경계는 U2 공개 포트의 보장과 맞춘다(J8).

후속 연결은 다음처럼 둔다.

- 제공: 승인된 `CandidateRegistry.register(...)`, `viewForSession(...)`, 생활 팁 조회, 상황 생성 서비스.
- 소비: U2 아이/계정 접근 포트, U4 활동 근거/목록 메타 조회 포트, 세션에 고정된 목표 문맥 조회 포트(J8).
- `ActivityRecorded` 알림을 받으면 U4의 Core 종료 기록 조회 포트로 실제 노출 항목을 읽고 해당 `core_scenario`만 EXPOSED로 갱신한다. 이벤트에 노출 목록이 들어 있다고 가정하지 않는다. 중복 알림도 한 번 반영한다.
- U4/U5b 연결 전에는 테스트 대역으로 각 포트를 검증한다. Realtime 표를 직접 읽거나 가짜 세션을 운영 응답으로 만들지 않는다.

## 4. API와 계약 반영 계획

현재 `contracts/parent-api.yaml`, `internal-api.yaml`은 U1 범위라 C4/C10 및 전용 JSON Schema가 없다. 아래 경로·기본 응답은 승인된 [계약 요약](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/contract-design/contract-summary.md)의 C4/C10을 그대로 따른다. 승인 계약을 파일로 옮기는 작업과 새로운 가정에 따른 추가 편차를 구분한다.

| 메서드·경로 | 요청 / 응답 |
|---|---|
| `GET /api/goal-selections` | `200 {items: GoalSelection[]}`. 즐겨찾기 묶음 → 담은 순 |
| `POST /api/goal-selections` | 요청 `{recommendationId}`는 J1. `201 GoalSelection`, 이미 담김 `409 ALREADY_SELECTED` |
| `PATCH /api/goal-selections/{goalSelectionId}` | 요청 `{isFavorite: boolean}`은 J1. `200 GoalSelection` |
| `DELETE /api/goal-selections/{goalSelectionId}` | 소프트 삭제, `200 {restoreUntil}` |
| `POST /api/goal-selections/{goalSelectionId}/restore` | `200 GoalSelection`, 기한 경과 `410 RESTORE_EXPIRED` |
| `POST /internal/practice/scenarios` | 필수 `sessionId, goalSelectionId, kind: ORIGINAL\|NEW_SCENARIO`, 선택 `previousScenarioId`. `200 Scenario`, 생성/확인 두 번 실패 `422 SCENARIO_GEN_FAILED` |

- `GoalSelection` 기본 필드: `goalSelectionId, displayName, isFavorite, sortOrder, selectedAt`. 화면 메타를 위한 추가 필드는 J2에 기록한다.
- `Scenario`: `scenarioId, kind, relationType, opportunityCondition, scenarioText, partnerRoleText, triggerScript, validation{goalIncluded,triggerIncluded,forbiddenFound}`. `scenarioText`에는 만들어진 상황이라는 안내를 포함한다.
- C10은 `X-Internal-Token` 인증뿐 아니라 요청한 세션·목표·아이의 일치와 고정 버전도 검증한다(J8). NEW_SCENARIO의 이전 상황은 같은 세션/목표의 ORIGINAL이어야 한다(J5).
- 보호자 변경 요청은 세션+CSRF, 남의 자원은 404, 삭제 접수 계정은 차단한다. 보호자 응답은 명시적 DTO로 만들며 상황 본문·판정 기준·판정값·구조화 결과·보호자 입력 원문을 포함하지 않는다.
- 승인된 세 오류를 `contracts/errors.md`와 `ErrorCode`에 함께 추가한다. 기존 오류 봉투 `{code,message,traceId}`와 시간/로그 규칙을 유지한다.
- 후보 등록·생활 팁은 앱 안 포트다. 새 공개 REST 경로나 목표 카탈로그 추천 화면을 임의로 추가하지 않는다. `POST /api/activities/practice`는 U5b 시작 통합 범위다.

미래 편차 후보: U5a-a = J1 요청 본문/재담기 규칙, U5a-b = J2 표시 메타, U5a-c = J5 요청 재전송 규칙, U5a-d = J7 텍스트 AI 설정/재시도. U2 이후 구현 시 최신 `contracts/deviations.md`와 대조하여 필요한 추가만 기록한다. 지금 계약 파일은 변경하지 않는다.

## 5. 업무 규칙과 상황 생성

1. **정의/후보:** 4요소 및 판정 기준이 없는 정의는 ACTIVE/연습 대상이 될 수 없다. 후보는 실제 활동의 발화 위치와 출처를 검증하며 후보 등록만으로 18 목록에 넣지 않는다(AC4.3.1·4). 정의와 시드는 버전으로 고정하고 과거 참조를 최신 버전으로 바꾸지 않는다(ADR-012).
2. **담기/정렬:** 목록 정렬은 `isFavorite DESC, sortOrder ASC`이며 최초 담기 순번을 서버가 원자적으로 부여한다. 별 변경으로 담은 순번을 바꾸지 않는다. 같은 후보 재담기는 같은 선택 자원을 재활성화한다(AC4.3.5, J1). 연습 실행의 가중 랜덤 추첨(A8)은 U5b다.
3. **삭제/복구:** UI는 즉시 숨김 → 초점이 없고 되돌리기 창이 끝나면 DELETE → 실패 시 줄 복원 순서다. 되돌리기면 DELETE 0건이다(AC4.1.6·11). 서버 복구 기한은 DELETE가 반영된 시각부터 기존 예약 키 `mujoong.timing.goal.restore-window`의 5초이며, 반복 DELETE로 기한을 연장하지 않는다(J3). 복구는 별·순번·기존 선택 id를 보존한다.
4. **상황:** 고정된 목표 정의와 최소 아이 문맥 확인 → 요청 선점 → DB 트랜잭션 밖 생성 → 독립 확인(목표/기회 트리거 포함, 금지 표현, 역할/관계/언어 부담, 지문 중복) → 성공 결과를 원자적으로 저장/발급한다. AI가 반환한 validation 플래그만 믿고 통과시키지 않는다.
5. **재생성:** 내용/구조/검증 실패는 처음 1회+재생성 1회까지만 허용하고, 두 번 실패하면 상황을 내주지 않는다(AC4.2.1, FR7.5). 목표 전체를 미리 생성하지 않는다. 첫 목표 실패의 시작 거절과 중간 목표 실패의 미실시는 호출자인 U5b가 처리한다.
6. **새 상황/노출:** NEW_SCENARIO는 목표·관계·기회·언어 부담을 유지하고 소재를 바꾼다(AC6.5.5). 발급된 상황은 다른 논리 요청에 재배정하지 않는다. 발급과 실제 노출은 분리하며 EXPOSED는 종료 기록 근거로만 갱신한다(ADR-016, J5/J6).
7. **AI 경계:** 공급자 DTO는 해당 작업에 필요한 목표/기회/상황 조건과 선택된 연령·관심 정보만 허용한다. 계정·세션 식별자, 전사/보호자 입력 원문, 과거 판정은 기본 전송 목록에서 제외한다. 대화 역할과 `partnerRoleText`에는 판정 기준·정답 문장을 넣지 않는다(NFR3).
8. **표현/기록:** 금지 표현 7분류를 검사하고 모델·프롬프트 버전은 성공 상황에 저장한다. 표현 사전만으로 의미적 안전성 전체를 증명했다고 보지 않는다. 요청·응답 원문과 비밀은 로그에 쓰지 않으며 사건 코드·traceId·횟수·허용된 버전 메타만 기록한다.

## 6. 화면 18와 공용 파일 경계

- `frontend/src/app/parent/practice/page.tsx`의 자리 페이지를 얇은 진입점으로 바꾸고, 실제 화면/상태/API/문구는 `src/parent/screens/Practice18.tsx`, `src/parent/practice/` 등에 둔다.
- 제목은 **이번에 살펴볼 목표**, 묶음은 **★ 즐겨찾기 / 담은 목표**. 출처 날짜는 Asia/Seoul의 m/d, 활동 종류는 자유대화/연습을 실제 근거대로 표시한다. 메타의 연습 횟수는 완료 기록의 사실만 사용한다(J2).
- 각 줄의 선택·별·삭제는 독립 조작이며 이벤트가 서로 전파되지 않는다. 각각 다른 접근성 이름, 44×44px 이상 터치 영역. 선택 수와 목록은 별도 상태로 관리한다.
- 빈 상태는 **아직 담은 목표가 없어요 / 이렇게 해 보세요**와 문구표의 3단계 안내, **자유대화 하러 가기**로 구성한다. 0개 단추 라벨은 승인 목업의 **목표를 골라 주세요**다(J12).
- 로딩은 스켈레톤/화면 전체 차단 없이 처리하고, 진입 조회 1회+변경 후 재조회로 동기화한다. 오류는 토스트와 재시도, 401은 U2 공용 로그인 처리에 위임한다. 조회 완료 전을 빈 목록으로 오인하지 않는다.
- 시작 단추의 선택 수 표시는 구현하되 U5b 시작 어댑터 연결 전에는 실행 불가 상태를 명확히 한다(J4). 시작 요청·순서 추첨·12 화면 구현은 하지 않는다.
- 삭제 대기 상태는 practice 전용 관리자가 소유한다. 기존 공용 Toast는 하나를 교체하며 만료 콜백이 없으므로 별도 타이머만으로 DELETE를 보내면 초점 유지 규칙을 어긴다. J3의 취소/만료 정책을 적용한다.
- 공용 네비게이션에는 이미 경로가 있다. U2 병합 후 `apiClient.ts`의 PATCH/DELETE 지원, 토스트 수명 API, 부모 Provider를 재확인하고 꼭 필요한 선택적 확장만 한다.
- 현재 `tests/u1/shared/routes.test.tsx`는 practice 리다이렉트를 기대한다. 허용된 자리 페이지 교체 시 이 기대값을 실제 화면 연결 검사로 바꾸되 기존 보호자/기기 경계와 나머지 단언·커버리지 하한은 유지하고 report에 이유를 적는다(J11).

## 7. 시험 계획과 구현 순서

각 층에서 정상 경로와 최소 두 종류의 실패/경계를 검증한다. 실제 DB·HTTP·상태 변화를 단언하며 대역 호출 횟수만으로 완료를 판정하지 않는다.

| 층 | 주요 시험 |
|---|---|
| 마이그레이션/저장소 | V1+U2+V4 신규 DB, 정의 버전 유일/불변, 4요소 누락, 순번 경합, 같은 후보 동시 담기, FK/계정별 격리, 다른 스키마 접근 금지 |
| knowledge/후보 | ACTIVE 완전 정의·조건/한계 반환, 비활성/없는 버전 거절, 실제 근거 없음/다른 아이 근거 거절, 등록 재전송 중복 방지, 자동 후보 등록 뒤 선택 0건 |
| 목표 서비스/삭제 | 정렬·별 보존, 재담기 한 행, 반복 DELETE 기한 불변, 복구 기한 직전/정각/이후, 다른 보호자 404, 삭제 참가자 재호출/롤백, 늦은 AI 결과와 계정 삭제 경합 |
| textai | 최소 필드 allowlist, 금지 7분류, 잘못된 출력, 모델/프롬프트 버전, 기술 재시도 성공·소진·취소, 전체 시한, 실제 공급자/Neo4j 없이 가짜 구현 |
| 상황/C10 | 첫 성공·1회 재생성 성공·2회 실패, NEW 조건 불일치, 기존 발급/노출 지문 거절, 동시 동일 요청 결과 하나, 다른 요청 재배정 금지, 삭제된 현재 선택과 고정된 세션 문맥 분리 |
| API/계약 | C4/C10 상태/JSON Schema, 세션·CSRF·내부 토큰, 삭제 계정/소유권, 보호자 응답의 금지 필드 부재, 원문 없는 로그 |
| 화면 | 목록/빈 상태/조회 실패, 체크/별/삭제 독립, 정렬 실패 복원, undo 시 DELETE 0건, 초점 중 DELETE 0건, 연속 삭제·이동·재시도, 삭제 실패 복원, 접근성/401 |
| 통합 경계 | ActivityRecorded 중복 반영, 후보 담김 조회 일치, U2 삭제 지휘 참가, U1/U2 회귀. 실제 시작·Realtime 스냅숏 E2E는 U5b 미완료 항목으로 분리 |

구현 재개 순서: U2 main 반영/포트 대조 → V4·데이터 → 저장소 → knowledge/textai/practice 업무 로직 → 계약·API → 화면 → 회귀·커버리지·report. 층마다 `feat(u5a): …` 또는 `fix(u5a): …`로 커밋한다.

태그는 `u5a`, 범위는 `backend/coverage-scope/u5a.txt`와 `frontend/coverage-scope/u5a.json`. 실제 신규/변경 운영 소스를 빠짐없이 넣고 U1/U2 범위를 줄이지 않는다. U5a 줄 커버리지 하한은 80%다. [지시서 5절](brief.md)의 백엔드 전체 회귀, Core u5a 커버리지, 프런트 u5a 커버리지/U1·U2 회귀/typecheck/lint를 실행한 결과를 구현 후 report에 쓴다. 지금은 문서만 검증하므로 시험 통과/커버리지 수치를 보고하지 않는다.

## 8. 준하 제안(가정) 목록

아래는 정본에 없는 세부 판단이며 구현 때 근거가 달라지면 번호를 유지해 수정한다.

| 번호 | 판단 · 이유 · 재확인 조건 |
|---|---|
| 준하 제안(가정) U5a-J1 | POST는 `{recommendationId}`, PATCH는 토글 명령 대신 `{isFavorite}`. 재전송으로 별이 두 번 뒤집히지 않는다. `(child_id,recommendation_id)` 선택 자원은 하나로 유지하고 소프트 삭제 뒤 재담기는 같은 id·별·순번·최초 selectedAt을 복원한다. 이미 활성인 중복 담기는 승인된 409이며 클라이언트는 재조회한다. 후보 등록의 중복 키는 출처 세션+아이+행동 정의 버전으로 잡아 동일 파이프라인 재전송을 합친다. 후보의 근거 추가/대체 규칙은 U6 결과 버전 계약과 대조한다. |
| 준하 제안(가정) U5a-J2 | 화면에 필요한 `sourceActivityType, sourceActivityAt, practiceCount`를 GoalSelection에 호환 가능한 추가 필드로 제안한다. 날짜는 후보가 나온 활동 시각, count는 해당 선택 자원을 사용한 서로 다른 종료 활동 중 실제 연습 근거가 있는 건수다. U4 읽기 포트에서 얻으며 미연결을 0회로 꾸미지 않는다. 메타 미확인 상태와 연결 전 화면 시험 대역은 분리한다. |
| 준하 제안(가정) U5a-J3 | UI 되돌리기 창은 기존 3초 이상 조작 토스트 설정을 사용하고 초점 중에는 만료/DELETE를 함께 멈춘다. 서버 5초 restore 창과 별개다. 한 번에 삭제 대기 한 건을 유지하며 새 삭제·다른 토스트 교체·화면 이탈이 먼저 오면 이전 미전송 삭제를 취소하고 줄을 복원한다. 탭 종료/새로고침도 서버에 미전송한 삭제는 취소로 본다. 응답이 유실된 DELETE는 우선 화면을 잠정 복구하고 재조회해 실제 상태를 확인하며, 이미 삭제됐으면 기한 내 restore로 보상한다. 기한이 지난 미확인 요청은 서버 상태로 수렴시키고 복구 완료로 표시하지 않는다. 이 통신 경계는 AC4.1.11의 실패 복구 보장과 별도로 통합 시험/보고한다. 반복 DELETE는 최초 restoreUntil을 반환한다. |
| 준하 제안(가정) U5a-J4 | U5a 단독 상태에서는 시작 어댑터 가용 여부로 시작 단추를 비활성화하며 임시 안내 `연습 시작 기능을 준비하고 있어요.`를 제안 문구로 관리한다. 연결 뒤에는 0개/전송 중만 비활성화하는 정식 규칙으로 전환한다. 이 임시 상태를 AC4.1.3·4·10 완료로 계산하지 않는다. |
| 준하 제안(가정) U5a-J5 | C10 논리 요청 키는 `sessionId + goalSelectionId + kind + previousScenarioId(없음 포함)`. 최초 호출이 요청 장부를 선점하고 동일 요청 재전송은 같은 성공 결과를 돌려준다. ORIGINAL은 이전 id 없음, NEW_SCENARIO는 같은 원 상황 id 필수로 검증한다. 발급 예약은 ISSUED, 실제 노출은 EXPOSED로 분리한다. 같은 응답 재전송이 두 번째 재생이 되지 않도록 U5b가 논리 요청/상황 id로 중복 재생을 막아야 한다. 처리 중 충돌·프로세스 중단 시 회복/오류는 15초 예산 안에서 닫고 U5b 계약에 명시한다. |
| 준하 제안(가정) U5a-J6 | 아이별 상황/트리거의 Unicode·공백 정규화 지문을 저장하고 기존 ISSUED/EXPOSED 지문과 비교한다. 같은 요청의 재전송은 예외이고 다른 요청에는 동일 지문을 재사용하지 않는다. NEW_SCENARIO는 별도 소재 변화 검사를 거친다. 지문만으로 의미가 같은 모든 재서술을 검출한다고 주장하지 않으며 의미/소재 검사 기준은 AI 계약의 검증 fixture로 고정한다. |
| 준하 제안(가정) U5a-J7 | 텍스트 AI 기술 재시도는 일시 실패에 한해 최대 1회, 호출별 3초·간격 250ms를 초안으로 둔다. 전체 생성+재생성+기술 재시도는 `mujoong.timing.practice.scenario-request-timeout=15s` 안에 들어야 하며 남은 시간 부족/취소 후 호출 금지. 새 키는 `mujoong.text-ai.request-timeout`, `mujoong.text-ai.retry.max-retries`, `mujoong.text-ai.retry.backoff`로 제안하고 타입 설정·시간 계약·검사에 함께 반영한다. Realtime의 판정 5초 예산이나 U6 작업 재시도 정책을 재사용하지 않는다. |
| 준하 제안(가정) U5a-J8 | U2/U4 공개 포트의 실제 Java 시그니처는 병합 후 확정한다. C10에는 가칭 `PracticeSessionContextPort`로 세션 소유·아이·고정된 목표 버전을 받는다. 현재 선택 행의 삭제/별 상태를 진행 중 세션의 허가 근거로 쓰지 않는다. 기존 계약에 이 조회 포트는 없어 U4/U5b 추가 연결 지점으로 기록하며, 미연결/알 수 없는 세션은 거절하고 테스트 대역을 운영 fallback으로 쓰지 않는다. |
| 준하 제안(가정) U5a-J9 | 미구현 U4/U6 활동/구조화 결과는 UUID 논리 참조와 제공 유닛 포트로 검증한다. CandidateRegistry 입력에 없는 structuredExperienceId는 nullable로 시작하며 다른 유닛 저장소를 조회해 채우지 않는다. V4에 U4/U6 표를 만들거나 없는 표의 FK를 넣지 않는다. 실제 포트가 들어오면 FK 가능 여부·근거 출처·삭제 순서를 대조한다. |
| 준하 제안(가정) U5a-J10 | 초기 행동 정의 2~3개와 생활 팁은 버전 있는 데모용 참조 시드로 두고, 특정 아이의 후보/선택은 테스트·명시적인 데모 fixture에서만 합성 근거와 함께 만든다. 모든 실제 계정에 자동 담기하지 않는다. Neo4j 기본 off(`mujoong.knowledge.neo4j.enabled` 제안), 텍스트 AI fake는 테스트 전용이다. 실공급자 설정은 `MUJOONG_TEXT_AI_ENDPOINT`, `MUJOONG_TEXT_AI_MODEL`, `MUJOONG_TEXT_AI_API_KEY` 이름만 예약하며 키 없는 운영 성공 응답을 만들지 않는다. |
| 준하 제안(가정) U5a-J11 | U2 이후에도 API client가 GET/POST만 받으면 PATCH/DELETE를 추가하고 기존 CSRF 흐름을 재사용한다. 토스트는 선택적인 종료 사유/취소 알림만 확장해 J3 관리자가 만료를 알도록 하며, 화면/타이머를 별도로 중복 구현하지 않는다. U1 practice 리다이렉트 시험은 정식 route 렌더 검증으로 바꾸고 근거/공용 변경 목록을 report에 남긴다. |
| 준하 제안(가정) U5a-J12 | 빈 상태의 분기 요약보다 같은 화면의 상세 문구표/목업을 따라 `아직 담은 목표가 없어요`를 사용한다. 0개 라벨은 승인 목업의 `목표를 골라 주세요`. 목록 메타는 문구표의 자유대화/연습 예시를 모두 반영한다. |
| 준하 제안(가정) U5a-J13 | V4 시험은 격리된 신규 DB에서 하며 U4a V3이 없는 공유 DB에 먼저 적용하는 운영 절차는 만들지 않는다. V3/V4 배포 순서는 조정자 통합 때 맞춘다. |

## 9. 근거와 재개 체크리스트

읽은 근거(저장소 루트 기준):

- [화면 읽는 순서](../../../aidlc/spaces/default/knowledge/documents/00_읽는순서.md), [화면 18 분기](../../../aidlc/spaces/default/knowledge/documents/10_화면설계_v1.5j/화면설계_v1.5j_화면과_분기.md), [문구표 C.S-18](../../../aidlc/spaces/default/knowledge/documents/10_화면설계_v1.5j/화면설계_v1.5j_문구표.md).
- [유닛 U5](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/units-generation/unit-of-work.md), [스토리 담당](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/units-generation/unit-of-work-story-map.md), [US4·AC6.5](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/user-stories/stories.md).
- [컴포넌트·엔터티](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/domain-design/components.md), [ADR-011·012·016·018·019](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/domain-design/decisions.md), [FR/NFR](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/requirements-analysis/requirements.md).
- [승인 목업](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/refined-mockups/mockups.md), [상호작용](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/refined-mockups/interaction-spec.md), [C4/C10/C14 계약](../../../aidlc/spaces/default/intents/261008-mujoong-service/inception/contract-design/contract-summary.md).
- 현재 `contracts/{parent-api.yaml,internal-api.yaml,ai-io.md,errors.md,timing.md,ports.md,deviations.md}`, U1 코드·삭제 포트·구조 시험·커버리지 목록. U2 design/report는 부재하여 읽지 못했다.

재개 시: (1) 원격 main 갱신과 U2 report 존재 확인, (2) U2 포트/삭제/프런트 공용 변경 대조, (3) 미확정 계약을 가정 번호와 연결해 추가, (4) 층별 구현·시험·커밋, (5) 실제 결과를 report로 인계한다.
