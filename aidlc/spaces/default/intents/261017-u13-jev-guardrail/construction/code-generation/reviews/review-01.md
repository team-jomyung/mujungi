## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-17T14:23:45Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailSupport.java > `SAFETY_FACTOR = 2` / `safetyDelay()` | 검사 안전 타이머(jev-budget의 2배 = 1,600ms)는 코드 상수 배수로 정해지는 파생 시간 값인데 `contracts/timing.md` · `contracts/ai-io.md`에 값이 없다. 숫자 시간 리터럴은 아니라서 `TimeLiteralScanTest`는 통과하지만 R-F7 · R-M5의 취지와 어긋난다. 포트 규약 위반 때만 쓰이는 안전망이라 영향은 작다. | `contracts/timing.md`에 파생값으로 한 줄 적거나 설정 키로 올린다. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261017-u13-jev-guardrail/construction/code-generation/code-summary.md > 4절 '계획 대비 편차' | 낡은 서술이 남아 있다. 'report.md를 만들지 못했고 Step 16 체크박스는 report.md가 쓰인 뒤에 표시한다', '계획 체크박스는 Step 15 · 15b만 표시했다'라고 적었지만, `docs/units/u13/report.md`는 커밋 bf2a176에 있고 계획 17단계가 모두 `[x]`다. 편차 기록이 최종 상태와 다르다. | 4절의 두 항목을 현재 상태로 고친다. | New |
| R-03 | Minor | backend/coverage-scope/u13.txt > 머리 주석 / docs/units/u13/report.md > 2절 | U13 범위 줄 커버리지 86.1%는 가장 많이 고친 운영 파일 `SessionExecutor`(+338줄)를 분모에서 뺀 값이다. 사유는 정직하게 적혀 있고 CI가 막는 범위도 아니며 하한을 낮춘 것도 아니다(R-F2 위반 아님). 다만 ALLOW 전 송신 차단의 실행기 쪽 줄이 이 지표에 안 들어간다. | Build and Test에서 `SessionExecutor`의 U13 변경 줄 커버리지를 따로 보고하거나, 보고서에 '실행기 변경 줄은 이 수치에 없다'를 더한다. | New |
| R-04 | Minor | backend/realtime-activity/src/test/java/kr/mujoong/realtime/observability/RealtimeMetricsTest.java > 라벨 키 `isSubsetOf` 단언 | 허용 라벨 키에 `mode` · `source` · `decision`을 더했다. 단언을 지운 것은 아니지만 허용 집합이 모든 지표에 공통이라 다른 지표가 이 키를 쓰는 것도 통과해 고카디널리티 방지 단언이 그만큼 느슨해졌다. | `mujoong.guardrail.*` 지표에만 세 키를 허용하는 지표별 단언으로 좁힌다. | New |
| R-05 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/GuardrailCoordinator.java > `blocked()`(597-611행) / `resetTurn()`(640행) | (코드 읽기로 확인, 시험 재현 없음) ① `retries`는 아이 확정 전사나 턴 경계에서만 0이 된다. 재지시가 ALLOW로 끝난 뒤 아이 발화 없이 서버가 다음 응답을 만들면 그 첫 막힘은 재지시 없이 곧바로 포기된다. ② 대기열에서 꺼낸 구간(`fromQueue`)이 막혀도 `lastInstruction`은 앞 구간의 지시다. 둘 다 fail-closed라 안전 문제는 아니고 재지시를 못 받는 품질 문제다. | 설계 의도(턴 = 아이 발화 하나)면 functional-spec에 한 줄로 못 박고, 아니면 ALLOW 송신 때 `retries`를 0으로 돌린다. | New |
| R-06 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java > `Services` 호환 생성자(`GuardrailSupport.DISABLED`) | 제브 장치 없이 만드는 공개 생성자는 OFF가 기본이라 호출자가 장치를 빼먹으면 조용히 fail-open이 된다. 운영 호출자는 `RealtimeRuntimeConfig` 하나뿐이고 20인자 생성자를 쓴다(grep 확인). | 호환 생성자를 시험 전용으로 제한하거나 ArchUnit으로 운영 코드의 사용을 막는다. | New |
| R-07 | Minor | contracts/ai-io.md > 10.11 / backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailStartupRules.java > `problems` | '프로필 없음'은 주소 규칙에서는 STRICT지만 모드 기본값은 STUB(자동 통과 + WARN 한 줄)이다. 공개 프로필 판정이 demo · prod 이름에 의존하는 기존 S1b 관례와 같아 새 결함은 아니나, 배포 구성이 프로필을 빠뜨리면 자동 통과로 뜬다. | `infra/RUNBOOK.md` 배포 점검에 'SPRING_PROFILES_ACTIVE=demo 확인과 기동 로그 `GuardrailModeStub` 부재 확인'을 한 줄 더한다. | New |

