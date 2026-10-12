# U2 보호자 계정 (u2-guardian-account) 보고

> 브랜치 `unit/u2-guardian-account`. 설계는 `docs/units/u2/design.md`, 계약 편차는 `contracts/deviations.md` "U2 편차" 절.
> 엔진(`/aidlc`) · `scripts/verify.sh`는 쓰지 않았고 `aidlc/` 아래는 읽기만 했다. push하지 않았다.

## 1. 커밋

| 커밋 | 내용 |
|---|---|
| `docs(u2)` 설계 | 스토리 · 엔터티 · API · 규칙 R1 ~ R13 · 화면 · 시험 계획 · 가정 U2-J1 ~ J18 |
| `feat(u2)` 계약 | parent-api.yaml 경로 12개 · 스키마 10개 · 오류 code 3개 · 편차 (U2-a) ~ (U2-i) · 시간 키 2개 · 설정 레코드 |
| `feat(u2)` Core | V2 마이그레이션 · 시드 002 · GuardianIdentity · ConsentPolicy · ChildProfile · ApprovedContentCatalog · AccountDeletion |
| `feat(u2)` 화면 | 01 · 콜백 · 02 · 02-1 · P5 · 03 · 04(아이 · 지금 활동 틀 · SET0 · P7) · 05 · SET-01 |
| `docs(u2)` 보고 | 이 문서, design.md를 구현에 맞춤 |

## 2. 만든 것

### Core (core-api)

| 컴포넌트 | 만든 것 |
|---|---|
| GuardianIdentity | `POST /api/auth/google`(서버가 code를 구글 토큰 엔드포인트와 직접 교환, id_token의 iss · aud · exp · sub 확인, 실패는 모두 401 `GOOGLE_AUTH_FAILED` + 보안 로그), 첫 로그인 때 보호자 + `core_social_account` 생성, 세션 고정 방지(앞 세션 무효 · 새 세션 id), Spring Session JDBC 쿠키 `MUJOONG_SESSION`, `POST /api/auth/logout`, `GET /api/me`. 삭제 접수된 계정의 로그인은 세션 없이 `Me{deletionStatus: IN_PROGRESS}`. U1 dev 로그인은 그대로 둠 |
| ConsentPolicy | `GET /api/policies/current`(약관 4종을 승인 문구 카탈로그에서 읽음, 모든 항목에 생성형 AI 안내 절), `GET · PUT /api/consents`(4항목 모두 · 현재 버전만, 항목마다 consent_version 올린 새 행), 상태 NOT_GIVEN · GIVEN · OUTDATED, U4가 쓸 게이트 포트 `ConsentPolicyService.check(guardianId)`. U1 `ConsentGate`는 같은 규칙(`ConsentStatusRule`)으로 보호자 기준 판정 |
| ChildProfile | `GET · POST · PATCH /api/child`(계정당 1명, 두 번째 POST 409 `CHILD_EXISTS`, 없으면 404 `CHILD_NOT_REGISTERED`, 이름 1 ~ 10자 · 나이 6 ~ 12 · 관심사 0 ~ 5개), `GET · PUT /api/child/stop-expressions`(통째 교체, 2 ~ 20자, 5개까지, 정규화 기준 중복 · 기본 표현 '무중아 그만할래'와 중복 금지), U4가 쓸 ChildContext 포트 `ChildProfileService.childContext(guardianId)` |
| ApprovedContentCatalog | `ContentCatalogService` — 코드별 최신 승인 버전, 세션 고정(pin), 새 버전은 DRAFT, 승인하면 같은 코드의 앞 APPROVED를 RETIRED. 시드 002에 약관 4종(APPROVED)과 세션 문구 자리 7종(DRAFT) |
| AccountDeletion | `DELETE /api/me`(접수형 202 `{deletionRequestId}`, 같은 보호자 재요청은 같은 id, 즉시 차단 — 그 뒤 `/api/me` 외 보호자 API는 403 `ACCOUNT_DELETION_IN_PROGRESS`, 모든 기기의 로그인 세션 삭제). 지휘 `DeletionCommander`(주기 실행 · 요청 행 임대): 진행 세션에 CONSENT_WITHDRAWN 끝내기 → 세션 끝 또는 30초 대기 → Core 참가자 `@Order` 순서(활동 → 기기 → 동의 → 아이 · 종료 표현 → 메시징) → `DeletionRequested` Outbox → `DeletionCompleted` 수신 → 소셜 계정 · 세션 · 보호자 행 삭제 → DONE. 실패는 attempt_count · last_error(예외 종류 이름만) |
| DeviceRegistry 확장 | U1 DeviceView를 05 화면에 연결(새 API 없음) |

