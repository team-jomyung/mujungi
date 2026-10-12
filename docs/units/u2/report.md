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
| `fix(u2)` 리뷰 반영 | 조정자 리뷰 10건(7절) |

## 2. 만든 것

### Core (core-api)

| 컴포넌트 | 만든 것 |
|---|---|
| GuardianIdentity | `POST /api/auth/google`(서버가 code를 구글 토큰 엔드포인트와 직접 교환, 구글 JWKS로 id_token RS256 서명 확인 뒤 iss · aud · exp · sub 확인, token-uri · jwks-uri는 https만, 실패는 모두 401 `GOOGLE_AUTH_FAILED` + 까닭별 보안 로그), 첫 로그인 때 보호자 + `core_social_account` 생성, 세션 고정 방지(앞 세션 무효 · 새 세션 id), Spring Session JDBC 쿠키 `MUJOONG_SESSION`, `POST /api/auth/logout`, `GET /api/me`. 삭제 접수된 계정의 로그인은 세션 없이 `Me{deletionStatus: IN_PROGRESS}`. U1 dev 로그인은 그대로 둠 |
| ConsentPolicy | `GET /api/policies/current`(약관 4종을 승인 문구 카탈로그에서 읽음, 모든 항목에 생성형 AI 안내 절), `GET · PUT /api/consents`(4항목 모두 · 현재 버전만, 항목마다 consent_version 올린 새 행), 상태 NOT_GIVEN · GIVEN · OUTDATED, U4가 쓸 게이트 포트 `ConsentPolicyService.check(guardianId)`. U1 `ConsentGate`는 같은 규칙(`ConsentStatusRule`)으로 보호자 기준 판정 |
| ChildProfile | `GET · POST · PATCH /api/child`(계정당 1명, 두 번째 POST 409 `CHILD_EXISTS`, 없으면 404 `CHILD_NOT_REGISTERED`, 이름 1 ~ 10자 · 나이 6 ~ 12 · 관심사 0 ~ 5개), `GET · PUT /api/child/stop-expressions`(통째 교체, 2 ~ 20자, 5개까지, 정규화 기준 중복 · 기본 표현 '무중아 그만할래'와 중복 금지), U4가 쓸 ChildContext 포트 `ChildProfileService.childContext(guardianId)` |
| ApprovedContentCatalog | U1 `activity.ApprovedContentCatalog` 하나에 코드별 최신 승인 버전 · 세션 고정(pin)을 더함(약관 · 정책 버전 · 세션 문구를 같은 곳에서 읽음). 새 버전 · 승인은 시드 SQL로 넣는다. 시드 002에 약관 4종(APPROVED)과 세션 문구 자리 7종(DRAFT) |
| AccountDeletion | `DELETE /api/me`(접수형 202 `{deletionRequestId}`, 같은 보호자 재요청은 같은 id, 즉시 차단 — 그 뒤 `/api/me` 외 보호자 API는 403 `ACCOUNT_DELETION_IN_PROGRESS`, 모든 기기의 로그인 세션 삭제). 지휘 `DeletionCommander`(주기 실행 · 요청 행 임대): 진행 세션에 CONSENT_WITHDRAWN 끝내기 → 세션 끝 또는 30초 대기 → Core 참가자 `@Order` 순서(활동 → 기기 → 동의 → 아이 · 종료 표현 → 메시징) → `DeletionRequested` Outbox → `DeletionCompleted` 수신 → 소셜 계정 · 세션 · 보호자 행 삭제 → DONE. 실패는 attempt_count · last_error(예외 종류 이름만) |
| DeviceRegistry 확장 | U1 DeviceView를 05 화면에 연결(새 API 없음) |

마이그레이션 `V2__u2_guardian_account.sql`(core 스키마만, realtime 스키마는 건드리지 않음): `core_consent` child_id 선택화 · `terms_version_id` · 버전 CHECK, `core_stop_expression` 길이 · 순번 · 유일 제약, `core_social_account` · `core_data_deletion_request` 신설. 시간 키 `mujoong.timing.account.deletion-tick`(5초) · `deletion-session-wait`(30초)는 `contracts/timing.md` 8절.

