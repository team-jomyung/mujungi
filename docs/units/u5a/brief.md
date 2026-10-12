# U5a 연습 · Core 쪽 — 처음 보는 작업자(Codex)용 지시서

> 2026-10-12, 조정자(Claude Code 세션)가 썼다. 이 저장소를 처음 보는 작업자가 이 파일 하나로 시작할 수 있게 밑바탕부터 적었다.
> 다 읽은 뒤 4절의 순서대로 진행하고, 8절의 방법으로 넘겨준다.

## 1. 이 프로젝트가 무엇인가

**무중**은 6~12세 아이의 화용 능력(상황에 맞게 말을 주고받는 능력)을 돕는 AI 음성 대화 서비스다. LG CNS AM Inspire Camp 5기 조명팀의 최종 프로젝트이고 11/5에 제출한다.

- **보호자**는 휴대폰 웹 `/parent`에서 로그인 · 동의 · 아이 정보 · 기기 등록을 하고, 아이와 할 **자유대화**나 **연습**을 시작하며, 끝난 활동의 기록과 상담용 리포트를 본다.
- **아이**는 별도 기기의 웹 `/device`에서 말로 AI(무중)와 대화한다. 아이 화면에는 글이 거의 없다.
- **연습**은 보호자가 고른 "연습 목표"(예: 친구가 그만하자고 할 때 받아들이기)마다 상황을 만들어 아이가 반응하게 하고, 도움(Cue)을 주고, 판정한다. 판정값은 보호자에게 보여 주지 않는다.

### 실행 구조
| 단위 | 위치 | 하는 일 |
|---|---|---|
| Next.js 16 한 앱 | `frontend/` | `/parent`(보호자) · `/device`(아이 기기) 두 화면 묶음 |
| Core API (Spring Boot 4, Java 21) | `backend/core-api` | 보호자 API, 계정 · 동의 · 아이 · 기기 등록 · 활동 기록 · 리포트 · **연습 목표** |
| Realtime Activity (Spring Boot 4, Java 21) | `backend/realtime-activity` | 기기 WSS, 음성 중계, 세션 진행, 연습 진행 |
| 공용 계약 타입 | `backend/contract-model` | 앱 사이 메시지 · 오류 코드 타입 |
| 시험 지원 | `backend/test-support` | PostgreSQL 시험 백엔드 등 |
| PostgreSQL 16 | 스키마 `core` · `realtime` | 앱마다 자기 스키마에만 쓴다 |

앱 사이는 내부 HTTP(즉시 명령)와 Outbox → Inbox 이벤트(영속 전달)로만 통신한다. 상대 앱의 스키마를 직접 읽거나 쓰지 않는다.

### 저장소에서 먼저 볼 곳
- `README.md`의 "개발 · 검증" 절: 디렉터리 설명, 로컬 기동, 시험 명령.
- `contracts/`: API · 이벤트 · 오류 · 시간 계약의 정본(OpenAPI · AsyncAPI · JSON Schema · errors.md · timing.md · ports.md · ai-io.md · deviations.md).
- `docs/units/README.md`: 유닛별 담당과 마이그레이션 번호.
- `aidlc/`: 설계 문서가 있는 곳(아래 3절). **읽기만 한다.**

## 2. 지금까지 어떻게 왔나

1. AWS의 AI-DLC 방법론으로 요구사항 → 사용자 스토리 → 목업 → 도메인 설계 → 유닛 나누기 → 계약 → 일정까지 문서를 만들었다(`aidlc/spaces/default/intents/261008-mujoong-service/inception/`).
2. 첫 유닛 **U1 워킹 스켈레톤**(기기 코드 등록 → 홈 → 음성 1턴 → 끝내기)을 끝까지 구현했다. 시험 약 1,800건, 커버리지 80% 이상.
3. 지금은 AI-DLC 엔진 없이, 위 문서를 스펙으로 삼아 유닛별 작업자가 **병렬로** 구현한다.

