# U2 보호자 계정 (u2-guardian-account) 설계

> 기준: 화면 설계 v1.5j(문구표 · 화면과 분기) > `unit-of-work.md` U2 절 · `stories.md` US1.1 ~ US1.6 · US2.1 · US2.3 >
> `contract-summary.md` 3 · 6 · 7 · 8절 > `contracts/` > U1 구현(`construction/u1-walking-skeleton/`).
> 엔진(`/aidlc`) 없이 직접 구현한다. 가정은 "준하 제안(가정) U2-J<번호>"로 적는다(맨 아래 표).

## 1. 포함 스토리와 범위

| 스토리 | 화면 | 이번에 완성하는 AC | 다른 유닛 몫(틀만 · 포트만) |
|---|---|---|---|
| US1.1 구글 로그인 | 01 | AC1.1.1 ~ 1.1.5 · 1.1.7 ~ 1.1.10 | AC1.1.6 '진행 중 활동이 지금 활동 칸에 보임'은 U4 |
| US1.2 기록 · 동의 안내 | 02 · 02-1 · P5 | AC1.2.1 ~ 1.2.9 | — |
| US1.3 아이 정보 | 03 | AC1.3.1 ~ 1.3.10 | — |
| US1.4 홈 | 04 | AC1.4.1 ~ 1.4.6 · 1.4.9(연습 → 18은 U5 스텁) · 1.4.11 · 1.4.12 · 1.4.14 · 1.4.15 | 지금 활동 진행 중 · 미확인 · 재조회(AC1.4.7 · 1.4.8 · 1.4.10 · 1.4.13)는 U4(J18) |
| US2.1 우리 집 기기 상태 | 05 | AC2.1.1 ~ 2.1.9 · 2.1.12 ~ 2.1.14 | 판정 값(AC2.1.10 · 2.1.11)은 U1 · U3가 보고한 값을 그대로 보인다 |
| US1.5 설정 · 로그아웃 | SET0 | AC1.5.1 ~ 1.5.5 | — |
| US2.3 종료 표현 | SET-01 | AC2.3.1 ~ 2.3.6 · 2.3.8 | AC2.3.7(세션 스냅숏에 넣기)은 U4가 ChildContext 포트로 쓴다 |
| US1.6 계정 삭제 | P7 | 선행 묶음 AC1.6.1 · 1.6.2 · 1.6.4 · 1.6.7 · 1.6.9, 후행 AC1.6.3 · 1.6.5 · 1.6.6(Core · U1 · U2 데이터) | Realtime 쪽 삭제 · DeletionCompleted 발행(U4), 늦은 이벤트 폐기(AC1.6.8, U3 · U4 · U6) |

05에 "기기 다시 등록" 진입 단추는 만들지 않는다(화면 설계에 없음, OQ12 — report.md 열린 질문).

## 2. 컴포넌트 · 패키지 (core-api)

