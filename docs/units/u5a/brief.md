# U5a 연습 Core 쪽 — API 크레딧 작업자 지시서 (2026-10-12)


작업 트리: `C:\Users\baikj\Desktop\mujoong-w5`, 브랜치 `unit/u5a-practice-core`. 같은 시간에 U2(보호자 계정, Core 계정 · 동의 · 아이 · 삭제 · /parent 01~05 화면)와 U3(Realtime 음성 · 기기 WSS · /device)를 다른 작업자가 만든다. 그 범위는 건드리지 않는다.

이 작업은 API 크레딧으로 돌고 지출 상한이 있다. 문서를 통째로 다시 읽지 말고 필요한 절만 찾아 읽는다(Grep으로 위치를 찾고 Read에 offset · limit을 쓴다). 같은 시험을 이유 없이 반복해서 돌리지 않는다. 층이 끝날 때마다 커밋해서 상한에 걸려도 작업이 남게 한다.

## 왜 U5를 나누나
AI-DLC 유닛 U5(연습)는 U2 · U3 · U4에 기대지만, 그중 Core 쪽(목표 · 상황 · 행동 정의 · 텍스트 AI 출입구 · 18 화면)은 계약만 있으면 먼저 만들 수 있다. 이번에는 그 Core 쪽만 만든다(U5a). Realtime 쪽 연습 진행(PracticeProtocol · ResponseEvaluator)과 연습 시작 경로는 U4가 합쳐진 뒤 U5b로 따로 한다.

## 범위 (unit-of-work.md U5 절과 components.md가 정본)
- **PragmaticsKnowledge**: 행동 정의 카탈로그 `core_behavior_definition`(behavior_key + version + 4요소: 행동 정의 · 기회 조건 · 상황 생성 조건 · 판정 기준, status). 한 번 쓴 버전은 고치지 않고 새 버전을 만든다(ADR-012). 생활 팁 목록 조회. 초기 시드(데모용 목표 2~3개). Neo4j는 원장이 아니다: 조회 어댑터를 포트 뒤에 두고 기본은 꺼 둔다(설정 키로 켬). 시험에 Neo4j 컨테이너를 쓰지 않는다.
- **TextAiGateway**: 사후 텍스트 AI 호출의 한 출입구. 최소 정보만 보냄, 금지 표현 검사, 모델 · 프롬프트 버전 기록, 재시도. 실제 공급자 키는 없다: 공급자 포트 + 시험용 가짜 구현, 실제 구현은 설정 이름만 두고 키는 환경 변수 이름만.
- **PracticeGoal**: 18 '내 연습 목표' — 담은 목표, 즐겨찾기, 순서(담은 순 · 즐겨찾기 가중), 소프트 삭제. 목표 후보 등록은 자동이고 18에 쌓는 것은 보호자 「담기」(ADR-018 21번). 연습 상황 생성 · 자동 확인(목표 · 트리거 포함, 금지 표현) · 실패 시 1회 재생성 · 두 번 실패면 시작 안 함 · 이전 노출 상황 재사용 금지(FR7.5 · ADR-016). Realtime이 다음 목표 상황을 받는 내부 HTTP(계약 C10, `contracts/internal-api.yaml`)의 Core 쪽 제공.
- **화면 18(/parent/practice)**: 목록 · 빈 상태 · 즐겨찾기 · 삭제 · 순서, 화면 설계 v1.5j 그대로. U1이 만든 자리 페이지를 채운다. `/parent`의 공용 파일(네비게이션 · 공용 컴포넌트)은 되도록 고치지 말고 새 파일로 만든다. 꼭 고쳐야 하면 최소한으로 고치고 report.md에 적는다(U2와 합칠 때 충돌 지점).
- 아이 정보가 필요하면 U1의 core_child를 읽는 포트로만 쓴다. `kr.mujoong.core.identity` 클래스는 고치지 않는다(U2가 고치는 중).

## 정해 둔 값
- 마이그레이션: core 스키마에 `V3__u5a_practice_core.sql`(필요하면 V3 아래로 쪼갠다). V2는 U2 몫이라 쓰지 않는다. realtime 스키마는 건드리지 않는다.
- 시험 태그 `u5a`, 커버리지 범위 `backend/coverage-scope/u5a.txt` · `frontend/coverage-scope/u5a.json`.
- 문서: `docs/units/u5a/design.md`, `docs/units/u5a/report.md`.

## 공통 규칙 (U2 · U3 작업자 모두)

너는 무중(Mujoong) 저장소의 유닛 하나를 맡은 개발자다. 지금 있는 git 작업 트리와 브랜치에서 그 유닛을 끝까지 구현하고 시험을 통과시킨 뒤 커밋하고 멈춘다. 같은 시간에 다른 작업자가 다른 작업 트리에서 다른 유닛을 만든다. 조정자가 끝난 브랜치를 리뷰해 main에 합친다.

무중은 6~12세 아이의 화용 능력을 돕는 AI 음성 대화 서비스다. 실행 단위는 Next.js 한 앱(/parent 보호자 · /device 아이 기기), Spring Boot 둘(Core API · Realtime Activity), PostgreSQL(core · realtime 스키마)이다. U1 워킹 스켈레톤(기기 코드 등록 → 홈 → 음성 1턴 → 끝내기)은 이미 구현돼 main에 있다.