| 유닛 | 담당 | 상태 |
|---|---|---|
| U2 보호자 계정(로그인 · 동의 · 아이 · 종료 표현 · 계정 삭제 · /parent 01~05) | Claude(조정자 PC) | 완료, 리뷰 반영 후 main에 합침 |
| U3 음성 대화(기기 WSS · 음성 중계 · 출력 게이트 · /device) | Claude(클라우드) | 진행 중, realtime 스키마 V2 |
| U4a 활동 세션 Core 쪽(시작 · 진행 조회 · 활동 기록 · 08/12/17 화면) | Claude(조정자 PC) | U2 다음, core 스키마 V3 |
| **U5a 연습 Core 쪽** | **너** | **이 지시서**, core 스키마 V4 |
| U4b · U5b · U6 · U7 | 나중 | U3 · U4가 합쳐진 뒤 |

## 3. 스펙: 어디에 무엇이 있나 (충돌하면 앞쪽이 이긴다)

기록 폴더를 `R = aidlc/spaces/default/intents/261008-mujoong-service/`라고 부른다.

1. **화면 설계 v1.5j**(최우선): `aidlc/spaces/default/knowledge/documents/` 아래 `10_` 묶음. 읽는 순서는 같은 폴더의 `00_읽는순서.md`. 너에게 필요한 화면은 **18 내 연습 목표**다.
2. 승인된 설계 문서:
   - `R/inception/units-generation/unit-of-work.md`의 **U5 절**, `unit-of-work-story-map.md`의 U5 스토리(US4.x)
   - `R/inception/user-stories/stories.md`의 US4.1(18 목표 목록) 등 연습 스토리와 수용 기준(AC)
   - `R/inception/refined-mockups/mockups.md` · `interaction-spec.md`의 18 화면
   - `R/inception/domain-design/components.md`의 **PracticeGoal · PragmaticsKnowledge · TextAiGateway**와 소유 엔터티, `decisions.md`의 ADR-011 · 012 · 016 · 018
   - `R/inception/contract-design/contract-summary.md`의 C4(보호자 연습 REST) · C10(Realtime이 다음 목표 상황을 받는 내부 HTTP) · C14(AI 입출력 뼈대)
   - `R/inception/requirements-analysis/requirements.md`의 FR4.x · FR7.4 · FR7.5 · NFR1 · NFR3
3. 계약 정본 `contracts/`(`parent-api.yaml`, `internal-api.yaml`, `ai-io.md`, `errors.md`, `timing.md`, `deviations.md`).
4. 이미 있는 코드와 U1 · U2 설계: `R/construction/u1-walking-skeleton/`(functional-design/rules.md · entities.md, code-generation/code-summary.md), `docs/units/u2/`(design.md · report.md). 패키지 구조, 오류 봉투, 로깅 규칙, 시험 방식은 여기서 정한 대로 따른다. **코드를 먼저 읽고 맞춘다.**

문서가 많으니 통째로 읽지 말고 `grep`으로 위치를 찾아 필요한 절만 읽는다.

## 4. 네가 만들 것: U5a

AI-DLC 유닛 U5(연습)는 U2 · U3 · U4에 기대지만, 그중 **Core 쪽**은 계약만 있으면 지금 만들 수 있다. 이번에는 그 Core 쪽만 만든다. Realtime 쪽 연습 진행(PracticeProtocol · ResponseEvaluator)과 연습 시작 경로는 U4가 합쳐진 뒤 U5b로 따로 한다.