| 컴포넌트 | 패키지 | 주요 클래스 |
|---|---|---|
| GuardianIdentity | `kr.mujoong.core.identity` (+ `.google` · `.web`) | `SocialAccountRepository` · `GoogleTokenClient` · `GoogleKeysClient`(JWKS) · `GoogleIdTokenReader`(서명 · 내용 확인) · `GoogleLoginService` · `GuardianSessions` · `GuardianWriteLock`(보호자 행 잠금 SQL의 한 곳 — FOR SHARE(U1 등록) · FOR UPDATE(삭제 접수) · 쓰기 전 ACTIVE 재확인, 2차 리뷰 5) · `GoogleKeyCache`(JWKS 캐시) · `web.AuthController` · `web.MeController` |
| ConsentPolicy | `kr.mujoong.core.consent` (+ `.web`) | `ConsentStatusRule`(순수 규칙) · `ConsentPolicyService` · `PolicyTermsReader` · `PolicyTermsDevSeed`(dev 시드) · `ConsentDeletionParticipant` · `web.ConsentController`(`/policies/current` · `/consents`) |
| ChildProfile | `kr.mujoong.core.child` (+ `.web`) | `ChildInput`(검증) · `ChildProfileService`(ChildContext 포트 `childContext(guardianId)` 포함) · `StopExpressionRules` · `StopExpressionRepository` · `ChildProfileDeletionParticipant`(종료 표현 → 아이) · `web.ChildController` |
| ApprovedContentCatalog | `kr.mujoong.core.activity` (U1 클래스) | U1 `ApprovedContentCatalog`에 `latestApproved` · `pin`(계약 8절 ApprovedContentPin)을 더해 카탈로그를 하나로 둔다(리뷰 10). 약관 · 정책 버전 · 세션 문구가 같은 곳에서 읽힌다. 새 버전 · 승인은 참조 시드 SQL로 넣는다(U2-J17) |
| AccountDeletion | `kr.mujoong.core.account` | `DeletionRequestRepository` · `AccountDeletionService`(접수) · `DeletionCommander`(지휘 한 바퀴) · `AccountDeletionScheduler`(주기 실행) · `DeletionCompletedHandler`(Inbox) |
| (U1 데이터 참가자) | `kr.mujoong.core.activity` | `ActivityDeletionParticipant`(core_activity, U1 데이터 — U4가 뒤에 넘겨받는다) |

U1 업무 문맥 시험(`CoreBusinessContext`)은 `device · activity · messaging …`만 스캔하므로, U1 패키지(`activity`)에 둔 새 빈은 JPA 저장소 · 같은 패키지 빈만 쓴다(U2 빈에 기대지 않는다). U1 `ConsentGate`는 같은 규칙(`ConsentStatusRule`, 정적)과 JPA 저장소로 보호자 기준 판정을 한다.

## 3. 엔터티와 마이그레이션

`backend/core-api/src/main/resources/db/migration/core/V2__u2_guardian_account.sql` 한 파일(V1은 손대지 않음, realtime 스키마 없음):

| 표 | 변경 | 근거 |
|---|---|---|
| `core_consent` | `child_id` NOT NULL 해제(동의가 아이 등록보다 먼저), `terms_version_id uuid`(동의 당시 약관 본문 버전, FK RESTRICT) 추가, 보호자 · 항목 색인 | components Consent(아이 nullable · 약관 버전 참조) · U1 J115 '완화는 U2가 V2로' |
| `core_child` | 바꾸지 않는다. 이름 · 나이 · 관심사 규칙(AC1.3.8)은 `ChildInput`에서 검증한다(DB CHECK를 걸면 U1 시험 데이터(SessionPackageIT 등)가 깨져 넣지 않음) | AC1.3.8 |
| `core_stop_expression` | (child_id, normalized_text) 유일, 2 ~ 20자 · 순번 1 ~ 5 CHECK, child 색인 | AC2.3.6 · FR2.4 |
| `core_social_account` (신설) | id · guardian_id(FK RESTRICT) · provider(GOOGLE) · provider_user_id(구글 sub) · created_at · updated_at, (provider, provider_user_id) 유일 | GuardianSocialAccount · AC1.6.7 '같은 구글 계정 식별'. 이메일은 저장하지 않는다(U2-J3) |
| `core_data_deletion_request` (신설) | id · guardian_id(논리 참조, FK 없음 — 보호자 행이 지워진 뒤에도 DONE 기록이 남는다) · child_id · status(REQUESTED · DONE) · requested_at · session_end_requested_at · core_cleared_at · realtime_requested_at · realtime_completed_at · completed_at · attempt_count · next_attempt_at · last_error · created_at · updated_at, 보호자당 REQUESTED 하나(부분 유일) | DataDeletionRequest · AC1.6.1 · 1.6.7 · 1.6.8 |

참조 시드 `infra/seed/002-u2-reference.sql`(멱등): 약관 본문 4종(`POLICY_TERMS_*`, 문구표 원문 + F2 생성형 AI 안내, APPROVED), 세션 승인 문구 자리(INTRO_FREE_TALK · INTRO_PRACTICE · RE_PROMPT · DECLINE_ALTERNATIVE · SILENCE_WARNING · STOP_CONFIRM · HELP_OFFER, 임시 글 · 음성 없음 · DRAFT). dev 시드(`DevSeedRunner`)도 같은 약관 4종을 넣는다.

