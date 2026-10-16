**Collaborator:** aidlc-developer-agent

## Contribution

> 담당: 이름 · 계층/모듈 경계 · 오류 처리 · 파일 구성 · 코드 스타일. 기준 커밋 HEAD `72d7034`(브랜치 `feat/doc-sync-code-update`).
> 리드 초안 `team-practices.md` 「Code Style」 · `discovered-rules.md` R-F4 · R-F5를 저장소에서 다시 확인했고, 아래는 통합할 사실 · 정정 · 빠진 후보다.

### 1. 서식 · 포매터 (TD-5) — 원인 설명 정정과 측정값

- **Prettier는 실행되더라도 Java를 다루지 않는다.** `.prettierignore`가 `backend/`를 통째로 뺀다(1행 주석 「U1 3.5 Step 2」).
  그래서 U6 백엔드 압축 서식은 "Prettier가 실행되지 않아서"가 아니라 "Java 포매터 · 줄 길이 검사가 처음부터 없어서" 들어왔다.
  Prettier 미실행이 실제로 놓친 것은 프런트 쪽뿐이다(`frontend/src/parent/screens/Report30.tsx` · `ReportRange28.tsx` · `RecordDetail27.tsx` · `frontend/src/parent/api/results.ts`에 200자 넘는 줄이 각 11줄).
- **압축 서식은 길이만의 문제가 아니다.** 예: `backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java`에
  `for(int i=0;i<candidateDrafts.size();i++){var c=…;checks.add(…);}`처럼 연산자 주위 공백 없음 · 한 줄 여러 문장 · 중괄호 붙이기가 함께 나타난다.
  다른 유닛 코드(U1 ~ U5b · U7)는 4칸 들여쓰기 · 한 줄 한 문장 · 연산자 공백을 지킨다.
- **분포(측정, `awk length>N`)**:

  | 대상 | 전체 줄 | >100 | >120 | >150 | >200 |
  |---|---|---|---|---|---|
  | `backend/**/*.java` | 115,761 | 10,186 | 2,598 | 572 | 326 |
  | `frontend/src/**/*.ts` | 6,809 | 287 | 116 | 41 | 20 |
  | `frontend/src/**/*.tsx` | 4,627 | 288 | 161 | 70 | 49 |

  Java의 150자 초과 572줄 중 477줄(83%)이 `core/result`(333) · `core/report`(144) 두 패키지(U6)에 몰려 있다.
- **함의(인터뷰 선택지에 반영 제안)**: `.prettierrc.json`의 `printWidth: 100`은 실제 관례가 아니다. Java는 100자 초과가 1만 줄이 넘어 100자 기준을 강제하면 U6와 무관한 대량 서식 변경이 생긴다.
  프런트도 Prettier 전면 적용 시 575줄 이상 + 따옴표 · 쉼표 정규화로 큰 diff가 난다. 그래서 질문을 "포매터 강제 여부" 하나가 아니라 다음처럼 나눌 것을 제안한다.
  - A. 이번 작업이 **손대는 파일만** 기존 다른 유닛 관례(한 줄 한 문장 · 연산자 공백 · 4칸)로 정리하고, 포매터 도입은 별도 작업으로 미룬다.
  - B. 측정 가능한 하한만 CI에 건다(예: Java · TS 한 줄 150자 초과 0건, 기존 위반은 허용 목록). 도구 없이 `infra-validate` 같은 스크립트로도 가능하다.
  - C. 포매터(Spotless + google-java-format/palantir, 프런트 Prettier `--check`)를 도입하고 전체 재서식 커밋을 한 번 만든다(다른 진행 중 브랜치와 충돌 큼).
- 리드 초안의 `-Xlint:all`은 정확히는 `-Xlint:all,-processing,-serial,-this-escape,-try`이고 `-Werror`가 없다(`mujoong.java-conventions.gradle.kts`). 경고가 빌드를 막지 않는다는 결론은 그대로다.

### 2. 계층 · 모듈 경계 — ArchUnit이 이미 강제하는 것 (리드 초안에 빠짐)

리드 초안은 R-F5(두 앱 직접 참조 금지)만 뽑았다. `backend/*/src/test/java/kr/mujoong/*/arch/`의 시험이 실제로 강제하는 규칙은 더 많고, 모두 CI `backend` 작업(`gradle check`)이 막는다.