- **PragmaticsKnowledge**: 행동 정의 카탈로그 `core_behavior_definition`(behavior_key + version + 4요소: 행동 정의 · 기회 조건 · 상황 생성 조건 · 판정 기준, status). 한 번 쓴 버전은 고치지 않고 새 버전을 만든다(ADR-012). 생활 팁 목록 조회. 데모용 초기 시드(목표 2~3개). Neo4j는 원장이 아니다: 조회 어댑터를 포트 뒤에 두고 기본은 꺼 둔다(설정 키로 켬). 시험에 Neo4j를 쓰지 않는다.
- **TextAiGateway**: 사후 텍스트 AI 호출의 한 출입구. 최소 정보만 보냄, 금지 표현 검사, 모델 · 프롬프트 버전 기록, 재시도. 실제 공급자 키는 없다: 공급자 포트 + 시험용 가짜 구현을 두고, 실제 구현은 설정 이름과 환경 변수 이름만 둔다.
- **PracticeGoal**: 18 '내 연습 목표' — 담은 목표, 즐겨찾기, 순서(담은 순 · 즐겨찾기 가중), 소프트 삭제와 복구. 목표 후보 등록은 자동이고 18에 쌓는 것은 보호자의 「담기」다(ADR-018 21번). 연습 상황 생성 · 자동 확인(목표 · 트리거 포함, 금지 표현) · 실패하면 1회 재생성 · 두 번 실패하면 시작하지 않음 · 이전에 노출된 상황 재사용 금지(FR7.5 · ADR-016). Realtime이 다음 목표 상황을 받는 내부 HTTP(C10)의 Core 쪽 제공.
- **화면 18(`/parent/practice`)**: 목록 · 빈 상태 · 즐겨찾기 · 삭제 · 순서, 화면 설계 그대로. U1이 만든 자리 페이지를 채운다. `/parent` 공용 파일(네비게이션 · 공용 컴포넌트)은 되도록 고치지 말고 새 파일로 만든다. 꼭 고쳐야 하면 최소한으로 고치고 report에 적는다.
- **삭제 참가자**: 네가 만드는 Core 표(후보 · 선택 · 상황 · 노출 등)를 U2의 계정 삭제 지휘 틀에 참가자로 등록한다(`docs/units/u2/design.md`의 삭제 절 참고).
- 아이 정보가 필요하면 U2가 만든 포트(`ChildProfileService` 쪽)로만 읽는다. `kr.mujoong.core.identity` 클래스는 고치지 않는다.

### 정해 둔 값
- 마이그레이션: core 스키마에 `V4__u5a_practice_core.sql`(필요하면 V4 아래로 쪼갠다). **V2는 U2, V3은 U4a 몫이라 쓰지 않는다.** realtime 스키마는 건드리지 않는다.
- 시험 태그 `u5a`, 커버리지 범위 `backend/coverage-scope/u5a.txt` · `frontend/coverage-scope/u5a.json`(U1 · U2 파일과 같은 형식).
- 문서: `docs/units/u5a/design.md`, `docs/units/u5a/report.md`.

### 진행 순서
1. **main을 받는다.** main에 `docs/units/u2/report.md`가 있어야 U2가 합쳐진 것이다. 없으면 설계 문서(아래 2번)까지만 쓰고, U2가 합쳐진 main을 다시 받은 뒤 구현을 시작한다.
2. `docs/units/u5a/design.md`를 먼저 짧게 쓴다: 포함 스토리, 엔터티와 마이그레이션, API(계약 경로 그대로), 업무 규칙(AC 번호와 연결), 화면과 상태, 시험 계획, 가정 목록. 네가 정한 가정은 "준하 제안(가정) U5a-J<번호>"로 번호를 붙인다.
3. 층마다 구현한 뒤 그 층의 시험을 쓰고 돌린다(데이터 → 저장소 → 업무 로직 → API → 화면). 층이 끝날 때마다 커밋한다.
4. 계약과 다르게 해야 하면 더하기만 안전한 변경으로 하고 `contracts/deviations.md`에 기호(U5a-a, b …)와 이유를 더한다. 깨는 변경은 하지 말고 report에 남긴다.
5. 끝나면 5절의 시험을 모두 돌리고 `docs/units/u5a/report.md`를 쓴다: 만든 것, 시험 결과(건수 · 커버리지), 계약 편차, 가정, 남은 일과 막힌 것.

## 5. 코드 규칙과 시험

