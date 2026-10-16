# U9 Luna 호출부 — 단위 시험 실행 방법

## 시험 틀

- 백엔드 단위: JUnit 5 · AssertJ · Mockito. 통합: Spring Boot 시험 컨텍스트 + `test-support`의 `LunaResponsesStub`(JDK `HttpServer` 루프백). U9는 DB를 쓰지 않아 Docker 없이 돈다.
- 태그: U9 시험은 모두 `@Tag("u9")`. 실호출 사례만 `@Tag("luna-live")`이고 기본 `test`에서 언제나 빠진다.
- 기록 확인: `test-support` `LogCapture`(`attach()` · `events(EventCode.LUNA_CALL)` · `attrs(...)` · `anyLineContains(...)`).

## U9 범위 실행 명령

Step 2에서 이 명령이 도는지 먼저 확인한다(시험이 아직 없으면 "시험 0건"으로 성공해야 한다).

```bash
cd backend && ./gradlew :contract-model:test :core-api:test :realtime-activity:test -PincludeTags=u9
```

- 범위 커버리지(줄 80%): `cd backend && ./gradlew :core-api:test :core-api:jacocoTestCoverageVerification :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u9 -PcoverageScope=u9`
- 빌드 구성 확인: `cd backend && ./gradlew verifyLunaLiveIsolation`
- 인프라 스크립트: `bash infra/ci/test-host-tools.sh` · `infra/bin/infra-validate`
- 실호출(사람이 키를 넣고 돌림, 기본 빌드 · CI 밖): `cd backend && ./gradlew lunaLiveTest` — 키가 없으면 SKIPPED로 끝난다.

## 커버리지 목표

- U9 범위 파일 `backend/coverage-scope/u9.txt` 줄 80%(로컬 · 검토 근거, CI가 막는 범위는 U5b · U7뿐).
- 모듈 전체 줄 80%(CI가 막음)는 지금처럼 유지한다. 하한을 낮추지 않는다(R-F2).

## 컴포넌트별 시험(Standard: 컴포넌트당 5 ~ 8개)

| 컴포넌트 | 앱 | 핵심 사례 |
|---|---|---|
| `EventLog` 확장 | contract-model | LABEL · NAME_LIST 정상, 형식 위반 → `UNKNOWN`, 허용 밖 속성 거부 |
| `LunaHosts` | core · realtime | STRICT · DEV · TEST 허용/거부, 프로필 없음 → STRICT, 상수 = `ai-io.md` 값 |
| `LunaSchemas` | core · realtime | 요청용 사본 비지원 키워드 없음, strict 판정, 원본 검증이 제약 위반 잡음, 파일 없음 → 기동 거부 |
| `LunaInputSanitizer` | core · realtime | 중첩 금지 키 제거, 별칭, 연락처, 잔존 → 미호출, 필드 이름 목록 |
| `LunaTransport` | core · realtime | 302 → `REQUEST_REJECTED`, 1MB 초과, 느린 본문 → `TIMEOUT`, 키가 로그에 없음 |
| `CoreLunaClient` · `RealtimeLunaClient` | core · realtime | 응답 분류 사유별, 재시도 분류별 시도 수, `Retry-After` · 남은 시간, 401, `store=false` · `temperature` 없음, `LUNA_CALL` 속성 |
| 어댑터 2종 | core | 결정표 사유별 예외, 게이트웨이 경유 정상 · 실패, 참조 복원 |
| 빈 조건 | core · realtime | 키 있음 · 없음 · 공백 · 대역 빈 조합에서 빈 하나만 |
| ArchUnit | core · realtime | `..luna..` 금지 의존 목록 |

## 대역 · 시험 자료

- 공급자는 언제나 `LunaResponsesStub`(루프백)만 쓴다. 키는 가짜 문자열(`test-key-not-real` 꼴)을 시험 안에서만 env로 넣는다(R-F1 · R-F3).
- 입력 자료는 합성 값만 쓴다(아이 표시 이름 · 전사 · UUID · 연락처 모양 문자열). 실제 아동 데이터는 쓰지 않는다.
- 대역 대본은 시험마다 따로 만들고 공유 상태를 두지 않는다. 받은 요청 본문은 대역이 보관해 시험이 금지 키 · 이름 유출을 확인한다.
- 시간은 `MujoongClock` 시험 시계와 대역 지연으로 다룬다. 숫자 시간 값이 필요하면 설정 레코드 · 시험 속성으로 넣는다(`TimeLiteralScanTest`).
