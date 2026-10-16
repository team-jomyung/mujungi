## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-15T17:25:00Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-design/security-design.md > SD6 | SD6은 "공백(유니코드 공백 포함)만 지운 뒤 … 기존 `PracticeSpeech.matches`와 같은 정규화"라고 쓴다. 실제 `backend/realtime-activity/.../voice/PracticeSpeech.java`의 `matches`는 `replaceAll("\s+", "")`이고, Java의 `s`는 `UNICODE_CHARACTER_CLASS` 없이는 ASCII 공백(space, t, n, x0B, f, r)만 지운다. NBSP(U+00A0) · 전각 공백(U+3000) · 제로폭 공백은 지워지지 않아 `NOT_MATCHED`가 된다. 한 문장 안의 두 주장이 서로 모순이라 개발자가 어느 쪽을 구현할지 추측해야 하고, NFR2.1의 '한 글자 차이' 시험도 이 차이를 못 잡는다. | 둘 중 하나로 확정한다. (a) 기존 `matches`를 그대로 재사용하고 문구를 "ASCII 공백만"으로 고친다. (b) 유니코드 공백까지 지우려면 새 정규화(예: `p{Z}` 또는 `s` + `UNICODE_CHARACTER_CLASS`)를 정의하고 `matches`와 갈라지는 점을 적는다. 어느 쪽이든 NBSP · U+3000이 낀 입력의 기대 결과를 단위 시험 항목에 넣는다. | New |
| R-02 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-design/security-design.md > SD4 시험(`LogCapture`) | NFR3.6 '로그를 남기지 않는다'의 증명을 `LogCapture`로만 한다. 그런데 `testsupport/log/LogCapture.attach()`는 Logback의 `EventLog.LOGGER_NAME` 로거에 붙은 줄만 모은다(클래스 javadoc). SD4는 `LiveContentRegistry`가 로그를 쓰지 않고 `EventLog`도 받지 않는다고 하므로, 이 시험은 어떤 구현에서도 통과하는 약한 단언이다. 레지스트리가 slf4j 로거나 `System.out`으로 전사를 쓰는 회귀는 잡지 못한다(team.md는 운영 코드의 slf4j 직접 사용을 금지하지만 이를 막는 장치는 SD4에 없다). `test-support`가 realtime-activity의 `testImplementation`이라 시험에서 쓰는 것 자체는 가능함을 확인했다. | '로그 없음'의 근거를 구조 시험으로 보강한다. 예: ArchUnit 규칙으로 레지스트리 패키지가 `org.slf4j` · `EventLog` · `System.out`에 의존하지 못하게 한다. `LogCapture` 시험은 세션 수신부(이상 수신 `EventLog` 경로)가 문구 텍스트를 남기지 않는지 확인하는 용도로 범위를 좁혀 적는다. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-design/security-design.md > SD3 비밀 분배 표 | `render-env`의 `write_env`는 `${param[$src]:-}`로 값이 없어도 `OPENAI_API_KEY=` 같은 빈 줄을 쓴다(OPTIONAL 선례 `GOOGLE_OAUTH_CLIENT_SECRET`와 동일). SD3은 "없으면 실제 어댑터를 끈다"고 하지만 빈 문자열을 '없음'으로 취급해야 한다는 규칙이 없다. 빈 값으로 어댑터가 켜지면 인증 실패 호출이 생긴다. 또 `infra-validate`의 `SECRET_TABLE` 배열은 이름순으로 적혀 있어 새 이름도 그 순서를 지켜야 한다. | SD3에 "빈 값 = 미설정으로 어댑터를 끈다"를 명시하고 단위 시험 항목으로 둔다. `SECRET_TABLE` 정렬 유지도 한 줄 적는다. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-design/security-design.md > SD4 이상 수신 | "기존 `EventLog`로 사유 코드만 남긴다"고 했지만 `EventCode`는 닫힌 열거(속성도 `AttrKey` 허용 목록)이고, 사유를 실을 수 있는 기존 코드는 `APP_ERROR(ERROR_KIND, REPEAT)`뿐이다. 새 `EventCode`/`AttrKey`를 더하면 contracts 카탈로그와 대조 시험 변경이 따라오는데 SD4는 어느 쪽인지 정하지 않는다. | `APP_ERROR`의 `errorKind` 식별자 값(이름 규칙 포함)을 쓸지, 새 코드를 더할지 정하고 후자면 contracts 갱신 범위를 SD4에 적는다. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-design/security-design.md > SD5 | "`RealtimeStartupChecks`에 적재 확인을 더한다"로 쓰지만 현재 생성자는 `Supplier<SeedDictionary>` 하나만 받고 `SeedDictionary.LoadException`만 잡아 `reject`한다. 판 리소스는 다른 적재기 · 다른 예외 형식이 되므로 시험용 생성자 시그니처와 catch가 함께 바뀐다. 기존 기동 시험이 이 생성자를 쓰므로 하위 호환(오버로드 유지) 여부가 열려 있다. | 적재기 주입 방식(두 번째 Supplier 또는 오버로드)과 예외 형식을 SD5에 한 줄로 정한다. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-design/security-design.md > 3. 남는 위험 3행 | 3행은 "정산 경로에서 지움 + 세션 수 상한 시험"을 대응으로 적지만 SD4 시험 목록에는 정산 뒤 `UNKNOWN` 단언만 있고 세션 수 상한 시험이 없다. 상한 값과 초과 시 동작도 정해져 있지 않다. | SD4 시험에 상한 시험을 더하거나(상한은 설정 키로, 숫자 하드코딩 금지 R-F7) 대응 칸에서 그 문구를 뺀다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| 코드 사실 대조(`SeedDictionary` · `LoadException` · `RealtimeStartupChecks`) | 일치 | `JsonCodec` 적재 + `LoadException`, 생성자에서 `reject`로 기동 거부. 확장 방식만 R-05로 남김 |
| `JsonCodec` 모르는 속성 | 일치 | `FAIL_ON_UNKNOWN_PROPERTIES` 비활성(JsonCodec.java:54). SD7 맞음 |
| `LogCapture` 사용 가능 | 일치(단 범위 한계) | realtime-activity `testImplementation(project(":test-support"))` 확인. 수집 범위는 R-02 |
| `PracticeSpeech.matches` | 불일치 | `\\s+`는 ASCII 공백만. R-01 |
| `infra-validate` `SECRET_NAMES` · `SECRET_TABLE` / `render-env` `OPTIONAL` · `write_env` / `ci.yml:249` · `image-checks.sh:119` / `log-hygiene-check` `SECRET_VARS` | 형식 일치 | 설계가 가리킨 위치가 실제로 있고 갱신 대상이 맞음. 빈 값 처리는 R-03 |
| NFR 대응(NFR2.1 ~ NFR6.2 13건) | 빠짐 없음 | `traceability.json`의 13건이 SD1 ~ SD7에 대응. NFR2.1은 R-01 때문에 내용이 불확정 |

### Summary

SD1 ~ SD7은 NFR 13건을 빠짐없이 덮고 코드 사실(SeedDictionary · JsonCodec · 비밀 목록 위치)과 대체로 맞다. 다만 SD6의 '유니코드 공백 포함 = 기존 matches와 같음'은 실제 구현(ASCII 공백만)과 모순이고(R-01), SD4의 로그 없음 시험은 LogCapture 범위 때문에 사실상 아무것도 증명하지 못한다(R-02). 이 두 건은 사람이 보고 승인 여부를 정하는 편이 좋고, 나머지는 구현 단계에서 닫아도 되는 Minor다.