### 화면 (/parent)

01 로그인(대기 · 인증 중 · 실패 토스트 · 삭제 처리 중 안내), 구글 시작 경로 처리기(state를 HttpOnly 쿠키 `MUJOONG_OAUTH_STATE`로), 콜백(state 비교 → 교환 → 02 · 03 · 04), 02 동의(4항목 · 「동의하고 계속」), 02-1 약관 바텀 시트(생성형 AI 안내 포함), P5 뒤로 가기 확인, 03 아이 정보(처음 등록 · 보기 · 수정), 04 홈(아이 구역 · 대화 기기 구역 · '지금 활동' 틀 · 앱 시작 점검 · 「⚙」), SET0 설정 시트(로그아웃 · 계정 삭제), P7 계정 삭제 확인, 05 우리 집 기기(등록 줄 · 네트워크 줄 · 판정 카드 6상태 · 종료 표현 구역 · 탭 잠금), SET-01 종료 표현 설정. U1 화면 5곳(04 · 05a · 08 · 12 · 17)의 401은 dev 로그인 대신 01로 보낸다("로그인이 필요해요." 토스트). 05에 "기기 다시 등록" 단추는 두지 않았다(5절 OQ12).

## 3. 시험 결과

2차 리뷰 반영(7절) 뒤 다시 돌린 값이다.

| 범위 | 결과 |
|---|---|
| backend U2(`-PincludeTags=u2`, core-api) | 89건 통과(1차 리뷰 전 47 · 1차 뒤 76), U2 범위 줄 커버리지 **96.5%**(963/998, 파일 29), `jacocoTestCoverageVerification -PcoverageScope=u2` 통과 |
| backend U1 회귀(`-PincludeTags=u1`, 네 모듈) | BUILD SUCCESSFUL — contract-model 285 · core-api 609 · realtime-activity 538 · test-support 27(건너뜀 3건, U2 전부터 있던 것), 실패 0. core-api · contract-model U1 커버리지 검증(`-PcoverageScope=u1`) 통과. 참고: 2차 반영 중 프런트 시험과 동시에 돌린 한 번은 4건(`ActivityStartEndIT` 시작 시간 초과 · realtime 3건 DB 연결 실패로 문맥 기동 실패)이 실패했다. 그 넷만 따로 돌려 통과했고, 아무것도 함께 돌리지 않고 전체를 다시 돌려 위 결과를 얻었다(부하에 따른 실패로 판단, 코드 변경 없음) |
| frontend U2(`vitest run tests/u2 --coverage`, scope u2) | 124건 통과(1차 리뷰 전 113 · 1차 뒤 121), 줄 커버리지 **97.37%**(556/571) |
| frontend U1 회귀(`vitest run tests/u1 --coverage`, scope u1) | 514건 통과, U1 범위 줄 커버리지 98.47%(하한 유지) |
| `tsc --noEmit` · `eslint .` | 오류 0 |
| `infra/ci/test-host-tools.sh` | **돌리지 못함** — 이 세션에서 셸 시험 실행(`bash …`) 권한이 막혀 있었다. render-env 사례를 더했으므로 CI · 사람이 한 번 돌려야 한다(7절 리뷰 3) |

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
| (U2-g) | 동의 상태 OUTDATED 정의(예전 정책 버전 하나에서 네 항목의 최신 행이 모두 GIVEN) · 화면은 02 재동의(앱 시작 04에서도) |
| (U2-h) | `DELETE /api/me` 202 `{deletionRequestId}`, 재요청 같은 id, 쿠키 Max-Age=0 |
| (U2-i) | `PUT /api/child/stop-expressions` 본문 `{items[{text}]}`, 기본 표현과 같은 표현도 400, 공백 = 유니코드 White_Space · 글자 수 = NFC 코드 포인트 |
| (U2-j) | 로그 `SECURITY_AUTH_FAILED.kind`에 `GOOGLE_*` 까닭 6종을 더함, `/auth/google` Origin 불일치는 `SECURITY_ORIGIN_DENIED`(REST, 401). attrs 키는 그대로 |
| (U2-k) | U1 `POST /api/dev/login`(dev 전용)은 시드 보호자의 계정 삭제가 접수됐으면 403 `ACCOUNT_DELETION_IN_PROGRESS`, 세션 없음 |