마이그레이션 `V2__u2_guardian_account.sql`(core 스키마만, realtime 스키마는 건드리지 않음): `core_consent` child_id 선택화 · `terms_version_id` · 버전 CHECK, `core_stop_expression` 길이 · 순번 · 유일 제약, `core_social_account` · `core_data_deletion_request` 신설. 시간 키 `mujoong.timing.account.deletion-tick`(5초) · `deletion-session-wait`(30초)는 `contracts/timing.md` 8절.

### 화면 (/parent)

01 로그인(대기 · 인증 중 · 실패 토스트 · 삭제 처리 중 안내), 구글 시작 경로 처리기(state를 HttpOnly 쿠키 `MUJOONG_OAUTH_STATE`로), 콜백(state 비교 → 교환 → 02 · 03 · 04), 02 동의(4항목 · 「동의하고 계속」), 02-1 약관 바텀 시트(생성형 AI 안내 포함), P5 뒤로 가기 확인, 03 아이 정보(처음 등록 · 보기 · 수정), 04 홈(아이 구역 · 대화 기기 구역 · '지금 활동' 틀 · 앱 시작 점검 · 「⚙」), SET0 설정 시트(로그아웃 · 계정 삭제), P7 계정 삭제 확인, 05 우리 집 기기(등록 줄 · 네트워크 줄 · 판정 카드 6상태 · 종료 표현 구역 · 탭 잠금), SET-01 종료 표현 설정. U1 화면 5곳(04 · 05a · 08 · 12 · 17)의 401은 dev 로그인 대신 01로 보낸다("로그인이 필요해요." 토스트). 05에 "기기 다시 등록" 단추는 두지 않았다(5절 OQ12).

## 3. 시험 결과

| 범위 | 결과 |
|---|---|
| backend U2(`-PincludeTags=u2`, core-api) | 47건 통과, U2 범위 줄 커버리지 **96.6%**(878/909), `jacocoTestCoverageVerification -PcoverageScope=u2` 통과 |
| backend U1 회귀(`-PincludeTags=u1`) | BUILD SUCCESSFUL — contract-model 285 · core-api 609 · realtime-activity 538 · test-support 27(건너뜀 3건, U2 전부터 있던 것), 실패 0 |
| frontend U2(`vitest run tests/u2 --coverage`, scope u2) | 113건 통과, 줄 커버리지 **97.61%** |
| frontend U1 회귀(`vitest run tests/u1`) | 514건 통과, U1 범위 줄 커버리지 98.58%(하한 유지) |
| `tsc --noEmit` · `eslint .` | 오류 0 |

구글 쪽은 가짜 토큰 엔드포인트(무작위 포트)로만 시험했다. DB 시험은 Testcontainers, 고정 포트는 쓰지 않았다.

### U1 시험을 고친 곳(단언 · 하한은 낮추지 않음)

| 시험 | 고친 이유 |
|---|---|
| CoreSchemaIT · FlywayConfIT · SchemaVersionCheckTest · CoreStartupSequenceIT · SchemaPrivilegeIT | core V2가 생겨 표 수 · 이력 버전 · 열 수 · 최대 버전("2")을 새 값으로. SchemaPrivilegeIT의 시험용 마이그레이션 V2는 실제 V2와 겹쳐 V90으로 옮김 |
| DbTestSupport.cleanUp | U2 표(종료 표현 · 소셜 계정 · 삭제 요청) 정리 추가 |
| NoSqlNowTest | Windows 경로 구분자에서 U2 전부터 실패하던 것을 구분자 무관 비교로(검사 대상은 같음) |
| ErrorCodeTest | 새 code 3개를 HTTP 표에 추가 |
| frontend home-04 · done-17 · free-08 · live-12 · p1-dialog · register-05a | 401 목적지를 `/parent/dev-login` → `/parent/login`(+ 토스트)으로. home-04 재시도 횟수는 04가 `/api/me`도 읽어 `/api/home` 요청만 셈 |
| frontend routes.test | `/parent/device` 스텁 대신 Device05를 그리는지 확인 |