## 4. API (contracts/parent-api.yaml에 더함, 경로는 계약 3절 그대로)

| 연산 | 인증 | 성공 | 오류 |
|---|---|---|---|
| `POST /api/auth/google` {code, redirectUri} | 없음(공개 체인) | 200 Me + 세션 쿠키(삭제 처리 중이면 쿠키 없이 Me{deletionStatus: IN_PROGRESS}) | 401 GOOGLE_AUTH_FAILED(모든 실패) |
| `POST /api/auth/logout` | 세션 + CSRF | 204 + 쿠키 지움 | 401 · 403 CSRF |
| `GET /api/me` | 세션(삭제 접수여도 허용) | 200 Me | 401 |
| `DELETE /api/me` | 세션 + CSRF(삭제 접수여도 허용) | 202 {deletionRequestId} + 쿠키 지움 | 401 · 403 CSRF |
| `GET /api/policies/current` | 세션(U2-J6) | 200 {policyVersion, items[4]{type, title, sections[{heading, body}]}} | 401 · 403 삭제 |
| `GET /api/consents` | 세션 | 200 {status, policyVersion, items[{type, status}]} | 401 · 403 |
| `PUT /api/consents` {policyVersion, items[4]{type, agreed: true}} | 세션 + CSRF | 204 | 400 VALIDATION_FAILED(4개 아님 · false · 중복 · 현재 버전 아님) · 401 · 403 |
| `GET /api/child` | 세션 | 200 Child | 404 CHILD_NOT_REGISTERED · 401 · 403 |
| `POST /api/child` {displayName, age, interests} | 세션 + CSRF | 201 Child | 400 · 409 CHILD_EXISTS · 401 · 403 |
| `PATCH /api/child` {displayName?, age?, interests?} | 세션 + CSRF | 200 Child | 400 · 404 · 401 · 403 |
| `GET /api/child/stop-expressions` | 세션 | 200 {default, items[{id, text, sortOrder}]} | 404 · 401 · 403 |
| `PUT /api/child/stop-expressions` {items[{text}]} | 세션 + CSRF | 204 | 400 · 404 · 401 · 403 |

`GET /api/home` · `GET /api/device`(U1)는 모양을 바꾸지 않는다. 05의 등록 줄 · 네트워크 줄 · 판정 카드는 U1 DeviceView를 그대로 쓰고(DeviceRegistry 확장 = 합산 응답을 05 화면에 연결 + 상태별 계약 시험), 종료 표현 구역은 `GET /api/child/stop-expressions`로 채운다.

앱 안 포트(계약 8절 C12): `ConsentPolicyService.check(guardianId)` → {ok, status, snapshot}(ConsentGate), `ChildProfileService.childContext(guardianId)`(ChildContext), `ApprovedContentCatalog.pin(codes)`(ApprovedContentPin), `DeletionParticipant`(U1 인터페이스 그대로, 참가자 등록 = Spring 빈 목록).

## 5. 업무 규칙