### 하지 말 것
- `/aidlc` 실행, `aidlc` 엔진 명령, `aidlc/` 아래 파일 수정 금지. AI-DLC 문서는 읽기만 한다. 이 작업은 엔진 없이 직접 구현한다.
- `scripts/verify.sh` 실행 금지(리눅스 전용이다. 통합 검증은 조정자가 따로 한다).
- 포트 3000 · 5432 · 8081 · 8443 · 9090 · 9091 · 18080 · 18090을 고정으로 쓰지 않는다. 이 PC에서 데모 스택이 돌고 있다. 시험은 무작위 포트와 Testcontainers로.
- `git push` 금지. 커밋만 한다.
- `README.md` · `contracts/README.md` 수정 금지. 유닛 문서는 `docs/units/<유닛>/` 아래에 쓴다.
- 비밀 값(키 · 비밀번호 · 토큰 · 실제 도메인)을 코드 · 설정 · 시험 · 문서 · 커밋 어디에도 쓰지 않는다. 환경 변수 이름만 쓴다.
- U1 시험을 깨거나, 커버리지 하한 · 단언을 낮추지 않는다. 고칠 수 없으면 그대로 두고 보고한다.
- 다른 유닛 범위를 구현하지 않는다. 필요하면 계약에 정해진 포트 · 확장점까지만.
- 하위 에이전트를 여러 개 띄워 리뷰를 돌리지 않는다. 리뷰는 조정자가 한다.

### 기준 (충돌하면 앞쪽이 이긴다)
1. 화면 설계 v1.5j: `aidlc/spaces/default/knowledge/documents/` 아래 `10_` 묶음(읽는 순서는 같은 폴더의 `00_읽는순서.md`).
2. 승인된 AI-DLC 산출물. 기록 폴더 `R = aidlc/spaces/default/intents/261008-mujoong-service/`:
   - `R/inception/units-generation/unit-of-work.md`(자기 유닛 절), `unit-of-work-story-map.md`(자기 유닛 스토리), `unit-of-work-dependency.md`
   - `R/inception/user-stories/stories.md`(해당 US와 AC), `R/inception/refined-mockups/mockups.md` · `interaction-spec.md` · `design-system-mapping.md`
   - `R/inception/domain-design/components.md`(자기 유닛 컴포넌트와 소유 엔터티), `decisions.md`(ADR-011~019)
   - `R/inception/contract-design/contract-summary.md`(자기 유닛 계약)
   - `R/inception/requirements-analysis/requirements.md`(FR · NFR)
3. 계약 정본 파일 `contracts/`(OpenAPI · AsyncAPI · JSON Schema · errors · timing · ports · ai-io · deviations).
4. U1 구현과 그 설계: `R/construction/u1-walking-skeleton/`(functional-design/rules.md · entities.md, nfr-design/, infrastructure-design/, code-generation/code-summary.md). 패키지 구조, 오류 봉투, Outbox/Inbox, 로깅 규칙(전사 원문 · 원음 · 비밀을 로그에 남기지 않음), 시험 태그, 커버리지 범위 파일, ArchUnit 구조 시험 등 U1이 정한 방식을 그대로 따른다. 이미 있는 코드를 먼저 읽고 맞춘다.

### 방법
1. 먼저 `docs/units/<유닛>/design.md`를 짧게 쓴다: 포함 스토리, 엔터티와 마이그레이션, API(계약 경로 그대로), 업무 규칙(AC 번호와 연결), 화면과 상태, 시험 계획, 가정 목록. 네가 정한 가정은 "준하 제안(가정) <유닛>-J<번호>"로 번호를 붙인다.
2. 계약과 다르게 해야 하면 더하기만 안전한 변경으로 하고 `contracts/deviations.md`에 기호와 이유를 더한다. 깨는 변경은 하지 말고 보고한다.
3. DB는 자기 스키마에 새 마이그레이션 파일만 더한다(V1은 손대지 않는다). 버전 번호는 아래 유닛 절에 정한 것을 쓴다.
4. 층마다 구현한 뒤 그 층 시험을 쓰고 돌린다. 시험 태그는 유닛 이름(`u2` · `u3`)으로, 범위 파일은 `backend/coverage-scope/<유닛>.txt` · `frontend/coverage-scope/<유닛>.json`을 U1과 같은 형식으로 만든다. 유닛 범위 줄 커버리지 80% 이상.
5. 명령(저장소 루트 기준, Git Bash):
   - U1 회귀: `cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1`
   - 자기 유닛 시험과 커버리지: 위 명령에서 `u1`을 자기 유닛으로 바꾸고 `:모듈:jacocoTestCoverageVerification -PcoverageScope=<유닛>`을 더한다(README의 U1 예시와 같은 모양).
   - 프런트: `cd frontend && npm ci` 한 번, 그다음 `MUJOONG_COVERAGE_SCOPE=<유닛> npx --no-install vitest run tests/<유닛> --coverage`, 회귀는 `npx --no-install vitest run tests/u1`, 타입 검사 `npx --no-install tsc --noEmit`, 린트 `npx --no-install eslint .`
   - tools 시험이 필요하면 `cd tools && npm ci && npx --no-install vitest run`
6. 커밋은 층 단위로 작게, 메시지는 `feat(<유닛>): …` 모양(한국어 설명 가능). 작업 도중 멈출 수 있으니 층이 끝날 때마다 커밋한다.
7. 끝나면 `docs/units/<유닛>/report.md`에 만든 것, 시험 결과(건수 · 커버리지), 계약 편차, 가정 목록, 남은 일과 막힌 것을 쓰고 커밋한다. 마지막 응답에도 같은 요약을 한국어로 쓴다.

### 환경
- Windows 11 + Git Bash. JAVA_HOME은 JDK 21. Node 24(package.json의 엔진 경고는 무시). Docker Desktop이 돌고 있어 Testcontainers를 쓸 수 있다.
- 셸에서 `/`로 시작하는 인자가 경로로 바뀌는 문제가 나면 `MSYS_NO_PATHCONV=1`을 붙인다.
- 대화와 문서는 한국어, 코드 식별자는 영어.