새 오류 code 3개(`GOOGLE_AUTH_FAILED` · `CHILD_NOT_REGISTERED` · `CHILD_EXISTS`)는 errors.md · ErrorCode · 봉투 스키마 enum · 프런트 code 표에 함께 넣었다.

## 5. 가정 (준하 제안(가정), 상세는 design.md 8절)

| 번호 | 요지 |
|---|---|
| U2-J1 | 구글 값은 환경 변수 이름만, Core · web이 같은 이름(`GOOGLE_OAUTH_*`). 비어 있으면 로그인 401(시작은 막지 않음) |
| U2-J2 | id_token은 구글 JWKS로 RS256 서명을 확인한 뒤 iss · aud · exp · sub(리뷰 1로 바뀜). JWKS는 max-age만큼(상한 6시간) 캐시하고 코드 교환 전에 확보(2차 리뷰 4) |
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
| U2-J17 | 문구 새 버전은 다음 번호 행, 승인 때 앞 버전 RETIRED — 시드 SQL로 넣는다(앱 쓰기 API는 리뷰 10으로 뺌) |
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
| 구글 OAuth 실제 값 | 팀이 Parameter Store에 `GOOGLE_OAUTH_CLIENT_ID`(String) · `GOOGLE_OAUTH_CLIENT_SECRET`(SecureString)을 넣고 구글 콘솔에 `https://<SERVICE_HOST>/parent/login/callback`을 등록해야 함(RUNBOOK 단계 4). `GOOGLE_OAUTH_REDIRECT_URI`는 render-env가 만든다. 실제 구글과는 시험하지 않음 |
| `infra/ci/test-host-tools.sh` 실행 | 이 세션에서 셸 시험 실행 권한이 없어 돌리지 못했다. render-env 바뀐 분배 · 구글 사례를 CI에서 확인해야 함 |
| 세션 승인 문구 7종(INTRO_FREE_TALK 등) | 임시 글 · 음성 없음 · DRAFT. 사람 승인과 음성이 필요 |
| 5절 임시 문구 | 문구표 확정 필요 |
| **OQ12** 05의 "기기 다시 등록" 진입 | 화면 설계에 없어 만들지 않음. 기기를 바꾸거나 등록이 풀렸을 때 05에서 05a로 갈 길이 필요한지 결정 필요(지금은 등록 없음 상태에서만 「기기 등록」이 보임) |
| U3와 병합 충돌 위험 | FlywayConfIT 표 수 · 이력, `ko.ts` · `codeToCopy.ts` · `errors.md` · ErrorCode 표, `deviations.md` 끝, `timing.md` 절 번호. 병합 때 두 쪽 값을 합쳐야 함 |
| test-support 건너뜀 3건 | U2 전부터 있던 것(손대지 않음) |

## 7. 리뷰 반영

조정자 리뷰 10건을 모두 고쳤다(틀렸다고 판단해 남긴 건 없음). 건마다 먼저 실패하는 시험을 쓰고 지금 코드에서 실패를 확인한 뒤 고쳤다.
"RED"는 고치기 전 실제로 돌려 실패를 본 시험, "RED(컴파일)"은 새 API가 없어 컴파일 단계에서 실패한 시험(고친 뒤 처음 실행), "가드"는 고치면서 같이
쓴 재발 방지 시험이다.