## 4. 계약 편차 (`contracts/deviations.md` "U2 편차")

U1이 (a) ~ (z)를 다 써서 U2는 `(U2-x)` 기호, YAML 표시는 `x-u2-deviation`. 모두 더하기만 했다(기존 연산 · 필드 삭제 · 의미 변경 없음).

| 기호 | 내용 |
|---|---|
| (U2-a) | 새 code `GOOGLE_AUTH_FAILED`(401) |
| (U2-b) | `GET /api/policies/current`는 보호자 세션 필요(계약은 `security: []`) |
| (U2-c) | 약관 항목 모양 `{type, title, sections[{heading, body}]}` |
| (U2-d) | 삭제 접수된 계정의 구글 로그인은 200 `Me{deletionStatus: IN_PROGRESS}` + 쿠키 없음 |
| (U2-e) | `/auth/google`의 형식 오류 · redirectUri 불일치 · Origin 불일치 · 설정 없음도 401 `GOOGLE_AUTH_FAILED` |
| (U2-f) | `PUT /api/consents`의 policyVersion이 현재 버전이 아니면 400 `VALIDATION_FAILED` |
| (U2-g) | 동의 상태 OUTDATED 정의(예전 버전 GIVEN만 있음) · 화면은 02 재동의 |
| (U2-h) | `DELETE /api/me` 202 `{deletionRequestId}`, 재요청 같은 id, 쿠키 Max-Age=0 |
| (U2-i) | `PUT /api/child/stop-expressions` 본문 `{items[{text}]}`, 기본 표현과 같은 표현도 400 |

새 오류 code 3개(`GOOGLE_AUTH_FAILED` · `CHILD_NOT_REGISTERED` · `CHILD_EXISTS`)는 errors.md · ErrorCode · 봉투 스키마 enum · 프런트 code 표에 함께 넣었다.

## 5. 가정 (준하 제안(가정), 상세는 design.md 8절)

| 번호 | 요지 |
|---|---|
| U2-J1 | 구글 값은 환경 변수 이름만. 비어 있으면 로그인 401(시작은 막지 않음) |
| U2-J2 | id_token 서명 검증 생략, iss · aud · exp · sub만(서버 직접 TLS 교환) |
| U2-J3 | 구글 이메일 저장 안 함, 식별은 sub |
| U2-J4 | OAuth state는 HttpOnly 쿠키 + 콜백 서버 컴포넌트 비교 |
| U2-J5 | 삭제 처리 중 로그인은 세션 없는 IN_PROGRESS 응답 |
| U2-J6 | 약관 조회는 세션 필요 |
| U2-J7 | 약관 응답 sections 모양 |
| U2-J8 | 약관 버전이 바뀌면 OUTDATED → 02 재동의(지시 우선) |
| U2-J9 | 앱 시작(04)에서 동의 · 아이가 없으면 01 |
| U2-J10 | 동의 행 child_id 비움, 보호자 기준 판정 |
| U2-J11 | 종료 표현 정규화 = NFC + 공백 제거 + 소문자 |
| U2-J12 | Core 참가자는 DeletionRequested 전에, Realtime 완료 전까지 REQUESTED 유지 |
| U2-J13 | core_activity 삭제 참가자를 U2가 activity 패키지에 만듦(U4가 넘겨받음) |
| U2-J14 | 03 처음 등록 뒤 04, 수정 뒤 03 보기 |
| U2-J15 | 04 지금 활동은 틀만(없음 · 잠김 사유) |
| U2-J16 | 문구표에 없는 글은 mockups 9절 제안 → 없으면 임시(아래 목록) |
| U2-J17 | 문구 새 버전 DRAFT, 승인 때 앞 버전 RETIRED |
| U2-J18 | 삭제 지휘 시간 키 2개를 timing.md에 |