| 번호 | 규칙 | AC |
|---|---|---|
| R1 | 구글 교환: redirectUri는 설정값과 같아야 하고, Origin이 있으면 허용 origin과 같아야 한다. 토큰 엔드포인트에 code를 보내 id_token을 받고, 구글 JWKS(`jwks-uri`)의 열쇠로 RS256 서명을 확인한 뒤 iss(accounts.google.com) · aud(client id) · exp · sub를 확인한다. token-uri · jwks-uri는 https만(평문 http는 루프백 시험 주소만, 어기면 기동 거부). 실패는 모두 401 GOOGLE_AUTH_FAILED, 쿠키 없음. 로그는 까닭별 kind(`GOOGLE_*`), Origin 불일치는 SECURITY_ORIGIN_DENIED(편차 (U2-j)) | AC1.1.4 · 1.1.7 · 리뷰 1 · 4 |
| R2 | 첫 로그인은 보호자(ACTIVE) + 소셜 계정을 만든다. 로그인 때 앞 세션을 무효로 하고 새 세션 id를 만든다(세션 고정 방지). 세션에는 보호자 id만 | AC1.1.1 · NFR9 |
| R3 | 로그인 응답 Me로 화면이 고른다: 동의 GIVEN 아님 → 02, 아이 없음 → 03, 둘 다 → 04. 앱 시작(04 진입)에서 동의 · 아이가 없으면 01로(다음 실행은 01부터), 동의가 OUTDATED면 02 재동의로(리뷰 6). 02 · 03 처음 등록의 기기 뒤로가기 가드는 기록 칸을 한 칸만 쌓고 마치고 떠날 때 되돌린다(리뷰 5). 가드 칸 표시는 이 문서의 id라 새로고침 뒤 남은 표시는 가드로 보지 않고 새 칸을 쌓는다(2차 리뷰 1) | AC1.1.1 ~ 1.1.3 · 1.1.5 · 1.2.7 |
| R4 | 401(세션 없음 · 만료)은 어느 화면이든 토스트 "로그인이 필요해요." 뒤 01. 진행 세션에는 영향 없음 | AC1.1.9 |
| R5 | 로그아웃은 세션만 끝낸다(세션 종료 요청 아님) | AC1.5.2 · 1.5.5 |
| R6 | 동의 상태: 현재 정책 버전(CONSENT_POLICY 최신 승인)의 4항목이 모두 효력 있는 GIVEN이면 GIVEN, 아니고 예전 정책 버전 하나에서 4항목의 항목별 최신 행이 모두 GIVEN이면 OUTDATED(리뷰 7), 그 밖 NOT_GIVEN. 쓰기는 보호자 행을 잠그고 ACTIVE가 아니면 403 ACCOUNT_DELETION_IN_PROGRESS(리뷰 9, R8 · R9도 같다). 저장은 4항목 모두 agreed=true · 현재 버전일 때만, 항목마다 consent_version을 올린 새 행(이미 GIVEN이면 그대로 204). 변경 · 철회 API 없음 | AC1.2.4 · 1.2.8 · FR1.2 |
| R7 | 약관 본문 4종은 승인 문구 카탈로그에서 읽고 모든 항목에 '생성형 AI 안내' 절이 '동의하지 않으면' 바로 앞에 있다 | AC1.2.2 · 1.2.9 · F2 |
| R8 | 아이: 계정당 1명(두 번째 POST는 409). 이름은 앞뒤 공백을 뺀 1 ~ 10자, 나이 6 ~ 12 정수, 관심사 0 ~ 5개 · 각 1 ~ 10자 · 중복 없음. 수정은 같은 행을 바꾼다(id 유지) | AC1.3.5 · 1.3.8 · 1.3.9 |
| R9 | 종료 표현: 통째 교체, 최대 5개, 앞뒤 공백을 뺀 2 ~ 20자(NFC 뒤 코드 포인트), 정규화(공백 제거 · 소문자) 기준 중복 금지 — 공백은 유니코드 White_Space 속성이고 화면과 서버가 같은 규칙(리뷰 8), 기본 표현 '무중아 그만할래'와 같은 것 금지. 기본 표현은 저장하지 않는 코드 상수 | AC2.3.1 · 2.3.5 · 2.3.6 |
| R10 | 삭제 접수: 한 트랜잭션에서 보호자 DELETION_REQUESTED + 요청 REQUESTED 1행(이미 있으면 같은 id), 커밋 뒤 그 보호자의 모든 로그인 세션 삭제. 접수 뒤에는 /api/me 외 모든 보호자 API가 403 ACCOUNT_DELETION_IN_PROGRESS | AC1.6.1 · 1.6.7 |
| R11 | 지휘 한 바퀴(주기 실행, 요청 행 FOR UPDATE SKIP LOCKED): ⓪ Realtime 요청 전 바퀴는 그 보호자의 로그인 세션을 다시 지움(접수 뒤 남은 · 늦게 저장된 세션, 리뷰 2 — 실패는 APP_ERROR만 남기고 진행, 2차 리뷰 3). 마지막 단계는 세션 행을 보호자 행과 같은 트랜잭션에서 지운다(2차 리뷰 2). 시험 로그인도 삭제 접수면 세션을 만들지 않는다(편차 (U2-k)) ① 진행 세션 사본이 있으면 CONSENT_WITHDRAWN 끝내기를 한 번 보내고 사본이 종료 상태가 되거나 `deletion-session-wait`(30초)가 지날 때까지 기다림 ② Core 참가자 순서 활동 → 기기 → 동의 → 아이(종료 표현 → 아이) → 메시징(`DeletionParticipant`의 `@Order` 상수) ③ 같은 트랜잭션에 DeletionRequested Outbox ④ DeletionCompleted 수신 뒤 메시징 참가자 한 번 더 → 소셜 계정 · 로그인 세션 · 보호자 행 삭제 → 요청 DONE. 실패는 attempt_count · last_error(예외 종류 이름만) 기록 뒤 다음 바퀴 | AC1.6.3 · 1.6.6 · 1.6.7 |
| R12 | 삭제 처리 중 같은 구글 계정 로그인은 데이터 · 세션 없이 "계정을 삭제하고 있어요. 끝나면 새로 시작할 수 있어요."만. 완료 뒤 로그인은 새 보호자(02부터) | AC1.6.4 · 1.6.5 |
| R13 | 로그 · 오류 메시지 · Outbox에 이름 · 관심사 · 종료 표현 · 구글 sub · 코드 · 토큰을 남기지 않는다(엔터티 toString도 id만) | NFR9 · 공통 완료 조건 |