| # | 지적 | 처리 | 시험 |
|---|---|---|---|
| 1 | [보안] id_token 서명 미확인 · token-uri가 평문 http · 아무 호스트 허용 | `CoreProperties.Google`이 token-uri · 새 jwks-uri를 https만 받고 평문 http는 루프백(127.0.0.1 · localhost · [::1])만 받는다(어기면 기동 거부). `GoogleKeysClient`가 로그인마다 구글 JWKS를 읽고 `GoogleIdTokenReader`가 alg RS256 · 아는 kid · SHA256withRSA 서명을 먼저 확인한다(none · HS256 · 모르는 kid · 다른 열쇠 · 내용 변조 거절). 열쇠를 못 읽으면 새 까닭 `KEYS_UNAVAILABLE`. 시험 토큰은 실행마다 만든 RSA 열쇠로 서명한다 | RED: `GuardianAccountApiIT.aTokenWithoutAValidGoogleSignatureIsRejected`(고치기 전 200). RED(컴파일): `GoogleSettingsTest`(주소 규칙 13건), `GoogleIdTokenReaderTest.anUnsignedForgedOrForeignKeySignatureIsRejected`, `GoogleKeysClientTest`, `GoogleLoginServiceTest`(열쇠 없음 → KEYS_UNAVAILABLE) |
| 2 | [보안] 로그인 · 삭제 접수 경합으로 세션이 남음, 커밋 뒤 closeAll 실패가 500 | ① `AuthController`가 세션을 연 뒤 Me를 다시 읽어 그 사이 삭제가 접수됐으면 연 세션을 닫고 '삭제 처리 중'만 준다. ② 접수의 closeAll은 커밋 뒤라 실패해도 APP_ERROR만 남기고 202(같은 id)를 준다. ③ `DeletionCommander`가 바퀴마다 먼저 `GuardianSessions.closeAll`을 부른다 — 응답 끝에 늦게 저장된 세션 · 지우지 못한 세션도 다음 바퀴(기본 5초)에 지워진다. 그 틈 동안 남은 세션은 `/api/me` 외 모든 보호자 API에서 403이다(U1 GuardianPrincipal) | RED: `AccountDeletionIT.aSessionThatSlippedPastTheAcceptanceIsClosedByTheNextCommanderRound`(고치기 전 1건 남음), `AccountDeletionIT.aFailureToCloseSessionsAfterTheCommitStillAcceptsAndTheCommanderClosesThemLater`(고치기 전 예외). RED(컴파일): `AuthControllerTest.aDeletionAcceptedWhileTheSessionWasOpeningClosesThatSessionAndAnswersInProgress` · `anActiveGuardianKeepsTheNewSession` |
| 3 | [설정] Core · web이 다른 이름, 배선 없음 | web도 Core와 같은 `GOOGLE_OAUTH_CLIENT_ID` · `GOOGLE_OAUTH_REDIRECT_URI`를 읽는다(`MUJOONG_GOOGLE_*` 폐지). `infra/bin/render-env`가 Parameter Store의 client id(선택, String) · secret(선택, SecureString)을 core-api.env · web.env에 나누고(secret은 Core만), redirect는 허용 origin + `/parent/login/callback`으로 만들어 두 파일에 같은 값을 쓴다. id · secret 하나만 있으면 렌더 실패. `infra/ci/gen-env.sh`(검증 · dev)도 같은 분배(빈 id · secret). compose.demo.yml은 이미 두 env 파일을 env_file로 받으므로 배선 주석을 달았다. RUNBOOK 단계 4에 구글 콘솔 리디렉션 URI 등록과 파라미터 넣는 법(이름만)을 적었다 | RED: `login-routes.test` '환경 변수 둘이 다 있어야…'(예전 이름은 읽지 않음) · 'state를 HttpOnly 쿠키에…' · 'state가 쿠키와 같으면…'. `infra/ci/test-host-tools.sh`에 `render-env-by-path` · `by-names` 분배 기대값, `render-env-google-oauth`, `render-env-google-id-without-secret`를 더했지만 **이 세션에서는 셸 시험 실행 권한이 없어 돌리지 못했다**(3절 · 6절) |
| 4 | [관측] 로그인 실패 까닭이 로그에 없음, Origin 불일치가 AUTH_FAILED | 실패를 `SECURITY_AUTH_FAILED`(kind `GOOGLE_NOT_CONFIGURED` · `GOOGLE_BAD_REQUEST` · `GOOGLE_REDIRECT_MISMATCH` · `GOOGLE_EXCHANGE_FAILED` · `GOOGLE_KEYS_UNAVAILABLE` · `GOOGLE_TOKEN_INVALID`, statusCode 401)로, Origin 불일치는 `SECURITY_ORIGIN_DENIED`(channel REST, statusCode 401)로 남긴다. contract-model `AttrKey.KIND`에 값만 더했다(attrs 키 표 · log-hygiene-check 허용 표는 그대로). code · 토큰 · sub는 남기지 않는다. 편차 (U2-j) | RED: `GuardianAccountApiIT.everyLoginFailureIsLoggedWithItsReasonAndAForeignOriginAsAnOriginEvent`(고치기 전 kind 없음). RED(컴파일): `AuthControllerTest.everyLoginFailureReasonHasItsOwnLogKind` |
| 5 | [화면] 뒤로가기 가드 칸을 되돌리지 않음 · StrictMode에서 두 칸 | `useBackGuard`가 가드 표시가 있는 칸만 쌓고 맨 위가 이미 가드 칸이면 다시 쌓지 않는다(StrictMode 한 칸). 새 `leave(navigate)`가 가드 칸을 `history.back()`으로 되돌린 뒤 이동한다. 02(동의 완료 · P5 로그아웃 · 401)와 03 처음 등록(완료 · 401)이 이동 전에 leave를 거친다 — 이동이 replace라 마친 화면이 뒤로가기 기록에 남지 않는다 | RED: `back-guard.test` 5건(StrictMode 한 칸 · 뒤로가기 재적층 · leave 순서 · 02 완료 · 03 완료) |
| 6 | [화면] OUTDATED 보호자를 01로 보냄 | 04 앱 시작 점검이 OUTDATED면 `/parent/consent`(02 재동의)로 보낸다(로그인 직후 분기 `routeAfterLogin`과 같다). NOT_GIVEN · 아이 없음은 그대로 01 | RED: `home-04-account.test` '04 앱 시작 점검' OUTDATED 2건 |
| 7 | [로직] OUTDATED가 아무 GIVEN 행이나 봄 | 예전 정책 버전마다 네 항목의 항목별 최신 효력 행이 모두 GIVEN일 때만 OUTDATED(그 버전 철회가 뒤에 쌓였거나 일부 항목만이면 NOT_GIVEN). 편차 (U2-g) · me-response 스키마 설명도 고쳤다 | RED: `ConsentStatusRuleTest.anOlderVersionWithdrawnAfterItWasGivenIsNotGivenRatherThanOutdated` · `anOlderVersionWithOnlySomeItemsGivenIsNotOutdated` |
| 8 | [검증] 화면 · 서버 공백 · 글자 수 규칙이 다름 | 두 쪽 모두 공백 = 유니코드 White_Space 속성(서버 `\p{IsWhite_Space}`, 화면 `/\p{White_Space}/u`), 앞뒤 공백 제거 · 글자 수는 NFC 뒤 코드 포인트. 화면 입력 칸의 UTF-16 `maxLength`를 없애고 넘으면 오류 글로 알린다. 같은 표본(전각 공백 · NBSP · NEL · BOM · U+2028 · NFD 20자 · 이모지 20/21자)을 양쪽 시험에 둔다 | RED: `stop-expressions.test` '서버와 같은 규칙' 2건. `ChildRulesTest.whitespaceAndLengthFollowTheSameUnicodeRulesAsTheScreen`은 서버를 고치기 전에 썼지만 다른 시험과 한 번에 돌려 고친 뒤에야 실행했다(고치기 전 실패는 Java `\s`가 전각 공백 · NBSP를 못 잡는 것으로 확인 가능) |
| 9 | [보안] 동의 · 아이 쓰기가 잠근 행 상태를 다시 보지 않음 | 새 `GuardianWriteLock.lockActive`가 보호자 행을 FOR UPDATE로 잠그고 ACTIVE가 아니면 403 `ACCOUNT_DELETION_IN_PROGRESS`(행이 없으면 401). 동의 저장 · 아이 등록 · 수정 · 종료 표현 교체가 트랜잭션 첫머리에 부른다(삭제 접수와 같은 행 잠금이라 줄을 선다) | RED: `GuardianAccountApiIT.consentAndChildWritesAreRefusedOnceTheAccountIsMarkedForDeletion`(고치기 전 예외 없이 씀) |
| 10 | [정리] 카탈로그 둘 · 세션 표 SQL 중복 | `ContentCatalogService`를 지우고 U1 `activity.ApprovedContentCatalog`에 `latestApproved` · `pin`(ApprovedContentPin)을 더했다. `PolicyTermsReader`는 정책 버전 · 약관 모두 이 카탈로그에서 읽는다. 시험에서만 쓰이던 `addVersion` · `approve`는 뺐다(새 버전 · 승인은 시드 SQL, U2-J17). `DeletionCommander.finish`의 세션 표 SQL을 지우고 `GuardianSessions.closeAll`(바퀴 첫머리)로 일원화 | 가드: `U2SingleSourceTest`(승인 문구 저장소를 쓰는 운영 클래스는 카탈로그 하나 · 세션 표 이름은 SessionJdbcConfig 하나), `GuardianAccountApiIT.theSingleCatalogPinsTheLatestApprovedVersionAndSharesThePolicyVersionWithTheGate`, `PolicyTermsReaderTest`(카탈로그 대역으로 바꿈) |

