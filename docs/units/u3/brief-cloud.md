# U3 음성 대화 — 클라우드 세션용 지시서

> 조정자가 2026-10-12에 쓴 지시서다. 이 파일을 읽은 세션은 아래를 끝까지 수행한다. AI-DLC 엔진(`/aidlc`)은 쓰지 않는다. 같은 시간에 U2는 다른 PC에서, U5의 Core 쪽은 또 다른 작업자가 만든다.

작업 위치: 이 클라우드 세션의 저장소와 지정 브랜치(main에서 시작). 같은 시간에 U2(보호자 계정 · /parent 화면 · Core 계정 · 동의 · 아이 · 삭제)를 다른 작업자가 만든다. U2 범위와 `frontend/src/parent/`는 건드리지 않는다.

사용 한도가 있으니 문서를 통째로 다시 읽지 말고 필요한 절만 찾아 읽는다(Grep으로 위치를 찾고 Read에 offset · limit을 쓴다). 같은 시험을 이유 없이 반복해서 돌리지 않는다. 층이 끝날 때마다 커밋해서 상한에 걸려도 작업이 남게 한다.

## 범위 (unit-of-work.md의 U3 절이 정본)
- **DeviceGateway**: 기기 WSS(계약 `contracts/device-ws.asyncapi.yaml`), 연결 5단계 판정(안정 · 불안정 · 끊김 · 응답 없음 · 꺼짐, 시간값은 계약 timing과 요구사항 NFR6), 상태 보고(Core 내부 HTTP), 명령 ACK · PLAYBACK_MARK, 같은 토큰의 새 연결이 옛 연결을 대체.
- **VoiceRelay**: 공급자 실시간 음성 세션 중계(B안), 대화 지시, 끼어들기 취소, 동의된 세션의 원음 파일 저장(저장 위치는 설정 키로, 기본값은 개발용 로컬 디렉터리). 실제 공급자 키는 없다. 시험과 개발은 `tools/fake-provider`(계약 `contracts/ai-io.md`)로 한다.
- **OutputGate**: 아이에게 나가는 모든 소리는 승인된 것만(승인 콘텐츠 또는 게이트를 통과한 AI 말). 금지 표현 시드 사전(범주별 최소 1항목, SCORE 범주는 '비정상').
- **ConversationLog**: 전사 조립(아이 발화 · 승인된 AI 말, 확정 전 조각), 불확실 표시, 끊김 구간.
- **DialogPolicy**: 대화 역할 지시, 아이 참여 판단(참여 대기 20초 · 재안내 · 20초), 종료 의도 판단(종료 표현 스냅숏 사용), 역할 지식 분리(판정 기준이 대화 역할로 새지 않게).
- **DeviceWeb T19(/device)**: 아이 기기 화면의 상태(대기 · 듣는 중 · 말하는 중 · 연결 중 · 꺼짐 등), 켜기 · 끄기, 끊김 때 재접속, 끼어들기 로컬 감지.
- U4(활동 세션 상태 기계 · 08 · 12 · 17 화면 완성)와 U5(연습)는 만들지 않는다. U1이 만든 세션 경로 위에서 U3 컴포넌트를 완성하는 것이 범위다.

## 정해 둔 값
- 마이그레이션: realtime 스키마에 `V2__u3_voice_conversation.sql`(필요하면 V2 아래로 쪼갠다). core 스키마는 건드리지 않는다(U2가 core V2를 쓴다).
- 시험 태그 `u3`, 커버리지 범위 `backend/coverage-scope/u3.txt` · `frontend/coverage-scope/u3.json`.
- 문서: `docs/units/u3/design.md`, `docs/units/u3/report.md`.

## 공통 규칙 (U2 · U3 작업자 모두)

너는 무중(Mujoong) 저장소의 유닛 하나를 맡은 개발자다. 지금 있는 git 작업 트리와 브랜치에서 그 유닛을 끝까지 구현하고 시험을 통과시킨 뒤 커밋하고 멈춘다. 같은 시간에 다른 작업자가 다른 작업 트리에서 다른 유닛을 만든다. 조정자가 끝난 브랜치를 리뷰해 main에 합친다.

무중은 6~12세 아이의 화용 능력을 돕는 AI 음성 대화 서비스다. 실행 단위는 Next.js 한 앱(/parent 보호자 · /device 아이 기기), Spring Boot 둘(Core API · Realtime Activity), PostgreSQL(core · realtime 스키마)이다. U1 워킹 스켈레톤(기기 코드 등록 → 홈 → 음성 1턴 → 끝내기)은 이미 구현돼 main에 있다.

### 하지 말 것
- `/aidlc` 실행, `aidlc` 엔진 명령, `aidlc/` 아래 파일 수정 금지. AI-DLC 문서는 읽기만 한다. 이 작업은 엔진 없이 직접 구현한다.
- `scripts/verify.sh`는 마지막에 한 번만 돌린다(아래 '클라우드 환경' 참고).
- 푸시는 이 세션에 지정된 작업 브랜치에만 한다. main에는 직접 푸시하지 않는다. 끝나면 main으로 가는 draft PR을 연다.
- `README.md` · `contracts/README.md` 수정 금지. 유닛 문서는 `docs/units/<유닛>/` 아래에 쓴다.
- 비밀 값(키 · 비밀번호 · 토큰 · 실제 도메인)을 코드 · 설정 · 시험 · 문서 · 커밋 어디에도 쓰지 않는다. 환경 변수 이름만 쓴다.
- U1 시험을 깨거나, 커버리지 하한 · 단언을 낮추지 않는다. 고칠 수 없으면 그대로 두고 보고한다.
- 다른 유닛 범위를 구현하지 않는다. 필요하면 계약에 정해진 포트 · 확장점까지만.
- 하위 에이전트를 여러 개 띄워 리뷰를 돌리지 않는다. 리뷰는 조정자(다른 PC의 Claude Code 세션)가 한다.

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

### 클라우드 환경
- 리눅스 컨테이너다. 시작할 때 `.claude/settings.local.json`을 만들어 `{"disableAllHooks": true}`만 넣는다(gitignore 대상, 커밋 금지). `aidlc`는 설치하지 않는다. 훅 오류가 보이면 무시한다.
- JDK 21 · Node 22 · Docker가 없으면 설치하거나 켠다(Testcontainers와 검증 명령이 Docker를 쓴다). Gradle은 저장소의 래퍼를 쓴다.
- 구현과 시험이 끝나 커밋한 뒤, 작업 트리가 깨끗한 상태에서 `bash scripts/verify.sh`(전체, compose 모드)를 한 번 돌려 U1 끝-끝 한 바퀴가 여전히 통과하는지 확인하고 결과를 report.md에 적는다. 실패하면 원인을 고치거나 report.md에 남긴다.
- 사용 한도에 걸려 멈췄다가 이어갈 때는 `git log`와 `docs/units/u3/`를 먼저 보고 남은 층부터 계속한다.
- 대화와 문서는 한국어, 코드 식별자는 영어.