## 6. 화면과 상태 (/parent)

| 화면 | 경로 | 상태 · 동작 |
|---|---|---|
| 01 | `/parent/login` | 대기 / 인증 중(단추 잠김) / 실패 토스트(9-1) / 삭제 처리 중 안내 카드(9-7). 「구글로 시작」 → `/parent/login/google`(Next 라우트 처리기: state 쿠키(HttpOnly · 10분) + 구글 인가 주소로 이동) |
| 01 콜백 | `/parent/login/callback` | 서버 컴포넌트가 state 쿠키와 비교 → 맞으면 code를 `POST /api/auth/google`로 교환(인증 중 표시) → Me로 02 · 03 · 04, 틀리면 01 + 실패 토스트 |
| 02 · 02-1 · P5 | `/parent/consent` | 4항목 체크 · 모두 체크해야 「동의하고 계속」, 「자세히 보기 ›」 → 바텀 시트(약관 본문 · 「확인」 · 바깥 · Escape), 「←」 → P5(「로그아웃」 → 01 · 「계속 동의하기」 → 02, 기본 초점 계속 동의하기) |
| 03 | `/parent/child` | 처음 등록(뒤로 없음) / 보기(카드 + 기기 상태 줄 + 「정보 수정」) / 수정 중(카드 안 입력 + 「취소」 · 「확인」) / 저장 실패 토스트. 나이 오류 글은 칸 아래 글 |
| 04 | `/parent` | 아이 구역(이름 · 나이 · 「수정」 → 03) · 대화 기기 구역(U1 카드) · 지금 활동 구역(틀: 없음 · 잠김 사유, 진행 중은 U4) · 「⚙」 → SET0 · 탭 잠금(U1) |
| SET0 | 04 위 바텀 시트 | 「로그아웃」 → 01, 「계정 삭제」 → P7, 「←」 · 바깥 → 04 |
| P7 | 04 위 팝업 | 「아니오」(기본 초점) → SET0, 「삭제」(위험 단추) → 접수 → 01 |
| 05 | `/parent/device` | 머리 "← 대화 기기 상태" · 마지막 확인, 등록 줄 · 네트워크 줄 · 판정 카드(6상태) · 종료 표현 구역 · 하단 탭(04와 같은 잠금) |
| SET-01 | `/parent/child/stop-expressions` | 기본 카드(파란 고정 · 단추 없음) · 추가 카드(누르면 수정 칸 · 「삭제」) · 「+ 표현 추가」(5개면 꺼짐) · 입력 칸 「확인」 · 「저장」 · 「취소」 · 「←」 |