- 패키지: `kr.mujoong.core.<컴포넌트>`(예: `kr.mujoong.core.practice`, `kr.mujoong.core.knowledge`, `kr.mujoong.core.textai`). 컴포넌트 사이 순환 금지, 다른 컴포넌트의 저장소를 직접 쓰지 않음(ArchUnit 구조 시험이 검사한다).
- 오류 응답은 계약의 오류 봉투 `{code, message, traceId}`. 새 오류 코드는 `contracts/errors.md`와 `ErrorCode`에 같이 더한다.
- 로그에 전사 원문 · 원음 · 비밀 값 · 보호자 입력 원문을 남기지 않는다(U1 로깅 규칙).
- 시간 값(재시도 간격 등)은 코드에 박지 않고 설정 키로(`contracts/timing.md`, 기존 자동 검사가 있다).
- 시험: 층마다 정상 경로 + 오류 · 경계 경우 두 개 이상. 항상 통과하는 시험 금지. 유닛 범위 줄 커버리지 80% 이상, **U1 · U2 시험을 깨지 않는다**(커버리지 하한 · 단언을 낮추지 않는다).
- 명령(저장소 루트 기준):
  - 백엔드 전체 회귀: `cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test`
  - U5a 시험 + 커버리지(Core만): `cd backend && ./gradlew --no-daemon :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=u5a -PcoverageScope=u5a` (커버리지 범위 옵션은 `:core-api` 작업에만 건다. 전체에 걸면 다른 모듈 설정 단계에서 실패한다)
  - 프런트: `cd frontend && npm ci` 한 번, `MUJOONG_COVERAGE_SCOPE=u5a npx --no-install vitest run tests/u5a --coverage`, 회귀 `npx --no-install vitest run tests/u1 tests/u2`, `npx --no-install tsc --noEmit`, `npx --no-install eslint .`

## 6. 환경 준비

### Codex 클라우드(리눅스)에서 할 때
- JDK 21(Temurin), Node 22, PostgreSQL 16을 설치한다. 예: Ubuntu면 PGDG 저장소에서 `postgresql-16`. 서버를 띄울 필요는 없다(시험이 바이너리로 직접 띄운다).
- Docker가 없으면 DB 시험을 호스트 PostgreSQL로 돌린다: Gradle 명령에 `-Pmujoong.test.db=local`을 붙인다(바이너리 경로 기본값 `/usr/lib/postgresql/16/bin`). root로 돌면 저장소 경로를 `postgres` 사용자가 지나갈 수 있어야 한다(README 참고).
- `scripts/verify.sh`는 돌리지 않는다(Docker 스택이 필요하고 조정자가 따로 돌린다).

### 조정자 PC(Windows)의 Codex CLI로 할 때
- 작업 폴더는 `C:\Users\baikj\Desktop\mujoong-w5`(브랜치 `unit/u5a-practice-core`)를 쓴다. 시작할 때 `git merge main`으로 최신 main을 받는다.
- JDK 21은 `C:\Users\baikj\.jdks\jdk-21.0.12.1+1`(JAVA_HOME으로 지정). Docker Desktop이 있어 Testcontainers로 DB 시험이 돈다.
- 포트 3000 · 5432 · 8081 · 8443 · 9090 · 9091 · 18080 · 18090을 고정으로 쓰지 않는다(데모 스택이 돌고 있다).

## 7. 하지 말 것
- `aidlc/` 아래 파일 수정, `aidlc` 명령 실행. 이 문서들은 읽기만 한다.
- U2 · U3 · U4a 범위 구현(계정 · 동의 · 아이 · 기기 · 음성 · 세션 진행 · 08/12/17 화면). 필요하면 계약에 정해진 포트까지만.
- `README.md` · `contracts/README.md` 수정. 유닛 문서는 `docs/units/u5a/` 아래에.
- 비밀 값(키 · 비밀번호 · 토큰 · 실제 도메인)을 코드 · 설정 · 시험 · 문서 · 커밋 어디에든 쓰는 것. 환경 변수 이름만 쓴다.
- main에 직접 푸시.

## 8. 넘기는 법
- 작업 브랜치에서 층마다 커밋하고(메시지 `feat(u5a): …`, 고침은 `fix(u5a): …`), 끝나면 main으로 가는 PR(초안 가능)을 연다. 클라우드면 브랜치를 푸시하고 PR을 열고, 조정자 PC의 CLI면 커밋만 하고 푸시하지 않는다.
- PR 설명과 마지막 응답에 report.md 요약을 한국어로 쓴다.
- 조정자가 리뷰한 뒤 고칠 점을 돌려주면 같은 브랜치에서 고친다.