리뷰로 바뀐 커버리지 범위: `backend/coverage-scope/u2.txt`에서 `ContentCatalogService` 빼고 `GuardianWriteLock` · `GoogleKeysClient`를 더함(28개). 고친 U1 파일
`ApprovedContentCatalog` · `AttrKey`는 U1 범위로 재며 U1 커버리지 검증을 통과했다.

### 2차 리뷰

1차 10건 가운데 8건은 그대로 두고, 2차 지적 6건을 모두 고쳤다(틀렸다고 판단해 남긴 건 없음). 1차 표의 2 · 10 처리 중 '지휘가 바퀴마다 세션을
지운다' · '마지막 단계 세션 SQL 제거'는 아래 2 · 3으로 바뀌었다. 표기는 1차와 같다(RED = 고치기 전 실제로 돌려 실패를 봄).

| # | 지적 | 처리 | 시험 |
|---|---|---|---|
| 1 | [화면] 새로고침 뒤 남은 가드 표시를 이 문서 칸으로 믿음 | 가드 칸 표시를 `true`가 아니라 이 문서(모듈 적재 한 번)의 무작위 id로 둔다. 맨 위 칸이 이 문서 id일 때만 가드로 보고, 다른 문서가 남긴 표시 위에서는 새 칸을 쌓는다 — leave()의 back()과 기기 뒤로가기가 같은 문서 안에서 끝난다(AC1.2.5) | RED: `back-guard.test` '다른 문서가 남긴 가드 표시 위에서는 이 문서의 칸을 새로 쌓는다' · '그 상태에서 떠나면 이 문서가 쌓은 칸만 되돌리고 이동한다'. '그 상태에서 기기 뒤로가기는 … onBack(P5)'은 jsdom이 문서 다시 불러오기를 흉내 내지 못해 고치기 전에도 통과했다(가드로 둔다) |
| 2 | [보안] 마지막 단계에서 세션 행 삭제가 보호자 행 삭제와 원자적이지 않음 · 시험 로그인이 삭제 상태를 안 봄 | `GuardianSessions.deleteAllInCurrentTransaction`(호출자 트랜잭션, 표 이름은 `SessionJdbcConfig.TABLE_NAME` 하나)을 두고 `DeletionCommander.finish`가 소셜 계정 · 세션 행 · 보호자 행 삭제 + DONE을 한 트랜잭션에서 한다. 초반 바퀴의 `closeAll`은 그대로 둔다(3 참고). `DevLoginController`는 시드 보호자가 삭제 접수 상태면 403 `ACCOUNT_DELETION_IN_PROGRESS`이고 세션을 만들지 않는다 — U1 dev 전용 연산에 새 응답이라 편차 (U2-k)로 계약(parent-api.yaml)에 더했다 | RED: `AccountDeletionIT.aSessionSavedDuringTheLastRoundIsDeletedTogetherWithTheGuardianRow`(첫머리 closeAll 뒤 · finish 커밋 전에 세션을 저장하는 시험 참가자, 고치기 전 1건 남음), `DevLoginDeletionIT.aSeedGuardianWhoseDeletionWasAcceptedGetsNoSession`(고치기 전 200). 가드: `DevLoginDeletionIT.anActiveSeedGuardianStillLogsIn` |
| 3 | [견고성] 바퀴 첫머리 closeAll 실패가 삭제 진행 전체를 막고 30초마다 반복 | 세션 재정리를 Realtime 요청 전 바퀴로 한정하고(그 뒤에는 구글 · 시험 로그인 모두 세션을 만들지 않아 새 세션이 생길 길이 없다), 실패는 따로 잡아 APP_ERROR(errorKind)로 남긴 뒤 진행을 계속한다(단계 실패 · 시도 수로 세지 않음). 남은 세션은 다음 초반 바퀴 · 마지막 단계(2)가 지운다 | RED: `AccountDeletionIT.aSessionStoreFailureIsLoggedAndDoesNotHoldUpTheDeletionAndLaterRoundsSkipTheSweep`(고치기 전 FAILED) |
| 4 | [성능 · 신뢰성] JWKS를 로그인마다 캐시 없이, 코드 교환 뒤에 받음 | `GoogleKeysClient`가 `Cache-Control: max-age`를 함께 돌려주고(no-store · no-cache · 없음 = 0), 새 `GoogleKeyCache`가 max-age만큼 · 상한 `mujoong.timing.account.google-keys-max-age`(6h, timing.md 8절 · application.yml · `AccountTiming`, ConfigKeyContractTest · TimeLiteralScanTest 대상)까지 둔다. 토큰의 kid가 캐시에 없을 때만 다시 받는다. 로그인은 열쇠를 **코드 교환 전에** 확보하고, 못 얻으면 코드를 쓰지 않고 `KEYS_UNAVAILABLE`로 끝난다. 받기 실패 때 지난 열쇠로 넘어가지 않는다 | RED: `GoogleLoginServiceTest.whenTheGoogleKeysCannotBeReadTheAuthorizationCodeIsNotSpent`(고치기 전 교환을 먼저 함). RED(컴파일): `GoogleLoginServiceTest.keysAreCachedForTheirMaxAgeAndFetchedAgainOnlyWhenStaleOrForAnUnknownKid` · `theCacheIsCappedByTheTimingKeyAndANoCacheResponseIsNotKept`, `GoogleKeysClientTest.cacheControlMaxAgeIsReadFromTheResponse`(JDK HTTP 서버, 루프백 무작위 포트) · `maxAgeParsingIsStrictAndNoCacheWins` |
| 5 | [정리] 보호자 행 잠금 SQL이 세 곳 | 보호자 행을 잠그는 SQL(FOR SHARE · FOR UPDATE)을 `GuardianWriteLock` 한 곳에 두었다: `statusForShare`(정적 — U1 `DeviceJdbcRepository.lockGuardianStatusForShare`가 생성자를 바꾸지 않고 위임, 등록 트랜잭션의 첫 잠금 순서는 그대로), `statusForUpdate`(삭제 접수), `lockActive`(동의 · 아이 · 종료 표현 쓰기). `GuardianRepository` 주석을 고쳤다. U1 시험(`RegistrationDecisionTest` 순서 확인 · `DeviceJdbcRepositoryIT`)은 손대지 않고 통과 | RED: `U2SingleSourceTest.onlyOneClassLocksTheGuardianRow`(고치기 전 세 파일) |
| 6 | [정리] 카탈로그 안 조회 경로 둘 | `ApprovedContentCatalog.item()`을 `latestApproved(...)`에 '음성 있음' 필터를 더한 모양으로 줄였다. 레코드 둘(`ApprovedItem` · `PinnedContent`)은 남겼다: `ApprovedItem`은 U1 세션 패키지 · 시작 정책 · 시험 여러 곳이 쓰는 '음성 필수' 모양(생성자가 음성 경로를 검사)이고 `PinnedContent`는 음성이 없을 수 있는 계약 8절 ApprovedContentPin 모양이라, 하나로 합치면 U1 코드 · 시험을 함께 바꿔야 해 정리 이득보다 위험이 크다고 봤다 | 가드(동작이 같은 정리라 RED 없음): `ApprovedContentCatalogTest` 3건(세션 문구 = 같은 버전 + 음성 · 음성 없는 문구 · 승인 아닌 버전) |

