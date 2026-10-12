# U2 보호자 계정 — 이 PC 작업자 지시서 (2026-10-12)


작업 트리: `C:\Users\baikj\Desktop\mujoong-w2`, 브랜치 `unit/u2-guardian-account`. 같은 시간에 U3(Realtime 음성 · 기기 WSS · 아이 기기 화면 T19)를 다른 작업자가 만든다. U3 범위와 `frontend/src/device/`는 건드리지 않는다.

## 범위 (unit-of-work.md의 U2 절이 정본)
- **GuardianIdentity**: 구글 OAuth 로그인(`POST /api/auth/google`, 서버가 code를 교환), Spring Session JDBC 세션 쿠키 `MUJOONG_SESSION`, 상태 변경 요청의 CSRF 헤더, 만료 401이면 01로, 접근 범위, 삭제 접수된 계정 차단. 실제 구글 클라이언트 값과 리다이렉트 주소는 아직 없다(팀이 정함). 설정 이름만 두고, 시험은 가짜 토큰 엔드포인트로 한다. U1의 dev 시험 로그인은 그대로 둔다.
- **ConsentPolicy**: 약관 정책 버전, 동의 4항목 모두 필수, 버전이 바뀌면 OUTDATED로 재동의, 활동 시작 게이트가 쓸 응답(U4가 부를 포트).
- **ChildProfile**: 계정당 아이 1명(1:1), 입력 검증, 종료 표현(문장 전체 2~20자, 추가 5개, 빈 값 · 중복 금지).
- **ApprovedContentCatalog**: 승인 문구 · 콘텐츠 버전 카탈로그(U1 참조 시드 위에).
- **AccountDeletion**: 접수형 계정 삭제(`DELETE /api/me` 계열), 즉시 차단, 각 유닛이 자기 데이터 삭제 참가자를 등록하는 지휘 틀, Realtime으로 DeletionRequested 발행 · DeletionCompleted 수신. 참가자는 U2 소유 데이터만 이번에 구현한다.
- **DeviceRegistry 확장**: 05 · 04에 보여 줄 기기 등록 줄 + 네트워크 줄 + 판정 카드를 합친 응답.
- **화면(/parent)**: 01 로그인, 02 동의(4항목 모두 필수), 02-1 약관 본문(생성형 AI 안내 포함), P5, 03 아이 정보(1명만, 처음 등록 · 보기 · 수정 한 화면), 04 홈(아이 구역 · 대화 기기 구역. '지금 활동' 구역은 틀만, 탭 잠금), SET0 설정, P7, 05 우리 집 기기(두 줄 + 판정 카드), SET-01 종료 표현.
- 05에 "기기 다시 등록" 진입 단추는 화면 설계에 없다. 만들지 말고 report.md의 열린 질문(OQ12)에 남긴다.

## 정해 둔 값
- 마이그레이션: core 스키마에 `V2__u2_guardian_account.sql`(필요하면 V2_1, V2_2처럼 V2 아래로 쪼갠다). realtime 스키마는 건드리지 않는다(U3가 realtime V2를 쓴다).
- 시험 태그 `u2`, 커버리지 범위 `backend/coverage-scope/u2.txt` · `frontend/coverage-scope/u2.json`.
- 문서: `docs/units/u2/design.md`, `docs/units/u2/report.md`.

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