U1 임시 동작을 대체하는 곳: 401 → `/parent/dev-login`을 01로(U1 화면 6개), `/parent/device` 스텁 → 05 화면. U1 시험의 해당 단언도 새 목적지로 고친다(약화 없음, report.md에 목록).

## 7. 시험 계획 (태그 u2)

- **contract-model**: 새 ErrorCode 표 대조는 U1 `ContractFilesTest`가 그대로 본다(errors.md · 스키마 enum 갱신).
- **core-api 단위**: `ConsentStatusRule` · `ChildInput` · `StopExpressionRules` · `GoogleIdTokenReader` · `PolicyTermsReader` 형식 · 삭제 지휘 분기(대역).
- **core-api DB(IT)**: V2 표 · 제약(아이 1명 · 표현 유일 · 요청 하나) · 시드 002 멱등 · 참가자(활동 · U2 데이터) 멱등 · 지휘 전체(REQUESTED → DeletionRequested → DeletionCompleted → DONE, 행 0건).
- **core-api API(IT)**: 가짜 구글 토큰 엔드포인트(ContractStubServer)로 로그인 · 재로그인 · 실패 4종 · 삭제 중 로그인, 동의 · 아이 · 종료 표현 CRUD와 검증, 남의 자원 · 익명 401 · CSRF, 삭제 접수 → 첫 호출 401, 응답 스키마 계약 대조.
- **프런트(vitest, tests/u2)**: 01 · 콜백 · 02 · 02-1 · P5 · 03 · 04(아이 · 지금 활동 · ⚙) · SET0 · P7 · 05(6상태 · 종료 표현) · SET-01 상호작용 · 검증, 401 처리, data-testid 규칙, 라우트 처리기(state 쿠키).
- 커버리지: `backend/coverage-scope/u2.txt` · `frontend/coverage-scope/u2.json`(U2 새 파일) 줄 80% 이상. U1 회귀 전부 통과.

## 8. 준하 제안(가정)