| 강제 내용 | 근거 시험 |
|---|---|
| core-api ↔ realtime-activity 패키지 참조 0, **Realtime 질의 문자열이 core 스키마 · 표를 가리키지 않음**(코드뿐 아니라 DB 스키마 경계) | `CoreArchTest` ①, `RealtimeArchTest.realtimeNeverReferencesCoreCodeOrTheCoreSchema` |
| 운영 코드 → 시험 코드(`testsupport` 등) 참조 0, 운영 bootJar에 시험 클래스 0 | `CoreArchTest` ③ · `RealtimeArchTest` ③, `verifyBootJarHasNoTestClasses` |
| 시스템 시각 직접 읽기 0 — 시각은 `kr.mujoong.contract.time.MujoongClock` 주입으로만 | `CoreArchTest` ⑤ · `RealtimeArchTest` ⑤ (운영 코드의 `Instant.now()`는 `SystemMujoongClock` 한 곳) |
| 숫자 시간 상수 0 — `Duration.ofMillis(50)` · `TimeUnit.` · `Thread.sleep(` 등은 허용 목록 밖 금지, 시간 값은 설정 키에서만 | `TimeLiteralScanTest`(core · realtime) |
| 음성 공급자 클라이언트(WebSocket) 의존은 `realtime/voice` 하나, 공급자 요청 객체는 공장만 생성 | `RealtimeArchTest` ⑥ · `PROVIDER_REQUESTS_ONLY_FROM_THE_FACTORY` |
| 프레임 처리 경로 → JDBC · 파일 · Outbox 의존 0 | `RealtimeArchTest` ④ |
| Core의 세션 사본 상태 변경은 BR6.17 허용 호출자만 | `CoreArchTest` `COPY_STATE_CHANGES_ONLY_AT_BR6_17_EXCEPTIONS` |
| 단일 인스턴스 표시 · contract-model 의존 방향 | `SingleInstanceMarkerTest` · `ContractModelArchTest` |

- **R-M5(시간 값 세 곳 동시 수정)와 직결**: 문서 갱신으로 시간 값을 바꿀 때 코드에 숫자를 직접 쓰면 `TimeLiteralScanTest`가 실패한다. R-M5 문구에 "코드에는 숫자 시간 값을 쓰지 않고 설정 키로만 읽는다"를 붙이면 강제 수단과 규칙이 맞는다.
- 프런트 경계(R-F4)는 리드 기술이 맞다. 덧붙여 ESLint가 `no-restricted-imports`(문자열)와 `import/no-restricted-paths`(해석 경로)를 **둘 다** 걸고, 라우트 디렉터리 `src/app/{parent,device}`도 같은 영역으로 본다.

### 3. 오류 처리 관례 (리드 초안에 없음)

- **오류 코드는 한 곳**: `backend/contract-model/src/main/java/kr/mujoong/contract/error/ErrorCode.java` enum ↔ `contracts/errors.md` 표. `ContractFilesTest`가 code · HTTP · message를 대조한다.
  새 오류 코드는 enum과 표를 같은 커밋에서 더하고, 받는 쪽은 모르는 code를 일반 오류로 처리한다(더하기만 안전, 계약 10절).
- **업무 거절은 `CoreApiException.of(ErrorCode)`**: 예외는 code 하나만 담고(메시지 = code 이름, 스택 추적 끔), 웹 계층 `ApiExceptionHandler`(core · realtime 각 하나)가 봉투 `{code, message, traceId}`로 바꾼다.
  message는 code별 고정 문장이고 입력값 · 전사 · 예외 메시지 · 스택을 담지 않는다(BR8.6 · BR8.7 · SD-11). 내부 code는 `ErrorCode#forGuardian()`으로 번역해 내보낸다.
- **화면 문구는 code로 고른다**: `frontend/src/shared/errors/codeToCopy.ts` + `shared/copy/*`(ESLint `react/jsx-no-literals`).
- **로그는 `EventLog`로**: 운영 코드의 오류 기록은 `events.appError(EventLog.Component.…, e)`(75곳)이고, slf4j `LoggerFactory` 직접 사용은 운영 앱 코드에 0곳(contract-model `EventLog` · `DelayQueueTimerDriver`와 test-support만).
- **넓은 catch의 모양**: 운영 코드 `catch (Exception|RuntimeException|Throwable …)` 103곳은 대부분 ① `events.appError` 후 계속(주기 작업 · 기동 비필수 단계, 근거 주석 동반) ② 고정 code 결과로 번역(`DeliveryOutcome.failed("PUSH_DELIVERY_FAILED")`) ③ 의미 있는 예외로 다시 던짐 중 하나다.
  주석 없이 삼키는 곳은 확인한 표본에서 없었다(`catch (… ignored)`는 운영 코드 1곳 `PeriodicJobs.java:116`, 근거 주석 있음). 이 관례를 Code Style에 한 줄로 적을 것을 제안한다.

### 4. 이름 · 파일 구성 (리드 초안 보강)

- **백엔드는 기능별 패키지(package-by-feature)**: `kr.mujoong.core.{account, practice, result, report, notification, …}`, `kr.mujoong.realtime.{session, voice, dialog, gateway, …}`. 전통적 계층 패키지(controller/service/repository 최상위)는 없다.
  컨트롤러는 기능 패키지 아래 `web` 하위 패키지(`core/practice/web`, `core/report/web`), 공용 웹 필터 · 오류 처리기는 앱 최상위 `web`.
