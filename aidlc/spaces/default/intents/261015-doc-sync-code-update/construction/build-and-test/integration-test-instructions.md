# 통합 시험 방법 — U8 · U9 범위

## 1. 핵심 경계와 시험

| 경계 | 시험 | 명령 |
|---|---|---|
| core 시작 패키지 → realtime 세션 수신(`approvedSpeech`, U8) | 두 앱 `@Tag("u8")` 통합 시험(시작 요청 스키마 · 수신부 · 중복 code) | `cd backend && ./gradlew :core-api:test :realtime-activity:test -PincludeTags=u8 -Pmujoong.test.db=docker` |
| 승인 발화 시드 004 멱등 · dev 시드(U8) | `@Tag("db")` 시드 시험(Testcontainers PostgreSQL) | 위 명령(Docker 필요) |
| Luna 호출부 → 루프백 Responses 대역(U9) | `CoreLunaClient` · `RealtimeLunaClient` 대역 시험 | `cd backend && ./gradlew :core-api:test :realtime-activity:test -PincludeTags=u9` |
| 기존 어댑터 → 게이트웨이 → 검사 경로(U9) | `TextAiGateway` → `ScenarioService`, `ResultTextGateway` → `OutputChecker` 통합 | 위 명령 |
| 로그 계약 ↔ 로그 위생 표(U9) | `test-host-tools.sh` 대조 사례, `verify.sh` P2 | `bash infra/ci/test-host-tools.sh` |
| 끝-끝 자유대화 한 턴 | 기기 시뮬레이터 C1 ~ C12, 사후 단언 P1 ~ P3 · T | `bash scripts/verify.sh` |

U8 · U9 사이의 경계(U9가 U8 스키마 · 시간 키 · 비밀 이름을 씀)는 같은 시험 묶음에서 함께 확인된다. U10 ~ U15와의 경계(U9 호출부를 실제로 쓰는 쪽)는 그 단위가 만들어질 때 시험한다.

## 2. 시험 환경 · 자료

- DB 시험: `-Pmujoong.test.db=docker`(Testcontainers). Docker가 없으면 `@Tag("db")` 시험은 돌지 않으므로 통과로 보고하지 않는다.
- 공급자: GPT-Live는 `tools/fake-provider`, Luna는 `LunaResponsesStub`(루프백). 실제 공급자는 부르지 않는다(R-F1).
- 자료: 합성 값만(아이 표시 이름 · 전사 · UUID · 연락처 모양 문자열).

## 3. 기대 커버리지

- 모듈 줄 80%(CI가 막음), U8 범위 · U9 범위 줄 80%(로컬 · 검토 근거).
- 실호출(`lunaLiveTest`)은 이 시험에 들지 않는다. 사람이 키를 넣고 따로 돌린다(편차 U9-c).