| 번호 | 준하 제안(가정) | 근거 |
|---|---|---|
| U2-J1 | 구글 OAuth 값은 환경 변수 이름만 둔다: Core · web이 같은 이름 `GOOGLE_OAUTH_CLIENT_ID` · `GOOGLE_OAUTH_REDIRECT_URI`를 읽고 `GOOGLE_OAUTH_CLIENT_SECRET`은 Core만(리뷰 3). `infra/bin/render-env`가 Parameter Store의 client id · secret을 core-api.env · web.env에 나누고 redirect는 허용 origin + `/parent/login/callback`으로 만든다. 비어 있으면 로그인은 401(시작은 막지 않는다 — 시연 전 팀이 값을 넣음, RUNBOOK 단계 4) | 지시 '설정 이름만' · RequiredSecretsCheck 'U2 전 비워 둠' |
| U2-J2 | id_token은 구글 JWKS 열쇠로 RS256 서명을 확인하고 iss · aud · exp · sub를 본다(리뷰 1: TLS 직접 교환만 믿지 않는다). 열쇠는 응답 `Cache-Control: max-age`만큼, 상한 `mujoong.timing.account.google-keys-max-age`(6시간)까지 캐시하고 모르는 kid일 때만 다시 받는다. 열쇠는 코드 교환 전에 확보해, 받지 못하면 코드를 쓰지 않고 실패한다(2차 리뷰 4). userinfo 호출 없음 | 리뷰 1 · 2차 리뷰 4 · OIDC Core 3.1.3.7 |
| U2-J3 | 구글 이메일은 저장하지 않는다(쓰는 곳이 없음, 최소 수집 NFR9). 식별은 sub | 개인정보 최소화 |
| U2-J4 | OAuth state는 Next 라우트 처리기가 HttpOnly 쿠키(`MUJOONG_OAUTH_STATE`, Path=/parent/login, 10분)로 두고 콜백 서버 컴포넌트가 비교한다. 보호자 JS 저장소를 쓰지 않는다(SD-6 시험) | 로그인 CSRF 방지 |
| U2-J5 | 삭제 처리 중 계정의 구글 로그인은 200 Me{deletionStatus: IN_PROGRESS}이고 세션 쿠키를 만들지 않는다 | AC1.6.4 '어떤 API도 데이터를 주지 않음' |
| U2-J6 | `GET /api/policies/current`는 보호자 세션을 요구한다(02 · 02-1은 로그인 뒤에만 열림). 계약의 `security: []`보다 좁힘 | U1 익명 401 전수 시험 · 노출 최소 |
| U2-J7 | 약관 응답 모양은 항목마다 {type, title, sections[{heading, body}]}(항목마다 소제목이 달라 고정 필드로 담을 수 없음). 본문은 승인 문구 `POLICY_TERMS_<type>`의 JSON 글 | 문구표 02-1 |
| U2-J8 | 약관 버전이 바뀌면 OUTDATED로 02 재동의(지시 · unit-of-work). interaction-spec의 '개정 재동의 범위 밖'보다 지시를 따른다. 화면은 NOT_GIVEN과 같은 02 | 지시 |
| U2-J9 | 앱 시작 때 동의 · 아이가 없으면 01로 보낸다(AC1.1.5 · 1.2.7 '다음 실행은 01부터'). 04가 `/api/me`를 함께 읽고, 그 응답을 못 읽으면 막지 않는다(04 자체 오류 처리 유지) | AC1.1.5 |
| U2-J10 | 동의는 아이보다 먼저라 동의 행의 child_id는 비우고 보호자 기준으로 판정한다(U1 ConsentGate도 보호자 기준으로 바꿈) | components Consent |
| U2-J11 | 종료 표현 정규화 = NFC 뒤 공백 전부 제거 + 소문자. 기본 표현과 같으면 중복 | FR2.4 |
| U2-J12 | 지휘는 Core 쪽 참가자를 DeletionRequested 발행 전에 돌린다(세션 끝 확인 · 30초 뒤). Realtime 쪽 삭제 · DeletionCompleted는 U4가 붙기 전까지 오지 않아 요청은 REQUESTED로 남는다(보호자는 계속 차단) | J19 · BR5.14 (d) |
| U2-J13 | core_activity 삭제 참가자는 U1 데이터라 U2가 activity 패키지에 만든다(U4가 넘겨받아 세션 · 기록 표를 더한다). 쓰는 문장은 사본 저장소 한 곳에 둔다(ArchUnit) | BR2.10 FK 순서 |
| U2-J14 | 03 「확인」 처음 등록 뒤 04, 수정은 03 보기. 03 진입 때 아이가 없으면 처음 등록 모양 | AC1.3.1 · 1.3.3 |
| U2-J15 | 04 지금 활동 구역은 `currentSession`이 null인 동안 잠김이면 "지금은 시작할 수 없어요 + 사유", 아니면 "진행 중인 활동 없음"만 보인다(진행 중 · 미확인은 U4) | J18 |
| U2-J16 | 문구표에 없는 글은 mockups 9절 제안 문구, 그것도 없으면 TEMP 문구로 두고 report.md에 목록을 남긴다 | U1 J118 방식 |
| U2-J17 | 승인 문구 새 버전은 덮어쓰지 않고 다음 번호 행으로 넣고, 승인하면 같은 코드의 앞 APPROVED를 RETIRED로 바꾼다(세션은 contentVersionId로 고정하므로 영향 없음). 앱에 쓰기 경로는 두지 않고 참조 시드 SQL로 넣는다 — 시험에서만 쓰이던 앱 쓰기 API는 리뷰 10으로 뺐다 | A5 · '버전은 바꾸지 않고 새 버전' · 리뷰 10 |
| U2-J18 | 삭제 지휘 주기(`mujoong.timing.account.deletion-tick` 5초)와 세션 끝 대기(`mujoong.timing.account.deletion-session-wait` 30초)는 timing.md에 더한다 | NFR6 시간 계약 한 곳 |
