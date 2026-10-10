# 코드 생성 계획 — U1 u1-walking-skeleton

> 3.5 Code Generation Step 2(Planning) 산출물 · 2026-10-10 · 대화 언어 한국어
>
> 입력: U1 3.1 ~ 3.4 산출물(`functional-design/` · `nfr-requirements/` · `nfr-design/` · `infrastructure-design/`), inception(`unit-of-work.md` · `unit-of-work-story-map.md` · `contract-summary.md` · `components.md` · `requirements.md` · `stories.md` · `bolt-plan.md`), `docs/cloud_run_report.md` 2 ~ 4절, 영역별 구현 시트 8개(scope-trace · data-model · rules · contracts · flows-ui · runtime · security-obs · infra-cicd). 시트끼리 다른 곳은 원 설계 문서를 다시 읽어 맞췄다(## 시트 사이 충돌과 푼 방법).
>
> 이 계획은 Plan Approval(사람이 선택지로 누름) 전까지 코드를 만들지 않는다. 승인 뒤에는 단계 체크박스만 고친다.

## 개요

- **목표(B1 워킹 스켈레톤).** 검증 명령 하나(C안, `bash scripts/verify.sh`)가 가짜 공급자 · 기기 시뮬레이터로 "D0 코드 → 시험 로그인 → 05a 등록 → 04 안정 → 08 시작 → 음성 1턴(게이트 경유) → P1 끝내기 → ENDED(기기 멈춤 확인)"를 끝까지 통과하고, 같은 실행에서 교차 스키마 쓰기 거부(B1 ②)와 로그에 전사 원문 · 기기 토큰 없음(B1 ③)을 단언한다. B1 ④(스켈레톤 확인 승인)는 사람 몫이다.
- **U1 필수(완성).** DeviceRegistry(BR1 · BR2.1 ~ 2.7 · BR2.10 · BR3.1 ~ 3.5 · BR3.8 · BR3.9 · BR4.1 ~ 4.4), CoreMessaging · RealtimeMessaging(BR5.1 ~ 5.14), DeviceWeb D0(BR9.1 ~ 9.4), 실행 골격(BR8.1 ~ 8.11), 계정 삭제 참가자 셋(BR2.10 · BR5.14 양쪽, J58), `contracts/` 편차 반영, FD 보강 A1 ~ A6, 검증 명령 C안.
- **U1 최소(스켈레톤 슬라이스).** GuardianIdentity 시험 로그인 · DevSeedRunner(BR8.3 · BR8.4), ParentWeb 04 기기 구역 · 05a · 05 스텁 · 08 · 12 · P1 · 17 최소(BR2.8 · BR2.9 · BR4.5 · BR4.6), ActivityCoordinator · SessionLifecycle(BR6.1 ~ 6.17), DeviceGateway(BR3.6 · BR3.7 · BR4.7 · BR4.8), VoiceRelay 1턴 · OutputGate 최소 · ConversationLog(BR7.1 ~ 7.6), T19 6상태(BR9.5 ~ 9.7), 승인 문구 시드(J60 · J102).
- **넣지 않는 것.** unit-of-work U1 원문 "원음 저장 · 참여 확인 · 시간 규칙 · 공백은 넣지 않는다". U2(구글 OAuth · 01 · 02 · 03 · 05 화면 · 동의 로직 · 삭제 지휘), U3(UNSTABLE 판정 · HELLO resume · 백오프 · 끼어들기 · T19 8상태 · 정식 사전 v1), U4(WAITING_CHILD · 참여 확인 · 공백 · CHILD_STOP · `GET /api/sessions/current` · SessionRecordCaptured 생산), U5 · U6 · U7, Neo4j(LC-10).
- **추적 대상.** BR 89개(BR1.1 ~ BR9.7), AC 83개(OK 56 · Deferred 27), NFRx.y 67개. 3.5 `traceability.json`이 셋을 모두 열거한다(Step 53).
- **방법론.** Testing Contract의 test-after. 층마다 구현한 뒤 그 층의 시험을 쓰고 돌린다. 앱이 셋(core-api · realtime-activity · web)이라 층마다 앱별 하위 단계를 두되 방법론은 바꾸지 않는다.
- **진행 방식.** unit-major · 스켈레톤 자세 off 유지, 솔로 진행(Bolt 워크트리 · 스웜 없음). 3.6 Build and Test까지 끝나면 자율 모드를 gated로 바꾸고 멈춘다(오케스트레이터 몫). U2 3.1은 시작하지 않는다.

## 사람 결정과 가정

### 사람 결정 (2026-10-09, 최우선)

| # | 결정 | 이 계획에서 반영한 곳 |
|---|---|---|
| 1 | 반복 순서 unit-major와 스켈레톤 자세 off는 그대로, U1을 3.5로 가서 워킹 스켈레톤을 먼저 세운다 | 전체 |
| 2 | 검증 명령은 C안(A + B + 기기 시뮬레이터 한 턴, 가짜 공급자). Plan Approval은 사람이 선택지로 누른다 | ## 검증 명령(C안), Step 49 · 51, J103 |
| 3 | 솔로 유지, 클라우드 담당은 A | J104, Step 47 · 50 |
| 4 | 도메인 · 인증서 · GHCR 공개 여부 · 스모크 인증 수단은 팀이 10/13에 정한다. 임시값으로 두고 J번호로 남긴다 | J98 ~ J100 |
| 5 | FD 보강 A1 ~ A6과 계약 편차 (a) ~ (t)를 반영하고 목록을 남긴다 | ## FD 보강과 계약 편차 반영 목록, Step 7 · 53, J130 |
| 6 | D 담당 문서는 임시값(시드 사전 범주별 1항목, 임시 WAV 2개) | J101 · J102, Step 11 |
| 7 | `knowledge/documents` 원본은 손대지 않는다 | ## 구현 공통 규칙 |
| 8 | J1 ~ J96은 아직 확정이 아니다 | 코드 주석 · traceability에 J번호를 남긴다 |
| 9 | 실제 아동 데이터 운영 전 항목(Q-2 등)은 열어 둔다 | ## 위험과 열린 질문 |
| 10 | (2026-10-10) 엔진 checkpoint에 기록할 검증 명령은 J134 (가): `bash scripts/verify.sh --engine`. 미리 만든 이미지 · jar의 소스 지문이 지금과 같을 때만 스택 기동 → D0 ~ ENDED 한 턴 → 사후 단언을 110초 안에 돌고, 지문이 다르면 실패. A(전체 시험)와 이미지 빌드는 `bash scripts/verify.sh` 전체 실행이 먼저 맡는다. 명령 확정은 verification-command 체크포인트의 Approve로 한다 | J134 · J103 · ## 검증 명령(C안) · Step 49 · 51 |
| 11 | (2026-10-10) Docker 데몬을 띄우지 못하면 host 모드(로컬 PostgreSQL 16 · java -jar 두 개 · Next.js · Nginx 대역)로 돈 끝-끝 한 턴을 C안 통과로 인정한다. 확인하지 못한 이미지 · Compose · Nginx 설정 · 망 격리 항목은 `NOT_VERIFIED(host)`로 보고에 남겨 CI 몫으로 넘긴다 | J133 · J132 · Step 49 · 51 |
| 12 | (2026-10-10) 금지 표현 시드 사전 SCORE 항목은 `점수` 대신 `비정상` | J101 · Step 11 · 29 |

함께 받은 운영 지시: 코드는 저장소 루트에 둔다(J97). 승인된 계약(`contract-summary.md`)과 U1 규칙(`rules.md`)을 그대로 따른다. 샌드박스에 JDK나 Docker가 없으면 설치를 시도하고, 안 되면 건너뛴 검증을 보고에 적는다(Step 1 · 51). Docker를 쓸 수 없을 때 DB 시험과 검증 명령을 대신 돌리는 길은 J132 · J133이고, 그래도 못 돌린 것만 건너뜀으로 적는다.

### 준하 제안(가정)

J1 ~ J104는 모두 아직 확정이 아닌 가정이다. J97 ~ J104는 사람 결정의 임시값을 번호로 남긴 것이고, J105부터는 이 계획을 쓰며 정한 코드 수준 판단이다.

| 번호 | 내용 | 근거 | 확정 시점 |
|---|---|---|---|
| J97 | 코드 배치 — 사람 지시 '저장소 루트에 유닛별로'를, 세 실행 단위가 모든 유닛에 공유되므로 저장소 루트의 실행 단위 디렉터리(`backend/` · `frontend/` · `infra/` · `contracts/` · `tools/` · `scripts/`)에 두고, 유닛 구분은 컴포넌트 패키지 이름과 유닛별 `source-manifest.json`으로 한다고 풀었다 | ADR-019 · K2 · ADR-001 · cicd 2.2.2 경로 필터 · 충돌 C8 | Plan Approval |
| J98 | 서비스 도메인 임시값 — 실제 도메인 대신 환경 변수 `SERVICE_HOST` 자리표시자를 쓴다. 저장소 · 설정 파일에는 자리표시자만 두고 dev · CI는 localhost와 자체 서명 인증서로 돈다. 인증서 방식(Let's Encrypt webroot)은 설계 그대로 두되 도메인이 정해질 때(10/13) 켠다 | 사람 결정 4 · Q-1 · cicd 열림 3 | 10/13 팀 |
| J99 | GHCR 임시값 — 이미지 저장소는 비공개로 두고, 서버는 읽기 전용 토큰(read:packages)으로 당긴다. 토큰 값은 SSM Parameter Store에만 있고 저장소에는 매개변수 이름(`GHCR_PULL_TOKEN`)만 쓴다 | 사람 결정 4 · Q-8 · cicd 열림 4 | 10/13 팀 |
| J100 | 스모크 인증 임시값 — 시험 로그인. 단 시험 로그인은 dev · test(CI) 프로필에서만 켜지므로(NFR9.11 · A2 demo+dev 거부) 로컬 · CI 스모크와 C안 검증에서만 쓰고, demo 서버 배포 스모크는 인증 없는 확인(`GET /api/health` · 준비 상태 · 정적 화면)으로 한정한다. 팀이 10/13에 정식 수단을 정하면 바꾼다 | 사람 결정 4 · Q-13 · SD-14 · 충돌 C3 | 10/13 팀 |
| J101 | 금지 표현 시드 사전 임시값 — 계약 범주(7개)마다 1항목. 정식 사전 v1(D 담당, OQ15)은 실제 아동 데이터 운영 전 승인 항목으로 열어 둔다. BR7.4 (나)가 예로 드는 '정상 · 비정상 류' 단정을 막으려고 SCORE 항목은 `점수` 대신 `비정상`으로 둔다(사람 결정 12, SD-18 대표 패턴). `점수` · `정상이에요` · `정상입니다`는 정식 사전 v1에서 더하고, 그 전까지 code-summary.md에 알려진 틈으로 남긴다 | 사람 결정 6 · 12 · A5 · NFR1.2 · V-4 · BR7.4 · SD-18 | D 담당 · D-14 |
| J102 | 승인 음성 임시값 — 임시 WAV 2개(마무리 인사 · 대기/대체 안내 자리)를 저장소에서 생성 가능한 합성음(무음 · 톤)으로 둔다. 실제 녹음 · 승인 방법은 D 담당이 정한다. J60의 임시 WAV를 구체화한다 | 사람 결정 6 · J60 · Q-12 | D 담당 |
| J103 | 검증 명령 C안 — 한 줄 명령 `bash scripts/verify.sh`. 진입 스크립트 `scripts/verify.sh`, 단계 스크립트 `scripts/verify/a-checks.sh` · `b-stack.sh` · `c-skeleton-turn.sh`, 끝-끝 시나리오 `tools/skeleton-e2e/run-c.mjs`. J39 후보 이름을 따르고, 후보에 있던 Playwright 두 브라우저 E2E는 C안의 기기 시뮬레이터로 바꾼다. Plan Approval은 이 명령을 승인하지 않는다. 엔진에 기록할 명령과 그 구성(엔진 120초 제한)은 J134대로 사람이 verification-command 체크포인트에서 Approve해야 확정된다 | 사람 결정 2 · J39 · cicd ## 5 열림 1 · J134 | verification-command 체크포인트(사람 Approve) |
| J104 | 클라우드 담당 A · 솔로 진행(사람 결정 3)을 계획과 CI 소유자 표기에 반영한다. 소유자는 `infra/RUNBOOK.md` '담당' 절과 `.github/workflows/*.yml` 머리 주석에 역할 이름('A 계정 · 클라우드 담당')으로만 적고 실명 · 계정은 쓰지 않는다 | 사람 결정 3 · J41 · team-allocation | 10/13 팀 |
| J105 | 백엔드 모듈 · 패키지 이름 — Gradle 프로젝트 `contract-model`(J70의 '공통 계약') · `core-api` · `realtime-activity` · `test-support`(시험 전용, `testImplementation`으로만 쓰여 운영 jar에 들어가지 않음). 기본 패키지 `kr.mujoong.contract` · `kr.mujoong.core` · `kr.mujoong.realtime` · `kr.mujoong.testsupport`. 빌드 규약은 포함 빌드 `backend/build-logic` | tech-stack 2.2 · Q10 · LC-11 · contracts 시트 2절 | Plan Approval |
| J106 | 유닛 범위 시험 명령 — 모든 U1 시험 클래스에 JUnit 5 `@Tag("u1")`, PostgreSQL이 필요한 시험에는 `@Tag("db")`(백엔드는 Docker 또는 호스트 PostgreSQL, J132)를 더 붙이고 Gradle 속성 `-PincludeTags` · `-PexcludeTags`로 거른다. 프런트 U1 시험은 `frontend/tests/u1/`, tools 시험은 `tools/tests/u1/`에만 둔다 | Step 2 규정(유닛 범위 명령) | Plan Approval |
| J107 | 커버리지 하한 적용 범위 — 80% 줄 하한을 `contract-model` · `core-api` · `realtime-activity` 각 모듈과 `frontend/src/**`에 따로 건다. 제외 목록은 비워 두고, 제외가 필요하면 사람 승인을 받는다. `test-support/` · `tools/`는 시험 지원 코드(시험 소스와 같은 지위)라 하한 대상이 아니되 자기 시험은 둔다. 유닛 하한 판정 명령의 측정 범위는 J137 | J91 · cicd ## 5 열림 2 · scope-trace G6 | 사람(cicd 열림 2) |
| J108 | 검증 스택 프로필 · TLS · origin — Compose 검증 스택은 `SPRING_PROFILES_ACTIVE=dev` + `MUJOONG_DEVLOGIN_ENABLED=true`(SD-14 조합 2). Gradle 시험은 `test` 프로필(J94의 ci = test), 시험 로그인 시험만 조합 13(`dev,test` + enabled). 검증 스택은 `SERVICE_HOST=localhost`, nginx 443을 호스트 `127.0.0.1:8443`에 게시, 허용 origin `https://localhost:8443`, 자체 서명 인증서는 실행 때 `build/verify/certs/`에 만든다(저장소에 두지 않음). 단위 · 통합 시험의 허용 origin은 예약 TLD `https://mujoong.invalid` | J94 · J98 · J100 · SD-14 · scope-trace C6 · C7 | Plan Approval |
| J109 | 주소 키 — `mujoong.internal-http.core-base-url`(기본 `http://core:8080`, infra-spec D-12의 backend 별칭) · `mujoong.internal-http.realtime-base-url`(기본 `http://realtime:8081`) · `mujoong.provider.url`(기본 `wss://api.openai.com/v1/live/sessions`). 공급자 허용 호스트 목록은 코드 상수라 설정으로 바꿀 수 없고 demo · prod에서 목록 밖이면 S1b가 기동을 거부한다. 공급자 기본값을 두는 파일은 J141이 고친다. 앞 둘은 `contracts/ports.md`, 공급자 키는 `contracts/ai-io.md`에 적는다 | infra-spec D-12 · SD-25 · J81(b) · runtime C9 | Plan Approval |
| J110 | 공급자 연결 형식 — `ProviderPort`는 앱 안 이벤트 형식, `ProviderClient`는 계약 9절 · J27의 메시지(`session.start` · `session.started` · `session.input_audio.append` 등)를 SDK 없이 JDK `java.net.http.WebSocket`으로 주고받는다. 가짜 공급자는 같은 선 형식을 흉내 낸다. 실제 형식 차이(V-2)는 11/2 실공급자 한 턴에서 어댑터만 고친다 | Q10 · J81 · V-2 · runtime C9 | 11/2 측정 |
| J111 | V-12 — Realtime이 모르는 sessionId에 `GUARDIAN_STOP` end가 오면 409 SESSION_NOT_ACTIVE(묘비 없음, 새 오류 코드 없음, 편차 (w)). Core는 보호자에게 그대로 409를 주고 12가 상태를 다시 읽는다(W8). `CORE_ABANDONED`의 202 본문은 `{}` | V-12 · J54 · scope-trace G1 · rules 5-6 | 사람(V-12) |
| J112 | 3.5가 더하는 계약 편차 — (u) `GET /api/session/csrf`(J75 · SD-6), (v) RT `POST /internal/sessions` 409 SESSION_NOT_ACTIVE(10분 묘비, BR6.6), (w) J111, (x) 계약 C13 `owner_role: core_app`은 '쓰는 역할'로 읽고 표 소유는 `mujoong_owner`(SD-13), (y) Nginx가 만든 502 · 504 JSON 봉투의 code `UPSTREAM_UNAVAILABLE`(상태 코드는 유지, R4-17). 모두 더하기만이고 `contracts/deviations.md`에 출처 · 소비자 · 호환 분류를 적는다 | 계약 10절 · contracts 시트 5-1 ~ 5-3 · data-model 5-7 · infra 시트 5-1 | Plan Approval(사람 확인) |
| J113 | ArchUnit 앞당김 — R3-21은 ArchUnit 묶음 전체를 B1 이후로 두지만 싼 규칙 6개(① core ↔ realtime 패키지 참조 0 ② `contract-model`에 Entity · Repository · Spring 설정 0 ③ 운영 코드 → `kr.mujoong.testsupport` 0 ④ 프레임 경로 패키지 → JDBC · 파일 · Outbox 0 ⑤ `Instant.now()` · `System.currentTimeMillis()` · `LocalDateTime.now()` 등 시각 직접 호출 0(시간 어댑터 한 곳 제외) ⑥ 공급자 클라이언트(`voice.ProviderClient` · `java.net.http.WebSocket`)에 의존하는 패키지는 `kr.mujoong.realtime.voice` 하나(SD-20))를 U1에 넣는다. SD-20은 R3-21의 B1 필수라 요청 레코드 필드 구조 시험(리플렉션)도 U1에서 한다(Step 30). 나머지 묶음(SD-9 · SD-22 · SD-25 구조, SCD-4 표식, PD-1 · PD-11 규칙)은 3.6 이후로 두고 그동안 코드 리뷰 점검표로 지킨다 | security C13 · RD-2 · cicd A안 · R3-21 · SD-20 | Plan Approval |
| J114 | 현재 정책 버전의 자리 — `core_approved_content(content_code='CONSENT_POLICY')`의 APPROVED 최고 버전 행 `content_text`(임시 '1.0')를 현재 정책 버전 문자열로 읽는다. demo 참조 시드와 dev 시드는 정책 1행 + 승인 문구 2종이다 | data-model 5-1 · components ConsentPolicy · R4-15 · BR6.1 ① | U2(OQ7) |
| J115 | DB 형식 — uuid → `uuid`, string(n) → `varchar(n)`, timestamp → `timestamptz`(엔터티 필드 `Instant`), json → `jsonb`. DB 기본값으로 시각을 넣지 않는다(R2). Spring Session 두 표는 Spring Session 표준 정의(CASCADE · PK 순서)에 이름만 `core_spring_session*`. `core_consent.child_id`는 entities.md대로 NOT NULL(완화는 U2가 V2로). ERD v0.2 목록에 올린다 | data-model 5-2 ~ 5-5 · entities.md | ERD v0.2(OQ2-a) |
| J116 | Flyway 세부 — 이력 표 `flyway.core_schema_history` · `flyway.realtime_schema_history`, `defaultSchema=flyway`, `createSchemas=false`, `cleanDisabled=true`. V1 DDL은 스키마를 한정해 쓴다. 자기 이력 표 SELECT 권한은 각 위치의 `afterMigrate.sql`이 멱등으로 준다(init 때는 표가 없다) | J84(a) · SD-13 · RD S2 · data-model 5-6 | Plan Approval |
| J117 | 시드 임시값 — 시드 보호자 · 아이 · 동의 4행 · 승인 문구 행의 고정 UUID는 코드 상수이고 `infra/seed/`와 DevSeedRunner가 같은 값 + `ON CONFLICT DO NOTHING`을 쓴다. 아이 '하늘' · 8세 · 관심 ["공룡","그림"], 동의 `policy_version` '1.0', `content_type` 'APPROVED_AUDIO', 문구 CLOSING '오늘 이야기 즐거웠어. 다음에 또 만나!' · WAITING_FILLER '음, 잠깐만 생각해 볼게.', DB 이름 `mujoong` | functional-spec 9절 · G4 · data-model 5-16 · infra 5-11 | U2(OQ7) · D 담당 |
| J118 | 화면 임시 문구 — 문구표에 없는 문구(08의 503 '지금은 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.', 12 PREPARING 중 끝내기 토스트 '아직 시작하는 중이에요. 시작된 뒤 다시 눌러 주세요.', 잠긴 탭 DEVICE_STATUS_UNKNOWN '기기 상태를 확인할 수 없어 시작할 수 없어요', 05a 429 '잠시 뒤 다시 시도해 주세요' · DEVICE_ALREADY_ACTIVE '이미 연결된 기기가 있어요', 오류 봉투의 code별 고정 message)는 `frontend/src/shared/copy/ko.ts`의 `TEMP_COPY` 묶음과 `contracts/errors.md`에만 두고 code-summary에 목록을 남긴다. 화면에는 '(문구 미정)'을 보이지 않는다 | V-6 · scope-trace G2 · flows 5절 빈칸 | 사람(문구표 개정) |
| J119 | 시험 로그인 · 홈 응답 · 화면 라우트 — `POST /api/dev/login` 200 본문 `{guardianId}`. `GET /api/home` U1 모양은 child = 시드 아이, device = DeviceView, currentSession = null(U4), lock = `{locked: !canStart, reasonCode}`. 라우트 `/device` · `/parent` · `/parent/device`(스텁 → `/parent`, J57) · `/parent/device/register` · `/parent/free` · `/parent/sessions/[sessionId]` · `/parent/sessions/[sessionId]/done` · `/parent/dev-login`(서버에서 `MUJOONG_DEVLOGIN_ENABLED`가 true가 아니면 notFound) · `/parent/practice` · `/parent/records`(스텁 → `/parent`). 401은 `/parent/dev-login`으로 보낸다(01은 U2) | contracts 시트 5-8 · flows 시트 2절 · J57 | U2 |
| J120 | 12 · 17 분기 빈칸 — 12는 BR6.15대로 `final` 플래그가 있는 PARTIAL 행도 받아 seq로 덮어쓰고 '(말하는 중)' 표시는 U4로 둔다. P1 409 뒤 다시 읽은 상태가 FAILED면 17이 아니라 08 사유 카드로 간다. 17의 「오늘 기록 보기」는 감춘다(U6) | flows 시트 충돌 5 · 7, 빈칸 | U4 · U6 |
| J121 | 기기 웹 오디오 — 캡처는 「켜기」 클릭 핸들러 안에서 `AudioContext({sampleRate: 24000})`를 만들고 AudioWorklet이 20ms(480표본) 프레임을 만든다. 브라우저가 24kHz 문맥을 거부하면 AudioWorklet이 선형 보간으로 48 → 24kHz를 바꾼다. `echoCancellation` 켬 | flows 3.7 빈칸 · J27 · NFR10.4 | 3.6 브라우저 실기(NFR10.1) |
| J122 | Next.js CSP — nonce · CSP 헤더는 요청마다 런타임 환경 변수(`MUJOONG_SECURITY_ALLOWED_ORIGIN` · `SERVICE_HOST`)로 만든다(`NEXT_PUBLIC_*` 금지, 같은 이미지 이동 J94). 파일은 Step 1에서 고정한 Next.js 판에 맞춰 `frontend/src/middleware.ts`(15.x) 또는 `frontend/src/proxy.ts`(16.x). `connect-src`의 wss 출처는 J142 | SD-2 · X-6 · security 5.2 | Step 1 |
| J123 | WSS 세부 — (s) 적용 범위: 크기 초과 · 965B가 아닌 0x01 · 서버가 받은 0x02 · HELLO 형식 오류는 4400, READY 뒤 8KB 이내 JSON의 모르는 type · 틀린 payload는 ERROR(UNKNOWN_TYPE · BAD_PAYLOAD) 뒤 연결 유지. HELLO 타이머는 시계 1이 맡고 만기 때 그 연결의 제어 레인에 4408 닫기를 넣는다(연결 시도마다 세대). 연결 traceId는 Nginx `X-Trace-Id`가 형식에 맞으면 쓰고 아니면 새로 만든다. DEVICE_STATE · PLAYBACK_MARK PROGRESS는 받아도 무시한다. 컨테이너 텍스트 버퍼 16KB | contracts 시트 5-5 · 5-11 · runtime C6 · security 5.2 · C10 | Plan Approval |
| J124 | 기동 · 운영 세부값 — Retry-After = `mujoong.health.db-check-interval`(5초, NOT_READY · DRAINING 공통). 기록기 지연 해제선 = `limits.recorder-queue`의 절반. DB 재시도 지터 0 ~ 50ms와 LAST_INPUT_FRAME 에너지 임계값은 허용 목록에 올린 코드 상수. `outbox.control-event-policy=RETRY_FOREVER`는 보류 옵션이라 기동 실패. `mujoong_db.data_class`가 없거나 모르는 값이면 `real`로 본다(fail-closed). 비밀 하한: `INTERNAL_SERVICE_TOKEN` 256비트(base64url 43자 이상), ownerHash 비밀 32바이트 이상. C-INT 401 봉투 code는 `UNAUTHENTICATED` | runtime C4 · C7 · security 5.2 · data-model 5-12 | Plan Approval |
| J125 | last_seen_at · 정상 종료 — `last_seen_at`은 HEARTBEAT를 받을 때만 DB에 쓰고 ④ 시계는 메모리의 마지막 메시지 시각을 쓴다. 정상 종료는 최상위 phase의 SmartLifecycle이 T1 ~ T5를 한 뒤 웹 서버 graceful 단계로 넘긴다(V-9) | runtime C5 · C8 · BR4.8 | 사람(RD ## 5) · 3.6 리허설 |
| J126 | SERVER_RESTART 사용 범위 — 고아 세션 점검과 실행기 예외 처리(R3-6)도 failure_code SERVER_RESTART를 쓴다(BR6.8 문구는 BR6.13에만 허용). A1 ~ A6 밖 FD 보강 후보로 반영 목록에 올린다 | runtime C3 · R3-6 | 사람 |
| J127 | 시험용 값의 생성 — 시험용 비밀(DB 비밀번호 · 내부 토큰 · ownerHash 비밀)은 실행 때 `infra/ci/gen-env.sh`가 만들어 `build/verify/env/`(umask 077, 실행 끝에 삭제)에 두고, Testcontainers 시험은 `test-support`가 실행마다 만든다. infra 시트의 값 파일 `infra/ci/fake.env`는 두지 않는다 | 사람 지시(시험용 값은 실행 때 생성) · NFR9.10 · SD-12 | Plan Approval |
| J128 | 이미지 · 배포 세부 — 자체 `mujoong-backup` 이미지(PostgreSQL 16 클라이언트 + supercronic + flock, 이미지 6종, V-15 결과에 따라 바뀜). 로그인 사용자 이름용 `GHCR_PULL_USER`(String, 비밀 아님) 매개변수를 더한다. SSM 경유 배포의 `deploy.log`는 `trigger=CI runId=none` | infra 시트 5-2 · 3.7 · 5-8 · V-15 | 클라우드 담당 A · 10/13 |
| J129 | tools 언어 · 위치 — 가짜 공급자 · 기기 시뮬레이터 · C안 시나리오 · WAV 생성기는 Node 22 ESM(`.mjs`) + `ws` 패키지로 `tools/`에 두고 앱 이미지 밖에 둔다(가짜 공급자는 dev · ci Compose 서비스로만). 앱 통합 시험의 대역(가짜 공급자 포트 · 시험용 WS 서버 · 기기 시뮬레이터 클라이언트 · 계약 대역 서버 · 수신기 대역)은 `backend/test-support`(자바, 시험 전용)에 둔다 | J81 · SD-25 · RD-23 · scope-trace C5 | Plan Approval |
| J130 | FD 보강 반영 방식 — A1 ~ A6과 3.3이 더한 동작은 코드 · 시험 · 반영 목록(이 계획의 표와 code-summary.md)으로 반영하고, 승인된 `rules.md` · `entities.md` · `contract-summary.md` 본문은 고치지 않는다. 문서 본문 개정이 필요하면 사람이 지시한다 | 사람 결정 5 · 보고서 7절 8 · logical-components ## 5 | 사람 |
| J131 | 검증 명령 보탬 — C안의 A에 tools 시험을, C에 B1 확인할 가설의 측정(Outbox → Inbox로 04 STABLE에 닿는 시간, 입력 음성 바이트 해시 일치로 본 PCM16 · 24kHz 무변환 중계, LATENCY_TURN 시각 키)을 기록만 더한다. 가설 측정은 판정이 아니다(OD-8 키가 있는지는 판정, J143) | bolt-plan B1 확인할 가설 · OD-8 | Plan Approval |
| J132 | DB 시험 백엔드 — `kr.mujoong.testsupport.db.MujoongPostgres`는 백엔드 둘(`docker` = Testcontainers, `local` = 호스트 PostgreSQL 16 바이너리)을 두고 시스템 속성 `mujoong.test.db=auto\|docker\|local`(Gradle `-Pmujoong.test.db`로 넘김)로 고른다. 기본 `auto`는 Docker가 없거나 고정 태그 pull이 실패하면 `local`을 고른다. `local`은 `runuser -u postgres -- /usr/lib/postgresql/16/bin/initdb -D build/tmp/pg-<uuid> --auth=scram-sha-256 --pwfile=<0600 생성 파일>`(initdb · pg_ctl은 root로 돌지 않음) 뒤 `pg_ctl -o "-p <빈 포트> -c listen_addresses=127.0.0.1 -k <임시 소켓 디렉터리> -c timezone=UTC" start`로 띄우고, `infra/postgres/init/01-roles.sh`를 같은 환경 변수로 실행한 뒤 Flyway Java API로 두 위치를 적용하고 역할별 DataSource를 준다. JVM 종료 훅에서 `pg_ctl stop -m fast`로 멈추고 디렉터리를 지운다. 시험 태그 `docker`는 `db`(PostgreSQL 필요)로 바꾸고, 결과 보고에 어느 백엔드로 돌았는지 적는다 | R-01 · 운영 지시(설치 시도 · 건너뜀 보고) · SD-13 · J106 | Plan Approval |
| J133 | 검증 명령 host 모드 — `scripts/verify.sh --mode auto\|compose\|host`. `auto`는 `docker info`와 postgres 고정 태그 pull이 성공할 때만 `compose`를 고른다. `host`는 A를 `local` DB 백엔드(J132)로 돌리고, B를 같은 방식의 로컬 클러스터 + dev 프로필 bootJar 두 개(`java -jar`, 127.0.0.1:8080 · 8081) + `node .next/standalone/server.js`(3000) + `node tools/fake-provider/server.mjs`(8090) + Nginx 대역 `tools/dev-edge/edge.mjs`(Node https + ws 중계로 `/api/` · `/ws/device` · `/media/approved/` · `/`를 나르고, `/internal/` · `/actuator/` 403, `X-Trace-Id` · `X-Forwarded-For` 덮어쓰기, `gen-selfsigned` 인증서로 127.0.0.1:8443)로 올려 같은 3종 호출 · C1 ~ C12 · 사후 단언(psql 42501, 호스트 프로세스 로그 파일 위생)을 돈다. host 모드의 앱 신뢰 프록시 주소는 127.0.0.1이다(SEC-11 키를 환경 변수로 줌). `report.txt` 첫 줄에 `mode=host`를 적고, host 모드가 확인하지 못하는 이미지 · Compose 불변식 · Nginx 설정 · 망 격리 단언은 `NOT_VERIFIED(host)` 줄로 남겨 CI `skeleton` 작업 몫으로 넘긴다. 사람 결정 11(2026-10-10)에 따라 Docker 데몬을 띄우지 못할 때 host 모드 통과를 C안 통과로 인정한다. 단 `NOT_VERIFIED(host)` 줄은 보고에서 빼지 않는다 | R-01 · 사람 결정 2 · 11 · org.md Walking Skeleton | 사람 결정 11(확정) · verification-command 체크포인트 |
| J134 | 엔진 checkpoint 검증과 명령 구성 — `aidlc engine bolt checkpoint --action verify`는 기록된 명령을 저장소 루트에서 `/bin/bash -c`로 실행한다. 120초가 지나면 SIGKILL로 끊고, stdout · stderr가 각 1 MiB를 넘으면 실패이며, 통과는 종료 코드 0 · 신호 없음 · 증거 지문 불변일 때뿐이다(`.claude/tools/aidlc-construction-checkpoints.ts` 110 · 111 · 521 ~ 553행). C안 전체(gradle clean check + Testcontainers, npm ci · next build, 이미지 6종 빌드, Compose 기동, 04 STABLE 대기, 한 턴)는 120초 안에 끝나지 않으므로 Plan Approval 전에 사람에게 구성을 물었고, 사람 결정 10(2026-10-10)으로 (가)를 고른다. host 모드(사람 결정 11)에서도 `--engine`은 같은 규칙을 따른다: 전체 실행이 만든 bootJar 두 개와 `.next/standalone`의 소스 지문 파일(`build/verify/fingerprint`)이 지금 지문과 같을 때만 로컬 클러스터 · 호스트 프로세스를 띄워 C · 사후 단언을 110초 안에 돌고, 다르거나 없으면 종료 코드 1이다. (가) 엔진 명령은 `bash scripts/verify.sh --engine`. 빌드 때 이미지 라벨에 넣은 소스 지문(`git rev-parse HEAD` + 작업 트리 diff · 미추적 파일 해시)이 지금과 같은 로컬 이미지가 없으면 종료 코드 1로 끝내 낡은 증거로 통과하지 못하게 하고, 있으면 B · C · 사후 단언만 내부 기한 110초로 돈다. A와 이미지 빌드는 `bash scripts/verify.sh`(CI `skeleton` · Step 51)가 맡는다. (나) 엔진 명령을 A의 일부로 줄인다. (다) 그 밖. 검증 명령은 Plan Approval이 아니라 verification-command 체크포인트의 사람 Approve 영수증으로만 확정된다(stage-protocol-construction.md 145 ~ 204행) | SEC-01 · SEC-09 · J103 | 사람(Plan Approval 전 질문 · verification-command 체크포인트) |
| J135 | pg_hba 허용 대역 — `infra/postgres/init/02-hba.sh`는 허용 대역을 필수 환경 변수 `PG_HBA_ALLOWED_CIDR`로 받는다(기본값 없음, 비면 종료 코드 1). 그 대역의 `scram-sha-256` 한 줄과 나머지 `reject`만 쓴다. Compose demo · ci는 `172.30.20.0/24`(backend 서브넷, N-2), `MujoongPostgres`는 `docker` 백엔드면 컨테이너가 보는 출발지(브리지 게이트웨이 /32), `local`이면 `127.0.0.1/32`를 넘긴다. 비밀이 아니므로 Compose `environment:`로 준다 | R-02 · SEC-04 · infra-spec 2.1 postgres · 2.5 | Plan Approval |
| J136 | 검증 스택 Compose 덧붙임 — `compose.ci.yml`은 덮어써야 하는 시퀀스를 `!override`로 쓴다(nginx `ports: !override ["127.0.0.1:8443:443"]`, 각 서비스 `env_file: !override [build/verify/env/<서비스>.env]`). Compose v2.24.4 이상이 필요하므로 Step 1 판 표에 적는다. 호스트 절대 경로 바인드는 같은 컨테이너 경로로 다시 선언한다: postgres 데이터 → 이름 있는 볼륨 `verify-pgdata`, init → `./infra/postgres/init`(읽기), nginx 설정 → `build/verify/nginx`(render-nginx 출력), 인증서 → `build/verify/certs`, 승인 음성 → `./infra/seed-audio`(읽기). `gen-env.sh`는 인프라 명세 3.7 분배표대로 서비스별 env 파일(postgres · migrate · core-api · realtime · web · nginx)을 만들고 받지 않는 비밀은 넣지 않는다 | SEC-02 · infra-spec 2.1 · 2.2 · 3.7 · SD-12 | Plan Approval |
| J137 | 커버리지 측정 범위 — 유닛 하한 판정 명령은 그 유닛이 만든 파일만 잰다. 목록은 `backend/coverage-scope/u1.txt` · `frontend/coverage-scope/u1.json`(Step 5 · 6이 읽는 장치와 빈 목록을 만들고, Step 53이 source-manifest의 U1 소스 파일로 채운 뒤 대조한다. 하나라도 빠지면 실패)이고, Gradle 속성 `-PcoverageScope=u1` · 환경 변수 `MUJOONG_COVERAGE_SCOPE=u1`일 때만 JaCoCo `classDirectories` · Vitest `coverage.include`가 이 목록을 읽는다. 속성이 없으면 모듈 전체를 잰다(CI backend · frontend 작업과 검증 명령 A). 하한 80%와 빈 제외 목록은 그대로다 | R-10 · J107 · 단계 정의 189 ~ 192행 | 사람(cicd 열림 2) |
| J138 | 실시간 구동기 시험 · 커버리지 제외 후보 — 운영 타이머 구동기 `DelayQueueTimerDriver`와 `SystemMujoongClock`의 자기 시험만 수십 ms 단위 실제 시한을 상한 있는 `CountDownLatch` 대기로 확인한다(`Thread.sleep` 없음). NFR6.2 경계 시험은 계속 `ManualTimerDriver`를 쓴다. 프런트에서 jsdom으로 실행할 수 없는 줄(AudioWorklet `registerProcessor` 등록부 등)은 순수 함수로 빼고, 남은 줄은 Plan Approval 질문 '커버리지 제외 후보(파일 · 사유 · 3.6 실기 확인 항목)'로 올린다. 사람이 승인하기 전에는 제외하지 않는다 | R-09 · NFR6.2 · J107 | Plan Approval |
| J139 | 스택 판 확정 — Gradle 래퍼는 설치된 8.14.3으로 만든다(9.x로 바꾸는 것은 사람이 정함). Boot 4.1 판정은 Step 3 끝에서 ① 두 앱 `runtimeClasspath` · `testRuntimeClasspath` 해석에 FAILED 0 ② Spring Session JDBC · Flyway + `flyway-database-postgresql` · springdoc · Testcontainers · ArchUnit · JaCoCo가 Boot 4.1 BOM이나 명시 판으로 해석됨 ③ `bootJar` 성공으로 한다. 하나라도 실패하면 Step 8 전에 3.5.x로 전환하고 판 표에 사유를 적는다. Step 8 이후 드러난 호환 문제로는 자동 전환하지 않고 그 기능만 우회 · 보고하며 전환은 사람이 정한다. 전환 비용을 줄이려고 앱 코드의 Jackson 직접 import는 `kr.mujoong.contract.json` 한 곳으로 모은다. 설계의 확정 조건(기동 · Security · Session JDBC · JPA 동작)은 Step 28 · 34 · 51 결과로 판 표에 적는다 | R-07 · R-11 · R-15 · tech-stack 2.2 34 · 35행 | Step 3 · 사람(전환) |
| J140 | 확인 결과 기록 위치 — tech-stack-decisions.md 78행은 확인 세 건(Boot 4.1 호환 · Gradle 판 · 공급자 SDK 지원)의 결과를 2.2절 행에 적으라고 하지만, 승인 문서 본문을 고치지 않는 방침에 따라 결과는 code-summary.md 판 표에 두고 tech-stack 2.2 갱신은 사람이 지시할 때 한다(J130 확장) | R-18 · J130 | 사람 |
| J141 | 공급자 주소 기본값 위치(J109 보완) — `mujoong.provider.url`의 실제 공급자 기본값은 `application-demo.yml` · `application-prod.yml`에만 두고 `application.yml`에는 두지 않는다. 비어 있으면 S1b가 기동을 거부한다. `test` 프로필은 공급자 호스트가 루프백이 아니면 기동을 거부한다. 검증 스택은 `MUJOONG_PROVIDER_URL`을 `fake-provider`로 명시하므로 덮어쓰기를 빠뜨리면 실공급자로 가지 않고 기동이 실패한다 | SEC-20 · J109 · SD-25 | Plan Approval |
| J142 | CSP `connect-src`(J122 보완) — wss 출처는 `SERVICE_HOST`가 아니라 `MUJOONG_SECURITY_ALLOWED_ORIGIN`의 스킴만 https → wss로 바꾼 값(포트 유지)으로 만든다. 검증 스택에서는 `wss://localhost:8443`이다 | SEC-17 · SD-2 2.2.1 · J108 | Plan Approval |
| J143 | OD-8 키 존재 판정(J131 보완) — LATENCY_TURN 한 줄의 시각 키 8종(tSpeechEnd · tProviderFirstOut · tSegmentConfirmed · tGatePass · tFirstFrameSent · tDevicePlayStart · tDevicePlayStartRecv · tTranscriptFinal)과 tSpeechEndSource · clockOffsetMs · rttMs · providerResponseMs · quietWaitMs, SESSION_ITEMS_SERVED의 tViewItemReady · tViewItemFirstServed가 있는지는 판정(없으면 실패)이고 값은 기록만 한다. 기기 시뮬레이터 · 기기 웹은 HEARTBEAT 송신 직전에 제어 봉투 `sentAt`을 채운다(R3-4). PLAYBACK_MARK_STOPPED 줄(PD-19)은 R3-21에 따라 B1 이후다 | SEC-13 · F19 · OD-8 · R3-4 · R3-21 | Plan Approval |
| J144 | 검증 명령 보탬 2 — verify 스크립트의 엄격 모드 · 대기 기한 · 시작 정리(SEC-01 · SEC-05), A의 비밀 스캔 `scripts/verify/a-secrets.sh`(SEC-10), B의 병합 Compose 설정 단언 · 프로필 가드 이미지 기동 · Nginx 단언(SEC-02 · SEC-08 · SEC-12 · F6), C의 기기 쪽 바이트 · Origin · CSRF · 쿠키 판정(SEC-07 · SEC-16), 사후 단언의 접속 방식 · 로그 위생 목록(SEC-04 · SEC-06 · F14), 시작 · 끝 작업 트리 비교(SEC-18)를 더한다. 모두 판정이고 어기면 종료 코드 1이다. 덜어 내는 단언은 없다 | 사람 결정 2 · J103 · J131 | Plan Approval |

## 구현 공통 규칙

**위치 · 범위**
- 애플리케이션 코드는 저장소 루트의 실행 단위 디렉터리에 둔다(J97). 기록 디렉터리(`aidlc/`)에는 계획 · 지시 · 요약 · 추적 파일만 둔다.
- `aidlc/spaces/default/knowledge/documents/` 원본과 다른 유닛의 construction 디렉터리는 읽거나 쓰지 않는다. 기존 `scripts/cloud-setup.sh`는 고치지 않는다.
- 승인된 계약(`contract-summary.md`)과 U1 규칙(`rules.md`)을 그대로 따른다. 계약을 넓히는 편차는 `contracts/`에만 더하고 표시한다(J112). 승인 문서 본문은 고치지 않는다(J130).
- U2 ~ U7 몫은 만들지 않는다. 라우트 스텁(J57 · J119) 말고는 자리표시자 구현을 두지 않고, TODO는 사유와 소유 유닛을 함께 적을 때만 쓴다.
- 코드 주석 · 커밋 메시지에 근거 ID(BR · NFR · AC · J · RD/SD/OD/PD)를 남긴다. J번호는 확정이 아니라 가정임을 나타낸다(사람 결정 8).

**비밀값 규칙(예외 없음)**
- 계정 · 비밀번호 · API 키 · 토큰 값 · 실제 도메인을 계획 · 코드 · 설정 파일 · 시험 · 픽스처 · 로그 · 문서 · 커밋 어디에도 쓰지 않는다.
- 저장소에는 환경 변수 · 매개변수 **이름**만 둔다(SD-12 등록부: `POSTGRES_PASSWORD` · `DB_OWNER_PASSWORD` · `DB_CORE_PASSWORD` · `DB_REALTIME_PASSWORD` · `DB_BACKUP_PASSWORD` · `INTERNAL_SERVICE_TOKEN` · `PROVIDER_API_KEY` · `MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET` · `GOOGLE_OAUTH_CLIENT_SECRET` · `GHCR_PULL_TOKEN`). 코드에 기본 비밀이 없고, 필수 비밀이 비거나 형식이 틀리면 값을 출력하지 않고 종료 코드 1로 끝난다.
- 도메인은 `SERVICE_HOST` 자리표시자(J98). 시험 origin은 `https://mujoong.invalid`(단위 · 통합) · `https://localhost:8443`(검증 스택)이다(J108).
- 시험용 값은 실행 때 생성한다(J127). Testcontainers 비밀번호 · 내부 토큰 · ownerHash 비밀 · 기기 토큰 · bootstrap 비밀은 시험마다 SecureRandom으로 만든다.
- `.env*`, 인증서 · 키 파일(`*.pem` · `*.key` · `*.p12` · `*.jks`), `build/verify/` 산출물은 `.gitignore`로 막고, CI `secrets` 작업(Step 50)과 검증 명령의 `scripts/verify/a-secrets.sh`(Step 49, J144)가 함께 확인한다. 샌드박스에서는 Actions가 돌지 않으므로 3.5 · 3.6 동안의 확인은 검증 명령이 맡는다.

**data-testid 규칙**
- 모든 상호작용 UI 요소(단추 · 링크 · 입력 칸 · 탭 · 칩 · 대화상자와 그 단추 · 조작이 달린 토스트)에 `data-testid`를 붙인다. 이름은 화면 접두 kebab-case이고 flows 시트 3.4 후보를 그대로 쓴다(예: `d0-code` · `t19-on` · `t19-off` · `home-device-card` · `tab-free` · `code-cell-0` ~ `code-cell-7` · `register-submit` · `free-start` · `live-end` · `p1-yes` · `p1-no` · `done-home`).
- 상태는 `data-state`(예: `t19-view[data-state=listening]`)로, 말풍선은 `data-seq` · `data-speaker`로 노출한다.
- D0에는 클릭 요소가 없다(BR9.2). `frontend/tests/u1/shared/test-ids.test.tsx`가 화면마다 `data-testid`가 빠진 상호작용 요소 0건을 단언한다(Step 42).

**시각 · 설정 · 로그**
- 시각은 주입한 `MujoongClock` 하나로 읽는다(RD-2). `Instant.now()` 같은 직접 호출과 SQL `now()` · `clock_timestamp()` · `CURRENT_TIMESTAMP`를 쓰지 않고 `:now`로 바인딩한다(R1 · R2 · NFR6.4). JVM · DB 모두 UTC.
- 시한 · 주기 · 한도는 모두 `mujoong.*` 설정 키다(BR8.11). 정본은 `reliability-design.md ## 3`이고, 계약 7절 밖 시간 키는 `contracts/timing.md`에 더하기만 한다.
- 로그는 JSON 한 줄, 키 순서 `ts · level · traceId · component · event · ownerHash · attrs`, 사건 코드 17종과 허용 attrs만 쓴다(OD-1 ~ OD-4). 키 · 내부 토큰 · 쿠키 · 기기 토큰 · bootstrap 비밀(해시 포함) · 전사 · 원음 · 입력 원문 · 등록 코드는 로그에 쓰지 않는다(BR8.7 · BR5.13, 등록 코드는 앞 4자도 쓰지 않음).
- 커버리지 하한(JaCoCo 80% LINE · Vitest lines 80%)을 낮추거나 끄는 설정, 제외 목록 추가를 넣지 않는다. 하한에 못 미치면 시험을 더하고, 그래도 안 되면 틈을 보고한다.

## 저장소 구조

```
(저장소 루트)
├── backend/                         Gradle 멀티 프로젝트 (Java 21 · Spring Boot 4.1.x, 실패 시 3.5.x)
│   ├── settings.gradle.kts · build.gradle.kts · gradle/libs.versions.toml · gradlew · gradle/wrapper/
│   ├── build-logic/                 규약 플러그인 mujoong.java-conventions (JUnit 5 태그 · JaCoCo 80% · UTC)
│   ├── contract-model/              kr.mujoong.contract  계약 · 오류 · 시간 · 타입만 (LC-11)
│   ├── core-api/                    kr.mujoong.core      DeviceRegistry · CoreMessaging · GuardianIdentity(dev) · ActivityCoordinator
│   │   ├── src/main/resources/db/migration/core/   V1__core_base.sql · afterMigrate.sql
│   │   └── Dockerfile
│   ├── realtime-activity/           kr.mujoong.realtime  DeviceGateway · SessionLifecycle · VoiceRelay · OutputGate · ConversationLog · RealtimeMessaging
│   │   ├── src/main/resources/db/migration/realtime/  V1__realtime_base.sql · afterMigrate.sql
│   │   ├── src/main/resources/gate/seed-dictionary.json
│   │   └── Dockerfile
│   └── test-support/                kr.mujoong.testsupport  시험 전용(가변 Clock · 수동 타이머 · PostgreSQL 컨테이너 · 대역)
├── frontend/                        Next.js App Router 한 앱 (Node 22 · React · TS · TanStack Query v5 · Vitest · ESLint)
│   ├── src/app/                     /device · /parent/** · /healthz
│   ├── src/device/                  D0 · T19 · wsClient · audio  (/parent import 금지, NFR11.1)
│   ├── src/parent/                  04 · 05a · 08 · 12 · P1 · 17 · apiClient
│   ├── src/shared/                  timing · protocol · copy · toast · tokens
│   ├── tests/u1/                    U1 시험 (device · parent · shared · lint)
│   └── Dockerfile
├── contracts/                       계약 정본 (OpenAPI · AsyncAPI · JSON Schema · errors · timing · ports · ai-io · deviations)
├── infra/                           compose.{demo,dev,ci}.yml · nginx/ · postgres/init/ · migrate/ · backup/ · seed/ · seed-audio/
│                                    bundle/ · bin/ · host/ · aws/ · ci/ · RUNBOOK.md · release.env.tmpl
├── tools/                           Node 22 ESM: fake-provider/ · device-simulator/ · skeleton-e2e/ · dev-edge/(host 모드 Nginx 대역, J133) · seed/ · lib/ · tests/u1/
├── scripts/                         verify.sh · verify/*.sh   (기존 cloud-setup.sh는 그대로)
├── ci/security-exceptions.yml
└── .github/workflows/               ci.yml · deploy.yml
```

- 유닛 구분은 컴포넌트 패키지(`kr.mujoong.core.device` 등)와 `source-manifest.json`으로 한다(J97). 뒤 유닛은 같은 실행 단위에 패키지를 더한다.
- 포트: core-api 8080 · realtime 8081 · 관리 9090(노출 `health,prometheus`) · web 3000 · 가짜 공급자 8090(dev · ci 전용).
- 내부 호출 이름은 backend 망의 `core`(core-api 별칭) · `realtime`이다(infra-spec D-12, J109).

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "org",
  "ordering": "implement each applicable testable layer, then write and run that layer's tests.",
  "scope": "mujoong-mvp",
  "test_strategy": "standard",
  "project_type": "greenfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
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
    "runner_step": "Bootstrap the minimal test runner/configuration and record the exact unit-scoped command.",
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
      "Bootstrap the minimal test runner/configuration and record the exact unit-scoped command.",
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
  "input_sha256": "sha256:c98ee83c76ef18703cb2a301c165b2f5ee29d89d09a74caa5b2e6200c62d9674",
  "contract_sha256": "sha256:e3dbecfc856a69ab6ac857b5d990db96595aff96c82b2813fe5f338ff61d1d75"
}
```

## 시험 전략과 커버리지

- **순서.** 위 계약의 `plan_profile.steps`를 그대로 따른다: 구조 · 설정 골격(Step 1 ~ 4) → 실행기 부트스트랩(Step 5 · 6) → 데이터 모델 구현(7 ~ 11) → 그 시험(12 ~ 14) → 데이터 접근 구현(15 · 16) → 그 시험(17 · 18) → 업무 로직 구현(19 ~ 26) → 그 시험(27 ~ 30) → API 구현(31 ~ 33) → 그 시험(34 ~ 36) → 프런트 구현(37 ~ 40) → 그 시험(41 · 42) → 환경 · 빌드 설정(43 ~ 51) → 문서 · 추적(52 · 53).
- **Standard 분량.** 컴포넌트(시험 클래스가 겨냥하는 단위)마다 시험 5 ~ 8개 + 핵심 경계 통합 시험. 경계는 ① PostgreSQL(Testcontainers 또는 호스트 PostgreSQL 16(J132), 실제 init 스크립트와 두 마이그레이션 위치) ② 앱 사이 HTTP(양쪽에서 `contracts/schemas`로 요청 · 응답을 검사하는 계약 대역 서버) ③ WSS(실제 Spring 서버 · 무작위 포트에 시험 클라이언트로 핸드셰이크)다. 실제 두 앱 사이 HTTP · Outbox → Inbox는 C안 Compose 스택에서 끝-끝으로 확인한다. 각 시험 단계에 시험 클래스와 증명 ID를 적었다.
- **요구가 정한 추가 종류.** 보안 시험(SD-4 ~ SD-15 해당분), 끝-끝(C안). 성능 · 부하는 11/2 측정 몫이다.
- **커버리지 하한.** 80% 줄 커버리지를 백엔드 각 모듈(JaCoCo LINE 0.80, `check`에 묶음)과 프런트 `src/**`(Vitest v8 `thresholds.lines` 80)에 건다(J107). 유닛 하한 판정 명령은 U1이 만든 파일 목록만 재고, 모듈 전체 80%는 태그 필터 없는 전체 시험(CI · 검증 명령 A)이 판정한다(J137). Testing Contract의 `scope_floor`는 `mujoong-mvp`에 새 하한을 더하지 않지만, 80% 하한은 설계 입력(J91 · cicd 2.2 · CI 관문)이 정한 측정 목표라 그대로 적용한다(더 엄격한 쪽, 계약의 결합 규칙과 충돌하지 않음). 하한은 낮추지 않는다.
- **태그 · 범위 명령.** 모든 U1 시험 클래스 `@Tag("u1")`, PostgreSQL 필요 시험 `@Tag("db")`(J106 · J132). 정확한 명령은 `unit-test-instructions.md`.
- **대역 원칙.** 시각은 가변 Clock + 수동 타이머 구동기(실제 시간을 기다리지 않음, NFR6.2), 공급자는 가짜 공급자, 기기는 시뮬레이터, 상대 앱은 계약 대역. 장애 주입 훅은 시험 소스 집합과 `tools/`에만 있다(SD-25 · J81).
- **3.5에서 쓰지 않고 3.6 · 그 뒤로 넘기는 시험(R3-21 · 각 문서 `## 4`).** RD-10 8분 재시도 시나리오(재무장 함수와 조건 시험은 3.5), RD-13 SIGTERM · 정지 주입 리허설, RD-15 · LC-12 장애 격리 주입, RD-21(NFR4.16 폰 오프라인 주입, R3-21 347행), SD-2 브라우저 CSP 콘솔, SD-3 중 B1 필수(`/internal` 403 · 본문 64KB · 클라이언트 IP 덮어쓰기, 검증 명령 B 4단계)를 뺀 나머지 Nginx 시험, SD-10 한도기 전체, SD-16 의존성 관문 실측, SD-19(NFR1.3 · NFR2.1 문구 금지 어휘 단위 시험과 화면 8곳 체크리스트, R3-21 345행), SD-21 삭제 지휘 통합(U2), PD-7(승인 문구 · 정책 버전 캐시 — B1은 DB 직접 읽기로 대신, R3-21 344행), PD-8 · PD-9 EXPLAIN · 질의 수, PD-14 번들 예산, PD-17 · SCD-6(표본 판정 스크립트), PD-18(부하 시드 도구와 그 설정 시험, L1 ~ L7), PD-19(PLAYBACK_MARK_STOPPED 기록 경로, R3-21 348행), OD-5 · OD-6 · OD-9 · OD-10 · OD-12 이름 표 · 스냅샷 시험, ArchUnit 묶음 나머지(J113), 접근성 axe · Lighthouse · 실기 브라우저(NFR10.1 · 10.2). Step 53의 traceability는 이 목록을 Deferred · 사유의 근거로 쓴다.

## 구현 단계

**공유 파일 소유(숨은 편집 방지).** 여러 단계가 같은 파일을 고치지 않도록 소유 단계를 정한다. 여러 에이전트로 나눌 때는 같은 파일을 가진 단계를 한 에이전트에 묶는다.

| 파일 | 쓰는 단계 | 규칙 |
|---|---|---|
| `backend/build-logic/**` · `backend/gradle/libs.versions.toml` · `backend/*/build.gradle.kts` · `backend/settings.gradle.kts` | Step 3 · 5 | 운영 · 시험 의존성(Spring Security · Session JDBC · JPA · Flyway + postgresql 모듈 · WebSocket · Micrometer Prometheus · Testcontainers · JSON Schema 검증기 · ArchUnit · junit-platform-launcher)을 Step 3이 모두 선언하고, Step 5는 시험 실행 설정과 `verifyBootJarHasNoTestClasses` 작업만 더한다. 뒤 단계는 이 파일들을 고치지 않는다 |
| `frontend/package.json` · `frontend/package-lock.json` · `tools/package.json` · `tools/package-lock.json` | Step 4 · 6 | 의존성 추가는 `npm install --save-exact`로 package.json과 잠금 파일을 함께 갱신한다 |
| `README.md` · `contracts/README.md` · `infra/RUNBOOK.md` | Step 2 · 7 · 47이 만들고 Step 52가 절을 더하기만 | 앞 단계가 쓴 절을 고치지 않는다 |
| `backend/*/src/test/resources/application-test.yml` | Step 5 | 시험 시간 키 축소값의 유일한 출처 |
| `backend/coverage-scope/u1.txt` · `frontend/coverage-scope/u1.json` | Step 5 · 6(자리) · Step 53(내용) | 내용은 source-manifest에서만 채운다(J137) |

### A. 구조 · 설정 골격

- [ ] Step 1: 도구 · 버전 확인과 스택 판 고정
  - 파일: 없음(결과는 Step 3 · 4의 판 고정 파일과 Step 53의 code-summary.md에 남긴다)
  - 하는 일: `java -version` · `docker version` · `docker compose version`(검증 스택의 `!override`에 v2.24.4 이상 필요, J136) · `node --version` · `gradle --version` · `psql --version` · `/usr/lib/postgresql/16/bin/initdb --version`(J132)을 확인한다. JDK 21이 없으면 설치를 시도하고(툴체인은 설치된 `/usr/lib/jvm/java-21-openjdk-amd64`를 쓰고 자동 받기에 기대지 않음), Docker 데몬이 없으면 기동을 시도한다. TLS 검증은 끄지 않는다. Docker는 `docker pull <postgres 고정 태그>`를 실제로 실행해 보고, 실패하면 데몬을 HTTPS_PROXY 환경과 함께 다시 띄운다(저장소 파일에는 쓰지 않는다). `/root/.ccr/README.md`에는 데몬 pull 절차가 없고 컨테이너 안 빌드 규칙만 있다(그 규칙은 Step 43). pull 성공 · 실패를 판 표에 적고 verify 모드(J133)와 DB 시험 백엔드(J132) 결정에 쓴다. 정확한 판을 고정할 후보를 정한다: Spring Boot 4.1.x 최신 패치(의존성 해석 판정은 빌드 파일이 생긴 Step 3 끝, J139), Gradle 래퍼 8.14.3(설치판, J139), Next.js · React 안정판(Node 22 호환, Safari 16.4 하한 확인), PostgreSQL 16 이미지 태그, Flyway CLI · Node · nginx 이미지 태그. 전환 대상인 Spring Boot 3.5.x의 OSS 지원 종료일을 spring.io에서 확인해 판 표에 적고, 지원이 끝났으면 3.5.x로 전환하기 전에 사람에게 묻는다. 공급자 Java SDK의 지원 범위(연결 · 이벤트 · 취소 · 교정)를 문서로 확인해 판 표에 적는다(tech-stack 2.2, J110은 SDK 없이 시작). 확인 결과의 기록 위치는 J140.
  - 근거: V-1 · J70 · G5 · G7 · tech-stack 2.2 · 열림 4 · J132 · J133 · J136 · J139 · J140
  - 완료 확인: 판 표(도구 · 라이브러리 · 이미지, 정확한 판 번호, Compose 판, 3.5.x 지원 종료일, 공급자 SDK 확인 결과)와 Docker pull 결과, 설치 시도 결과(성공 · 실패와 영향받는 검증)가 정리돼 있다.

- [ ] Step 2: 저장소 루트 골격과 공통 설정 파일
  - 파일: `.gitignore`(기존 AI-DLC 블록 유지, `.env*` · `build/` · `.gradle/` · `node_modules/` · `.next/` · `coverage/` · `build/verify/` · `*.pem` · `*.key` · `*.p12` · `*.jks` 추가, 자체 서명 인증서는 `build/verify/certs/`에 생기므로 `infra/ci/certs/` 줄은 두지 않음), `.editorconfig`, `.prettierrc.json`, `.prettierignore`, `.nvmrc`(22), `README.md`(기존 내용 유지, '개발 · 검증' 절 추가 자리), 디렉터리 `backend/` · `frontend/` · `infra/` · `contracts/` · `tools/` · `scripts/verify/` · `.github/workflows/` · `ci/`
  - 하는 일: 실행 단위 디렉터리를 만들고 비밀 · 산출물이 커밋되지 않게 한다.
  - 근거: J97 · ADR-001 · org.md 코드 스타일 · NFR9.10 · infra 시트 5-12
  - 완료 확인: `git check-ignore`로 `.env` · `.env.local` · `build/verify/env/x.env` · `build/verify/certs/x.pem` · `frontend/.next/x`가 무시되고, 기존 AI-DLC gitignore 줄이 그대로다.

- [ ] Step 3: backend Gradle 멀티 프로젝트 골격과 운영 설정
  - 파일: `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/gradlew` · `backend/gradlew.bat` · `backend/gradle/wrapper/gradle-wrapper.properties` · `gradle-wrapper.jar`, `backend/build-logic/settings.gradle.kts` · `build.gradle.kts` · `src/main/kotlin/mujoong.java-conventions.gradle.kts`, `backend/{contract-model,core-api,realtime-activity,test-support}/build.gradle.kts`, `backend/core-api/src/main/java/kr/mujoong/core/CoreApiApplication.java`, `backend/realtime-activity/src/main/java/kr/mujoong/realtime/RealtimeApplication.java`, 두 앱의 `src/main/resources/application.yml` · `application-dev.yml` · `application-demo.yml` · `application-prod.yml` · `logback-spring.xml`, `backend/core-api/src/main/resources/application-load-test.yml`(`mujoong.load-test.enabled: true`와 `mujoong.timing.bootstrap.rate-limit` max 600 · window 1시간만, A6), `backend/core-api/src/main/java/kr/mujoong/core/config/CoreProperties.java`, `backend/realtime-activity/src/main/java/kr/mujoong/realtime/config/RealtimeProperties.java`
  - 하는 일: 빌드 스크립트를 쓰기 전, 빈 `backend/`에서 `/opt/gradle/bin/gradle wrapper --gradle-version 8.14.3 --distribution-type bin --gradle-distribution-sha256-sum <services.gradle.org의 gradle-8.14.3-bin.zip.sha256 값>`을 실행해 `gradlew` · `gradlew.bat` · `gradle/wrapper/*`를 만든다(래퍼 jar는 손으로 쓰지 않음, J139). `git ls-files -s backend/gradlew`가 100755인지 확인한다. Java는 HTTPS_PROXY를 읽지 않으므로 `JAVA_TOOL_OPTIONS`에 프록시 속성이 없으면 저장소 밖 `~/.gradle/gradle.properties`에 HTTPS_PROXY에서 뽑은 `systemProp.https.proxyHost` · `systemProp.https.proxyPort`와 `systemProp.http.nonProxyHosts=localhost|127.*|[::1]`를 둔다(중계 포트는 세션마다 바뀌므로 저장소의 `gradle.properties`에는 프록시 값을 넣지 않는다). 이어서 Java 21 툴체인, Boot 플러그인, 모듈 의존(`core-api` · `realtime-activity` → `contract-model`, 서로는 의존 없음, `test-support`는 `testImplementation`만)과 운영 · 시험 라이브러리 판 전부를 `libs.versions.toml`에 선언한다(공유 파일 소유 표). `application.yml`에 `reliability-design.md ## 3`의 `mujoong.*` 키 전부(소유 앱 것만)와 표준 키(`spring.flyway.enabled=false` · `spring.jpa.hibernate.ddl-auto=validate` · `spring.jpa.open-in-view=false` · `spring.threads.virtual.enabled=true` · `server.shutdown=graceful` · `management.server.port=9090` · 노출 `health,prometheus` · probes · `spring.session.jdbc.initialize-schema=never` · `table-name=core_spring_session` · hikari 10 · 2초 · 1초 · `server.error.include-*` 끔 · `server.server-header` 비움 · `server.forward-headers-strategy=native` · `server.tomcat.remoteip.remote-ip-header=X-Forwarded-For` · `server.tomcat.remoteip.internal-proxies`(Nginx 고정 IP 172.30.10.10을 정규식으로 이스케이프한 값 하나, Compose `environment:`로 주입, 인프라 명세 3.3)를 둔다. `mujoong.dev-login.enabled`는 어느 프로필 파일에도 true로 쓰지 않는다. demo · prod 프로필 파일에는 rate-limit 상향 값과 `mujoong.load-test.*` 키가 없다(A6). `mujoong.provider.url`의 실제 공급자 기본값은 realtime의 `application-demo.yml` · `application-prod.yml`에만 둔다(J141). 타입 있는 `@ConfigurationProperties` 레코드로 묶고 기동 때 검증한다(RD-24, 불변식 I-3 · I-6 · I-10 · cookie ≤ idle · 풀 합계 ≤ 20). 비밀은 환경 변수 이름만 참조한다. 끝에 스택 판정(J139)을 한다.
  - 근거: tech-stack 2.2 · J70 · J105 · BR8.11 · RD-24 · SD-14 · PD-11 · OD-11 · R3-10 · A6 · BR1.6 · infra-spec 3.3 · J139 · J141
  - 완료 확인: `cd backend && ./gradlew --no-daemon :core-api:bootJar :realtime-activity:bootJar`가 성공한다(앱 코드는 Application 클래스뿐인 상태). J139 판정: `cd backend && ./gradlew --no-daemon :core-api:dependencies :realtime-activity:dependencies --configuration runtimeClasspath`와 같은 명령의 `--configuration testRuntimeClasspath` 출력에 FAILED 0줄, 판정 결과(4.1 유지 또는 Step 8 전 3.5.x 전환과 사유)가 판 표에 있다.

- [ ] Step 4: frontend Next.js 골격과 운영 설정
  - 파일: `frontend/package.json` · `package-lock.json`(Step 1에서 고정한 판), `frontend/next.config.ts`(`output: 'standalone'`, `poweredByHeader: false`), `frontend/tsconfig.json`, `frontend/eslint.config.mjs`(영역 경계: `src/parent` ↛ `src/device`, `src/device` ↛ `src/parent`, `src/shared` ↛ 둘 다, `react/no-danger` 오류, `react/jsx-no-literals`, `getUserMedia` 호출 제한), `frontend/src/app/layout.tsx`(`lang="ko"`), `frontend/src/app/healthz/route.ts`(200), `frontend/src/shared/styles/tokens.css`(C1 토큰 임시값)
  - 하는 일: 한 앱에 `/parent` · `/device`를 두는 골격을 만든다. 서비스 워커 · PWA를 두지 않는다. 시간 설정은 빌드 때 굽지 않는다. `tsconfig.json` · `next-env.d.ts`는 `next build`가 더 고칠 것이 없는 상태로 커밋한다(빌드 뒤 `git diff` 0, 검증 명령의 작업 트리 비교 SEC-18 · J144).
  - 근거: tech-stack 2.2 L40 · NFR10.4 · NFR11.1 · PD-14 · PD-15 · SD-11 · R4-16
  - 완료 확인: `cd frontend && npm ci && npx --no-install next build`가 성공하고 `.next/standalone/server.js`가 생긴다.

### B. 시험 실행기 부트스트랩

- [ ] Step 5: backend 시험 실행기(JUnit 5 · 태그 · JaCoCo 80% · Testcontainers)
  - 파일: `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`(시험 설정 추가, 라이브러리 판은 Step 3이 `libs.versions.toml`에 이미 선언), `backend/core-api/src/test/resources/application-test.yml` · `backend/realtime-activity/src/test/resources/application-test.yml`(시험 시간 키 축소값, 예: `session.device-ack-timeout` 2초 · `device.disconnect` 4초 · `gate.segment-quiet` 50ms · `provider.connect-timeout` 1초), `backend/coverage-scope/u1.txt`(U1 소스 파일 목록의 자리, 내용은 Step 53이 채움, J137)
  - 하는 일: `useJUnitPlatform`에 Gradle 속성 `includeTags` · `excludeTags`(쉼표 구분)를 연결한다. `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`(Boot BOM 판)를 넣는다(Gradle 9는 자동으로 넣지 않고 8.14는 폐기 경고). 시험 JVM에 `-Duser.timezone=UTC`, 시스템 속성 `mujoong.test.db`를 Gradle 속성 `-Pmujoong.test.db`(기본 auto)에서 넘긴다(J132). JaCoCo를 각 모듈에 걸고 `jacocoTestCoverageVerification`에 `LINE` `COVEREDRATIO` 최소 0.80 규칙을 두며 `check`가 의존하게 한다(제외 목록 없음). `-PcoverageScope=u1`이면 `classDirectories`를 `coverage-scope/u1.txt`의 파일로 한정한다(J137). `test-support` 모듈은 JaCoCo 하한 대상이 아니다(J107). 운영 jar에 시험 클래스가 없는지 보는 `verifyBootJarHasNoTestClasses` 작업도 여기서 정의한다(공유 파일 소유 표, Step 43은 실행만 한다).
  - 근거: Testing Contract `runner_step` · J106 · J107 · J91 · NFR6.4 · J132 · J137 · SD-25
  - 완료 확인: `cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1`가 시험 0건(NO-SOURCE)으로 성공하고, `cd backend && ./gradlew --no-daemon :contract-model:dependencies :core-api:dependencies :realtime-activity:dependencies :test-support:dependencies --configuration testRuntimeClasspath` 출력에 FAILED 0줄(NO-SOURCE는 시험 의존성을 해석하지 않으므로 따로 확인), `./gradlew --no-daemon :core-api:jacocoTestCoverageVerification --dry-run`에 작업이 잡힌다. 명령은 `unit-test-instructions.md`에 기록돼 있다.

- [ ] Step 6: frontend · tools 시험 실행기(Vitest · 커버리지 80%)
  - 파일: `frontend/vitest.config.ts`(환경 jsdom, `setupFiles: tests/u1/setup.ts`, `coverage.provider: 'v8'`, `coverage.include: ['src/**/*.{ts,tsx}']`, `coverage.exclude`는 `*.d.ts`만, `coverage.thresholds.lines: 80`, 환경 변수 `MUJOONG_COVERAGE_SCOPE=u1`이면 `coverage.include`를 `coverage-scope/u1.json`으로 한정, J137), `frontend/coverage-scope/u1.json`(U1 소스 파일 목록의 자리, 내용은 Step 53이 채움), `frontend/tests/u1/setup.ts`(Testing Library · jest-dom), `frontend/package.json` · `frontend/package-lock.json`(`vitest` · `@vitest/coverage-v8` · `@testing-library/react` · `@testing-library/user-event` · `@testing-library/jest-dom` · `jsdom`), `tools/package.json` · `package-lock.json`(Node 22 ESM, `ws` · `vitest`), `tools/vitest.config.mjs`(환경 node)
  - 하는 일: 프런트와 tools의 실행기를 세운다. 개발 의존성은 `npm install --save-dev --save-exact <패키지>@<Step 1 판>`으로 더해 package.json과 잠금 파일을 함께 갱신한다(잠금 파일이 어긋나면 `npm ci`가 실패한다).
  - 근거: Testing Contract `runner_step` · J106 · J107 · J129 · J137
  - 완료 확인: `cd frontend && npm ci`와 `cd tools && npm ci`가 성공한 뒤 `cd frontend && npx --no-install vitest run tests/u1 --passWithNoTests`와 `cd tools && npx --no-install vitest run tests/u1 --passWithNoTests`가 종료 코드 0이다.

### C. 데이터 모델 · DB 동작 — 구현

- [ ] Step 7: contracts/ 정본과 편차 표시
  - 파일: `contracts/README.md`(소유 규칙 · 더하기만 안전 · J25 · (x)), `contracts/parent-api.yaml`(OpenAPI 3.0.3, U1 보호자 REST + `/session/csrf` (u) + `/health` (t), 오류 (d)(e)(m)(n)(o)(r), `x-unit` 표시), `contracts/device-api.yaml`(기기 REST + (a)(b)(c)(k)), `contracts/device-ws.asyncapi.yaml`(AsyncAPI 2.6.0, 메시지 · 닫기 코드 표 + (h)(j)(l)(s)), `contracts/internal-api.yaml`(C8 · C9 + (f)(i)(j)(n)(v)(w)), `contracts/events.asyncapi.yaml`(C11 6종 + (g)(p)(q)), `contracts/schemas/*.schema.json`(JSON Schema 2020-12: 오류 봉투 · WSS 봉투와 메시지 · 이벤트 봉투와 payload 6종 · DeviceView · LiveView · 내부 요청 · 응답), `contracts/errors.md`(code · HTTP · 고정 message, (y) 포함), `contracts/timing.md`(7절 키 + 7절 밖 U1 키 4종과 메시징 · 정리 · 내부 HTTP 키), `contracts/ports.md`(8080 · 8081 · 9090 · 3000, 내부 호출 이름 · 기본 URL 키, J109), `contracts/ai-io.md`(9절, 공급자 메시지 이름, 시드 사전 형식, `mujoong.provider.url`), `contracts/deviations.md`((a) ~ (t) + (u) ~ (y), 항목마다 출처 · 소비자 · 호환 분류 '더하기만')
  - 하는 일: `contract-summary.md`에서 정본 스펙을 만든다(G8). 승인 계약은 고치지 않고 편차는 스펙 안에 `x-deviation: "(a)"` 꼴로 표시한다. 미룬 경로(U2 ~ U7)는 넣지 않는다.
  - 근거: 계약 1 ~ 12절 · J20 · J59 · V-5 · 사람 결정 5 · J109 · J112
  - 완료 확인: 모든 YAML · JSON이 파싱되고, (a) ~ (y) 각각이 `deviations.md`에 한 행, 스펙 파일에 한 번 이상 표시돼 있다(Step 13 시험이 단언).

- [ ] Step 8: contract-model 공통 타입
  - 파일: `backend/contract-model/src/main/java/kr/mujoong/contract/error/{ErrorCode,ErrorEnvelope}.java`, `.../event/{EventEnvelope,EventType,AggregateType}.java` · `.../event/payload/{SessionStateChanged,SessionRecordCaptured,DeviceStatusChanged,DeletionCompleted,DeviceReleased,DeletionRequested}.java`, `.../ws/{WsEnvelope,WsMessageType,CloseCode}.java` · `.../ws/message/*.java`(17종: HELLO · HELLO_OK · HEARTBEAT · HEARTBEAT_ACK · ACK · START_SESSION · DEVICE_READY · SESSION_STATE · OUTPUT_BEGIN · OUTPUT_END · PLAY_APPROVED_CONTENT · PLAYBACK_MARK · STOP_OUTPUT · CLOSE_TRANSPORT · VOICE_SESSION_CLOSED · DEVICE_STATE(기기 → 서버, mic · player · visibility · wakeLock, 계약 4.2) · ERROR, 제어 봉투는 `sentAt` 포함), `.../audio/AudioFrameCodec.java`, `.../trace/TraceIds.java`, `.../time/{MujoongClock,SystemMujoongClock,TimerDriver,DelayQueueTimerDriver}.java`, `.../log/{EventCode,AttrKey,EventLog,StructuredLogFormatter,OwnerHasher}.java`, `.../json/JsonCodec.java`(앱 코드의 Jackson 직접 import가 모이는 한 곳, J139), `.../profile/ProfileGuardRules.java`, `.../secret/SecretCompare.java`, `.../status/{SessionStatus,EndReason,FailureCode,ConnectionState,RegistrationStatus}.java`
  - 하는 일: 두 앱이 같은 이름을 쓰는 계약 · 오류 · 시간 · 타입만 둔다. Entity · Repository · Service · Boot 설정은 두지 않는다. 시스템 시각 직접 호출은 `SystemMujoongClock` 한 곳뿐이다. 모르는 enum은 '알 수 없음'으로 읽고 모르는 필드는 무시한다.
  - 근거: LC-11 · J70 · BR8.6 · BR5.4 · BR5.9 · J27 · OD-1 ~ OD-4 · OD-7 · RD-2 · SD-14 표 · A2 · A6 · (n)(o)(p)(q) · NFR9.15
  - 완료 확인: `cd backend && ./gradlew --no-daemon :contract-model:compileJava`가 성공하고 Spring · JPA 의존이 없다.

- [ ] Step 9: PostgreSQL 초기화(역할 · 스키마 · 권한)
  - 파일: `infra/postgres/init/01-roles.sh`, `infra/postgres/init/01-roles.sql`, `infra/postgres/init/02-hba.sh`, `infra/postgres/postgresql.conf.tmpl`
  - 하는 일: 빈 볼륨에서 한 번만 실행된다. DB `mujoong`, 역할 4개(`mujoong_owner` 4 · `core_app` 12 · `realtime_app` 12 · `mujoong_backup` 2, 모두 NOSUPERUSER · NOCREATEDB · NOCREATEROLE · NOREPLICATION · NOBYPASSRLS), `REVOKE ALL ON DATABASE ... FROM PUBLIC` · `public` 스키마 권한 회수, 스키마 `core` · `realtime` · `flyway`(소유 `mujoong_owner`), 기본 권한(DML · 시퀀스만, TRUNCATE · DDL 없음), 역할별 `search_path`, `mujoong_backup`에 `pg_read_all_data`, `ALTER DATABASE mujoong SET mujoong_db.data_class = 'synthetic'`, `pg_hba`(허용 대역은 필수 환경 변수 `PG_HBA_ALLOWED_CIDR`로 받아 그 대역의 scram-sha-256 한 줄과 나머지 reject만 쓰고, 비면 종료 코드 1, J135. demo · ci Compose는 backend 서브넷 172.30.20.0/24를 넘긴다), PG 설정(UTC · `log_error_verbosity terse` · `log_statement none` 등). 비밀번호는 환경 변수를 psql 변수로 넘겨 SQL 파일에 값이 없다. Testcontainers와 호스트 PostgreSQL 백엔드(J132)도 같은 파일을 쓰고, 시험 JVM이 들어오는 출발지를 `PG_HBA_ALLOWED_CIDR`로 넘긴다.
  - 근거: SD-13 · E9 · BR8.1 · NFR9.7 · J84 · infra-spec 2.5 · SD-24 · J132 · J135
  - 완료 확인: 스크립트에 비밀번호 리터럴이 없고(`grep` 0건), `PG_HBA_ALLOWED_CIDR`가 비면 `02-hba.sh`가 종료 코드 1이며, Step 14 시험이 이 파일로 DB를 초기화한다.

- [ ] Step 10: Core V1 마이그레이션 · JPA 엔터티 · Flyway 설정
  - 파일: `backend/core-api/src/main/resources/db/migration/core/V1__core_base.sql`(core 12표: `core_guardian` · `core_spring_session` · `core_spring_session_attributes` · `core_consent` · `core_child` · `core_stop_expression` · `core_approved_content` · `core_approved_content_version` · `core_device`(22열 · fillfactor 90 · CHECK 4 · 부분 유일 색인 2) · `core_activity`(20열 · 부분 유일 · local_failed CHECK) · `core_outbox_event` · `core_inbox_event`(각 17열, `trace_id` 형식 CHECK, 색인)), `.../db/migration/core/afterMigrate.sql`, `backend/core-api/src/main/java/kr/mujoong/core/identity/{Guardian,Child,Consent}.java`, `.../catalog/{ApprovedContent,ApprovedContentVersion}.java`, `infra/migrate/flyway-core.conf`, `infra/migrate/flyway-realtime.conf`
  - 하는 일: data-model 시트 3절 DDL대로 만든다(timestamptz · jsonb · uuid, DB 시각 기본값 없음, FK는 RESTRICT, A3 `trace_id` · A4 `guardian_id` 열 포함). 단순 CRUD 표만 JPA 엔터티(`Instant` 필드)로 매핑하고 core_device · core_activity · Outbox/Inbox는 JDBC로 다룬다(Step 15). Flyway 설정은 이력 표 `flyway.core_schema_history`, `defaultSchema=flyway`, `createSchemas=false`, `cleanDisabled=true`.
  - 근거: BR8.2 · J43 · E1 ~ E8 · A3 · A4 · BR1.2 · BR2.4 · AC2.2.7 · BR6.2 · BR6.3 · BR5.6 · PD-8 · J115 · J116
  - 완료 확인: SQL에 `now()` · `CURRENT_TIMESTAMP` · `clock_timestamp()`가 0건이고 Step 14의 스키마 시험 대상이 된다.

- [ ] Step 11: Realtime V1 마이그레이션 · JPA 엔터티 · 시드 자원
  - 파일: `backend/realtime-activity/src/main/resources/db/migration/realtime/V1__realtime_base.sql`(realtime 7표: `realtime_activity_session`(29열 · 부분 유일 guardian · device · 스냅샷 CHECK) · `realtime_session_event` · `realtime_session_stop_expression` · `realtime_transcript`(UNIQUE(session_id, sequence_no)) · `realtime_device_connection`(10열 · fillfactor 90) · `realtime_outbox_event` · `realtime_inbox_event`), `.../db/migration/realtime/afterMigrate.sql`, `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/{SessionStopExpression,SessionEvent}.java`, `backend/realtime-activity/src/main/resources/gate/seed-dictionary.json`(헤더 version · level SEED, 7항목 `{id, pattern, category, appliesTo:[OUTPUT_GATE]}`: DIAGNOSIS `진단` · TREATMENT `치료` · SCORE `비정상`(사람 결정 12) · CAUSE `핵심원인` · GENERALIZATION `학교에서도그럴` · EMOTION_CLAIM `화가났군요` · PRESSURE `꼭해야해요`), `infra/seed/001-reference.sql`(멱등: 정책 1 · 승인 문구 2, `mujoong_owner`), `tools/seed/gen-approved-wav.mjs`, `infra/seed-audio/closing-v1.wav` · `infra/seed-audio/waiting-filler-v1.wav`(PCM16 · 24kHz · 모노, 합성 무음 · 톤, CLOSING 3초 이내)
  - 하는 일: Realtime 표와 시드 자원을 만든다. WAV는 생성기로 만들고 결과 파일도 커밋한다(J102). 정책 버전 자리는 J114, 시드 값은 J117.
  - 근거: BR8.2 · E4 · E9 · BR6.8 · BR7.5 · BR3.7 · A5 · J101 · J102 · J60 · R4-15 · SD-18 · NFR1.2 · AC3.3.3
  - 완료 확인: SQL 시각 함수 0건, WAV 두 개가 생성기 재실행 시 바이트가 같고, 사전 7범주가 모두 있다(Step 13 · 14 시험).

### C′. 데이터 모델 · DB 동작 — 시험

- [ ] Step 12: test-support 기반(시계 · 타이머 · PostgreSQL 컨테이너 · 비밀 생성)
  - 파일: `backend/test-support/src/main/java/kr/mujoong/testsupport/time/{MutableClock,ManualTimerDriver}.java`, `.../db/MujoongPostgres.java`(백엔드 `docker` · `local` · `auto`, J132: PostgreSQL 16 고정 태그 컨테이너 또는 호스트 `/usr/lib/postgresql/16/bin` 클러스터, `infra/postgres/init/` 실행, `PG_HBA_ALLOWED_CIDR`에 시험 출발지(docker는 브리지 게이트웨이 /32, local은 127.0.0.1/32)를 넘김 J135, 두 위치에 Flyway 적용, 역할별 DataSource 제공, 사용한 백엔드 이름 노출), `.../secret/TestSecrets.java`(실행마다 무작위), `.../log/LogCapture.java`(MUJOONG_EVENT 줄 수집 · JSON 파싱), `backend/test-support/src/test/java/kr/mujoong/testsupport/time/{ManualTimerDriverTest,MutableClockTest}.java`(`@Tag("u1")`: 마감 순서 실행 · 같은 마감 둘 · 취소 · 앞당긴 만큼 단조 값 증가 · 지난 마감 즉시 실행)
  - 하는 일: 시험 전용 지원을 만든다. `ManualTimerDriver.advance(d)`는 Clock을 앞당기고 도래한 타이머를 마감 순서대로 실행한다(RD-2). 운영 코드는 이 모듈에 의존하지 않는다. NFR6.2 경계 시험 전체가 이 구동기에 기대므로 자기 시험을 둔다(J107, 하한 대상은 아님). `local` 백엔드는 initdb · pg_ctl을 `runuser -u postgres`로 돌리고 JVM 종료 훅에서 멈추고 지운다.
  - 근거: RD-2 · RD-23 · J81 · SD-25 · J107 · J127 · J129 · J132 · J135
  - 완료 확인: `cd backend && ./gradlew --no-daemon :test-support:test -PincludeTags=u1`가 통과한다.

- [ ] Step 13: contract-model · contracts · 시드 자원 시험(단위)
  - 파일: `backend/contract-model/src/test/java/kr/mujoong/contract/` 아래 `error/ErrorCodeTest.java`(BR8.6 코드 · HTTP 표, (a)(b)(c)(e)(n)(o) 존재, message 고정 · 개인정보 없음), `audio/AudioFrameCodecTest.java`(J27 965B · 빅엔디언 순번 · 964 · 966B 거부 · 0x02 짝수 길이, BR7.1 · (s)), `event/EventEnvelopeValidationTest.java`(BR5.4 필수 7 · 모르는 필드 무시 · schemaVersion ≥ 1 · traceId 선택 (p) · BR5.9 모르는 enum), `ws/WsEnvelopeCodecTest.java`(계약 4.2 seq · opId 4종 한정 · ackOf), `trace/TraceIdsTest.java`(OD-7 형식 · 생성), `profile/ProfileGuardRulesTest.java`(SD-14 조합 1 ~ 13 · A2 · A6 · NFR9.11 · BR8.3), `log/StructuredLogFormatTest.java`(OD-1 · OD-2 키 순서 · 17종 허용 attrs · 미등록 UNKNOWN · NFR9.15), `log/OwnerHasherTest.java`(OD-4), `secret/SecretCompareTest.java`(SD-8 상수 시간 비교 · SHA-256), `time/DelayQueueTimerDriverTest.java`(운영 타이머 구동기: 마감 순서 실행 · 취소 · 같은 마감 둘 · 지난 마감 즉시 실행 · 종료 뒤 등록 거부, 수십 ms 시한을 상한 있는 래치 대기로만 확인, J138), `time/SystemMujoongClockTest.java`(UTC · 단조 값 증가), `json/JsonCodecTest.java`(모르는 필드 무시 · 모르는 enum '알 수 없음' · ISO 8601 UTC, BR8.10), `ContractFilesTest.java`(`contracts/` YAML · JSON 파싱, 스키마 2020-12 메타 검증, 예시 검증, (a) ~ (y) 표시, V-5), `tools/tests/u1/seed-audio.test.mjs`(J102 · AC3.3.3 RIFF · PCM16 · 24kHz · 모노 · CLOSING ≤ 3초 · 재생성 바이트 동일)
  - 하는 일: 데이터 모델 층(계약 타입 · 스펙 · 시드 자원)을 시험한다. 시험마다 정상 1개 + 오류 · 경계 2개 이상.
  - 근거: 위 각 ID
  - 완료 확인: `cd backend && ./gradlew --no-daemon :contract-model:test -PincludeTags=u1`와 `cd tools && npx --no-install vitest run tests/u1/seed-audio.test.mjs` 통과.

- [ ] Step 14: 스키마 · 권한 · 제약 통합 시험(Testcontainers)
  - 파일: `backend/core-api/src/test/java/kr/mujoong/core/db/` 아래 `CoreSchemaIT.java`(BR8.2 · J43 표 12개 · 열 수 22 · 20 · 17 · 17, JPA validate, A3 열), `SchemaPrivilegeIT.java`(BR8.1 · NFR9.7 · SD-13 · B1 ②: core_app의 realtime 읽기 · 쓰기 42501과 반대, 앱의 CREATE · ALTER · TRUNCATE 거부, public 사용 불가, 상대 이력 표 SELECT 거부 · 자기 이력 표 SELECT 허용, backup 읽기만, 앱의 `ALTER DATABASE ... data_class` 거부 SD-24, (x). SD-13 기본 권한: 시험 자원의 V2 마이그레이션(시험 전용 위치 `src/test/resources/db/testmigration/core/`, `mujoong_owner`로 적용, 운영 위치에는 두지 않음)이 만든 새 core 표에 core_app의 SELECT · INSERT · UPDATE · DELETE가 기본 권한으로 붙고 TRUNCATE는 거부되며 realtime_app은 권한이 없다. pg_hba(J135): `02-hba.sh`가 만든 `pg_hba.conf`의 허용 줄이 주어진 대역의 scram-sha-256 한 줄과 reject뿐이고, 시험 출발지가 아닌 /32를 허용 대역으로 준 별도 인스턴스에 접속하면 SQLSTATE 28000), `CoreConstraintIT.java`(BR1.2 · BR1.4 UNREGISTERED 코드 중복 금지 · EXPIRED 재사용, AC2.2.7 · BR2.4 보호자당 REGISTERED 1, RELEASED · REGISTERED CHECK, AC3.1.8 · BR6.2 · BR6.3 유일, local_failed CHECK, trace_id 형식, BR2.10 · J58 FK RESTRICT 23503), `NoSqlNowTest.java`(R2 · NFR6.4 마이그레이션 · 질의 문자열의 시각 함수 0건), `SeedReferenceSqlIT.java`(R4-15 `infra/seed` 두 번 적용 → 정책 1 · 문구 2, J114), `backend/realtime-activity/src/test/java/kr/mujoong/realtime/db/` 아래 `RealtimeSchemaIT.java`(표 7개 · 열 29 · 10 · 17 · 17 · 12, JPA validate), `RealtimeConstraintIT.java`(BR6.3 진행 중 세션 guardian · device 유일, 스냅샷 CHECK, transcript 유일, Inbox RECEIVED는 next_attempt_at 필수 BR5.6)
  - 하는 일: 실제 init 스크립트 + 두 마이그레이션 위치로 DB 동작을 시험한다. 모두 `@Tag("u1")` `@Tag("db")`. DB 백엔드는 J132(`docker` 또는 `local`)이고 결과에 백엔드 이름을 적는다.
  - 근거: 위 각 ID · E1 ~ E9 · J132 · J135
  - 완료 확인: `cd backend && ./gradlew --no-daemon :core-api:test --tests '*.db.*' :realtime-activity:test --tests '*.db.*' -PincludeTags=u1` 통과(Gradle의 `--tests`는 바로 앞 작업에만 걸리므로 작업마다 붙인다). Docker와 호스트 PostgreSQL이 둘 다 없을 때만 건너뛴 사실을 기록한다.

### D. 저장소 · 데이터 접근 — 구현

- [ ] Step 15: Core 데이터 접근
  - 파일: `backend/core-api/src/main/java/kr/mujoong/core/device/DeviceJdbcRepository.java`(BR2.3 · BR2.5 조건부 UPDATE와 행 수, BR4.1 사본 UPDATE와 COALESCE, BR3.1 claim 회전, BR1.4 만료 · 7일 삭제, 모든 문장에 `updated_at = :now, row_version + 1`), `.../activity/ActivityCopyRepository.java`(BR5.10 · BR6.14 · BR6.2 · BR6.12 ①), `.../messaging/{CoreOutboxRepository,CoreInboxRepository}.java`(Tx-A `FOR UPDATE SKIP LOCKED` 선점 · Tx-B 상태 조건, Inbox `ON CONFLICT DO NOTHING` · 선점 UPDATE · 정리), `.../identity/{GuardianRepository,ChildRepository,ConsentRepository}.java` · `.../catalog/ApprovedContentRepository.java`(JPA), `.../config/SessionJdbcConfig.java`(Spring Session JDBC), `.../common/db/{DbRetry,ClockParams}.java`(RD-22: 40P01 · 40001 · 55P03만 멱등 연산에서 0 ~ 50ms 지터 재시도, `lock_timeout` · `statement_timeout`)
  - 하는 일: 데이터 접근 패턴(조건부 UPDATE 한 문장 + 행 수 확인, `:now` 바인딩, READ COMMITTED, 락 순서 보호자 → 코드 → 옛 기기)을 구현한다.
  - 근거: BR2.3 · BR2.5 · BR4.1 · BR4.2 · BR3.1 · BR1.4 · BR5.2 · BR5.6 · BR5.7 · BR5.10 · BR5.11 · BR6.14 · RD-22 · PD-8 · J83 · J115
  - 완료 확인: `cd backend && ./gradlew --no-daemon :core-api:compileJava` 성공, SQL 시각 함수 0건.

- [ ] Step 16: Realtime 데이터 접근
  - 파일: `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/{ActivitySessionRepository,SessionEventRepository,SessionStopExpressionRepository}.java`(전이 `UPDATE ... WHERE id AND status = :from` · 출발 상태가 여럿이면 FOR UPDATE 뒤 `WHERE status = :읽은 상태`, INSERT `ON CONFLICT (id) DO NOTHING`), `.../gateway/DeviceConnectionRepository.java`(BR3.7 upsert · `connection_id` 조건 기록, BR4.8 DISCONNECTED 40초 조건), `.../log/TranscriptRepository.java`(BR7.5 `insert ... on conflict (session_id, sequence_no) do update` JDBC 배치), `.../messaging/{RealtimeOutboxRepository,RealtimeInboxRepository}.java`
  - 하는 일: Realtime 쪽 같은 패턴을 구현한다(Outbox/Inbox는 앱마다 따로, LC-11).
  - 근거: BR6.6 · BR6.8 · BR3.7 · BR4.8 · BR7.5 · BR5.1 ~ 5.12 · PD-5 · RD-22
  - 완료 확인: `cd backend && ./gradlew --no-daemon :realtime-activity:compileJava` 성공.

### D′. 저장소 · 데이터 접근 — 시험

- [ ] Step 17: Core 데이터 접근 시험(Testcontainers)
  - 파일: `backend/core-api/src/test/java/kr/mujoong/core/device/DeviceJdbcRepositoryIT.java`(AC2.2.6 · BR2.3 두 계정 동시 등록 승자 1, BR2.5 · J46 · AC2.2.7 교체 순서와 (4) 0행 주입 시 전체 롤백, BR4.1 두 경로 경합 · 늦은 보고 0행, BR4.2 REGISTERED 아님 무시, BR3.1 claim 회전, BR1.4 만료 갱신 · 7일 지난 EXPIRED 행 삭제 · 7일 직전 행 유지), `.../activity/ActivityCopyRepositoryIT.java`(BR5.10 역순 도착 · local_failed 제외, BR6.14 started_at만 갱신, BR6.3 유일 위반 → 승자 재읽기), `.../messaging/CoreMessagingRepositoryIT.java`(BR5.1 업무 쓰기가 롤백되면 Outbox 0행 · 커밋되면 1행 · 트랜잭션 밖 기록 거부(MANDATORY), BR5.2 · RD-8 두 폴러 한 행 한 번 선점 · 정렬 created_at · 재시도 대기 행이 뒤 행을 막지 않음, event_id · batch 2, BR5.6 Inbox 중복 삽입, BR5.7 선점 경주, BR5.11 payload `{}` · purged_at, A4 guardian_id NULL 0, A3 trace_id 저장), `.../common/db/DbRetryTest.java`(RD-22 멱등 연산만 재시도 · 비멱등은 즉시 실패 · 2회 상한)
  - 하는 일: 데이터 접근 층을 시험하고 돌린다.
  - 근거: 위 각 ID
  - 완료 확인: `cd backend && ./gradlew --no-daemon :core-api:test -PincludeTags=u1 --tests '*RepositoryIT' --tests '*DbRetryTest'` 통과.

- [ ] Step 18: Realtime 데이터 접근 시험(Testcontainers)
  - 파일: `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ActivitySessionRepositoryIT.java`(BR6.8 전이 승자 1 · 0행 재읽기 · 다중 출발 FOR UPDATE, BR6.6 ④ 중복 INSERT 무동작, status_version · row_version 증가), `.../gateway/DeviceConnectionRepositoryIT.java`(BR3.7 첫 접속 upsert · 옛 cid 기록 0행, BR4.8 40초 조건 경계 직전 · 직후), `.../log/TranscriptRepositoryIT.java`(BR7.5 PARTIAL 같은 번호 덮어쓰기 · FINAL · sequence_no 1부터 · 배치), `.../messaging/RealtimeMessagingRepositoryIT.java`(RD-8 · BR5.6 · BR5.7 · A3 · A4)
  - 하는 일: Realtime 데이터 접근 층을 시험하고 돌린다.
  - 근거: 위 각 ID
  - 완료 확인: `cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u1 --tests '*RepositoryIT'` 통과.

### E. 업무 로직 — 구현

- [ ] Step 19: Core DeviceRegistry
  - 파일: `backend/core-api/src/main/java/kr/mujoong/core/device/{RegistrationCode,CodeGenerator,DeviceSecrets,BootstrapService,DeviceClaimService,RegistrationService,DeviceTokenVerifier,ConnectionCopyWriter,DeviceVerdict,DeviceViewAssembler,DeviceDeletionParticipant,DeviceExpiryCleanupJob}.java`, `backend/core-api/src/main/java/kr/mujoong/core/security/ratelimit/SlidingWindowRateLimiter.java`
  - 하는 일: 코드 8자 · 31자 알파벳 · 정규화 · 4 · 4 표시(BR1.1, (d) 정규화 뒤 영숫자 8자 아니면 400 · 알파벳 밖은 404), 5회 추첨 · 503 (c)(BR1.2), 같은 쿠키 같은 코드 · 409 (a)(BR1.3), TTL · 만료 정리(BR1.4), 비밀 128 · 256비트 base64url · SHA-256만(BR1.5 · BR3.3), IP 한도 20회 · 1시간 · 같은 쿠키 미계수 (k) · IPv6 /64 · 키 상한 10000 LRU · 내부 오류는 통과 + WARN(BR1.6), status(BR1.7), 등록 분기 · 같은 보호자 201 (m) · 교체 허용 판정 7종 · 교체 트랜잭션 순서 · DeviceReleased(A4 guardian_id)(BR2.1 ~ 2.5 · J46), 등록 실패 10분 10회(BR2.6), 201 본문(BR2.7), claim · 410 · 회전(BR3.1 · BR3.2 · (b)), verify-token 캐시 없음(BR3.4 · (j)), me(BR3.5), 사본 쓰기(BR4.1 · BR4.2), 판정 7행 · canStart(BR4.3 · BR4.4), DeviceView(해시 · 판정값 미노출, BR4.6), 삭제 참가자(BR2.10 · J58, 멱등).
  - 근거: BR1.1 ~ 1.7 · BR2.1 ~ 2.7 · BR2.10 · BR3.1 ~ 3.5 · BR4.1 ~ 4.4 · BR4.6 · (a)(b)(c)(d)(j)(k)(m) · A4 · NFR9.4 · SD-10
  - 완료 확인: 이 단계의 파일이 모두 생겼고 숫자 시한이 코드에 박혀 있지 않다(설정 키만). 이 단계는 Step 20(`CoreOutboxWriter`) · Step 21(`EventLogger`)이 만드는 클래스를 쓰므로 컴파일 확인은 Core 업무 로직 마지막 단계인 Step 22에서 한 번에 한다(자리표시자 구현으로 맞추지 않는다).

- [ ] Step 20: Core 메시징 · 삭제 참가자 · 재무장
  - 파일: `backend/core-api/src/main/java/kr/mujoong/core/messaging/{CoreOutboxWriter,CoreOutboxSender,CoreInboxReceiver,CoreInboxDispatcher,CoreInboxRepoller,ControlEventRearm,MessagingCleanupJob,CoreMessagingDeletionParticipant}.java`, `.../messaging/handler/{DeviceStatusChangedHandler,SessionStateChangedHandler}.java`
  - 하는 일: Outbox 기록기(트랜잭션 전파 MANDATORY · guardian_id 인자 필수 A4 · trace_id A3), 발송기(500ms · 20건 · 직렬 · 200 PUBLISHED · 409/400 즉시 FAILED · 그 밖 백오프 1 · 2 · 4 · 8 · 16 · 30초 · 20회 상한 · 선점 30초), Inbox 수신(커밋 뒤 200 · 같은 해시 200 · 다른 해시 409 · 처리기 없음/상위 schemaVersion IGNORED), 디스패처(선점 · 한 트랜잭션 처리 · 1 · 2 · 4 · 8 · 16초 5회 뒤 DEAD · 풀 거절 시 제출 건너뜀, CallerRuns 금지), DeviceStatusChanged 처리기(occurredAt 순서 (g) · payload guardianId 우선 (q)), SessionStateChanged 처리기(statusVersion · local_failed 재취소 BR5.10 · BR6.14), 정리(BR5.11 · 7일), 메시징 삭제 참가자(BR5.14, 제어 이벤트 FAILED는 지우지 않음), A1 재무장 함수(대상 · 제외 조건 RD-10, `RETRY_FOREVER`면 기동 실패 J124).
  - 근거: BR5.1 ~ 5.14 · A1 · A3 · A4 · (g)(p)(q) · RD-7 ~ RD-11 · PD-10 · J49 · J50 · NFR4.5 · NFR4.6
  - 완료 확인: 이 단계의 파일이 모두 생겼다(발송기가 Step 21의 `InternalHttpClient`를 쓰므로 컴파일 확인은 Step 22).

- [ ] Step 21: Core 실행 골격 로직(프로필 가드 · 기동 · 준비 상태 · 시드 · 로그 · 내부 HTTP)
  - 파일: `backend/core-api/src/main/java/kr/mujoong/core/startup/{ProfileGuardEnvironmentPostProcessor,RequiredSecretsCheck,SchemaVersionCheck,CoreStartupSequence,DrainingLifecycle}.java`, `.../health/{ReadinessState,DbHealthChecker}.java`, `.../identity/{DevSeedRunner,SeedIds}.java`, `.../observability/{EventLogger,LogMergeWindow,SecurityEventCap,OwnerHashConfig}.java`, `.../internalhttp/{InternalHttpClient,InternalAuthInterceptor}.java`, `backend/core-api/src/main/resources/META-INF/spring/*`(후처리기 등록, 판 맞춤)
  - 하는 일: 웹 서버보다 먼저 프로필 조합 거부(SD-14 · A2 · A6, 비0 종료 · 키 이름만 로그), S1 · S2(이력 버전 · JPA validate) · S3c · S3d(A1) · S3f · S4 ~ S6, readiness(NOT_READY 5초 · 3회 · DRAINING), 정상 종료 SmartLifecycle(J125), DevSeedRunner(`@Profile("dev")` + enabled, 고정 UUID · `ON CONFLICT DO NOTHING`, 동의 4행 · 정책 1 · 문구 2, J114 · J117), 구조화 로그 연결과 반복 병합 · 보안 사건 상한 · ownerHash 비밀 검사(demo · prod 비면 기동 실패), 내부 HTTP 클라이언트(대상 그룹별 연결 풀, 인터셉터 한 곳이 `X-Internal-Token` · `X-Trace-Id`를 붙이고 들어온 헤더는 옮기지 않음, 연결 0.5초).
  - 근거: BR8.3 · BR8.4 · A2 · A6 · RD-12 · RD-13(구현) · RD-16 · OD-1 ~ OD-4 · OD-11 · OD-13 · SD-8 · SD-12 · SD-14 · J124 · J125
  - 완료 확인: 이 단계의 파일이 모두 생겼다(컴파일 확인은 Step 22).

- [ ] Step 22: Core ActivityCoordinator
  - 파일: `backend/core-api/src/main/java/kr/mujoong/core/activity/{RealtimeSessionClient,SessionStartBreaker,ConsentGate,ApprovedContentCatalog,SessionPackageBuilder,StartActivityService,PreparingTimeoutChecker,EndSessionService,SessionViewService,StaleEvaluator}.java`
  - 하는 일: 시작(BR6.2 재생 → BR6.1 ⓪ ~ ③, 비종료 사본은 view 1초로 한 번 대조, BR6.4 200자 검증 · 저장 안 함, BR6.5 패키지: stopExpressions '무중아 그만할래' 코드 상수 · approvedContent CLOSING · WAITING_FILLER · `/media/approved/` 상대 audioUrl · childContext는 displayName · age · interests 세 필드뿐, `SessionPackageBuilder`는 08 context를 인자로 받지 않는다(BR6.5 · NFR3.1 · SD-20), BR6.6 번역 423 → DEVICE_NOT_STABLE · 409 → SESSION_IN_PROGRESS, BR6.7 3초 · 시간 초과 1회 재시도 · 로컬 FAILED start_result_code 503 · 취소 CORE_ABANDONED (f)), 차단기(session_start에만 · 3회 · 5초 · 반열림 1건 · 409/423은 성공, R3-2 · J74), ① 10초 점검(BR6.12 ①), 끝내기(BR6.9 사본 종료면 RT 미호출 409 · 2초 · 차단기 없음), 조회(BR6.15 1초 · 실패면 사본 + 빈 items · nextSeq · stale 30초 · 폴백 카운터), 현재 정책 버전(J114). 보호자 세션 만료 · 삭제 · 로그아웃은 진행 중 세션에 end · 취소 호출을 만들지 않는다(BR6.16, 세션은 기기와 Realtime 사이에서 계속된다).
  - 근거: BR6.1 ~ 6.7 · BR6.9 · BR6.12 ① · BR6.14 · BR6.15 · BR6.16 · BR6.17 · RD-6 · RD-14 · J54 · J74 · (f) · NFR3.1 · SD-20 · NFR4.3 · NFR4.12
  - 완료 확인: `cd backend && ./gradlew --no-daemon :core-api:compileJava` 성공(Step 19 ~ 22 전체의 컴파일 확인).

- [ ] Step 23: Realtime 세션 실행 장치(SessionLifecycle)
  - 파일: `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/{SessionRegistry,SessionExecutor,SessionMailbox,TransitionService,SessionTimers,TombstoneMap,StartSessionHandler,EndRequestHandler,OrphanSessionChecker,SessionStateEmitter}.java`, `.../runtime/{TimerClocks,PeriodicJobs}.java`
  - 하는 일: 세션당 가상 스레드 실행기(L0H 먼저 · L0N · 세대 · 최상위 예외 → FAILED(SERVER_RESTART) J126), INIT 선등록(SCD-1), 시작 0 ~ 2단계(묘비 409 (v) · 같은 id 202 · 423 DEVICE_NOT_CONNECTED · 한 트랜잭션 PREPARING + 기본 종료 표현 + SessionStateChanged · 커밋 뒤 START_SESSION 먼저 송신 R3-1 · ② 무장 · 공급자 연결 스레드), 전이 표(BR6.8), 네 시한(BR6.12 ② · ③ · ④, 발동 규약 1 ~ 8, (h)), end 응답표(J54 + J111 (w) · CORE_ABANDONED 202 `{}`), 끝내기 순서 진입(BR6.10), 늦은 메시지 폐기(BR6.11), 고아 세션 점검 30초.
  - 근거: BR6.6 ~ 6.13 · PD-1 · SCD-1 · RD-1 · RD-3 · R3-1 · R3-6 · J51 · J54 · J111 · J126 · (h)(v)(w) · NFR6.2 · NFR6.3
  - 완료 확인: 이 단계의 파일이 모두 생겼다. Step 24(`ConnectionSender`) · Step 26(`RealtimeOutboxWriter`)이 만드는 클래스를 쓰므로 컴파일 확인은 Realtime 업무 로직 마지막 단계인 Step 26에서 한 번에 한다(자리표시자 구현으로 맞추지 않는다).

- [ ] Step 24: Realtime DeviceGateway
  - 파일: `backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/{DeviceHandshakeInterceptor,VerifyTokenClient,DeviceWebSocketHandler,WsFrameGuard,ConnectionHandover,SocketRegistry,DeviceConnection,ConnectionSender,HeartbeatHandler,ConnectionStateWriter,DisconnectChecker,StatusReporter,DeviceReleasedHandler}.java`
  - 하는 일: Origin 정확 일치 · 없거나 `null`이면 403(BR3.6 ①), 업그레이드 뒤 쿠키 없음 즉시 4401 · verify-token 1초 · 401 → 4401 · 시간 초과 → 1013 · 캐시 없음 (j), HELLO 5초 → 4408(J123), 형식 · 크기 위반 4400 (s), DEVICE_STATE는 알려진 type으로 받아 무시(ERROR 없음 · 연결 유지, J123), HEARTBEAT 제어 봉투 `sentAt`과 수신 시각 · 직전 rttMs로 연결별 clockOffsetMs 계산(`sentAt`이 없거나 형식이 틀리면 생략하고 CLOCK_OFFSET_MISSING, OD-8 · R3-4), HELLO_OK 인계(행 FOR UPDATE · 커밋 → 레지스트리 교체 · 옛 소켓 4001 → HELLO_OK, 실패면 새 연결만 1011, (l) · R3-7), 송신 레인(제어 100 · 음성 960,000B · STOP_OUTPUT이 음성 레인 비움), HEARTBEAT_ACK(hbSeq), 상태 기록(CONNECTED · 기기 1000 · 1001 즉시 OFFLINE · 40초 DISCONNECTED 5초 점검 · UNRESPONSIVE는 BR6.12 ② ③만 J55 · DeviceStatusChanged에 payload guardianId (q) · occurred_at은 UPDATE 직전 Clock), 10초 상태 보고(OFFLINE 포함 · 1초 · 라운드 3건 연속 실패면 건너뜀), DeviceReleased 처리(4401 + OFFLINE, BR3.8), last_seen_at은 HEARTBEAT 때만(J125).
  - 근거: BR3.6 ~ 3.8 · BR4.7 · BR4.8 · SD-7 · SCD-2 · RD-4 · R3-7 · J55 · J123 · (j)(l)(q)(s) · NFR4.1 · NFR9.4 · OD-8 · R3-4
  - 완료 확인: 이 단계의 파일이 모두 생겼다(`VerifyTokenClient` · `StatusReporter` · `ConnectionStateWriter`가 Step 26의 내부 HTTP · Outbox를 쓰므로 컴파일 확인은 Step 26).

- [ ] Step 25: Realtime 음성 · 게이트 · 기록
  - 파일: `backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/{ProviderPort,ProviderClient,ProviderConnector,InputRelay,ProviderHosts,ProviderRequestFactory}.java`, `.../gate/{OutputGate,SegmentBuffer,SeedDictionary,GateDecision,ApprovedPlayback,ClosingSequence}.java`, `.../log/{AsyncRecorder,TranscriptWriter,LatencyRecorder}.java`
  - 하는 일: 공급자 전송 최소화(SD-20 · NFR3.1 · NFR3.2): 공급자로 가는 모든 요청(`session.start` 지시문 · `input_audio.append`)은 `ProviderRequestFactory` 하나에서만 만들고, 요청 레코드는 지시문 · childContext(displayName · age · interests) · 음성 프레임 필드만 갖는다(자유 `Map` · 문자열 이어 붙이기 입구 없음, 판정 기준 · 보호자 입력 · 이전 판정 필드 없음). 연결을 여는 가상 스레드도 같은 팩토리만 쓰고, 연결 주소가 허용 목록(J109 · J141) 밖이면 연결하지 않는다. 공급자 연결(별도 가상 스레드 · 10초 · `session.start` → `session.started`, 실패 PROVIDER_UNAVAILABLE, J110), 입력 중계(0x01 순서대로 `input_audio.append` · ACTIVE 전 · 종료 · FAILED 뒤 폐기 · CLOSING 중 CLOSE_TRANSPORT까지 중계 R3-8 · L1 50프레임 오래된 것 버림 · 원음 저장 금지), 출력 구간(800ms 조용 또는 아이 입력 재개, 960,000B 상한), 게이트((가) 빈 전사 · 불일치 GATE_EMPTY (나) 시드 사전 GATE_PATTERN → 버퍼 비움 · WAITING_FILLER, 송신 직전 재확인 NOT_ACTIVE → RECORDER_DEGRADED → OUTBOUND_FULL, 통과 시 OUTPUT_BEGIN → 0x02 → OUTPUT_END), 출력 세대 번호(SD-17: STOP_OUTPUT 송신 · CLOSING 진입 · 종료 상태 전이 · CORE_ABANDONED 취소 · 기기 연결 교체(4001) · 끊김 때마다 세대를 올리고, 봉인한 구간 · 승인 표식과 송신 프레임마다 세대를 비교해 다르면 폐기한다. 게이트 처리 중 예외가 나면 폐기(fail-closed). tGatePass는 송신 직전 재확인을 모두 통과할 때만 찍고 RECORDER_DEGRADED · OUTBOUND_FULL 폐기에는 없다), 시드 사전 패턴 문법과 정규화(SD-18 2.14: 패턴은 리터럴과 숫자 자리표시 `#`만 허용하고 정규식은 쓰지 않음(ReDoS 없음). 게이트는 전사를 NFKC → 공백 · 제로폭 문자 · 구두점(`%` 제외) 제거 → 라틴 소문자로 정규화한 뒤 부분 문자열로 찾는다. 정규화 뒤 형태가 아닌 패턴(공백 · 구두점 · 대문자 라틴 · 정규식 메타 문자 포함)은 로딩 거부), 대체 문구 겹침 규칙, 끝내기 순서(STOP_OUTPUT + SESSION_STATE CLOSING → CLOSING 문구 새 playbackId 1회 → 같은 playbackId 마크 → CLOSE_TRANSPORT → VOICE_SESSION_CLOSED → ENDED → STANDBY, BR6.10), 기록기(칸 200행 지연 · 절반 아래 해제 · FINAL 즉시 플러시 · 아이 전사 확정 즉시 true · AI 통과 시 true와 FINAL 한 트랜잭션, BR7.5), 시각 키 8 + 2종(PD-6 · OD-8, 프레임 단위 로그 0줄), 사전 로딩(7범주 중 빈 것이 있으면 기동 거부, A5).
  - 근거: BR7.1 ~ 7.5 · BR6.5 · BR6.10 · BR6.15 · A5 · PD-2 ~ PD-6 · RD-5 · OD-8 · SD-17 · SD-18 · SD-20 · SD-22 · J27 · J110 · NFR1.1 · NFR1.2 · NFR3.1 · NFR3.2 · NFR4.2 · NFR5.1 · NFR5.2
  - 완료 확인: 이 단계의 파일이 모두 생겼다(기록기 · 전사 기록이 Step 26의 Outbox · 관측 클래스를 쓰므로 컴파일 확인은 Step 26).

- [ ] Step 26: Realtime 메시징 · 기동 · 프로필 가드
  - 파일: `backend/realtime-activity/src/main/java/kr/mujoong/realtime/messaging/{RealtimeOutboxWriter,RealtimeOutboxSender,RealtimeInboxReceiver,RealtimeInboxDispatcher,RealtimeInboxRepoller,ControlEventRearm,MessagingCleanupJob,RealtimeMessagingDeletionParticipant}.java`, `.../startup/{ProfileGuardEnvironmentPostProcessor,RealtimeStartupChecks,DataClassCheck,SchemaVersionCheck,RealtimeStartupSequence,DrainingLifecycle}.java`, `.../health/{ReadinessState,DbHealthChecker}.java`, `.../observability/*.java`, `.../internalhttp/{InternalHttpClient,InternalAuthInterceptor}.java`
  - 하는 일: Core와 같은 메시징 규칙을 따로 구현한다(LC-11). 기동 S1(프로필 가드, 두 앱 같은 규칙) · S1b(필수 비밀 · 공급자 주소가 비면 거부 · 공급자 허용 호스트 demo · prod · `test` 프로필은 루프백만 J141 · 사전 파싱 · 중복 id · 빈 범주 · 패턴 문법) · S2 · S2b(`data_class`가 real 또는 없음 + SEED면 거부, J124) · S3a(비종료 세션 → FAILED SERVER_RESTART) · S3b(CONNECTED · UNSTABLE → DISCONNECTED, cid 비움) · S3c · S3d(A1: DeletionCompleted · SessionRecordCaptured) · S3f · S3e STARTUP_RECOVERY 한 줄 · S4 ~ S6. 사전 로딩 전 readiness는 UP이 아니다.
  - 근거: BR5.1 ~ 5.14 · BR6.13 · BR8.3 · A1 · A2 · A5 · A6 · RD-12 · RD-16 · SD-14 · SD-24 · SD-25 · OD-11 · J124 · J141
  - 완료 확인: `cd backend && ./gradlew --no-daemon :realtime-activity:compileJava` 성공(Step 23 ~ 26 전체의 컴파일 확인).

### E′. 업무 로직 — 시험

- [ ] Step 27: Core 업무 로직 단위 시험
  - 파일: `backend/core-api/src/test/java/kr/mujoong/core/` 아래 `device/RegistrationCodeTest.java`(BR1.1 알파벳 · 정규화 표 · 4 · 4 표시, (d)), `device/CodeGeneratorTest.java`(BR1.2 난수 고정 · 5회 충돌 → (c)), `device/RegistrationDecisionTest.java`(BR2.1 ① ② ③ · (m) · BR2.4 판정 7종), `device/DeviceVerdictTest.java`(BR4.3 7행 · 30초 경계 직전 · 직후 · BR4.4 · BR4.6 해시 미노출 · AC2.1.1 ~ 2.1.6), `device/DeviceSecretsTest.java`(BR1.5 · BR3.3 · NFR9.4 비밀 길이 · base64url · 해시만), `security/ratelimit/SlidingWindowRateLimiterTest.java`(BR1.6 21번째 거절 · (k) · IPv6 /64 · 키 상한 LRU · 내부 오류 통과, BR2.6 10회 · 성공 시 삭제 · 201 재생 미가산, A6 600 · 1 바인딩은 dev+load-test만), `activity/StartActivityPolicyTest.java`(BR6.1 사유 조합 · BR6.2 재생 · local_failed 코드 재생 · BR6.4 201자 → 400. BR6.1 ② 계정 잠김 방지: 막는 사본이 REQUESTED가 아니면 view를 1회 부르고, 종료 상태를 받으면 BR5.10 조건부 UPDATE로 사본에 반영한 뒤 통과, 404(Realtime이 모르는 세션)이면 사본을 로컬 FAILED(failure_code 비움)로 닫고 통과, 조회가 실패하면 409 SESSION_IN_PROGRESS, REQUESTED 사본은 대조하지 않음), `activity/RealtimeSessionClientTest.java`(RD-14 · NFR4.12 호출별 제한 · 시간 초과만 1회 재시도 · 차단기 열림 · 반열림(가변 Clock) · 409/423 성공 계수 · 열림 중에도 end · view 시도), `activity/PreparingTimeoutCheckerTest.java`(BR6.12 ① 9.999 · 10.001초 · 0행 무동작 · 취소 송신 · AC3.1.9), `activity/StaleEvaluatorTest.java`(RD-6 · NFR4.3 30초 경계 · 조회 1회 실패는 stale 아님 · 래치 1회), `messaging/CoreOutboxSenderTest.java`(BR5.3 대기표 · 409/400 즉시 FAILED · 401 재시도 · 21번째 실패 FAILED · A1 재무장 대상 · 제외 조건 · `RETRY_FOREVER` 기동 실패), `startup/CorePropertiesValidationTest.java`(RD-24 불변식 I-3 · I-6 · I-10 · cookie ≤ idle · 풀 합계 · 필수 비밀 누락 종료 · 값 미출력), `startup/ProfileGuardEnvironmentPostProcessorTest.java`(SD-14 13조합 실제 Environment · 요청 때 이중 확인 · NFR9.11)
  - 하는 일: Core 업무 로직을 단위로 시험한다. 시간은 가변 Clock, 협력 객체는 Mockito.
  - 근거: 위 각 ID
  - 완료 확인: `cd backend && ./gradlew --no-daemon :core-api:test -PincludeTags=u1 -PexcludeTags=db` 통과.

- [ ] Step 28: Core 업무 로직 통합 시험(Testcontainers · 계약 대역)
  - 파일: `backend/test-support/src/main/java/kr/mujoong/testsupport/http/{ContractStubServer,ReceiverDouble}.java`(JDK HttpServer, `contracts/schemas`로 요청 검증, 200 · 401 · 409 · 400 · 503 · 지연 응답 스크립트, 받은 요청 본문 기록), `backend/test-support/src/test/java/kr/mujoong/testsupport/http/ContractStubServerTest.java`(`@Tag("u1")`: 스키마 위반 요청 거부 · 스크립트 순서대로 응답 · 받은 본문 기록), `backend/core-api/src/test/java/kr/mujoong/core/` 아래 `device/DeviceRegistrationFlowIT.java`(AC2.2.1 ~ 2.2.8 · BR2.2 삭제 접수와 동시 · J46 교체 + DeviceReleased REREGISTERED · DEVICE_ALREADY_ACTIVE), `activity/ActivityStartEndIT.java`(AC3.1.6 거절 순서 · AC3.1.8 같은 키 동시 · AC3.1.9 10초 로컬 FAILED + 취소 · RT 423/409/시간 초과 번역 · 차단기 열림 503 · AC3.3.8 end 응답표 · 같은 requestId 2회 → RT 1회 · AC3.3.1 · AC3.1.1 · AC3.1.3 · AC3.1.4 · BR6.1 ② 사본이 ACTIVE로 낡은 채 RT view가 404 → 새 키 시작 202 · BR6.16 · AC1.1.9 ACTIVE 중 `core_spring_session` 행을 지우면 `GET /api/sessions/{id}`는 401이고 계약 대역 RT에 end · 취소 호출 0건, 다시 시험 로그인하면 같은 세션이 ACTIVE로 보임), `activity/SessionPackageIT.java`(AC3.1.7 · BR6.5 · NFR3.1: ContractStubServer가 받은 `POST /internal/sessions` 본문에서 stopExpressions는 '무중아 그만할래' isDefault true 1건, approvedContent는 CLOSING · WAITING_FILLER의 contentVersionId · text · `/media/approved/` 상대 audioUrl, childContext는 displayName · age · interests 세 필드뿐, 08 knownContent · question에 넣은 표식 문자열과 보호자 이메일 표식이 본문 전체에 0건), `startup/ProfileGuardBootIT.java`(SD-14 · CD-7: `SpringApplicationBuilder`로 실제 기동해 표 5 ~ 12 거부 조합은 웹 서버가 뜨기 전에 실패하고 오류 줄에 충돌한 프로필 · 키 이름만 있고 값은 없음), `messaging/CoreMessagingFlowIT.java`(RD-8 수신기 대역 응답별 상태 · retry_count · available_at, RD-9 커밋 뒤 200 · 처리기 1회 · BR5.8 처리기 1 · 2 · 4 · 8 · 16초 재시도 뒤 6번째 실패에서 DEAD · retry_count 5 · 경고 한 줄 · 모르는 type IGNORED, RD-7 · NFR4.5 · NFR4.6 같은 eventId · 역순 · 로컬 FAILED 재취소, (g) occurredAt 순서, OD-7 trace_id 재시도 뒤 복원, S3c · S3d · S3f), `messaging/DeletionParticipantsCoreIT.java`(BR2.10 · BR5.14 2회 멱등 · FK 순서 · PENDING · RECEIVED 유지 · 다른 보호자 행 보존 · SessionRecordCaptured payload `{}` · FAILED 제어 이벤트 재무장 대상 유지(A1 · SD-21) · A4 NULL 0 · RD-11), `identity/DevSeedRunnerIT.java`(BR8.4 두 번 실행 → 보호자 1 · 아이 1 · 동의 4 · 정책 1 · 문구 2 · 종료 표현 0, dev+enabled 아니면 빈 없음 · NFR9.11 · AC1.1.7)
  - 하는 일: Core 업무 흐름을 실제 DB와 상대 앱 계약 대역으로 시험한다.
  - 근거: 위 각 ID · NFR4.4 · NFR3.1 · SD-20 · SD-14 · BR6.16
  - 완료 확인: `cd backend && ./gradlew --no-daemon :test-support:test --tests '*ContractStubServerTest' :core-api:test --tests '*FlowIT' --tests '*ActivityStartEndIT' --tests '*SessionPackageIT' --tests '*ProfileGuardBootIT' --tests '*ParticipantsCoreIT' --tests '*DevSeedRunnerIT' -PincludeTags=u1` 통과.

- [ ] Step 29: Realtime 업무 로직 단위 시험
  - 파일: `backend/realtime-activity/src/test/java/kr/mujoong/realtime/` 아래 `session/TransitionTableTest.java`(BR6.8 허용 · 불허 전이 · end_reason · J126), `session/SessionExecutorTest.java`(PD-1 생산자 여럿 처리 순서 · L0H 우선 · 세대 다른 타이머 무동작 · 실행기 예외 · SCD-1 INIT 중 명령 순서 · 늦은 메시지 폐기), `session/SessionTimersTest.java`(BR6.12 ② ③ ④ 경계 −1틱 · +1틱(수동 구동기 · 키 축소 두 방식) · (h) DEVICE_NO_ACK 대 PROVIDER_UNAVAILABLE · ④ 프레임마다 재무장 안 함 · CLOSING 중 ④ 무동작 · ③ 우선 · RD-1 · RD-3 · NFR6.2 · NFR6.3), `gate/OutputGateTest.java`(BR7.3 799 · 801ms · 입력 재개 확정 · BR7.4 GATE_EMPTY · GATE_PATTERN · 재확인 순서 · 대체 문구 겹침 · approved false로 nextSeq 풀림 · PD-3. SD-17: 취소 뒤 늦은 검사 결과 송신 0 · 프레임 중간 세대 변경 시 남은 프레임 0 · 게이트 예외 시 폐기 · NOT_ACTIVE는 송신 직전 재확인 한 곳에서만 판정 · RECORDER_DEGRADED · OUTBOUND_FULL 폐기에 tGatePass 없음 · 승인 문구 재생 중이면 WAITING_FILLER 건너뜀), `gate/SeedDictionaryTest.java`(A5 · NFR1.2 7범주 매개변수화 · 빈 범주 · 중복 id 거부 · SD-18 ② 항목 × (원형 · 조사 결합 · 띄어쓰기 변형) 매개변수화 폐기 ③ 금지어 없는 일상 문장 통과 · 정규화(NFKC · 제로폭 · 구두점 · 대소문자) · `#` 숫자 자리표시 · 정규식 메타 문자 · 공백을 담은 패턴 로딩 거부), `voice/InputRelayTest.java`(BR7.1 · PD-2 · R3-8 · 원음 미저장 NFR12.3), `log/AsyncRecorderTest.java`(PD-5 200행 지연 · 100행 해제 · FINAL 즉시 · BR7.5), `gateway/WsFrameGuardTest.java`((s) · J123 8KB · 965B · 0x02 수신 · HELLO 형식 4400 · READY 뒤 모르는 type ERROR 유지 · READY 뒤 DEVICE_STATE · PLAYBACK_MARK(PROGRESS)는 ERROR 0건 · 연결 유지 · NFR9.9), `gateway/DeviceStatusPolicyTest.java`(BR4.8 닫기 코드별 상태 · 서버가 보낸 1001 제외 · J55 UNRESPONSIVE 두 경우 · AC2.1.10 · J55 해제: 같은 연결의 다음 HEARTBEAT 또는 새 연결 HELLO_OK → CONNECTED · BR4.7 · AC2.1.11 ACTIVE 중 UNRESPONSIVE · 보고 중단만으로는 세션 전이 0), `startup/RealtimeStartupChecksTest.java`(RD-12 S1b · S2b 거부 4종 종료 코드 1 · I-10 · RD-24 · SD-24 · SD-25 공급자 호스트 · J141 공급자 주소 비면 거부 · `test` 프로필에서 루프백 아닌 공급자 호스트 거부), `startup/ProfileGuardEnvironmentPostProcessorTest.java`(SD-14 표 1 ~ 13을 `ProfileGuardRules` 상수에서 매개변수화, Core 시험과 같은 데이터 소스 · 두 앱이 같은 조합을 거부 · NFR9.11), `log/LatencyRecorderTest.java`(PD-6 · OD-8 키 8 + 2 · tSpeechEndSource 순서 · 프레임 단위 로그 0줄 · NFR5.1 · OD-8 · R3-4 HEARTBEAT 봉투 sentAt으로 clockOffsetMs 계산(주입 오프셋 ± rttMs/2 안) · sentAt 없으면 CLOCK_OFFSET_MISSING)
  - 하는 일: Realtime 업무 로직을 단위로 시험한다.
  - 근거: 위 각 ID
  - 완료 확인: `cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u1 -PexcludeTags=db` 통과.

- [ ] Step 30: Realtime 업무 로직 통합 시험(Testcontainers · 가짜 공급자 · 시뮬레이터)
  - 파일: `backend/test-support/src/main/java/kr/mujoong/testsupport/provider/{FakeProviderPort,FakeProviderWsServer}.java`(시나리오: 연결 거부 · started 없음 · 지연 · 정상 · 금지 패턴 응답 · 연결 단계 무응답), `.../device/DeviceSimulatorClient.java`(JDK WebSocket, Cookie · Origin 헤더, HELLO · HEARTBEAT(송신 직전 제어 봉투 `sentAt`을 채우고 시계 오프셋을 주입할 수 있음, J143) · HEARTBEAT 중단 · 열린 채 무음 · ACK 안 보냄 · 늦은 ACK · 늦은 VOICE_SESSION_CLOSED · 두 연결 경합 · PLAYBACK_MARK · DEVICE_STATE · 받은 0x02 바이트 기록), `backend/realtime-activity/src/test/java/kr/mujoong/realtime/` 아래 `session/SessionLifecycleIT.java`(BR6.6 · R3-1 START_SESSION 먼저 · ACK → DEVICE_ACK · READY + started → ACTIVE LISTENING · 한 턴 OUTPUT_BEGIN · 0x02 · OUTPUT_END · 전사 CHILD + 승인 AI · 금지 패턴 → WAITING_FILLER + approved false · GUARDIAN_STOP 끝내기 순서와 마무리 재생 1회 → ENDED confirmed → STANDBY · AC3.3.2 ~ 3.3.7 · 30초 ENDED_UNCONFIRMED · 40초 ABORTED · CORE_ABANDONED → FAILED + CLOSE_TRANSPORT(FAILED) · BR6.11 · AC6.2.1 ~ 6.2.3 · SD-17 · NFR1.1 CLOSING 뒤 도착한 공급자 출력은 기기 0x02 0바이트 · approved_for_display false · 금지 패턴 턴의 기기 OUTPUT_BEGIN 0건 · OD-8 · J143 한 턴 뒤 LATENCY_TURN 한 줄에 시각 키 8종과 tSpeechEndSource · clockOffsetMs · rttMs · providerResponseMs · quietWaitMs, SESSION_ITEMS_SERVED에 tViewItemReady · tViewItemFirstServed 존재, 출력 없는 턴은 unobservedReason NO_OUTPUT), `voice/ProviderClientIT.java`(RD-5 · NFR4.2 거부 · 무응답 → PROVIDER_UNAVAILABLE 1회 · 연결 걸린 동안 취소 2초 안 처리 · J110 선 형식), `voice/ProviderRequestWhitelistIT.java`(SD-20 · NFR3.1 · NFR3.2: FakeProviderWsServer가 받은 모든 메시지의 키가 허용 목록(지시문 · childContext 3필드 · 음성 프레임) 안, 세션 패키지에 08 원문 · 이메일 · 보호자 · 아이 · 기기 · 세션 id 표식을 넣어도 요청 전체에 0건, 요청 레코드에 judgmentCriteria · knownContent · question 등 허용 밖 필드가 없음(리플렉션), 허용 목록 밖 주소로는 연결을 열지 않음), `startup/ProfileGuardBootIT.java`(Core와 같은 표 5 ~ 12를 실제 기동으로 거부, SD-14 두 앱 계약 시험), `messaging/RealtimeMessagingIT.java`(RD-8 · RD-9 · RD-11 DeviceStatusChanged guardian_id · payload guardianId (q) · BR3.8 DeviceReleased → 4401 + OFFLINE · A1 · BR5.14 Realtime 참가자 멱등 · A4 NULL 0), `startup/RealtimeStartupRecoveryIT.java`(BR6.13 S3a · S3b · S3c · S3f · STARTUP_RECOVERY 한 줄 · 사전 로딩 전 readiness · RD-12 · RD-3 실행기 예외 → 고아 점검 FAILED 1회 → 새 키 시작 성공)
  - 하는 일: Realtime 업무 흐름을 실제 DB · 시험용 WS 서버 · 시뮬레이터로 시험한다.
  - 근거: 위 각 ID · NFR4.4 · NFR5.8 · NFR1.1 · NFR3.1 · NFR3.2 · SD-17 · SD-20 · OD-8
  - 완료 확인: `cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u1 --tests '*LifecycleIT' --tests '*ProviderClientIT' --tests '*ProviderRequestWhitelistIT' --tests '*ProfileGuardBootIT' --tests '*MessagingIT' --tests '*RecoveryIT'` 통과.

### F. API — 구현

- [ ] Step 31: Core 공통 웹 계층(필터 · 보안 체인 · CSRF · 쿠키 · 오류 · 헬스 · 시험 로그인)
  - 파일: `backend/core-api/src/main/java/kr/mujoong/core/web/{TraceIdFilter,ReadinessGateFilter,BootstrapIpLimitFilter,RestBodyLimitFilter,ApiExceptionHandler,JsonOnlyFilter}.java`, `.../security/{SecurityConfig,InternalTokenFilter,DeviceCookieAuthFilter,OriginCheckFilter,DeviceCookies,SessionCookieConfig,DevLoginBlockedRecorder}.java`, `.../web/{HealthController,CsrfController}.java`, `.../identity/DevLoginController.java`
  - 하는 일: 필터 순서 F1 traceId → F1b 준비 상태(503 SERVICE_NOT_READY + Retry-After, (n)) → F2 IP 한도(`BootstrapIpLimitFilter`는 `request.getRemoteAddr()`만 쓰고 헤더를 직접 읽지 않는다. 신뢰 프록시에서 온 요청만 Tomcat RemoteIp가 X-Forwarded-For로 바꿔 준다, Step 3 · 인프라 명세 3.3) → F3 인증 → F3b 익명 401 → F4 Origin · CSRF → F5 소유권. 체인 C-INT(`/internal/` · 토큰 정확히 1개 · SHA-256 `isEqual`) → C-DEV(기기 4경로) → C-PUB(`GET /api/health` (t) · `POST /api/dev/login`) → C-GRD(그 밖 `/api/**`), 맞는 체인 없으면 404. CSRF 면제는 정확한 5경로(U2 구글 교환 자리 포함), register는 필수, 실패 403 CSRF_TOKEN_INVALID (e). `GET /api/session/csrf` (u) no-store · 세션 쿠키 갱신. 쿠키 3종 속성 고정(Secure는 설정 고정, Domain 없음), 로그인 때 세션 ID 교체. 오류 봉투 `{code, message, traceId}` · 응답 헤더 `X-Trace-Id` 같은 값 · 입력값 미반사 · 500 INTERNAL_ERROR (o) · 64KB 바이트 계수 400 (r) · JSON 아닌 본문 400. 시험 로그인은 dev + enabled에서만 200 `{guardianId}`, 그 밖 404 + SECURITY_DEV_LOGIN_BLOCKED. CORS 헤더 없음.
  - 근거: BR8.5 ~ 8.10 · BR1.5 · BR1.6 · BR3.1 · BR8.3 · BR8.4 · SD-4 ~ SD-6 · SD-8 · SD-9 · SD-11 · OD-7 · OD-11 · RD-16 · J75 · J119 · (e)(n)(o)(r)(t)(u) · NFR9.2 · NFR9.3 · NFR9.5 · NFR9.6 · NFR9.9
  - 완료 확인: `cd backend && ./gradlew --no-daemon :core-api:compileJava` 성공.

- [ ] Step 32: Core 컨트롤러(기기 · 보호자 · 내부)
  - 파일: `backend/core-api/src/main/java/kr/mujoong/core/device/web/{DeviceBootstrapController,DeviceRegistrationController,DeviceViewController,DeviceInternalController}.java`, `.../web/HomeController.java`, `.../activity/web/{ActivityController,SessionController}.java`, `.../messaging/web/CoreInboxController.java`
  - 하는 일: 기기 REST(`POST /api/device/bootstrap` · `GET .../status` · `POST .../claim` · `GET /api/device/me`), 보호자 REST(`POST /api/device/register` · `GET /api/home` · `GET /api/device` · `POST /api/activities/free` · `GET /api/sessions/{id}?sinceSeq` · `POST /api/sessions/{id}/end`), 내부(`POST /internal/devices/verify-token` · `PUT /internal/devices/{id}/connection` (i) · `POST /internal/events` 2MB). 모든 보호자 자원은 (id ∧ guardian_id)로 읽고 아니면 404. 인증 필요 응답 no-store. 응답 모양은 `contracts/`와 같다.
  - 근거: 계약 3 · 4.1 · 5 · 6절 · BR1.3 · BR1.7 · BR2.7 · BR3.1 · BR3.4 · BR3.5 · BR4.1 · BR4.6 · BR5.4 · BR6.1 · BR6.9 · BR6.15 · BR8.5 · J119 · (a)(b)(c)(d)(i)(k)(m)
  - 완료 확인: `cd backend && ./gradlew --no-daemon :core-api:bootJar` 성공.

- [ ] Step 33: Realtime API(내부 · 이벤트 · WSS 엔드포인트)
  - 파일: `backend/realtime-activity/src/main/java/kr/mujoong/realtime/web/{TraceIdFilter,ReadinessGateFilter,InternalTokenFilter,ApiExceptionHandler}.java`, `.../session/web/SessionInternalController.java`, `.../messaging/web/RealtimeInboxController.java`, `.../gateway/WebSocketConfig.java`
  - 하는 일: `POST /internal/sessions`(3초 · 같은 id 202 · 423 · 409 · 묘비 409 (v)) · `POST /internal/sessions/{id}/end`(J54 표 + (w)) · `GET /internal/sessions/{id}/view?sinceSeq`(LiveView − observedAt · stale, 404) · `POST /internal/events`, `/ws/device` 등록(핸드셰이크 인터셉터 · 텍스트 8KB · 컨테이너 버퍼 16KB). 체인은 C-INT와 WS 둘뿐이고 외부 health는 열지 않는다. NOT_READY · DRAINING에서 새 WSS는 업그레이드 뒤 1013.
  - 근거: 계약 4.2 · 5 · 6절 · BR6.6 · BR6.7 · BR6.9 · BR6.15 · BR3.6 · BR3.9 · RD-16 · J54 · J111 · J123 · (f)(n)(v)(w)
  - 완료 확인: `cd backend && ./gradlew --no-daemon :realtime-activity:bootJar` 성공.

### F′. API — 시험

- [ ] Step 34: Core API 시험
  - 파일: `backend/core-api/src/test/java/kr/mujoong/core/web/` 아래 `SecurityChainIT.java`(SD-4 · BR8.8 면제 5경로 고정 · `/api/device/**` 통째 면제 없음 · register 포함 면제 밖 변경 요청은 CSRF 필수 · 익명 변경 요청 401 · 틀린 토큰 403 (e) · Origin 불일치 403 · 내부 토큰으로 보호자 API 불가 SD-8 · 체인 없음 404 · NOT_READY 503이 인증보다 먼저 · NFR9.3 · NFR9.6), `CookieAttributesTest.java`(SD-5 · BR1.5 · BR3.1 Set-Cookie 3종 정확 비교 · 세션 ID 교체 · Max-Age ≤ idle · NFR9.2), `DeviceBootstrapApiIT.java`(AC6.1.1 ~ 6.1.5 · BR1.3 · (a) · 21번째 429 (k) · (c) · BR1.7 코드 재송신 없음 · claim (b) 401 · 409 · 410 · 회전 · bootstrap 쿠키 삭제 · me 401 · 200 · 쿠키 재설정 BR3.5. BR1.6 · 신뢰 프록시: 내부 프록시가 아닌 출발지의 X-Forwarded-For는 무시, 프록시 출발지면 X-Forwarded-For 주소로 한도 계수, X-Forwarded-For 주소가 다른 두 요청은 한도를 공유하지 않음), `DeviceRegisterApiIT.java`(AC2.2.1 ~ 2.2.8 · (d) 정규화 · 400 · 알파벳 밖 404 · 409 두 종류 · (m) · 11번째 429 · BR2.7 본문 · 해시 미노출), `GuardianApiIT.java`(AC1.1.7 ~ 1.1.9 시험 로그인 · (u) csrf no-store · 익명 401 · `/api/home` 모양 · `/api/device` DeviceView · AC2.1.14 · BR4.6 DeviceView 필드 · 해시 · 판정 내부값 미노출 · AC1.4.2 ~ 1.4.5 · free 202 · LiveView 필드 · nextSeq · stale · 폴백 · end 202 · SD-9 · NFR9.5 · BR8.5 · AC1.1.8 남의 기기 · 세션 · 끝내기 · 12 조회가 없는 id와 같은 404 본문 · 헤더), `ErrorEnvelopeAndLimitsIT.java`(BR8.6 봉투 · 헤더 = 본문 traceId · 위조 traceId 교체 OD-7 · 64KB+1 → 400 (r) · JSON 아님 400 · 500 INTERNAL_ERROR 스택 없음 (o) · SD-11 입력 미반사 · BR8.10 시각 형식), `HealthAndReadinessIT.java`((t) 200 `{"status":"UP"}`만 · 요청마다 DB 미호출 · DB 3회 실패 → 503 + Retry-After(`/api/health` · `/internal/events` 포함) (n) · 1회 성공 복귀 · DRAINING 503 · OD-11), `CoreInternalApiIT.java`(BR3.9 토큰 없음 · 틀림 · 복수 401 · 2MB 400 · BR3.4 verify 200 필드 · BR3.2 first_connected_at 뒤 claim 410 · (j) 매번 조회 · (i) 400 · 늦은 보고 204 · BR5.5 · BR5.6), `ContractConformanceIT.java`(응답이 `contracts/schemas`에 맞음 · 판정값 · 08 원문 · 해시 없음 · 모르는 입력 필드 무시, 계약 2 · 10절), `CoreLogHygieneIT.java`(SD-15 · NFR9.8 · NFR9.14 · BR8.7 · BR5.13 흐름을 돈 뒤 로그 · 봉투 · 지표 · 전달 뒤 Outbox에 토큰 · 쿠키 · 전체 코드 · 시드 전사 0건, 보안 사건 5종 + 카운터, 400 · 404 · 409는 REGISTER_CODE로 세고 201은 세지 않음, 병합 repeat · OD-3)
  - 하는 일: Core API를 MockMvc · 실제 서버 + Testcontainers로 시험한다.
  - 근거: 위 각 ID
  - 완료 확인: `cd backend && ./gradlew --no-daemon :core-api:test -PincludeTags=u1 --tests 'kr.mujoong.core.web.*'` 통과.

- [ ] Step 35: Realtime API · WSS 시험
  - 파일: `backend/realtime-activity/src/test/java/kr/mujoong/realtime/web/` 아래 `RealtimeInternalApiIT.java`(J54 end 응답표 모든 칸 · (f) · (v) 묘비 409 · (w) 모르는 id GUARDIAN_STOP 409 · view 필드 · 404 · 토큰 401 · 2MB 400 · NOT_READY 503 (n) · BR6.6 · BR6.7 · BR6.9), `DeviceGatewayIT.java`(BR3.6 · SD-7 Origin 403(헤더 없음 포함) · 쿠키 없음 4401 · 무효 · 해제 토큰 4401 · Core 1초 초과 1013 · HELLO 4.9초 통과 · 5.1초 4408 · HELLO 형식 4400 · 8KB+1 4400 · 966B 4400 · READY 뒤 DEVICE_STATE · PLAYBACK_MARK(PROGRESS)를 받아도 ERROR 0건 · 연결 유지(J123) · HEARTBEAT 봉투 `sentAt`으로 계산한 clockOffsetMs가 주입한 오프셋 ± rttMs/2 안 · `sentAt` 없으면 CLOCK_OFFSET_MISSING(OD-8 · R3-4) · 두 연결 경합 → 커밋 뒤 HELLO_OK 전 4001 (l) · 트랜잭션 실패 주입 → 새 연결만 1011 · 4001 요청 0(SCD-2) · HEARTBEAT_ACK hbSeq · 모르는 type ERROR 유지 · 1000 → 즉시 OFFLINE · 40초 무음 → DISCONNECTED · HEARTBEAT 복귀 CONNECTED · 닫힌 소켓 → 시작 423(RD-4 · NFR4.1) · 10초 보고(OFFLINE 포함) · 라운드 건너뛰기 · (j) 연결 2회 = verify 2회 · AC6.2.5 · AC6.2.8 ~ 6.2.11 · AC2.2.5 · BR4.8 · NFR9.4), `RealtimeLogHygieneIT.java`(전사 · 토큰 · 원음 로그 0 · PUBLISHED 뒤 payload `{}` · purged_at SD-23 · 프레임 경로 DB 접근 0 SD-22 · NFR9.8)
  - 하는 일: Realtime 내부 API와 WSS를 실제 서버(무작위 포트)로 시험한다.
  - 근거: 위 각 ID
  - 완료 확인: `cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u1 --tests 'kr.mujoong.realtime.web.*'` 통과.

- [ ] Step 36: 구조 · 설정 대조 시험(ArchUnit 6규칙 · 키 대조)
  - 파일: `backend/contract-model/src/test/java/kr/mujoong/contract/arch/ContractModelArchTest.java`(② Entity · Repository · Spring 설정 0, ⑤ 시각 직접 호출은 `SystemMujoongClock`만), `backend/core-api/src/test/java/kr/mujoong/core/arch/CoreArchTest.java`(① realtime 패키지 참조 0 · ③ 운영 → testsupport 0 · ⑤ · BR6.17 Core에 세션 상태 전이 · 세션 원본 표 쓰기 코드 0(사본 · 조회만)), `backend/realtime-activity/src/test/java/kr/mujoong/realtime/arch/RealtimeArchTest.java`(① · ③ · ④ `gateway` 프레임 경로 · `voice` 입력 경로 → JDBC · 파일 · Outbox 0 · ⑤ · ⑥ 공급자 클라이언트 의존은 `voice` 패키지만, SD-20), `backend/core-api/src/test/java/kr/mujoong/core/config/ConfigKeyContractTest.java` · `backend/realtime-activity/src/test/java/kr/mujoong/realtime/config/ConfigKeyContractTest.java`(RD-24 일부 · BR8.11 `contracts/timing.md`의 키 ↔ `application.yml` ↔ 타입 있는 설정의 이름 · 기본값 일치. Core는 A6 · SD-10: `application.yml`의 bootstrap 한도 20 · 1, `application-demo.yml` · `application-prod.yml`에 rate-limit 상향 값과 `mujoong.load-test.*` 키 0건, 상향 600 · 1은 `application-load-test.yml`에만. Realtime은 J141: `mujoong.provider.url`이 `application.yml`에 없고 demo · prod 파일에만 있음)
  - 하는 일: J113의 싼 규칙 6개와 설정 키 대조를 시험한다.
  - 근거: BR8.1 · BR6.17 · LC-11 · SD-10 · SD-20 · SD-22 · SD-25 · RD-2 · NFR6.4 · NFR9.7 · BR8.11 · RD-24 · A6 · J113 · J141
  - 완료 확인: `cd backend && ./gradlew --no-daemon :contract-model:test --tests '*ArchTest' :core-api:test --tests '*ArchTest' --tests '*ConfigKeyContractTest' :realtime-activity:test --tests '*ArchTest' --tests '*ConfigKeyContractTest' -PincludeTags=u1` 통과(`--tests`는 작업마다 붙인다).

### G. 프런트 — 구현

- [ ] Step 37: shared 계층
  - 파일: `frontend/src/shared/timing/{timing.ts,TimingProvider.tsx,readTimingFromEnv.ts}`(서버 컴포넌트가 런타임 환경 변수 `MUJOONG_TIMING_*` · `MUJOONG_DEVICEWEB_*`에서 읽어 첫 응답에 싣는다, 빌드 때 굽지 않음), `frontend/src/shared/protocol/{frameCodec.ts,wsEnvelope.ts,closeCodes.ts,messages.ts}`, `frontend/src/shared/copy/{ko.ts,particle.ts}`(문구표 원문 · `TEMP_COPY` J118 · 받침 조사 함수), `frontend/src/shared/errors/codeToCopy.ts`, `frontend/src/shared/toast/{ToastProvider.tsx,Toast.tsx}`(layout 수준, `role=status` · 오류 assertive), `frontend/src/middleware.ts` 또는 `frontend/src/proxy.ts`(J122 nonce · CSP), `frontend/src/shared/security/csp.ts`(CSP 헤더를 만드는 순수 함수, 미들웨어/proxy가 부름)
  - 하는 일: 두 영역이 쓰는 시간 · 프로토콜 · 문구 · 토스트를 둔다. 화면은 시간 값을 박지 않는다. CSP `connect-src`의 wss 출처는 `MUJOONG_SECURITY_ALLOWED_ORIGIN`의 스킴만 wss로 바꾼 값(포트 유지)이다(J142).
  - 근거: PD-14 · LC-2 · NFR6.1 · J27 · J118 · J122 · J142 · SD-2 · interaction-spec 1 · 3절
  - 완료 확인: `cd frontend && npx --no-install tsc --noEmit && npx --no-install eslint src/shared` 통과.

- [ ] Step 38: /device(D0 · T19 · 오디오)
  - 파일: `frontend/src/app/device/page.tsx`, `frontend/src/device/machine/deviceMachine.ts`(useReducer 상태 기계), `frontend/src/device/api/deviceApi.ts`(bootstrap · claim 단일 비행, localStorage 금지), `frontend/src/device/ws/wsClient.ts`(seq 방향마다 1부터 · HELLO · HEARTBEAT 송신 직전에 제어 봉투 `sentAt`을 채움(OD-8 · R3-4) · ACK · 닫기 코드별 동작), `frontend/src/device/audio/{capture.ts,pcm-capture.worklet.ts,resample.ts,player.ts}`(J121, 48 → 24kHz 선형 보간과 480표본 프레임 조립은 순수 함수 `resample.ts`로 빼고 worklet은 그 함수를 부르는 얇은 등록부로 둔다(J138), 재생 대기열 20초 · QUEUE_OVERFLOW · PLAY_APPROVED_CONTENT와 OUTPUT_BEGIN 묶음만 재생 BR7.6), `frontend/src/device/mic/micPermission.ts`(클릭 핸들러에서만 동적 import), `frontend/src/device/components/{D0View.tsx,T19View.tsx,DebugLog.tsx}`
  - 하는 일: D0 순서(me → status → bootstrap/claim, 409 → claim, 401 → 새 코드, 410 → me, 409 → 2초 뒤 status, 코드 받기 전 실패는 '연결을 확인하는 중' + 5초 재요청, 「등록되었어요」 3초 → T19 꺼짐 J56), T19 6상태(꺼짐 · 대기 · 듣는 중 · 말하는 중(실제 재생 중만) · 마무리 · 마이크 허용 안 됨), 「켜기」 → 허용 → Wake Lock → WSS, 「끄기」 → 정리 후 1000 DEVICE_OFF, 4401 → me → 401이면 D0, 4001 → 꺼짐, 1001 · 1011 · 1013 · 4400 · 4408 → 켜짐이면 5초 재접속, START_SESSION → ACK → 미리 받기 → DEVICE_READY, 0x01은 DEVICE_READY 뒤 ~ CLOSE_TRANSPORT 전만, STOP_OUTPUT · CLOSE_TRANSPORT 처리 → VOICE_SESSION_CLOSED, `?debug=1` 상태 로그, 「켜기」 · 「끄기」 72px 이상.
  - 근거: BR9.1 ~ 9.7 · BR7.6 · BR3.7 · J56 · J121 · PD-13 · PD-16 · NFR10.4 · AC6.1.1 ~ 6.1.5 · AC6.2.1 ~ 6.2.3 · AC6.2.5 · AC6.2.8 ~ 6.2.11
  - 완료 확인: `cd frontend && npx --no-install tsc --noEmit && npx --no-install eslint src/device src/app/device` 통과.

- [ ] Step 39: /parent API 클라이언트 · 부품
  - 파일: `frontend/src/parent/api/{apiClient.ts,csrf.ts,queries.ts,types.ts}`(앱 시작 · 로그인 직후 csrf, 토큰은 메모리만, 403이면 1회 재취득 + 같은 키 1회 재시도, 두 번째 403 멈춤, 제한 `parent.request-timeout` 10초, TanStack Query v5), `frontend/src/parent/components/{BottomTabBar,DeviceCard,ConfirmDialog,CodeInput,ChipGroup,TextArea200,TranscriptList,StatusLine}.tsx`
  - 하는 일: 보호자 영역 API와 화면 부품을 만든다(접근성 체크리스트: tablist · `aria-disabled` 잠금 · alertdialog 초점 가둠 · CodeInput 칸별 `aria-label` · 오류 `aria-live=assertive` · TranscriptList `role=log`, 터치 44px). 모든 상호작용 요소에 `data-testid`.
  - 근거: SD-6 · BR2.8 · BR4.5 · BR4.6 · BR6.4 · BR6.15 · PD-12 · accessibility-checklist · interaction-spec 3절
  - 완료 확인: `cd frontend && npx --no-install tsc --noEmit && npx --no-install eslint src/parent` 통과.

- [ ] Step 40: /parent 화면
  - 파일: `frontend/src/app/parent/layout.tsx`, `frontend/src/app/parent/page.tsx`(04), `frontend/src/app/parent/device/page.tsx`(스텁 → `/parent`, J57), `frontend/src/app/parent/device/register/page.tsx`(05a), `frontend/src/app/parent/free/page.tsx`(08), `frontend/src/app/parent/sessions/[sessionId]/page.tsx`(12 + P1), `frontend/src/app/parent/sessions/[sessionId]/done/page.tsx`(17), `frontend/src/app/parent/dev-login/page.tsx`, `frontend/src/app/parent/practice/page.tsx` · `frontend/src/app/parent/records/page.tsx`(스텁 → `/parent`), `frontend/src/parent/screens/{Home04,Register05a,Free08,Live12,Done17,DevLogin}.tsx`, `frontend/src/parent/hooks/useLivePolling.ts`
  - 하는 일: 04(진입 때 1회 조회 · 기기 구역 6상태 문구 · 「기기 등록」은 없을 때만 · 「기기 상태 보기 ›」 · 탭 잠금 토스트 9-6 → 05 스텁 → 04), 05a(8칸 · '등록하고 있어요' · 404 · 409 · 429 빨간 카드 입력 유지 · 네트워크 · 5xx 토스트 + 「다시 시도」 · 「취소」 · 「←」 → 05 스텁, 해제 · 재등록 단추 없음), 08(칸 · 칩 · 200자 · 202 → 12 · 거절 사유 하나 · 키 재사용 표 2.9-C), 12(2초 폴링 · 숨기면 멈춤 · 보이면 즉시 1회 · 진행 중 요청 합류 · 연속 3회 실패 토스트 · seq 덮어쓰기 · 종료 상태 멈춤 · ACTIVE 전 「이야기 끝내기」 꺼짐 · stale 줄 · FAILED → 08 사유 카드 · CLOSING → 17 J120), P1(「아니오」 기본 초점 · 202 → 17 · 409 다시 읽기 · 503 · 401 · 네트워크 → 9-18 · 같은 requestId), 17(마무리 중 / ENDED · ENDED_UNCONFIRMED · ABORTED 사유 문구 · 시간 줄 · 「홈으로」), 시험 로그인(J119).
  - 근거: BR2.8 · BR2.9 · BR4.5 · BR4.6 · BR6.1 · BR6.4 · BR6.9 · BR6.15 · BR8.4 · J56 · J57 · J118 · J119 · J120 · AC1.4.2 ~ 1.4.5 · AC1.4.9 · AC1.4.11 · AC1.4.15 · AC2.2.x · AC3.1.x · AC3.3.x
  - 완료 확인: `cd frontend && npx --no-install tsc --noEmit && npx --no-install eslint . && npx --no-install next build` 통과.

### G′. 프런트 — 시험

- [ ] Step 41: /device · shared 시험(Vitest)
  - 파일: `frontend/tests/u1/device/device-machine.test.ts`(BR9.1 모든 간선 · J56 · (a)(b) · BR9.3 쿠키 없음(브라우저 데이터 지움 · 다른 기기) → 새 코드 · BR9.4 · BR9.7 · PD-13), `frontend/tests/u1/device/d0-view.test.tsx`(BR9.2 · AC6.1.1 ~ 6.1.5 문구 · 4 · 4 표시 · 클릭 요소 0 · 3초 표시), `frontend/tests/u1/device/t19-view.test.tsx`(BR9.5 · BR9.6 6상태 라벨 · 말하는 중은 재생 중만 · `?debug=1` · mic-denied 문구 · AC6.2.1 ~ 6.2.3 · AC6.2.5), `frontend/tests/u1/device/mic-permission.test.ts`(NFR10.4 · PD-16 클릭 전 `getUserMedia` 0건 · 거부 모킹 → mic-denied · WSS 0건), `frontend/tests/u1/device/ws-client.test.ts`(계약 4.2 seq · HELLO · HEARTBEAT 주기 · HEARTBEAT 봉투 `sentAt` 존재(OD-8) · ACK opId · 닫기 코드 4401 · 4001 · 1013 → 5초 · AC6.2.8 ~ 6.2.11 · BR3.7), `frontend/tests/u1/device/player.test.ts`(BR7.6 묶음만 재생 · 모르는 contentCode 폐기 · 20초 초과 QUEUE_OVERFLOW · PLAYBACK_MARK), `frontend/tests/u1/device/resample.test.ts`(J121 · J27 48 → 24kHz 보간 · 480표본 프레임 조립 · 경계 표본), `frontend/tests/u1/device/capture.test.ts`(AudioContext · audioWorklet 대역: 클릭 핸들러 안 생성 · 24kHz 거부 시 보간 경로 · `echoCancellation` 켬), `frontend/tests/u1/device/no-service-worker.test.ts`(NFR10.4 `serviceWorker.register` 0건 정적 검사), `frontend/tests/u1/shared/frame-codec.test.ts`(J27 965B · 빅엔디언), `frontend/tests/u1/shared/timing.test.ts`(NFR6.1 런타임 값 주입 시 동작 변화 · 박힌 숫자 없음), `frontend/tests/u1/shared/particle.test.ts`(받침 조사), `frontend/tests/u1/shared/csp.test.ts`(미들웨어/proxy 함수를 직접 불러 요청마다 다른 nonce · CSP 헤더 하나 · 런타임 환경 변수 반영, J142 origin이 `https://localhost:8443`이면 `connect-src`에 `wss://localhost:8443`), `frontend/tests/u1/shared/routes.test.tsx`(`/healthz` 200 · 스텁 라우트 → `/parent` · `/parent/dev-login`은 `MUJOONG_DEVLOGIN_ENABLED`가 true가 아니면 notFound, J119)
  - 하는 일: 기기 영역과 공용 계층을 시험하고 돌린다. 그래도 jsdom에서 실행할 수 없는 줄은 J138대로 Plan Approval의 커버리지 제외 후보 질문에 올린 목록과 대조한다.
  - 근거: 위 각 ID
  - 완료 확인: `cd frontend && npx --no-install vitest run tests/u1/device tests/u1/shared` 통과.

- [ ] Step 42: /parent · 경계 lint · data-testid 시험(Vitest)
  - 파일: `frontend/tests/u1/parent/api-client.test.ts`(SD-6 CSRF 1회 재취득 · 같은 키 재시도 · 두 번째 403 멈춤 · 제한 시간 주입), `frontend/tests/u1/parent/code-input.test.tsx`(BR2.8 자동 대문자 · 칸 이동 · 지우기 · 8자 붙여넣기 · 8칸 미만 「등록」 꺼짐 · 접근성 이름), `frontend/tests/u1/parent/register-05a.test.tsx`(AC2.2.1 ~ 2.2.4 · AC2.2.8 · 빨간 카드 · 입력 유지 · 토스트 · 스텁 경유 04 · BR2.9), `frontend/tests/u1/parent/home-04.test.tsx`(BR4.6 6상태 문구 · BR4.5 탭 잠금 `aria-disabled` · 토스트 9-6 · J57 · AC1.4.2 ~ 1.4.5 · AC1.4.9 · AC1.4.11 · AC1.4.15 · AC2.1.1 ~ 2.1.6 표시), `frontend/tests/u1/parent/free-08.test.tsx`(BR6.4 200자 카운터 · radiogroup · 9-12 사유 대응 · 202 → 12 · 키 재사용 표 · AC3.1.1 · AC3.1.3 · AC3.1.4 · AC3.1.6), `frontend/tests/u1/parent/live-12.test.tsx`(BR6.15 · PD-12 폴링 · 숨김 멈춤 · 3회 실패 · seq 합치기 · 종료 멈춤 · 「끝내기」 활성 조건 · stale 줄 · FAILED → 08 · CLOSING → 17 · J120 · BR6.16 · AC1.1.9 401이면 `/parent/dev-login`으로 가고 end 요청은 0건), `frontend/tests/u1/parent/p1-dialog.test.tsx`(BR6.9 · AC3.3.1 · AC3.3.8 · AC3.3.9 · 202 · 409 다시 읽기 · 503 · 네트워크 9-18 · 같은 requestId · 초점), `frontend/tests/u1/parent/done-17.test.tsx`(AC3.3.4 ~ 3.3.7 마무리 중 · 종료 사유 3종 문구 · 시간 줄 · 「홈으로」), `frontend/tests/u1/parent/dev-login.test.tsx`(AC1.1.7 · AC1.1.9 로그인 → `/parent` · 404 처리), `frontend/tests/u1/lint/eslint-boundary.test.ts`(PD-15 · NFR11.1 위반 샘플 parent → device · device → parent · shared → parent가 실제 lint 실패 · `react/no-danger` SD-11 · `getUserMedia` 제한. 위반 샘플은 파일로 두지 않고 ESLint API `lintText(code, { filePath: "src/parent/__sample__.tsx" })`로 넘겨 `eslint .`이 일부러 넣은 위반을 잡지 않게 한다), `frontend/tests/u1/shared/test-ids.test.tsx`(data-testid 규칙: 화면마다 상호작용 요소 누락 0)
  - 하는 일: 보호자 영역과 경계 규칙 · data-testid 규칙을 시험하고 돌린다. 프런트 줄 커버리지 80%를 확인한다.
  - 근거: 위 각 ID
  - 완료 확인: `cd frontend && npx --no-install vitest run tests/u1 --coverage` 통과(줄 커버리지 ≥ 80%).

### H. 환경 · 빌드 설정

- [ ] Step 43: 이미지 정의(Dockerfile · 번들 · 백업)
  - 파일: `backend/core-api/Dockerfile` · `backend/realtime-activity/Dockerfile`(Java 21 JRE, uid 10001, curl 포함, ENV에 비밀 없음, `JAVA_TOOL_OPTIONS`는 Compose), `frontend/Dockerfile`(Node 22, standalone, uid 1000, wget · curl 중 하나), `infra/migrate/Dockerfile`(Flyway CLI 고정 태그 + 두 위치 SQL), `infra/bundle/Dockerfile`(`FROM scratch` + cicd 2.3.1 목록만 명시 복사: `compose.demo.yml` · `release.env` · `bin/` · `nginx/` · `backup/` · `postgres/` · `seed/` · `seed-audio/` · `RUNBOOK.md` → `mujoong-infra`. `compose.ci.yml` · `compose.dev.yml` · `ci/` · `aws/`는 넣지 않는다), `infra/backup/{Dockerfile,backup.sh,crontab}`(J128, `data_class` ≠ synthetic이면 거부, 임시 파일 → `pg_restore --list` → 이름 변경), `backend/.dockerignore` · `frontend/.dockerignore`
  - 하는 일: 이미지 6종을 정의한다. 모든 이미지에 `LABEL org.opencontainers.image.revision=${GIT_SHA}`와 소스 지문 라벨(J134 (가), 사람 결정 10)을 넣는다(cicd 2.3.1 규칙). **샌드박스 빌드 규칙(R-03):** 컨테이너 안 프로세스는 프록시 중계에 닿지 못하고 CA를 모른다(`/root/.ccr/README.md` 86 ~ 91행). 그래서 앱 이미지 3종(core-api · realtime · web)은 멀티 스테이지를 유지하되 A가 만든 bootJar와 `.next/standalone`을 COPY하는 것을 기본으로 해 컨테이너 안 Gradle · npm 받기를 없애고, 네트워크를 쓰는 RUN 단계(최소 HTTP 클라이언트 · backup의 패키지 · supercronic 받기 등)는 BuildKit 비밀 마운트로만 CA(`extra_ca`)와 프록시 값(`proxy_env`)을 읽는다. 빌드 인자 · ENV로 넘기면 `docker history`에 남으므로 쓰지 않는다. 최종 이미지에 CA · 프록시 값이 없음을 `docker image inspect`와 `docker history`로 확인한다. 운영 jar에 시험 클래스가 없음은 Step 5가 정의한 `verifyBootJarHasNoTestClasses`로 확인한다.
  - 근거: cicd 2.3.1 · infra-spec 2.1 · R4-12 · R4-16 · SD-24 · SD-25 · J81 · J128 · J134 · NFR9.10 · NFR12.2 · CD-7
  - 완료 확인: Docker가 있으면 `docker build`가 6종 모두 성공하고 최종 이미지에 CA · 프록시 값과 `compose.ci.yml` · `compose.dev.yml` · `ci/`가 없으며, `cd backend && ./gradlew --no-daemon verifyBootJarHasNoTestClasses` 통과.

- [ ] Step 44: Nginx 템플릿 · Compose 3종 · 렌더 스크립트
  - 파일: `infra/nginx/nginx.conf.tmpl`, `infra/nginx/conf.d/{http80.conf.tmpl,http80-bootstrap.conf.tmpl,https443.conf.tmpl}`, `infra/nginx/snippets/{security-headers.conf,proxy-common.conf,error-json.conf}`(502 · 504 상태 유지 + JSON code `UPSTREAM_UNAVAILABLE` (y)), `infra/nginx/html/maintenance.html`, `infra/nginx/reload-watch.sh`, `infra/compose.demo.yml`(서비스 9개 · 공통 정책 · 망 2개 · 고정 IP · 메모리 · 헬스 · 의존(core-api는 realtime을 기다리지 않음) · 이미지 `${MUJOONG_REGISTRY}/mujoong-*:${MUJOONG_TAG:?}`), `infra/compose.dev.yml`(postgres + 선택 nginx, 127.0.0.1 게시), `infra/compose.ci.yml`(검증 덧붙임: 로컬 빌드 이미지 · `fake-provider` 서비스 · `SPRING_PROFILES_ACTIVE=dev` · `MUJOONG_DEVLOGIN_ENABLED=true` · `MUJOONG_PROVIDER_URL` → 가짜 공급자 · `SERVICE_HOST=localhost` · certbot · backup 끔, J108. J136대로 nginx `ports: !override ["127.0.0.1:8443:443"]`, 각 서비스 `env_file: !override [build/verify/env/<서비스>.env]`, 호스트 절대 경로 바인드를 같은 컨테이너 경로로 다시 선언: postgres 데이터 → 이름 있는 볼륨 `verify-pgdata`, init → `./infra/postgres/init`(읽기), nginx 설정 → `build/verify/nginx`, 인증서 → `build/verify/certs`, 승인 음성 → `./infra/seed-audio`(읽기)), `infra/bin/{render-nginx,render-env}`, `infra/release.env.tmpl`, `infra/ci/gen-env.sh`(실행 때 무작위 비밀을 인프라 명세 3.7 분배표대로 서비스별 `build/verify/env/{postgres,migrate,core-api,realtime,web,nginx}.env`에 만든다. umask 077, 받지 않는 비밀은 넣지 않음, 값은 base64url · INTERNAL_SERVICE_TOKEN 43자 이상 · ownerHash 비밀 32바이트 이상, J124 · J127 · J136)
  - 하는 일: 경로(`/internal/` · `/actuator/` 403, `= /api/health`, `= /api/device/bootstrap` limit_req 30r/m burst 20 nodelay · `limit_req_status 429` + `error_page 429` RATE_LIMITED 봉투, `^~ /api/`, `= /ws/device` 75초 · `proxy_buffering off`, `^~ /media/approved/` .wav GET · HEAD, `/_next/static/`, `/`), 원시 요청 URI에 `;` · `\` · `%2e` · `%2f` · `%5c`(대소문자 무관)가 있으면 400(`/api/..;/internal/…` 우회 방지), 443 기본 서버 `ssl_reject_handshake on` + 서비스 호스트 서버, 80은 ACME 외 301 `https://${SERVICE_HOST}$request_uri`(요청 Host를 쓰지 않음) · 모르는 Host 444, TLS 1.2 · 1.3 · `ssl_session_tickets off` · HSTS, 공통 포함 파일의 보안 헤더(HSTS · nosniff · Referrer-Policy · `Permissions-Policy: microphone=(self), camera=(), geolocation=()`), `/api/` · `/media/approved/` · Nginx 오류 응답에는 `default-src 'none'; frame-ancestors 'none'` CSP(Next.js 경로는 CSP를 더하지 않음), `client_max_body_size 64k` + `error_page 413 =400`, `client_header_timeout` · `client_body_timeout` 10s, `proxy_intercept_errors off`(앱이 낸 503 통과), XFF · X-Real-IP · X-Trace-Id 덮어쓰기, 접근 로그(쿼리 · 쿠키 · Referer 없음, 폴링 2xx · 304 미기록, stdout으로 내보내 Docker json-file에 남음), `server_tokens off`. 템플릿 변수는 `SERVICE_HOST` · `MUJOONG_SECURITY_ALLOWED_ORIGIN` · HSTS뿐이고 envsubst 변수 목록을 명시한다. `docker compose config`는 env_file 값을 펼쳐 출력하므로 유효성 확인에는 `--quiet`를 쓰고, 해석이 필요한 단언은 `--format json` 출력을 umask 077 임시 파일로만 받아 처리한 뒤 지운다(출력을 로그 · 대화에 붙이지 않음, infra-spec 2.2 · CD-11).
  - 근거: BR8.9 · SD-1 · SD-2 · SD-3 · SD-12 · LC-1 ~ LC-6 · infra-spec 2.1 ~ 3.3 · 3.7 · R4-1 · R4-17 · J98 · J108 · J136 · (r)(t)(y) · NFR4.13 · NFR9.6 · NFR9.9 · NFR9.12
  - 완료 확인: `bash infra/ci/gen-env.sh`로 만든 env로 `docker compose -f infra/compose.demo.yml config --quiet` · `-f infra/compose.demo.yml -f infra/compose.ci.yml config --quiet`가 종료 코드 0이고, 렌더된 설정이 `nginx -t`를 통과한다(Docker가 있을 때).

- [ ] Step 45: tools 구현(가짜 공급자 · 기기 시뮬레이터 · C안 시나리오)
  - 파일: `tools/lib/{frameCodec.mjs,envelope.mjs,cookieJar.mjs,httpClient.mjs,log.mjs}`, `tools/fake-provider/{server.mjs,scenarios.mjs,Dockerfile}`(J110 선 형식, 시나리오 정상 · 금지 패턴 · 거부 · 무응답 · 지연, 받은 입력 음성 바이트 수 · 해시와 보낸 출력 음성 해시 기록), `tools/device-simulator/simulator.mjs`(WSS 클라이언트 · Cookie · Origin · HELLO · HEARTBEAT(송신 직전 제어 봉투 `sentAt`, J143) · ACK · DEVICE_READY · 0x01 20ms 프레임 · PLAYBACK_MARK · VOICE_SESSION_CLOSED · 턴별로 받은 OUTPUT_BEGIN 수와 0x02 바이트 수 · 해시 기록 · 자체 서명 CA 지정), `tools/skeleton-e2e/{run-c.mjs,steps.mjs,assertions.mjs}`(## 검증 명령(C안)의 C1 ~ C12, 본 비밀 값은 `build/verify/c/secrets.list`(600)에만 적고 로그에 쓰지 않음), `tools/dev-edge/edge.mjs`(host 모드 Nginx 대역, J133)
  - 하는 일: C안이 쓰는 시험 도구를 만든다. 앱 이미지에는 넣지 않는다(J129 · SD-25). Node 클라이언트(`ws` · `fetch`)는 프록시 에이전트를 쓰지 않고 자체 서명 CA를 `ca` 옵션으로만 넘긴다(`NODE_TLS_REJECT_UNAUTHORIZED`는 쓰지 않음). run-c.mjs의 모든 변경 요청에 `Origin: https://localhost:8443`을 붙인다(Node fetch는 Origin을 보내지 않으므로 F4 Origin 비교를 실제로 거치게 함). 모든 대기에 기한을 둔다: status REGISTERED 30초, 04 STABLE 90초, ACTIVE 30초, 턴마다 30초, ENDED 60초(application.yml 시간 키에서 계산). 넘으면 단계 id를 출력하고 종료 코드 1(J144).
  - 근거: RD-23 · J81 · J110 · J129 · J131 · J133 · J143 · J144 · OD-8 · 사람 결정 2
  - 완료 확인: `node tools/skeleton-e2e/run-c.mjs --help`가 사용법을 보이고, `tools/` 아래 모든 `.mjs`가 `node --check`를 통과한다.

- [ ] Step 46: tools 시험
  - 파일: `tools/tests/u1/frame-codec.test.mjs`(965B · 순번), `tools/tests/u1/fake-provider.test.mjs`(시나리오별 응답 · 금지 패턴 출력 · 입력 · 출력 해시), `tools/tests/u1/cookie-jar.test.mjs`(Secure · Path · Max-Age=0 삭제), `tools/tests/u1/scenario-plan.test.mjs`(C1 ~ C12 순서 · 실패 시 비0 종료 · 대기 기한 초과 시 단계 id와 종료 코드 1 · 비밀 값을 출력에 쓰지 않음), `tools/tests/u1/dev-edge.test.mjs`(J133: `/internal/` · `/actuator/` 403 · 위조 `X-Trace-Id` · `X-Forwarded-For` 덮어쓰기 · WS 중계)
  - 하는 일: 시험 도구가 의도대로 동작함을 시험한다.
  - 근거: RD-23 · J129 · J133 · J144 · BR8.7
  - 완료 확인: `cd tools && npx --no-install vitest run tests/u1` 통과.

- [ ] Step 47: 배포 · 호스트 · AWS 파일과 RUNBOOK(U1 최소, 정적 검사까지)
  - 파일: `infra/bin/{deploy-stage1,deploy-apply,mj,probe-three,smoke,host-collect.sh,check-since-last,log-hygiene-check,power-mark,backup-trigger}`, `infra/host/{daemon.json,systemd/mujoong-compose.service,systemd/mujoong-power-mark.service,docker.service.d/mujoong-mounts.conf,cron.d/mujoong,logrotate.d/mujoong,sysctl.d/90-mujoong.conf,entry/deploy.sh,entry/mj,entry/host-collect.sh}`, `infra/aws/{ssm-mujoong-deploy.yml,iam-ec2-inline.json,iam-gha-deploy-trust.json,iam-gha-deploy.json}`, `infra/RUNBOOK.md`(4.5.1 순서 · '담당' 절 J104 · 매개변수 이름만 · `SERVICE_HOST` 자리표시자)
  - 하는 일: 배포 D1 ~ D9 · 롤백 · 스모크(demo는 cicd 2.6.2의 8-1 ~ 8-7, 인증 없는 확인만 J100) · 호스트 점검 · 백업 트리거 스크립트와 호스트 · IAM 정의를 만든다. 실호출(SSM · OIDC · Parameter Store · EC2)은 첫 배포 · 3.6 몫이다. `render-env`는 demo에서 `localhost` · `.invalid` 값을 거부한다. `deploy-stage1` · `deploy-apply` · `mj`는 Compose 파일로 `compose.demo.yml`만 받는다. D3에서 렌더된 env와 Compose environment의 `SPRING_PROFILES_ACTIVE`가 정확히 `demo`이고 `MUJOONG_DEVLOGIN_ENABLED` · `MUJOONG_LOADTEST_ENABLED`가 없거나 false인지 확인하고 아니면 D3 실패로 끝낸다(CD-7). `GHCR_PULL_TOKEN`은 `docker login --password-stdin`으로만 넘기고, 임시 `DOCKER_CONFIG`는 trap으로 지우며, 그 구간은 `set +x`로 둔다(cicd 2.5.2).
  - 근거: J96 · J99 · J100 · J104 · cicd 2.4 ~ 2.12 · CD-7 · infra-spec 1.4 · 3.5 · 3.6 · 4.4 · 4.5 · monitoring-design · R4-2 · R4-8 · R4-18
  - 완료 확인: 모든 셸 스크립트가 `bash -n`과 shellcheck(있으면)를 통과하고, JSON · YAML이 파싱된다.

- [ ] Step 48: infra 정적 검사와 그 시험
  - 파일: `infra/bin/infra-validate`(Compose 불변식 12개(demo에만) · `nginx -t` · `docker compose config --quiet` · shellcheck · expand 금지 SQL 검사 · 비밀 변수는 env 파일로만 · (y) 오류 JSON code 존재 · 렌더된 Nginx 설정에 Step 44의 방어 지시어(원시 URI 검사 · `ssl_reject_handshake on` · 444 · `client_header_timeout` · `client_body_timeout` · Permissions-Policy · `default-src 'none'` CSP · `proxy_intercept_errors off` · `limit_req_status 429` · `/ws/device`의 `proxy_buffering off` · `ssl_session_tickets off` · `proxy_set_header X-Forwarded-For $remote_addr`, `$proxy_add_x_forwarded_for` 0건)가 있음 · 번들 이미지 파일 목록에 `compose.ci.yml` · `compose.dev.yml` · `ci/` 0건), `infra/ci/invariant-samples/*.yml`(3망 · 게시 포트 추가 · `user` 없는 postgres · `cap_add` · core-api → realtime 의존 · 받지 않는 비밀 · dev 127.0.0.1 게시는 통과), `infra/ci/test-infra-validate.sh`(샘플마다 기대 결과 단언), `infra/ci/test-host-tools.sh`(`check-since-last` 합성 로그 MO-4 · `log-hygiene-check` grep 0 MO-3 · `render-env` 실패 시 중단 · OAuth 비밀 빈 변수 허용 CD-11 · D3 프로필 확인이 `demo,dev` · `MUJOONG_DEVLOGIN_ENABLED=true`를 거부 CD-7, 가짜 `aws`), `infra/ci/image-checks.sh`(cicd 2.3.2 이미지 점검 1 ~ 8: ① 레이어 · 번들 grep(비밀 변수 이름 · 키 형식) ② 비루트 · 베이스 태그 고정 ③ Trivy류 high 이상(`ci/security-exceptions.yml` 예외, 도구가 없으면 미실행으로 보고) ④ demo 프로필 더미 기동(postgres `user 999:999` · `cap_drop ALL` · `read_only`, migrate · seed 적용, 두 앱 readiness UP, demo + dev 거부) ⑤ 번들에 env 파일 없음과 불변식 통과 ⑥ 시험 클래스 0 ⑦ web `127.0.0.1:3000/healthz` 200 ⑧ 업스트림을 정지한 구성에서 `/` · `/api/health` · `/ws/device`가 상태 502를 유지하고 본문만 바뀜 · 앱이 낸 503은 통과, CD-2)
  - 하는 일: 정적 인프라 검사와 이미지 점검을 만들고 위반 샘플로 시험한다. 관문 음성 대조(CD-1)는 비밀 스캔이 생기는 Step 49에서 만든다.
  - 근거: CD-2 · CD-7 · CD-8 · CD-11 · MO-3 · MO-4 · R4-1 · R4-12 · R4-17 · SD-3 · NFR9.6 · NFR9.8 · NFR4.13
  - 완료 확인: `bash infra/ci/test-infra-validate.sh`와 `bash infra/ci/test-host-tools.sh`가 종료 코드 0이고, Docker가 있으면 `bash infra/ci/image-checks.sh`가 종료 코드 0이다(없으면 미실행을 code-summary에 기록하고 CI `images` 작업 몫으로 둔다).

- [ ] Step 49: 검증 명령 스크립트(scripts/verify.sh)
  - 파일: `scripts/verify.sh`, `scripts/verify/{lib.sh,a-checks.sh,a-secrets.sh,b-stack.sh,b-host.sh,c-skeleton-turn.sh,assert-schema-isolation.sh,assert-log-hygiene.sh,secret-patterns.txt}`, `infra/ci/gen-selfsigned.sh`(→ `build/verify/certs/`), `infra/ci/test-gate-negatives.sh`(CD-1: 실행 때 임시 디렉터리에 만든 가짜 비밀 · 게시 포트 추가 Compose · 틀린 Nginx 설정 · 파괴적 마이그레이션 · 경계를 넘는 import 샘플이 각각 a-secrets · infra-validate · `nginx -t` · expand 검사 · ESLint를 실패시킴. 취약 의존성 · 커버리지 하락 · `ci-gate` 경로 필터 동작은 첫 Actions 실행(3.6)에서 확인), `tools/tests/u1/verify-exit.test.mjs`(SEC-05: 임시 디렉터리에 `scripts/verify.sh` 사본과 가짜 단계 스크립트를 두어(verify.sh는 자기 위치에서 저장소 루트를 잡으므로 바꿔치기 훅이 필요 없음) 단계 실패 · 기한 초과 · 전제 누락일 때 각각 종료 코드 1 · 1 · 2이고 `report.txt`에 해당 단계 PASS 줄이 없음). `infra/ci/gen-env.sh`는 Step 44에서 만든다.
  - 하는 일: ## 검증 명령(C안)의 A · B · C와 사후 단언을 구현한다(J144).
    - 엄격 모드: `scripts/verify.sh`와 `scripts/verify/*.sh`는 `set -Eeuo pipefail`로 시작하고 `cd "$(dirname "$0")/.."`로 저장소 루트를 직접 잡는다. `trap 'rc=$?; collect_logs; cleanup; exit $rc' EXIT`로 정리한 뒤에도 원래 종료 코드를 유지한다. 단언 실패는 1, 전제 누락은 2, 그 밖의 오류도 비0이다. `SKIP_*` 같은 단계 건너뛰기 옵션은 두지 않는다(`KEEP=1`은 정리 방식만 바꿔 스택을 남긴다). `report.txt`의 PASS 줄은 그 단계가 종료 코드 0으로 끝난 뒤에만 쓴다.
    - 시작 정리 · 출력: 시작할 때 `docker compose -p mujoong-verify down -v --remove-orphans`와 남은 host 모드 프로세스 정리를 먼저 하고 `build/verify/env` · `build/verify/c`를 지운다(SIGKILL로 trap이 돌지 않은 이전 실행의 잔여물). 표준 출력에는 단계별 한 줄만 쓰고 Gradle · npm · docker 출력은 `build/verify/logs/*.log`로 보낸다(엔진 1 MiB 제한, J134).
    - 모드: `--mode auto|compose|host`(J133), `--engine`(J134 (가), 사람 결정 10: compose · host 두 모드 모두 소스 지문이 같은 미리 만든 산출물이 있을 때만 C · 사후 단언을 내부 기한 110초로 돈다). 전제(JDK 21 · Node 22 · compose 모드의 Docker + Compose v2.24.4 이상 · host 모드의 `/usr/lib/postgresql/16/bin`) 누락은 종료 코드 2로 끝내고 통과로 보고하지 않는다.
    - 프록시: `lib.sh` 첫머리에 `export NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1`을 두고, 모든 curl 호출에 `--noproxy localhost,127.0.0.1 --cacert build/verify/certs/ca.crt`를 붙인다(HTTPS_PROXY가 걸린 세션에서 루프백 · 8443 요청이 프록시로 가지 않게 함).
    - 이미지 빌드(`b-stack.sh`): `/root/.ccr/ca-bundle.crt`가 있을 때만 `docker build --network host --secret id=extra_ca,src=/root/.ccr/ca-bundle.crt --secret id=proxy_env,src=<HTTPS_PROXY를 담은 umask 077 임시 파일>`을 붙이고 CI에서는 붙이지 않는다(Step 43 샌드박스 빌드 규칙).
    - `docker compose config`는 `--quiet`로만 유효성을 보고, 해석이 필요한 단언은 `--format json` 출력을 umask 077 임시 파일로만 받아 처리한 뒤 지운다(표준 출력에는 단언 결과 줄만).
    - 작업 트리 비교: 시작과 끝에 `git status --porcelain=v1 -uall`과 `git diff --stat`을 비교하고 다르면 종료 코드 1(엔진 증거 지문 불변 조건, SEC-18). 생성기 시험은 임시 디렉터리에 만들어 커밋된 파일과 바이트만 비교한다.
    - 비밀 스캔(`a-secrets.sh`): ① gitleaks가 있으면 `.gitleaks.toml`로 `git ls-files -co --exclude-standard` 대상과 이력을 스캔하고, 없으면 `secret-patterns.txt`의 키 형식(AWS 키 · GitHub 토큰 · `sk-` · `BEGIN .*PRIVATE KEY` · 자격 포함 URL)으로 grep한다(패턴 파일 자신은 제외) ② `git check-ignore`로 `.env` · `.env.local` · `build/verify/env/x.env` · `build/verify/certs/x.pem`이 무시되는지 확인한다 ③ 사후 단언 단계에서 `build/verify/env/*`와 `secrets.list`의 모든 값이 추적 파일과 무시되지 않은 미추적 파일에 0건인지 본다.
    - DB 격리 단언(`assert-schema-isolation.sh`): compose 모드는 backend 망에 붙인 일회성 컨테이너(`docker run --rm --network <backend 망> --env-file build/verify/env/core-api.env <postgres 고정 태그> sh -c 'PGPASSWORD="$DB_CORE_PASSWORD" psql ...'` 꼴, realtime도 같음)에서, host 모드는 0600 `PGPASSFILE`로 127.0.0.1에 접속한다. 비밀번호는 명령줄 인자나 `-e`로 넘기지 않는다. 문장마다 SQLSTATE가 정확히 42501인지 `DO $$ … EXCEPTION WHEN insufficient_privilege` 또는 `\set VERBOSITY verbose`로 확인하고, 28P01 · 28000 같은 접속 실패는 단언 실패(종료 코드 1)로 센다.
    - 끝에 스택을 내리고 볼륨 · 생성 비밀 · 비밀 목록을 지운다(`KEEP=1`이면 스택만 남김).
  - 근거: 사람 결정 2 · J103 · J108 · J127 · J131 · J133 · J134 · J144 · B1 완료 조건 ① ② ③ · SD-13 · SD-15 · CD-1 · CD-11 · NFR9.10
  - 완료 확인: `bash -n` 통과, shellcheck가 있으면 통과(없으면 설치 시도 결과와 미실행을 code-summary에 기록), `bash scripts/verify.sh --help`가 단계 · 모드 · 종료 코드를 설명하고, `cd tools && npx --no-install vitest run tests/u1/verify-exit.test.mjs`가 통과하며, Docker가 있으면 `bash infra/ci/test-gate-negatives.sh`가 종료 코드 0이다.

- [ ] Step 50: CI 워크플로
  - 파일: `.github/workflows/ci.yml`(PR · main: `backend`(gradle check · Testcontainers · ArchUnit · 키 대조 · bootJar 시험 클래스 0 · JaCoCo 80%) · `backend-deps` · `frontend`(npm ci · ESLint · Vitest + 80% · build · 번들 grep) · `frontend-deps` · `tools` · `secrets`(gitleaks류 + 양성 대조) · `infra-validate` · `images`(PR은 푸시 없음, 이미지 라벨에 커밋 SHA, `infra/ci/image-checks.sh`로 cicd 2.3.2 점검 1 ~ 8, `infra/ci/test-gate-negatives.sh`로 CD-1 음성 대조) · `skeleton`(main · 수동에서 `bash scripts/verify.sh`) · `ci-gate`(필수 검사 하나), 기본 `contents: read`, `packages: write`는 이미지 작업만, 서드파티 액션 SHA 고정, 머리 주석에 소유자 'A 계정 · 클라우드 담당' J104), `.github/workflows/deploy.yml`(`workflow_call` + `workflow_dispatch` 입력 `sha` · `mode` · `reason`, environment `demo`, `id-token: write`는 배포 작업만, concurrency 하나, `SERVICE_HOST` 미설정이면 배포 건너뜀 J98, 저장소 변수 `AUTO_DEPLOY`가 false이면 자동 배포 건너뜀(수동 dispatch는 허용, 측정 창 동결), D0에서 `ec2:DescribeInstanceStatus`와 SSM 온라인을 1분 간격 최대 5분 재확인하고 꺼져 있으면 건너뜀(CD-10), 배포 뒤 S5b로 공개 도메인 3종 호출과 인증서 만료 20일 이상 확인), `ci/security-exceptions.yml`(사유 · 만료일 형식, 빈 목록), `.gitleaks.toml`
  - 하는 일: CI 관문과 배포 워크플로를 정의한다. 실제 Actions 실행 · OIDC 호출은 샌드박스 밖이다. `uses:`의 SHA는 기억에 기대지 않고 `gh api repos/<owner>/<repo>/git/ref/tags/<tag>`로 조회하며, 결과가 annotated tag 객체면 `gh api repos/<owner>/<repo>/git/tags/<sha>`로 한 번 더 풀어 커밋 SHA를 얻는다. 표기는 `uses: owner/repo@<40자> # <tag>`. 조회가 안 되면 SHA를 지어내지 않고 그 액션을 미고정으로 code-summary에 보고한다.
  - 근거: J91 · J92 · cicd 2.1 ~ 2.13 · 2.2.1 S4 · S5b · 2.3.2 · CD-1 · CD-2 · CD-10 · NFR9.10 · NFR9.13 · J104
  - 완료 확인: 워크플로 YAML이 파싱되고(가능하면 actionlint), 모든 `uses:`가 40자 SHA로 고정돼 있으며, 모든 SHA가 `gh api repos/<owner>/<repo>/commits/<sha>`로 200을 받는다.

- [ ] Step 51: 검증 명령 실행과 결과 기록
  - 파일: 없음(결과는 `build/verify/report.txt`와 Step 53의 code-summary.md)
  - 하는 일: `bash scripts/verify.sh`를 끝까지 돌린다. 실패하면 원인을 고치고 다시 돌린다(하한 · 단언을 약화하지 않는다). Docker를 쓸 수 없으면 `--mode host`(J133)로 돌리고 `report.txt`의 `mode=host`와 `NOT_VERIFIED(host)` 줄을 그대로 보고한다. 이 실행은 사람 확인용 수동 실행이며 엔진 checkpoint 증거가 아니다(엔진 검증은 verification-command 체크포인트에서 Approve된 명령으로만, J134). compose와 host가 둘 다 불가능할 때만 돌린 부분(A 일부 등)과 건너뛴 부분을 사유와 함께 기록한다.
  - 근거: 사람 결정 2 · G7 · B1 완료 조건 ① ② ③ · org.md Walking Skeleton · J133 · J134
  - 완료 확인: 종료 코드 0과 모드 · 단계별 결과 줄 · 단계별 소요 시간과 총 시간이 기록돼 있다. 건너뜀 목록은 compose와 host가 둘 다 불가능할 때만 허용한다.

### I. 문서 · 추적

- [ ] Step 52: 문서 마무리(README · contracts 대조 · 인라인 문서)
  - 파일: `README.md`('개발 · 검증' 절: 전제 · 로컬 기동 · 유닛 시험 명령 · 검증 명령 · 비밀 규칙), `contracts/README.md`(구현과 다른 곳이 없는지 최종 대조), `infra/RUNBOOK.md`(검증 스택 절 보강), 공개 계약 타입 · 설정 레코드의 Javadoc
  - 하는 일: 문서와 구현이 같은지 대조한다(오류 코드 · 키 · 경로 · 닫기 코드 · 편차 목록).
  - 근거: 계약 12절 · V-5 · J96 · BR8.11
  - 완료 확인: `ContractFilesTest` · `ConfigKeyContractTest`가 다시 통과하고 문서에 비밀 값 · 실제 도메인이 없다(`git grep` 점검).

- [ ] Step 53: source-manifest.json · code-summary.md · traceability.json
  - 파일: `aidlc/spaces/default/intents/261008-mujoong-service/construction/u1-walking-skeleton/code-generation/source-manifest.json`(만들거나 고친 모든 경로, 생성 트리는 끝 `/`, `scripts/cloud-setup.sh` 제외), `.../code-generation/code-summary.md`(파일 목록 · Step 1 판 표 · 핵심 결정 · 시험 · 커버리지 결과 · 계획과 달라진 곳 · 건너뛴 검증 · FD 보강 A1 ~ A6과 3.3 추가 동작 반영 목록 · 임시 문구 목록 J118 · J번호 목록), `.../code-generation/traceability.json`(upstream_ids = AC 83 + NFRx.y 67 + BR 89, OK 대상은 존재하는 구현 · 시험 파일 하나, Deferred 27은 대상 유닛을 적음, 3.6 · 11/2 몫 NFR은 상태와 사유)
  - 하는 일: Step 5(생성 뒤) 산출물을 만든다. 기록 디렉터리에는 이 세 파일만 더한다. source-manifest의 U1 소스 파일(backend `src/main` · frontend `src/`)로 `backend/coverage-scope/u1.txt` · `frontend/coverage-scope/u1.json`을 채운다(J137).
  - 근거: 단계 정의 Step 5 · traceability 센서 · J97
  - 완료 확인: 세 파일이 스키마에 맞고, `traceability.json`의 모든 OK 대상 파일이 존재하며, 엔진이 source-manifest를 받아들인다. `backend/coverage-scope/u1.txt` · `frontend/coverage-scope/u1.json`이 source-manifest의 U1 소스 파일(backend `src/main` · frontend `src/`)을 하나도 빠뜨리지 않는다(J137).

## 스토리 → 단계 추적

| 스토리 | U1 몫 | OK AC (U1이 맡는 것) | 구현 단계 | 시험 단계 |
|---|---|---|---|---|
| US6.1 처음 켜기 · 코드(주) | 완성 | AC6.1.1 ~ 6.1.5 | 7 · 8 · 10 · 15 · 19 · 31 · 32 · 37 · 38 | 13 · 14 · 17 · 27 · 34 · 41 · 51 |
| US2.2 기기 등록 05a(주) | 완성 | AC2.2.1 ~ 2.2.8 | 10 · 15 · 19 · 20 · 31 · 32 · 39 · 40 | 14 · 17 · 27 · 28 · 34 · 42 · 51 |
| US2.1 우리 집 기기 상태 | DeviceView 값 · reasonCode(화면은 U2, 불안정 판정은 U3) | AC2.1.1 ~ 2.1.6 · AC2.1.10 · AC2.1.11 · AC2.1.14 | 15 · 16 · 19 · 20 · 24 · 32 · 40 | 17 · 18 · 27 · 28 · 35 · 42 |
| US1.4 홈 04 | 대화 기기 구역 · 탭 잠금(「연습 → 18」은 U5) | AC1.4.2 ~ 1.4.5 · AC1.4.9 · AC1.4.11 · AC1.4.15 | 19 · 32 · 39 · 40 | 27 · 34 · 42 |
| US1.1 로그인 | 시험 로그인 · 세션 골격(「토스트 → 01」은 U2) | AC1.1.7 ~ 1.1.9 | 10 · 15 · 21 · 31 · 40 | 28 · 34 · 42 |
| US3.1 자유대화 시작 08 | 시작 최소 경로 | AC3.1.1 · AC3.1.3 · AC3.1.4 · AC3.1.6 ~ 3.1.9 | 22 · 23 · 25 · 32 · 33 · 40 | 27 · 28 · 29 · 30 · 34 · 35 · 42 · 51 |
| US3.2 진행 중 12 | 최소(AC 없음, BR6.15 · BR7.5, V-11) | — | 22 · 25 · 32 · 33 · 40 | 28 · 30 · 34 · 42 · 51 |
| US3.3 끝내기 P1 · 17 | 끝내기 최소(정리 중 표시는 U4 · U6) | AC3.3.1 ~ 3.3.9 | 22 · 23 · 25 · 32 · 33 · 40 | 28 · 29 · 30 · 35 · 42 · 51 |
| US6.2 상태 화면 · 켜기 끄기 T19 | 6상태 최소(resume은 U3) | AC6.2.1 ~ 6.2.3 · AC6.2.5 · AC6.2.8 ~ 6.2.11 | 24 · 25 · 33 · 38 | 29 · 30 · 35 · 41 · 51 |
| 기술 규칙(스토리 없음) | BR5 메시징 · BR8 골격 · BR3.9 · BR9.4 등(reverse N/A) | — | 3 · 7 ~ 11 · 15 · 16 · 20 · 21 · 26 · 31 · 33 · 43 ~ 50 | 13 · 14 · 17 · 18 · 27 ~ 30 · 34 ~ 36 · 48 · 51 |

- Deferred AC 27개(U2: AC1.1.1 ~ 1.1.6 · AC1.1.10 · AC1.4.1 · AC1.4.12 · AC2.1.7 ~ 2.1.9 · AC2.1.12 · AC2.1.13, U4: AC1.4.6 ~ 1.4.8 · AC1.4.10 · AC1.4.13 · AC1.4.14 · AC3.1.2 · AC3.1.5, U3: AC6.2.4 · AC6.2.6 · AC6.2.7 · AC6.2.12 · AC6.2.13)는 Step 53의 traceability에 Deferred와 대상 유닛으로 남긴다.
- B1 완료 조건: ① Step 49 · 51(C안), ② Step 14 `SchemaPrivilegeIT` + Step 49 `assert-schema-isolation.sh`, ③ Step 34 · 35 로그 위생 시험 + Step 49 `assert-log-hygiene.sh`, ④ 사람(## 검증 명령(C안) 끝의 B1 ④ 문단).

## FD 보강과 계약 편차 반영 목록

승인 문서 본문은 고치지 않고 코드 · 시험 · 이 목록으로 반영한다(J130). 계약 편차는 모두 '더하기만'이고 `contracts/deviations.md`와 각 스펙 파일의 `x-deviation`에 표시한다.

### FD 보강 A1 ~ A6

| 항목 | 반영 위치(파일) | 시험 |
|---|---|---|
| A1 FAILED 제어 이벤트 재무장(`REARM_ON_START`, 일반 이벤트 20회 상한 유지, `RETRY_FOREVER`는 기동 실패 J124) | `backend/core-api/.../messaging/ControlEventRearm.java` · `.../startup/CoreStartupSequence.java`(S3d), `backend/realtime-activity/.../messaging/ControlEventRearm.java` · `.../startup/RealtimeStartupSequence.java`, `application.yml` `mujoong.outbox.control-event-policy` | `CoreOutboxSenderTest`(대상 · 제외 조건) · `CoreMessagingFlowIT`(S3d) · `DeletionParticipantsCoreIT`(지우지 않음) · `RealtimeMessagingIT`. 8분 재시도 시나리오는 3.6(RD-10) |
| A2 시험 로그인 거부를 demo · prod로(SD-14 13조합) | `backend/contract-model/.../profile/ProfileGuardRules.java`, 두 앱 `startup/ProfileGuardEnvironmentPostProcessor.java`, `identity/DevLoginController.java` · `security/DevLoginBlockedRecorder.java` | `ProfileGuardRulesTest` · `ProfileGuardEnvironmentPostProcessorTest` · `RealtimeStartupChecksTest` · `GuardianApiIT` · `DevSeedRunnerIT` |
| A3 Outbox/Inbox 네 표 `trace_id` | `V1__core_base.sql` · `V1__realtime_base.sql`, 두 앱 OutboxWriter · InboxReceiver, `EventEnvelope.traceId` | `CoreSchemaIT` · `RealtimeSchemaIT` · `CoreConstraintIT`(형식) · `CoreMessagingRepositoryIT` · `CoreMessagingFlowIT`(재시도 뒤 복원) |
| A4 모든 Outbox/Inbox 행 guardian_id(DeviceStatusChanged는 payload.guardianId 우선) | 두 앱 `*OutboxWriter`(인자 필수) · `*InboxReceiver` · `DeviceStatusChangedHandler` · `ConnectionStateWriter` · 삭제 참가자 | `CoreMessagingRepositoryIT` · `RealtimeMessagingIT` · `DeletionParticipantsCoreIT`(NULL 0 · RD-11) |
| A5 시드 사전 7범주 각 1항목 이상, 비면 기동 거부 | `backend/realtime-activity/src/main/resources/gate/seed-dictionary.json` · `gate/SeedDictionary.java` · `startup/RealtimeStartupChecks.java` | `SeedDictionaryTest` · `RealtimeStartupChecksTest` · `OutputGateTest` · `SessionLifecycleIT`(금지 패턴 폐기) |
| A6 부하 시험 전용 시드 · `load-test` 프로필(demo · prod와 함께면 거부) | `ProfileGuardRules.java`(조합 9 · 12 · 13), core `SlidingWindowRateLimiter` 설정, `backend/core-api/src/main/resources/application-load-test.yml`(`mujoong.load-test.enabled: true` · rate-limit 600 · 1만, demo · prod 파일에는 없음) | `ProfileGuardRulesTest` · `SlidingWindowRateLimiterTest` · core `ConfigKeyContractTest`(demo · prod 파일에 상향 값 0건, SD-10). 부하 시드 도구 · L1 ~ L7 · PD-18 시드 도구 시험은 3.6 · 11/2 전(R3-21) |

### 3.3 설계가 더한 동작과 A1 ~ A6 밖 후보

| 항목 | 반영 위치 | 시험 |
|---|---|---|
| Outbox `claim-lease` 30초 선점과 S3f 선점 해제(BR5.2 · BR5.12 보강) | 두 앱 `*OutboxRepository` · `*StartupSequence` | `CoreMessagingRepositoryIT` · `RealtimeStartupRecoveryIT` |
| BR5.14 (a)의 예외: FAILED 제어 이벤트는 지우지 않고 재무장 | 두 앱 `*MessagingDeletionParticipant` | `DeletionParticipantsCoreIT` · `RealtimeMessagingIT` |
| SERVER_RESTART를 고아 세션 점검 · 실행기 예외에도 사용(J126) | `session/OrphanSessionChecker.java` · `session/SessionExecutor.java` | `SessionExecutorTest` · `RealtimeStartupRecoveryIT` |
| BR8.7보다 엄격: 등록 코드는 앞 4자도 로그에 쓰지 않음 | `observability/EventLogger.java`(허용 attrs에 codePrefix 없음) | `CoreLogHygieneIT` |

### 계약 편차 (a) ~ (t)

| 편차 | 반영 위치(파일) | 시험 |
|---|---|---|
| (a) bootstrap 409 ALREADY_REGISTERED | `contracts/device-api.yaml` · core `device/BootstrapService.java` · `device/web/DeviceBootstrapController.java` | `DeviceBootstrapApiIT` · `device-machine.test.ts` |
| (b) claim 401 BOOTSTRAP_NOT_FOUND | `contracts/device-api.yaml` · `device/DeviceClaimService.java` | `DeviceBootstrapApiIT` · `device-machine.test.ts` |
| (c) 503 CODE_ISSUE_FAILED | `contracts/device-api.yaml` · `device/CodeGenerator.java` | `CodeGeneratorTest` · `DeviceBootstrapApiIT` |
| (d) 400 VALIDATION_FAILED(register · activities/free), 정규화 뒤 영숫자 8자 · 알파벳 밖 404 | `contracts/parent-api.yaml` · `device/RegistrationCode.java` · `activity/web/ActivityController.java` | `RegistrationCodeTest` · `DeviceRegisterApiIT` · `GuardianApiIT` |
| (e) 403 CSRF_TOKEN_INVALID | `contracts/parent-api.yaml` · `contracts/errors.md` · `security/SecurityConfig.java` | `SecurityChainIT` · `api-client.test.ts` |
| (f) 내부 end reason CORE_ABANDONED | `contracts/internal-api.yaml` · core `activity/RealtimeSessionClient.java` · realtime `session/EndRequestHandler.java` | `ActivityStartEndIT` · `RealtimeInternalApiIT` · `SessionLifecycleIT` |
| (g) 6절 순서 문장 since → occurredAt | `contracts/events.asyncapi.yaml` · `messaging/handler/DeviceStatusChangedHandler.java` | `CoreMessagingFlowIT` |
| (h) device-ack-timeout 20초를 PROVIDER_UNAVAILABLE에도 | `contracts/timing.md` · `contracts/device-ws.asyncapi.yaml` · `session/SessionTimers.java` | `SessionTimersTest` |
| (i) connection PUT의 state · reportedAt 필수 | `contracts/internal-api.yaml` · `device/web/DeviceInternalController.java` | `CoreInternalApiIT` |
| (j) verify-token 결과 캐시 없음 | `contracts/internal-api.yaml` · `contracts/device-ws.asyncapi.yaml` · `gateway/VerifyTokenClient.java` | `CoreInternalApiIT` · `DeviceGatewayIT` |
| (k) rate-limit은 같은 쿠키 반복을 세지 않음 | `contracts/device-api.yaml` · `web/BootstrapIpLimitFilter.java` · `device/BootstrapService.java` | `SlidingWindowRateLimiterTest` · `DeviceBootstrapApiIT` |
| (l) 연결 대체 시점 = HELLO_OK | `contracts/device-ws.asyncapi.yaml` · `gateway/ConnectionHandover.java` | `DeviceGatewayIT` |
| (m) 같은 보호자 등록 재시도는 첫 성공과 같은 201 | `contracts/parent-api.yaml` · `device/RegistrationService.java` | `RegistrationDecisionTest` · `DeviceRegisterApiIT` |
| (n) 503 SERVICE_NOT_READY + Retry-After | 모든 OpenAPI · 두 앱 `web/ReadinessGateFilter.java` | `HealthAndReadinessIT` · `RealtimeInternalApiIT` |
| (o) 500 INTERNAL_ERROR | `contracts/errors.md` · 두 앱 `web/ApiExceptionHandler.java` | `ErrorEnvelopeAndLimitsIT` |
| (p) 이벤트 봉투 선택 필드 traceId · 네 표 trace_id | `contracts/events.asyncapi.yaml` · `contracts/schemas/event-envelope.schema.json` · V1 SQL | `EventEnvelopeValidationTest` · `CoreMessagingFlowIT` |
| (q) DeviceStatusChanged 선택 필드 guardianId | `contracts/events.asyncapi.yaml` · `gateway/ConnectionStateWriter.java` | `RealtimeMessagingIT` |
| (r) 본문 64KB 초과는 400 VALIDATION_FAILED | `contracts/parent-api.yaml` · `web/RestBodyLimitFilter.java` · `infra/nginx/conf.d/https443.conf.tmpl`(`error_page 413 =400`) | `ErrorEnvelopeAndLimitsIT`(앱). Nginx 쪽은 C안 B 4단계(64KB + 1바이트 → 400 봉투)와 `infra-validate` |
| (s) WSS 규정 외 프레임은 4400 닫기(범위 J123) | `contracts/device-ws.asyncapi.yaml` · `gateway/WsFrameGuard.java` | `WsFrameGuardTest` · `DeviceGatewayIT` |
| (t) 공개 `GET /api/health` | `contracts/parent-api.yaml` · `web/HealthController.java` · Nginx `location = /api/health` | `HealthAndReadinessIT` · C안 B 단계 |

### 3.5가 더하는 편차 (u) ~ (y) (J112, 사람 확인)

| 편차 | 반영 위치(파일) | 시험 |
|---|---|---|
| (u) `GET /api/session/csrf` → `{token, headerName}`, no-store, 익명 401 | `contracts/parent-api.yaml` · `web/CsrfController.java` | `GuardianApiIT` · `SecurityChainIT` |
| (v) RT `POST /internal/sessions` 409 SESSION_NOT_ACTIVE(10분 묘비) | `contracts/internal-api.yaml` · `session/StartSessionHandler.java` · `session/TombstoneMap.java` | `RealtimeInternalApiIT` |
| (w) 모르는 sessionId의 GUARDIAN_STOP end → 409 SESSION_NOT_ACTIVE(J111) | `contracts/internal-api.yaml` · `session/EndRequestHandler.java` | `RealtimeInternalApiIT` · `p1-dialog.test.tsx` |
| (x) C13 `owner_role: core_app`은 쓰는 역할, 표 소유는 `mujoong_owner` | `contracts/README.md` · `infra/postgres/init/01-roles.sql` | `SchemaPrivilegeIT` |
| (y) Nginx 502 · 504 JSON 봉투 code `UPSTREAM_UNAVAILABLE`(상태 유지) | `contracts/errors.md` · `infra/nginx/snippets/error-json.conf` | `infra-validate`(정적) · `infra/ci/image-checks.sh` 점검 8(업스트림 정지 시 상태 502 유지, CD-2 · R4-17, 3.5. Docker가 없으면 CI `images` 작업) |

## 검증 명령(C안)

**한 줄 명령(J103):** `bash scripts/verify.sh`

- **유닛 시험 명령이 아니다.** 이 명령은 저장소 전체를 도는 검증 명령이다(A 1번은 태그 필터 없는 저장소 전체 회귀). `unit-test-instructions.md`의 유닛 시험 명령 목록에 들어가지 않으며, Build and Test가 유닛마다 돌리는 명령이 아니다(단계 정의 189 ~ 192행).
- **엔진 checkpoint 조건(J134).** 엔진 checkpoint 검증(`aidlc engine bolt checkpoint --action verify`)은 기록된 명령을 저장소 루트에서 `/bin/bash -c`로 실행한다. 120초가 지나면 SIGKILL로 끊고, stdout · stderr가 각 1 MiB를 넘으면 실패이며, 통과는 종료 코드 0 · 신호 없음 · 증거 지문 불변일 때뿐이다(`.claude/tools/aidlc-construction-checkpoints.ts` 110 · 111 · 521 ~ 553행). 이 전체 명령은 120초 안에 끝나지 않으므로 엔진에 기록할 명령의 구성은 J134의 선택지로 사람이 정한다.
- **승인 경로(J103 · J134).** Plan Approval은 이 명령을 승인하지 않는다. 코드 생성 뒤 오케스트레이터가 `aidlc/spaces/default/intents/261008-mujoong-service/verification-command.txt`에 한 줄 명령(1024자 이하, 제어 문자 없음)을 파일 쓰기 도구로 쓰고, `aidlc engine log decision --checkpoint verification-command --command-file verification-command.txt`로 사람에게 묻는다. Approve를 받은 뒤 `log answer`와 `state set-construction-verification-command`로 기록한다. 기록 전에는 `bolt checkpoint --action verify`를 돌리지 않는다(stage-protocol-construction.md 145 ~ 204행).
- 실행 위치: 저장소 루트(스크립트가 자기 위치에서 직접 잡는다). 전제: JDK 21 · Node 22, compose 모드는 Docker + Compose v2.24.4 이상, host 모드는 호스트 PostgreSQL 16 바이너리(J133). 실공급자는 부르지 않는다(실공급자 한 턴은 11/2 측정).
- 종료 코드: 0 = A · B · C와 사후 단언 모두 통과, 1 = 어느 단언이든 실패(대기 기한 초과 포함), 2 = 전제 누락(통과로 보고하지 않음). 단계별 결과는 `build/verify/report.txt`에 한 줄씩 남긴다(첫 줄에 모드, 비밀 값 없음). 표준 출력은 단계별 한 줄뿐이고 도구 출력은 `build/verify/logs/`에 둔다.
- 시험용 값: `infra/ci/gen-env.sh`가 실행 때 서비스별 비밀 파일을 만들고(`build/verify/env/`, umask 077, J136), `infra/ci/gen-selfsigned.sh`가 CA와 `localhost` 인증서를 만든다(J108 · J127). 시작할 때 이전 실행의 잔여물을 먼저 지우고, 끝에 지운다.
- 시작 · 끝 작업 트리 비교: `git status --porcelain=v1 -uall`과 `git diff --stat`이 달라지면 종료 코드 1(SEC-18).

**A — 정적 · 단위 · 통합(`scripts/verify/a-checks.sh`)**
1. `cd backend && ./gradlew --no-daemon clean check verifyBootJarHasNoTestClasses` — 저장소 전체 회귀(유닛 범위 명령 아님): 세 모듈 단위 · 통합(DB 백엔드 J132) · ArchUnit 6규칙 · 설정 키 대조 · 모듈 전체 JaCoCo 80% LINE.
2. `cd frontend && npm ci && npx --no-install eslint . && npx --no-install vitest run tests/u1 --coverage && npx --no-install next build` — 경계 lint · Vitest · 80% 줄 · 빌드.
3. `cd tools && npm ci && npx --no-install vitest run tests/u1`(J131).
4. `bash scripts/verify/a-secrets.sh` — 비밀 스캔 ①과 무시 규칙 ②(J144 · SEC-10).

**B — 기동과 3종 호출(`scripts/verify/b-stack.sh`, host 모드는 `b-host.sh` J133)**
1. 이미지를 로컬 태그로 빌드하고(샌드박스에서는 Step 43 · 49의 빌드 규칙) `docker compose -p mujoong-verify -f infra/compose.demo.yml -f infra/compose.ci.yml`로 postgres(init)를 띄운다. 병합된 설정(`config --format json`을 umask 077 임시 파일로만 받음)에서 호스트 게시 포트가 nginx의 `127.0.0.1:8443` 하나뿐이고 서비스별 비밀 변수 이름이 인프라 명세 3.7 분배표와 같은지 단언한다(어기면 1, 출력은 로그에 남기지 않음, SEC-02).
2. `--profile migrate run --rm migrate`(소유자 역할, 두 위치, 종료 코드 0) → `infra/seed/001-reference.sql`을 두 번 적용하고 정책 1 · 문구 2를 확인(R4-15).
3. `up -d --wait`: realtime · core-api readiness(9090) → web → nginx, 그리고 fake-provider.
4. 3종 호출: `GET https://localhost:8443/api/health` 200 `{"status":"UP"}`, `GET /device` 200, `/ws/device` 업그레이드 101(Origin 허용, 쿠키 없음 → 이어서 4401 닫힘). 덧붙여 SD-3 중 B1 필수 단언(R3-21 341행): `/internal/x` · `/actuator/health` 403, 64KB + 1바이트 본문의 `POST https://localhost:8443/api/device/register` → 400 `{code: VALIDATION_FAILED}` 봉투((r)), 위조 `X-Trace-Id`를 단 요청의 응답 `X-Trace-Id` · 봉투 traceId가 위조 값이 아님(OD-7). 클라이언트 IP 덮어쓰기는 렌더된 설정 단언(`infra-validate`: `proxy_set_header X-Forwarded-For $remote_addr`, `$proxy_add_x_forwarded_for` 0건)과 앱의 신뢰 프록시 시험(Step 34 `DeviceBootstrapApiIT`)이 맡는다(실행 스택에서 bootstrap 한도를 소모하면 C 1이 막히므로 B에서 21회 호출은 하지 않음, BR8.9). 원시 URI 우회 `curl --path-as-is https://localhost:8443/api/..;/internal/x` → 400, SNI · Host가 `other.invalid`인 연결은 TLS 거절(SEC-12).
5. 프로필 가드 이미지 기동(SD-14 · CD-7, SEC-08): 빌드한 core-api · realtime 이미지를 `docker compose run --rm --no-deps`로 (i) `SPRING_PROFILES_ACTIVE=demo` + `MUJOONG_DEVLOGIN_ENABLED=true`, (ii) `SPRING_PROFILES_ACTIVE=demo,dev`로 띄워 60초 안에 비0으로 끝나는지 단언한다(아니면 1).

**C — 기기 시뮬레이터 한 턴(`scripts/verify/c-skeleton-turn.sh` → `node tools/skeleton-e2e/run-c.mjs`)**, 프로필 dev + `MUJOONG_DEVLOGIN_ENABLED=true`. 모든 변경 요청에 `Origin: https://localhost:8443`을 붙이고, 모든 대기에 기한을 둔다(Step 45, 넘으면 단계 id와 종료 코드 1).
1. D0: `GET /api/device/me` 401 → `GET /api/device/bootstrap/status` 401 BOOTSTRAP_NOT_FOUND → `POST /api/device/bootstrap` 201 `{code, expiresAt}` + bootstrap 쿠키(BR1 · BR9.1). 받은 Set-Cookie의 HttpOnly · Secure · SameSite · Path(`/api/device`)가 정확하고 Domain이 없다(SD-5, SEC-16).
2. 시험 로그인: `POST /api/dev/login` → MUJOONG_SESSION(HttpOnly · Secure · SameSite=Lax · Path `/` · Domain 없음), `GET /api/session/csrf`. `GET /api/home` → device.registration.status NONE · device.verdict `{canStart: false, reasonCode: NO_DEVICE}` · lock.locked true(BR8.4 · BR4.3 · 계약 3절 DeviceView).
3. 05a: 먼저 `X-CSRF-TOKEN` 없는 register는 403 CSRF_TOKEN_INVALID, `Origin: https://evil.invalid`인 register도 403임을 단언한다(SD-6 · BR8.8, SEC-16). 이어서 `POST /api/device/register {code}` + `X-CSRF-TOKEN` → 201 `{deviceId, codePrefix, registeredAt}`(BR2).
4. claim: status가 REGISTERED가 되면 `POST /api/device/bootstrap/claim` → 200 `{deviceId}` + MUJOONG_DEVICE(속성 단언은 1과 같음, BR3.1).
5. 켜기: 먼저 `Origin: https://evil.invalid`인 `/ws/device` 업그레이드가 403임을 단언한다(BR3.6 ① · SD-7). 이어서 `/ws/device`(쿠키 · 허용 Origin) → HELLO → HELLO_OK `{connectionId, serverTime, heartbeatIntervalMs, audio}`, HEARTBEAT(봉투 `sentAt` 포함, J143) ↔ HEARTBEAT_ACK. DeviceStatusChanged가 Outbox → `POST /internal/events` → Inbox → 사본(BR3.4 · BR3.7 · BR4.1 · BR4.8 · BR5).
6. 04 안정: `GET /api/home` 폴링으로 device.network.state STABLE · device.verdict.canStart true · lock.locked false(도달 시간 기록, J131).
7. 08: `POST /api/activities/free {idempotencyKey}` → 202 `{sessionId, status: REQUESTED}`, Core → RT 202 PREPARING(BR6.1 ~ 6.6).
8. 시작: START_SESSION `{approvedAudio[CLOSING, WAITING_FILLER]}` → ACK(DEVICE_ACK) → 승인 음성 `/media/approved/*.wav` 미리 받기 → DEVICE_READY `{mic, player, prefetch OK}` + 가짜 공급자 `session.started` → ACTIVE와 SESSION_STATE `{LISTENING}`. `GET /api/sessions/{id}?sinceSeq=0` ACTIVE(BR6.8 · BR7.2).
9. 음성 1턴(정상): 0x01 프레임 → `session.input_audio.append`(가짜 공급자가 받은 바이트 해시 = 보낸 해시, J131) → 아이 전사 FINAL → 출력 delta 뒤 800ms 조용 → 게이트 통과 → OUTPUT_BEGIN `{playbackId, source GPT_LIVE}` → 0x02 → OUTPUT_END → PLAYBACK_MARK STARTED · COMPLETED. 시뮬레이터가 받은 0x02 페이로드 해시가 가짜 공급자가 보낸 출력 해시와 같다(게이트 뒤 무변환, SEC-07). 12 items에 CHILD 전사와 승인된 AI 말이 있다(BR7 · BR6.15).
10. 게이트 경유 증명(NFR1.1 · SD-17, SEC-07): 같은 세션에서 가짜 공급자가 금지 문장과 출력 오디오 N바이트를 보낸 턴 동안 시뮬레이터가 받은 OUTPUT_BEGIN은 0건, 0x02는 0바이트다. 이어서 PLAY_APPROVED_CONTENT(WAITING_FILLER)가 정확히 1건 오고, 12 items에 금지 문장이 0건이며, `realtime_transcript`의 그 AI 행은 `approved_for_display` false다(BR7.4 · BR7.5 · A5. 폐기 구간의 행은 false로 남는 것이 설계다).
11. P1: `POST /api/sessions/{id}/end {requestId}` → 202 `{status: CLOSING}` → STOP_OUTPUT(GUARDIAN_STOP) + SESSION_STATE `{CLOSING}` → CLOSING 승인 음성을 새 playbackId로 정확히 1회(미리 받은 WAV를 PLAY_APPROVED_CONTENT로 재생) → 같은 playbackId PLAYBACK_MARK COMPLETED → CLOSE_TRANSPORT → VOICE_SESSION_CLOSED `{outputStoppedAt, micClosedAt}`(BR6.9 · BR6.10). STOP_OUTPUT 뒤 시뮬레이터가 받은 0x02는 0바이트이고, CLOSE_TRANSPORT 뒤 시뮬레이터가 일부러 보낸 0x01 때문에 가짜 공급자의 수신 바이트가 늘지 않는다(R3-8 · SD-17, SEC-07).
12. ENDED: confirmed true · end_reason GUARDIAN_STOP → SESSION_STATE `{STANDBY}`. Core 사본도 ENDED, 12도 ENDED(BR6.14). LATENCY_TURN 한 줄에 J143의 시각 키 8종과 tSpeechEndSource · clockOffsetMs · rttMs · providerResponseMs · quietWaitMs, SESSION_ITEMS_SERVED에 tViewItemReady · tViewItemFirstServed가 있는지는 판정하고 값은 기록만 한다(OD-8, J131 · J143).

**사후 단언**
- B1 ②(`scripts/verify/assert-schema-isolation.sh`): 실행 중 DB에서 `core_app`의 realtime 표 INSERT, `realtime_app`의 core 표 INSERT, 앱 역할의 CREATE TABLE이 모두 SQLSTATE 정확히 42501(SD-13 · NFR9.7). compose 모드는 backend 망에 붙인 일회성 컨테이너에서 `--env-file build/verify/env/core-api.env`(또는 `realtime.env`)로, host 모드는 0600 `PGPASSFILE`로 접속하며 비밀번호를 명령줄 인자나 `-e`로 넘기지 않는다. 28P01 · 28000 같은 접속 실패는 단언 실패(1)로 센다(SEC-04).
- B1 ③(`scripts/verify/assert-log-hygiene.sh`, SEC-06 · F14):
  1. 스택을 내리기 전에 `docker compose logs --no-color core-api realtime web nginx postgres`를 `build/verify/logs/compose.log`로 모은다. Nginx 접근 로그는 stdout(Docker json-file)이라 여기에 들어 있다. 가짜 공급자 · 시뮬레이터 출력은 전사를 직접 만드는 도구라 대상이 아니다. host 모드는 각 호스트 프로세스의 로그 파일(`build/verify/logs/host-*.log`)을 대상으로 한다.
  2. 모은 로그에 STARTUP_RECOVERY · SESSION_TRANSITION(ENDED) · LATENCY_TURN 줄과 Nginx 접근 로그 줄(compose 모드)이 각각 1개 이상 없으면 실패다(빈 수집으로 통과하지 않음).
  3. 찾을 값은 `build/verify/c/secrets.list`(600) 한 곳에 모은다: 실행 중 본 기기 토큰 · bootstrap 비밀 · 세션 쿠키 · CSRF 토큰과 각각의 SHA-256(16진 · base64url), 등록 코드 8자와 4 · 4 표시형, 가짜 공급자가 낸 아이 전사 · 통과한 AI 말 · 폐기된 금지 패턴 문장 원문, `gen-env.sh`가 만든 `INTERNAL_SERVICE_TOKEN` · `PROVIDER_API_KEY` 자리값 · DB 비밀번호 5종(`POSTGRES_PASSWORD` · `DB_OWNER_PASSWORD` · `DB_CORE_PASSWORD` · `DB_REALTIME_PASSWORD` · `DB_BACKUP_PASSWORD`) · ownerHash 비밀.
  4. `grep -F -c -f secrets.list`로 `compose.log` · `report.txt` · verify 표준 출력을 대조해 일치 건수만 출력하고, 0이 아니면 1(SD-15 · NFR9.8 · BR8.7). 이어서 `a-secrets.sh` ③(값이 추적 · 무시되지 않은 파일에 0건)을 돈다. 비밀 목록 파일은 끝에 지운다.

**B1 ④(F13):** 종료 코드 0과 `build/verify/report.txt`를 근거로 사람이 스켈레톤 확인을 승인한다. 자동 단언이 아니다. 스켈레톤 자세가 off라 엔진의 스켈레톤 체크포인트가 없으므로, 오케스트레이터가 3.6 Build and Test 뒤 gated 전환 정지 지점에서 질문으로 받는다.

**demo 서버 스모크(참고, J100):** demo에서는 시험 로그인이 막혀 있으므로 배포 스모크는 인증 없는 cicd 2.6.2의 8-1 ~ 8-7(두 앱 readiness · 3종 호출(health 200 · `/device` 200 · WSS 101) · 보안 경계 3건 · 승인 음성 정적 경로 · 컨테이너 상태 · STARTUP_RECOVERY 한 줄 · 중단 시간)과 배포 뒤 S5b로 한정한다. bootstrap 201은 배포마다 기기 행을 만들고 IP 한도를 쓰므로 스모크에 넣지 않는다. 한 바퀴 검증은 이 C안이 맡는다.

## 시트 사이 충돌과 푼 방법

| # | 충돌 | 푼 방법 |
|---|---|---|
| 1 | 임시값 J번호: scope-trace · infra 시트는 도메인 J97 · 인증서 J98 · GHCR J99 · 스모크 J100(또는 GHCR J98 · 스모크 J99)으로 제안 | 오케스트레이터가 준 번호(J97 코드 배치 · J98 도메인과 인증서 · J99 GHCR · J100 스모크 · J101 사전 · J102 WAV · J103 검증 명령 · J104 담당)를 따르고 시트 제안 번호는 쓰지 않았다 |
| 2 | 검증 명령 경로: `bash scripts/verify.sh`(J39) · `infra/bin/verify-skeleton`(infra) · 예시 `scripts/verify-u1.sh` | J39 후보 `scripts/verify.sh` 하나로 정하고 단계 스크립트는 `scripts/verify/`에 둔다(J103). `infra/bin/verify-skeleton`은 만들지 않는다 |
| 3 | Flyway 이력 표 이름: `core_schema_history`(data-model) 대 `core_history`(infra) | SD-13 · RD S2 · J84(a)의 `flyway.core_schema_history` · `flyway.realtime_schema_history`(J116) |
| 4 | 내부 호출 호스트: `core-api:8080`(contracts 5-4) 대 `core:8080`(infra) | infra-spec D-12 · N-2의 backend 별칭 `core`를 쓴다. 설정 키 기본값 `http://core:8080`(J109) |
| 5 | 이력 표 SELECT 권한: init에서(infra 5-10) 대 `afterMigrate.sql`(data-model 5-6) | init 때는 표가 없으므로 `afterMigrate.sql`(J116) |
| 6 | 새 편차 기호 (u): csrf(contracts) · owner_role(data-model) · UPSTREAM_UNAVAILABLE(infra) | (u) csrf · (v) 묘비 409 · (w) V-12 · (x) owner_role · (y) UPSTREAM_UNAVAILABLE로 나눴다(J112) |
| 7 | V-12 응답: 409 SESSION_NOT_ACTIVE(scope-trace · rules · flows) 대 RT 404 → Core 409(contracts) | 새 코드 없이 RT가 409 SESSION_NOT_ACTIVE, 묘비 없음(J111 · (w)) |
| 8 | 검증 스택 프로필: `dev,test` 조합 13(security) 대 dev + enabled(scope-trace · infra) | Compose 검증 스택은 dev + enabled(조합 2), Gradle 시험은 test, 시험 로그인 시험만 조합 13(J108) |
| 9 | 허용 origin 시험값: `https://localhost:8443`(infra) 대 `https://mujoong.invalid`(security) | 검증 스택은 localhost:8443, 단위 · 통합 시험은 `.invalid`(J108) |
| 10 | ArchUnit: R3-21은 묶음 전체 B1 이후 대 security C13(4규칙) · data-model R1 · cicd A안(ArchUnit 포함) · SD-20은 B1 필수(R3-21 341행) | 싼 규칙 6개(4 + 시각 직접 호출 금지 + 공급자 클라이언트 의존 한정 SD-20)만 U1, 나머지는 3.6 이후(J113) |
| 11 | 시험 값 파일 `infra/ci/fake.env`(infra) 대 사람 지시 '시험용 값은 실행 때 생성' | 값 파일을 두지 않고 `gen-env.sh`가 실행 때 만든다(J127) |
| 12 | 공급자 · 시뮬레이터 위치: 시험 프로필 훅(J81 문구) 대 운영 이미지에 훅 없음(SD-25) · C안은 실제 jar | 외부 도구(`tools/`, Compose dev · ci 서비스) + 시험 소스(`test-support`)(J129) |
| 13 | 승인 음성 경로: `/audio/approved/`(SD) 대 `/media/approved/`(3.4 결정 보강) | `/media/approved/`(cicd 다르게 푼 곳 9), audioUrl은 같은 origin 상대 경로 |
| 14 | 정책 버전 자리: 표 없음(data-model 5-1) | `CONSENT_POLICY` 승인 콘텐츠 행(J114) |
| 15 | `core_consent.child_id`: NOT NULL(entities) 대 NULL 허용 제안(data-model 5-2) | 승인된 entities.md대로 NOT NULL, 완화는 U2(J115) |
| 16 | BR8.3(prod만) 대 A2 · SD-14 13조합 | 사람 결정 5에 따라 A2 · SD-14로 구현(반영 목록) |
| 17 | 스모크 인증 '시험 로그인'(사람 결정 4) 대 demo의 시험 로그인 금지(A2 · NFR9.11) | 가드는 완화하지 않고 시험 로그인은 로컬 · CI · C안에서만, demo 스모크는 무인증(J100) |
| 18 | 등록 직후 착지: 화면 설계 D-00 '→ T19 대기' 대 J56 · BR9.2 '꺼짐' | 승인된 J56을 따른다(마이크는 사용자 동작 필요). 팀장 확인 항목으로 남긴다 |
| 19 | first-bolt.md(구글 로그인만 · 고정 ID · `/ws` · ENDING → PARTIAL) 대 U1 | 승인된 U1 설계를 따른다(중재 순서, C2) |
| 20 | 재접속 간격: interaction-spec 1 · 2 · 4 · 8초 · 계약 1 · 2 · 4초 대 BR9.7 5초 고정 | U1은 5초 고정, 백오프는 U3 |
| 21 | (s) 범위: 계약 '형식 오류도 ERROR' 대 (s) '4400' | 크기 · 규격 · HELLO 형식 · 방향 위반은 4400, READY 뒤 모르는 type · payload는 ERROR 유지(J123) |
| 22 | SERVER_RESTART 범위: BR6.8(BR6.13만) 대 R3-6(고아 · 실행기 예외) | R3-6대로 쓰고 FD 보강 후보로 목록에 올린다(J126) |
| 23 | A1 시점: R3-21은 RD-10 B1 이후 대 RD-12 S3d · 사람 결정 5 | 함수 · S3d · 조건 시험은 3.5, 8분 시나리오만 3.6(runtime C4) |
| 24 | 12 PARTIAL: W6은 FINAL만처럼 읽힘 대 BR6.15 nextSeq는 PARTIAL 전제 | BR6.15대로 `final` 플래그 전달, 표시는 U4(J120) |
| 25 | 끝내기 409 뒤 화면: 계약 '17로' 대 W8 · BR6.9 '다시 읽어 종료일 때만 17' | W8을 따르고 FAILED면 08 사유 카드(J120) |
| 26 | 12 실패 안내: interaction-spec '5초 넘으면 토스트' 대 PD-12 '연속 3회 실패 · 10초' | 12는 PD-12, 문구는 '연결이 원활하지 않아요' |
| 27 | C-INT 401 봉투 code 없음 · Retry-After 값 없음 · 비밀 길이 하한 없음 | `UNAUTHENTICATED` · `health.db-check-interval` 값 · 256비트/32바이트(J124) |
| 28 | `data_class` 없음: 기동 거부(data-model) 대 `real`로 간주(security) | 둘 다 같은 결과라 `real` 간주(fail-closed)로 통일(J124) |
| 29 | backup 이미지: 서드파티(cicd 5종) 대 자체(infra 5-2 · V-15) | 자체 `mujoong-backup`(J128), V-15 결과에 따라 바뀜 |
| 30 | ERD v0.1 TIMESTAMP · Spring Session 표 정의 대 Instant · Spring Session 표준 | `timestamptz` · 표준 정의(J115), ERD v0.2 목록 |
| 31 | 낡은 값: 보고서 Q-5 6.875 GiB · V-8 한 명령 재생성 | R4-19 6.575 GiB · R4-13 두 프로세스를 따른다 |
| 32 | 80% 하한: Testing Contract `scope_floor`(mujoong-mvp는 추가 하한 없음) 대 J91 · cicd · 사람 지시 | 설계 측정 목표라 80%를 적용한다(더 엄격, 약화 없음, J107) |
| 33 | 확인 결과 기록 위치: tech-stack-decisions.md 78행(2.2절 행에 적음) 대 승인 문서 본문 미수정 방침(J130) | 결과는 code-summary.md 판 표에 두고 tech-stack 2.2 갱신은 사람이 지시할 때 한다(J140, J130 확장) |
| 34 | 업스트림 정지 502 시험: 이 계획 초안 (y) 행의 '3.6' 대 cicd ## 4 CD-2(이미지 점검 7 · 8은 3.5 구현) | CD-2대로 3.5에서 `infra/ci/image-checks.sh` 점검 8로 둔다(Docker가 없으면 CI `images` 작업) |
| 35 | 로그 위생 대상: '모든 서비스'(초안) 대 가짜 공급자는 전사를 직접 만드는 도구 | 대상은 core-api · realtime · web · nginx · postgres 로그, 가짜 공급자 · 시뮬레이터 출력은 제외(SEC-06) |

## 위험과 열린 질문

**위험**
- **스택 호환(V-1).** Spring Boot 4.1 · Security 7 · Session JDBC · springdoc · Testcontainers 판 조합이 안 맞으면 Step 3 끝의 판정(J139)에서 Step 8 전에 3.5.x로 전환한다. 전환하면 Jackson 기준(`tools.jackson.*` → `com.fasterxml.*`)이 바뀌므로 Jackson 직접 import를 `kr.mujoong.contract.json` 한 곳에 모아 둔다(J139). Step 8 이후 드러난 호환 문제는 자동 전환하지 않고 사람이 정한다. 3.5.x의 OSS 지원이 끝났으면 전환 전에 사람에게 묻는다(Step 1). springdoc은 Boot 4.1과 맞을 때만 dev · test에서 켜고, 계약 일치는 `contracts/schemas` 검증으로 본다.
- **샌드박스 실행 환경(G7).** JDK 21 · Docker가 없거나 중첩 Docker에서 고정 서브넷(172.30.10.0/24 · 172.30.20.0/24)이 겹치거나 `mem_limit` · `read_only` · `cap_drop`이 다르게 동작할 수 있다. Testcontainers Ryuk이 막히면 끈다. Docker를 쓸 수 없으면 DB 시험은 호스트 PostgreSQL 16(J132), 검증 명령은 host 모드(J133)로 돌린다. 컨테이너 안 빌드는 프록시 · CA를 모르므로 Step 43 · 49의 빌드 규칙(BuildKit 비밀 마운트, `--network host`)을 따른다. Java는 HTTPS_PROXY를 읽지 않으므로 Gradle 프록시 속성은 저장소 밖에 둔다(Step 3). 루프백 · 8443 요청이 프록시로 가지 않게 `NO_PROXY`와 `--noproxy`를 쓴다(Step 49). 못 돌린 검증은 Step 51에 사유와 함께 남긴다.
- **엔진 checkpoint 시간 제한(J134).** 엔진 검증은 120초 · 출력 1 MiB 제한이라 C안 전체를 그대로 기록하면 매번 실패한다. 구성은 Plan Approval 전에 사람이 정한다.
- **분량.** 단계가 53개라 B1 일정(10/14 ~ 10/16)을 넘길 수 있다. 동작은 모두 구현하고, 시간이 모자랄 때만 R3-21 'B1 이후' 시험을 Deferred(→ 3.6)로 사유와 함께 남긴다. 커버리지 하한은 그 경우에도 낮추지 않는다.
- **브라우저 동작.** AudioWorklet과 `strict-dynamic` CSP(X-6), 24kHz 문맥(J121), Wake Lock은 jsdom에서 확인할 수 없어 3.6 실기(NFR10.1 · 10.2)에서 본다. 보간 · 프레임 조립 · CSP 헤더는 순수 함수로 빼서 단위 시험하고, 그래도 실행할 수 없는 줄은 커버리지 제외 후보로 사람에게 묻는다(J138). Next.js 16이면 미들웨어 파일 이름이 다르다(J122).
- **실공급자 형식(V-2).** 가짜 공급자는 계약 9절 형식을 흉내 낼 뿐이라 11/2 실공급자 한 턴에서 어댑터 수정이 생길 수 있다(J110).
- **배포 쪽 미검증.** Let's Encrypt 발급 · `renew --dry-run`, SSM · OIDC · Parameter Store 실호출, Actions 실행, EC2 기준선, 외부 프로브, 60초 중단 실측은 샌드박스에서 돌 수 없다(첫 배포 · 3.6 · 리허설 몫).

**열린 질문(사람)**
- 10/13 팀 결정: 도메인 · 인증서(J98), GHCR 공개 여부(J99), 스모크 인증 수단(J100), 외부 프로브 위치(O-2).
- Plan Approval 전에 사람이 고른 것(2026-10-10, 사람 결정 10 ~ 12): 엔진 검증 명령은 J134 (가), Docker가 없으면 host 모드 통과 인정(J133), SCORE 시드 항목 `비정상`(J101). 커버리지 제외 후보(J138)는 실제로 jsdom에서 실행할 수 없는 줄이 생겼을 때 파일 · 사유 · 3.6 실기 확인 항목과 함께 사람에게 묻고, 승인 전에는 제외하지 않는다.
- Plan Approval에서 확인할 해석: 코드 배치(J97 · C8), 새 편차 (u) ~ (y)(J112), ArchUnit 앞당김(J113), 정책 버전 자리(J114), FD 보강 반영 방식(J130), 커버리지 적용 범위와 측정 범위(J107 · J137 · cicd 열림 2), DB 시험 백엔드(J132), 스택 판정 기준(J139), 공급자 주소 기본값 위치(J141).
- 팀장 확인: 등록 직후 T19 꺼짐 착지(J56 대 화면 설계 D-00), last_seen_at 기록 시점(J125), SERVER_RESTART 범위(J126), V-12(J111), 임시 문구(J118).
- 실제 아동 데이터 운영 전(D-14, 사람 결정 9, 열어 둠): Q-2 백업 · 삭제 충돌, 정식 금지 표현 사전 v1(OQ15), S-9 · S-10 · S-11 · S-13(기기 토큰 서버 만료 없음), V-10 연결 행 계정 삭제(U3 · U4), V-7 로컬 FAILED failureCode(U4).
- D 담당: 승인 음성 녹음 · 승인 방법(J102), AI 입출력 계약(OQ4 · OQ14).
- J1 ~ J144는 모두 가정이다(사람 결정 8). 사람이 승인 · 정정한 것만 확정으로 본다.