### 반박 시도 결과

- **ALLOW 전 출력 경로**: 봉인 처리에서 `inspect` 통과 뒤 연습 확인 → 제브 `begin`으로 가고 그 경로는 `return`으로 끝나 `recheckAndSend`를 부르지 않는다. 기기로 나가는 곳은 `send` → `target.sendOutput` 하나이고 호출자는 `recheckAndSend` 하나다. 유효한 ALLOW(`allowed`)와 승인 원문 일치 생략(`onVerbatimSkipped`)만 거기에 닿는다. ACTIVE가 아니면 `activeAndCurrent`가 거짓이라 폐기된다. 막힘 · 시간 초과 · 전송 오류 · 잘못된 JSON · 정책 판 불일치는 모두 `FAILED`/`BLOCK`/`REVIEW`로 완료되고 자동 ALLOW가 없다. `check()`는 예외를 삼켜 `TRANSPORT_ERROR`로 바꾸고 `begin` 예외는 `onBeginFailed`가 fail-closed로 폐기한다.
- **늦은 ALLOW 무효(BR4.x)**: `voidCause`가 세션 종료 · 연결 변경 · 두 세대 변경 · 출력 보류 · 입력 묶음 새로 열림 · 연습 발화 변경을 검사한다. 무효는 재생도 재지시도 하지 않고 대기열을 폐기한다. 안전 타이머 만기 뒤 늦은 결과는 검사표가 없어 버려진다.
- **상태 기계**: `RETRYING`은 새 말 미시작, 세대 변경, 폐기 훅, 턴 경계 중 어느 길로든 `IDLE`이 되고, 그때 `finishDiscard`/`listeningAgain`이 기기를 '생각 중'에서 푼다(GuardrailExecutorTest 재지시 계열). 대기열은 1칸이고 가득 차면 `QUEUE_FULL`로 즉시 닫으며 보관 행은 `closeAll`이 닫는다.
- **비밀 · 원문 유출(R-F3 · R-F14)**: guardrail 패키지와 코디네이터에 로거 호출 · `System.out`이 없고 `toString`은 길이 · 모드만 낸다. `appError`는 예외 클래스 이름만 남기며 어댑터는 예외 원인 메시지를 버린다. 키는 호출마다 읽어 `Authorization` 머리에만 쓴다. V6 `guardrail_event`에는 원문 칸이 없고 `reason_code`는 정규식으로 제한된다. `jevLiveTest` 보고서에도 원문이 없다.
- **R-F2**: 기존 시험 변경 10건은 모두 V6 사실 · 새 클래스 등재이며 삭제 · `@Disabled` · 하한 인하가 없다(R-04의 허용 집합 확장 한 건만 지적).
- **R-F6 · R-F7**: 제브 패키지와 코디네이터에 `Instant.now` · `currentTimeMillis` · 시간 리터럴이 없다. 예외 1건은 허용 목록에 사유와 함께 있다.
- **추적성**: `traceability.json`의 BR ID는 `rules.md`의 51개와 같고 `source-manifest.json`은 변경 파일을 빠짐없이 덮는다.
- **인프라 정렬**: `render-env` · `infra-validate` · `image-checks` ④ · `test-host-tools.sh`가 JEV 키 · 주소 · 모델 demo 필수라는 같은 사실을 가리키고, 앱 `GuardrailStartupRules`가 demo · prod에서 어긋난 구성을 거부한다.
- **증거 구분(R-M11)**: 보고서는 합격을 '테스트 대역'으로만 적고 합성 대화 · 실제 API · 실제 기기는 '없음'으로 구분했으며 R-F15 확인 기록 공백과 일시 실패 1회도 밝혔다.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker` | PASS | U13 태그 시험(가짜 제브 · 가짜 공급자) 통과. 테스트 대역 증거이며 실제 제브 성공이 아니다 |
| traceability BR 대조 스크립트 | PASS | rules.md BR 51개 모두 traceability에 있음 |
| source-manifest 대 변경 파일 대조 | PASS | 누락 0 |

### Summary

ALLOW 전에 기기로 바이트가 나가는 길, 원문 · 비밀 유출, 기존 단언 약화, 시스템 시각 · 숫자 시간 값 위반을 찾지 못했고 상태 기계는 모든 종료 길에서 기기를 풀어 준다. 남은 7건은 모두 Minor(계약 · 문서 정합, 측정 범위 표기, 보수적 재지시 의미, 기본값 위험)이며 판정은 READY다.