구현하며 정한 것: `core_child`에는 DB CHECK를 걸지 않았다(U1 시험 데이터가 깨짐, 검증은 `ChildInput`). ChildContext 포트는 별도 인터페이스 없이 `ChildProfileService.childContext`로 두었다.

### 임시 · 제안 문구 (`frontend/src/shared/copy/account.ts`)

- mockups 9절 제안 문구(`ACCOUNT_PROPOSED_COPY`): 로그인 실패, 삭제 처리 중 안내, 나이 오류, 아이 저장 실패, 동의 저장 실패, 기기 상태 이름 5개, 잠김 사유 2개, 응답 없음 본문, '확인 안 됨', 종료 표현 오류 3개 · 저장 실패.
- 어느 문서에도 없어 임시로 둔 문구(`ACCOUNT_TEMP_COPY`): "로그인하고 있어요"(인증 중), "로그인이 필요해요."(401 토스트), "설정 열기" · "닫기" · "표현 고치기"(보조기기 이름), "계정 삭제를 접수하지 못했어요. 다시 시도해 주세요.", "로그아웃하지 못했어요. 다시 시도해 주세요.", 관심사 칸 도움말, "기기 상태를 확인할 수 없어요", 요약 줄 조각(따옴표 · 구분자 · '추가 표현 N개').

## 6. 남은 일과 막힌 것

| 항목 | 상태 · 넘길 곳 |
|---|---|
| Realtime 쪽 `DeletionRequested` 처리와 `DeletionCompleted` 발행 | U4. 그 전까지 삭제 요청은 REQUESTED에 머물고 보호자는 계속 차단된다(Core · U1 · U2 데이터는 이미 지워짐). 끝 단계(DONE)는 시험에서 가짜 DeletionCompleted로 확인 |
| AC1.6.8 늦은 이벤트 폐기 | U3 · U4 · U6 |
| AC2.3.7 종료 표현을 세션 스냅숏에 넣기 · 동의 게이트를 세션 시작에 연결 | U4가 `childContext` · `ConsentPolicyService.check` 포트로 |
| 04 '지금 활동' 진행 중 · 미확인 · 재조회(AC1.4.7 · 1.4.8 · 1.4.10 · 1.4.13), AC1.1.6 | U4 |
| 시드 `infra/seed/002-u2-reference.sql`을 검증 스크립트 · RUNBOOK 순서에 넣기 | 조정자(`scripts/verify.sh`를 고치거나 돌리지 않았음) |
| 구글 OAuth 실제 값 | 팀이 환경 변수로 넣어야 함: Core `GOOGLE_OAUTH_CLIENT_ID` · `GOOGLE_OAUTH_CLIENT_SECRET` · `GOOGLE_OAUTH_REDIRECT_URI`, web `MUJOONG_GOOGLE_CLIENT_ID` · `MUJOONG_GOOGLE_REDIRECT_URI`. 실제 구글과는 시험하지 않음 |
| 세션 승인 문구 7종(INTRO_FREE_TALK 등) | 임시 글 · 음성 없음 · DRAFT. 사람 승인과 음성이 필요 |
| 5절 임시 문구 | 문구표 확정 필요 |
| **OQ12** 05의 "기기 다시 등록" 진입 | 화면 설계에 없어 만들지 않음. 기기를 바꾸거나 등록이 풀렸을 때 05에서 05a로 갈 길이 필요한지 결정 필요(지금은 등록 없음 상태에서만 「기기 등록」이 보임) |
| U3와 병합 충돌 위험 | FlywayConfIT 표 수 · 이력, `ko.ts` · `codeToCopy.ts` · `errors.md` · ErrorCode 표, `deviations.md` 끝, `timing.md` 절 번호. 병합 때 두 쪽 값을 합쳐야 함 |
| test-support 건너뜀 3건 | U2 전부터 있던 것(손대지 않음) |