2차로 바뀐 커버리지 범위: `backend/coverage-scope/u2.txt`에 `GoogleKeyCache`를 더함(29개). 고친 U1 파일 `DeviceJdbcRepository` · `DevLoginController` ·
`ApprovedContentCatalog` · `GuardianRepository`는 U1 범위로 잰다.

### 최종 검증 — `AccountDeletionIT.anInProgressSessionIsEndedWithConsentWithdrawnBeforeDataIsDeleted` 흔들림

| 확인 | 처리 | 시험 |
|---|---|---|
| 원인(시험) | 앱 안의 U1 `PreparingTimeoutChecker`가 실제 1초마다 돌며 **시험의 가짜 시계**를 읽는다. 시험이 시계를 1시간 앞당긴 뒤 점검 한 바퀴가 다음 단계보다 먼저 돌면, 시험이 넣은 REQUESTED 사본을 '10초 넘게 PREPARING 없음'으로 닫고(진행 세션 없음 → 실패 모양 (1) `WAITING` 대신 `REALTIME_REQUESTED`) `CORE_ABANDONED` 끝내기를 보낸다(→ 모양 (2) 끝내기 2번). 벽시계 타이밍에 달려 기계가 바쁠 때 더 자주 났다. 시험 쪽 처리: 이 클래스의 `mujoong.timing.session.preparing-timeout`을 클래스 전체 시계 이동(10시간 미만)보다 넉넉한 30일로 두고, 시험은 시계를 앞당길 때마다 점검을 **직접 한 바퀴 돌려** 그 경우를 늘 일으킨다(벽시계 무관). 단언은 그대로이고 마지막 단계에도 '끝내기 한 번' 단언을 더했다 | RED: 점검을 직접 돌리게 바꾼 같은 시험이 고치기 전 매번 `expected WAITING but was REALTIME_REQUESTED` |
| 지휘가 스스로 두 번 보낼 수 있나 | **있었다.** 끝내기를 보낸 **뒤** 별도 트랜잭션에 `sessionEndRequestedAt`을 남겨, (가) 호출이 선점 시한(세션 대기 시간)보다 오래 걸리면 다음 바퀴가 같은 요청을 다시 선점해 또 보내고 (나) 보낸 뒤 기록이 실패하면 다음 바퀴가 또 보낸다. 운영 값(시한 30초 · 끝내기 제한 2초)에서는 드물지만 시계 이동 · 긴 멈춤에서 일어난다. 고침: 기록을 **먼저 커밋하고** 보낸다(요청당 최대 한 번, 보내기 실패는 기존 규칙대로 시한 뒤 데이터 삭제). requestId는 삭제 요청 id라 Realtime에도 같은 끝내기다 | RED: `AccountDeletionIT.anEndCallStillInFlightWhenTheClaimLeaseRunsOutIsNotSentAgain`(Realtime 대역 끝내기 응답 1.5초 지연 + 첫 호출이 걸려 있는 동안 시계를 선점 시한 너머로 → 고치기 전 끝내기 2번) |

확인: `AccountDeletionIT`(10건)을 5번 연속 다시 실행(`cleanTest`) 모두 통과, 프런트 시험을 함께 돌려 바쁘게 한 6번째도 통과. 그다음 backend U2 90건(줄 96.5%, 963/998, 검증 통과),
core-api U1 회귀 609건 · U1 커버리지 검증 통과. 참고: 부하용으로 함께 돌린 프런트 시험(tests/u1 + u2) 한 번에서 638건 중 1건이 실패했다(Gradle과 CPU를 다툰 시간 초과로 보임,
출력 꼬리만 남아 시험 이름은 확인하지 못했다). 프런트만 다시 돌리면 638건 모두 통과했고 이번에 프런트 코드는 바꾸지 않았다.
