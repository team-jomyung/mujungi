# U4a 활동 세션 Core 쪽 — 조정자 PC 작업자 지시서 (2026-10-12)


작업 트리: `C:\Users\baikj\Desktop\mujoong-w4`, 브랜치 `unit/u4a-activity-core`(U2가 합쳐진 main에서 시작). 같은 시간에 U3(Realtime 음성 · 기기 WSS · /device · realtime 스키마 V2)를 다른 작업자가 클라우드에서 만든다. U3 범위와 Realtime 앱(`backend/realtime-activity`)은 이번에 건드리지 않는다.

## 왜 U4를 나누나
AI-DLC 유닛 U4(활동 세션)는 뒤 유닛(U5 · U6 · U7)이 모두 기대는 가장 큰 유닛이다. 그중 Realtime 쪽 세션 상태 기계(SessionLifecycle)는 U3가 만드는 음성 경로와 맞물려 U3가 합쳐진 뒤 U4b로 한다. 이번에는 Core 쪽과 보호자 화면만 만든다(U4a). Realtime과 주고받는 것은 이미 정해진 계약(contracts/internal-api.yaml의 C8, events.asyncapi.yaml의 SessionStateChanged · SessionRecordCaptured)만 쓴다.

## 범위 (unit-of-work.md U4 절, components.md, 스토리 US3.1 · US3.2 · US3.3 · US3.4 · US3.5 · US3.6 · US6.3 · US6.6이 정본)
- **ActivityCoordinator(Core)**: 활동 시작 조건과 거절 사유 5종(stories AC3.1.6 · mockups 이름), 08 배경 네 칸 저장(결과 구조화 입력 전용, 대화 모델에는 보내지 않음), 세션 패키지 구성(아이 문맥 · 종료 표현 스냅숏 · 승인 문구 버전 · 동의 스냅숏, U2가 만든 포트 사용), 끝내기 전달, 12 진행 조회 중계(sinceSeq 증분, 계약 C8), Core 세션 사본(SessionStateChanged 반영). U1이 만든 최소 경로를 완성한다.
- **ActivityRecord(Core)**: SessionRecordCaptured 스냅숏 수신 · 저장(멱등, 해시 확인), ActivityRecorded 앱 내부 사건, 26 · 27 대화 기록 조회 API(판정값 · 구조화 결과 · 보호자 입력 원문은 응답에서 뺀다). DECLINED는 머리 정보만.
- **화면(/parent)**: 08 자유대화 시작(네 칸), 12 진행(자유대화 · 공통 상태 줄 · 말풍선 · 끊김 점선 · 2초 폴링), P1 끝내기 확인, P2 · P3(화면 설계의 해당 팝업), 17 활동 끝(마무리 중 · 종료 사유 한 줄 · 정리 중 기본 표시. 정리 끝 · 정리 실패와 27 화면은 U6 몫이니 틀만), 04 '지금 활동' 구역(진행 중 탭 → 12).
- **삭제 참가자(Core 쪽)**: 세션 사본 · 활동 기록 · 08 배경을 U2의 삭제 지휘 틀에 참가자로 등록. 진행 세션의 CONSENT_WITHDRAWN 종료 요청은 Core에서 Realtime으로 보내는 쪽까지만(Realtime 처리는 U4b).
- 연습 경로(연습 시작 · 단계 표시)는 U5가 확장한다. 이번에는 확장점만 남긴다.

## 정해 둔 값
- 마이그레이션: core 스키마에 `V3__u4a_activity_core.sql`(필요하면 V3 아래로 쪼갠다). V2는 U2가 이미 썼다. realtime 스키마는 건드리지 않는다(U3가 V2, U4b가 나중에 V3).
- 시험 태그 `u4a`, 커버리지 범위 `backend/coverage-scope/u4a.txt` · `frontend/coverage-scope/u4a.json`.
- 문서: `docs/units/u4a/design.md`, `docs/units/u4a/report.md`.
- U2가 남긴 열린 일 중 U4 몫(AC2.3.7 종료 표현 세션 스냅숏, 동의 판정을 세션 시작에 연결, 04 진행 중 · 미확인 상태)은 이번에 처리한다. `docs/units/u2/report.md` 6절을 읽고 시작한다.

## 먼저 할 것: U2 후속 9건 (Core 영역이라 네가 맡는다)
U2 3차 리뷰에서 남은 것과 U3 보고서가 Core 쪽에 남긴 것이다. 작은 건들이니 U4a 설계 메모를 쓴 뒤 첫 커밋(`fix(u2): …`)으로 처리하고, 각 건의 재현 시험을 같이 둬.
1. DeletionCommander 128행 근처: 세션 재정리를 'Realtime 요청 전 바퀴'로만 한정했는데, 구글 로그인이 잠금 없이 ACTIVE를 읽은 직후 삭제 접수가 커밋되고 바퀴가 지나간 뒤 세션 행이 저장되면 그 세션이 DeletionCompleted까지 남는다. WAITING 바퀴에서도 실패 격리된 sweep을 유지하거나 다른 방법으로 창을 닫아. AuthController 92행 주석("지휘가 바퀴마다 다시 지운다")도 사실에 맞게 고쳐.
2. DevLoginController 68행 근처: 시험 로그인의 삭제 접수 확인이 잠금 없는 읽기라 접수와 겹치면 세션이 만들어진다. 구글 경로처럼 세션을 연 뒤 상태를 다시 읽고 닫거나 잠금 읽기로.
3. GoogleKeyCache 479행 근처: max-age가 0이면(Cache-Control 없음 · no-store · no-cache) 로그인 한 번에 JWKS를 두 번 받는다. 코드 교환 전에 확보한 키에 kid가 있으면 다시 받지 말고, 캐시에 최소 바닥값을 둬.
4. GoogleKeyCache 467행 근처: keysFor가 synchronized 안에서 HTTP fetch를 해서 캐시 만료 순간 동시 로그인이 전부 직렬로 막힌다. 받는 동안 락을 풀거나 in-flight 요청 하나를 공유해.
5. useBackGuard.ts 69행 근처: 새로고침 뒤 leave()가 이전 문서 가드 칸 위에서 replace 이동해 아래 남은 02 기본 칸이 기록에 남는다(03에서 뒤로가기 → 02). 마운트 때 남의 표시 칸을 이 문서의 기본 칸으로 바꿔 쓰는 등 방침을 정하고 시험을 맞춰.
6. U2SingleSourceTest 875행 근처: 잠금 단일화 아키텍처 시험 정규식이 한 줄 SQL만 잡아 텍스트 블록으로 줄을 나눈 SQL은 통과시킨다. DOTALL 매칭 또는 파일 단위 검사 + 허용 목록으로.
7. GuardianWriteLock 42행 근처: statusForShare만 static이고 나머지는 인스턴스라 호출 모양이 둘이다. 한쪽으로 통일해.
8. (U3 보고서) DeviceRegisterApiIT가 흔들린 원인은 Core 시험 설정의 연결 시한 50ms로 추정된다. `docs/units/u3/report.md`의 수정안대로 운영값(500ms)으로 맞춰.
9. 위 처리 결과를 `docs/units/u2/report.md` "리뷰 반영" 절에 3차 표로 더해.

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