- 이름 접미어: `*Service` · `*Repository`(대부분 JDBC: JdbcClient/JdbcTemplate 55파일, JPA 엔티티는 `identity` · `catalog` · `realtime/session`의 7파일만) · `*Port`(기능 사이 경계 인터페이스 8개) · `*Adapter`(구현, 유닛 접두어 예: `U2PracticeAccountAdapter`) · `*Configuration` · `*Participant`(계정 삭제 참여자).
  **[확인 필요]** 새 저장소 코드를 JDBC로 쓰는 것을 관례로 둘지 — 증거는 JDBC 쪽이 압도적이지만 문서화된 결정은 확인하지 못했다.
- 시험 이름: 단위 `*Test`(core-api 92) · 통합 `*IT`(65), JUnit `@Tag("u1")`처럼 유닛 태그. `verifyBootJarHasNoTestClasses`가 `Test` · `Tests` · `IT` · `TestCase` 접미어로 운영 jar를 검사하므로 **시험 클래스는 이 접미어를 지켜야 한다**(운영 클래스에 이 접미어를 쓰면 빌드 실패).
- 프런트 화면 파일은 **화면 설계 번호를 이름 끝에 붙인다**: `Login01.tsx` · `Home04.tsx` · `Free08.tsx` · `Records26.tsx` · `RecordDetail27.tsx` · `Report30.tsx` · `Register05a.tsx`(`frontend/src/parent/screens/`).
  이번 작업이 화면 설계 v1.5j 갱신을 코드에 옮기는 일이라, 화면 번호가 바뀌거나 화면이 나뉘면 파일 이름 · 시험 이름을 같이 바꿀지 정해 두는 것이 좋다(**[확인 필요]**).
- 코드 주석의 결정 ID 인용 · TODO/FIXME 0건(측정: `backend/*/src` · `frontend/src`에서 0)은 리드 기술과 일치한다.

### 5. discovered-rules에 더할 후보 (모두 기존 시험이 이미 강제 — 확정해도 새 부담 없음)

- 후보 R-F6: NEVER 운영 코드에서 시스템 시각을 직접 읽지 않는다(`Instant.now()` · `System.currentTimeMillis()` 등). `MujoongClock`을 주입받는다. — `CoreArchTest` ⑤ · `RealtimeArchTest` ⑤
- 후보 R-F7: NEVER 코드에 숫자 시간 값을 쓰지 않는다. 시간 값은 `mujoong.timing.*` 설정 키에서만 읽는다(예외는 `TimeLiteralScanTest` 허용 목록에 근거와 함께). — R-M5와 묶어도 된다
- 후보 R-M6: ALWAYS 새 오류 코드는 `ErrorCode` enum과 `contracts/errors.md` 표를 같은 커밋에서 더하고, 오류 응답 message에 입력값 · 예외 메시지 · 개인정보를 담지 않는다. — `ContractFilesTest`, BR8.6 · BR8.7
- 후보 R-F8: NEVER 운영 코드가 시험 코드 · 시험 지원 모듈(`test-support`)을 참조하지 않는다. — ArchUnit ③ · `verifyBootJarHasNoTestClasses`

## Positions

- AGREE: 「Code Style」의 `.editorconfig` · ESLint flat config · shellcheck · ArchUnit 기술 — 설정 파일과 CI 작업에서 그대로 확인했다.
- AGREE: R-F4(`/parent` ↔ `/device` · `shared` import 금지) — ESLint 두 규칙(문자열 · 해석 경로)이 라우트 디렉터리까지 막는다.
- AGREE: R-F5(두 앱 직접 참조 금지) — 다만 ArchUnit이 코드 패키지뿐 아니라 Realtime의 core 스키마 질의 문자열도 막으므로 문구에 "상대 앱의 DB 스키마 · 표도 읽지 않는다"를 더할 것을 권한다.
- AGREE: R-M5(시간 값 세 곳 동시 수정) — 코드 쪽은 `TimeLiteralScanTest`가 숫자 시간 상수를 막으므로 규칙과 강제 수단이 맞는다(R-F7과 묶기 권장).
- OBJECT: 「Code Style」의 "Prettier가 실행되지 않아 U6 압축 서식이 막히지 않고 들어왔다"는 인과 — `.prettierignore`가 `backend/`를 빼므로 Java 압축 서식(U6 문제의 83%)은 Prettier와 무관하고 Java 포매터 · 줄 길이 검사 부재가 원인이다.
- OBJECT: 포매터 질문을 "강제할지 / 손대는 파일만 정리할지" 둘로만 두는 것 — `printWidth 100`은 실제 관례가 아니어서(Java 100자 초과 10,186줄) 전면 강제는 대량 재서식을 부른다. 1절의 A · B · C처럼 측정 가능한 선택지로 나눠 묻는 편이 결정 가능하다.
- OBJECT: 「Code Style」에 오류 처리 · 로그 · 시각 주입 관례가 없는 것 — `ErrorCode`/`errors.md` 단일 출처, `CoreApiException` → 봉투, `EventLog` 경유 기록, `MujoongClock` 주입은 시험이 강제하는 사실이라 Code Generation이 따라야 할 기준선으로 적어야 한다.
