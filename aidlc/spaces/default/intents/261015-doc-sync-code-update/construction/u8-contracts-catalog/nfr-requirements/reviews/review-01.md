## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-15T17:06:39Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-requirements/security-requirements.md > NFR3.6 확인 방법 ("로그 위생 검사") | `infra/bin/log-hygiene-check`는 컨테이너 로그만 읽는 운영 점검이다(헤더 2~22행). 검사 항목은 JSON 형식, 사건 코드별 attrs 키 허용 표(`ALLOWLIST_JSON`), 금지 헤더 이름, 비밀 변수 값, nginx, postgres뿐이다. 전사나 대본 문장이 허용된 attrs 값(예: `errorKind`)에 들어가도 잡지 못하므로 NFR3.6(전사를 로그에 남기지 않음)을 검증하지 못한다. 또 '결과 종류 · 판 번호'를 로그에 남기려면 `EventCode` · `AttrKey`에 새 키가 필요하고 `log-hygiene-check`의 `ALLOWLIST_JSON`과 `test-host-tools.sh` 대조도 함께 고쳐야 한다(`AttrKey`는 등록되지 않은 열거값을 거절한다). 이 연쇄는 요구에도 BR에도 없다. | 확인 방법을 실행 가능한 시험으로 바꾼다. 예: 로그 어펜더를 붙이고 원문 일치 검사 후 전사 · 대본 부분 문자열이 어떤 로그 줄에도 없다고 단언한다. 기록 방식을 둘 중 하나로 정한다. (a) 로그에 남기지 않고 판 번호는 조회로만 낸다. (b) 새 attrs 키를 `EventCode`/`AttrKey`와 `log-hygiene-check` 허용 표에 같은 커밋으로 더한다고 요구로 쓴다. | New |
| R-02 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-requirements/tech-stack-decisions.md > 판 리소스 파일 형식 · 읽기 행 ("JSON 파일 + Jackson 3(`tools.jackson`)") | "`gate/seed-dictionary.json`과 같은 방식"이라 했으나 실제는 다르다. `SeedDictionary.java`는 `tools.jackson`을 직접 import하지 않고 `kr.mujoong.contract.json.JsonCodec`을 쓴다. 운영 코드에서 `tools.jackson`을 import하는 곳은 `JsonCodec.java` 하나뿐이고, `contract-model/build.gradle.kts` 주석도 "Jackson 직접 import는 `kr.mujoong.contract.json` 한 곳(J139)"이라 적는다. 이 선택대로 구현하면 이 규약과 충돌하고, ArchUnit이 강제하는 경우 빌드가 깨진다. | 읽기 방법을 `JsonCodec`(필요하면 그쪽에 판 리소스 읽기 메서드를 더함)으로 고쳐 쓴다. `tools.jackson` 직접 사용이라는 문장은 지운다. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-requirements/security-requirements.md > NFR3.4 | R-M9 목록은 파일 수준(`ci.yml:249`, `image-checks.sh:119`, `infra-validate` 156~157행의 `SECRET_NAMES`, `log-hygiene-check`의 `SECRET_VARS`)과 `infra-validate:162`의 서비스별 비밀 매핑(`realtime: [...PROVIDER_API_KEY...]`)을 모두 갖고 있다. NFR3.4는 이름만 목록에 넣는다고 하고, 어느 앱(core · realtime)이 어느 키를 받는지는 정하지 않는다. 최소 권한이 확인 방법에 없고, BR4.2(기존 `PROVIDER_API_KEY` 유지 · `MUJOONG_TEXT_AI_API_KEY` 미사용)도 보안 요구에 옮겨지지 않았다. | NFR3.4에 앱별 매핑을 한 줄 추가한다(예: Luna용 키는 core만, 제브용 키는 realtime만). 확인 방법에 `infra-validate`의 서비스별 매핑 검사를 넣는다. BR4.2는 NFR로 가져오거나 N/A 근거를 쓴다. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/nfr-requirements/security-requirements.md > NFR3.3, NFR3.2 확인 방법 ("문서 검토" · "스키마 문서 확인") | R-F15 가드와 이름 치환 문구가 사람 검토에만 기대어 있다. NFR3.1처럼 시험으로 닫지 않으면 `ai-io.md` 문단이 이후 개정에서 사라져도 CI가 모른다. | `ContractFilesTest` 쪽에 `ai-io.md`의 R-F15 문장과 스키마 `description` 존재를 확인하는 대조 시험을 더하거나, 시험을 만들지 않는 이유를 적는다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| 단계 정의의 검증 도구 | 실행하지 않음 | 산출물이 Markdown과 traceability.json이며, 자동 점검 도구는 이 검토에 지정되지 않았다. |
| 코드 사실 확인 | `json-schema-validator`는 `contract-model` · `core-api` · `realtime-activity` · `test-support`에서 모두 시험 의존으로 쓸 수 있다 | 3.0.6과 Jackson 3.1 주장은 `libs.versions.toml` 주석과 일치한다. 다만 3.0.6이 Jackson 3.1 빌드라는 점은 주석만 근거이고, 실제 해석되는 jackson3-security 3.1.7 BOM과의 호환은 실행으로 확인하지 않았다. |
| 코드 사실 확인 | `tools.jackson`은 운영 코드에서 `JsonCodec.java`에만 있다 | R-02의 근거다. |
| 코드 사실 확인 | `log-hygiene-check` 점검은 형식 · attrs 키 · 금지 이름 · 비밀 값 · nginx · postgres | R-01의 근거다. |
| traceability.json 검토 | NFR1 · NFR4 · NFR5 · NFR7의 N/A 근거는 타당하다 | NFR5 N/A에 적힌 시험 방침 위치는 `tech-stack-decisions.md` 시험 행으로 존재한다. |

### Summary

보안 요구는 BR1~BR7과 R-F3 · R-F9 · R-F14 · R-F15 · R-M9를 대체로 덮는다. 다만 NFR3.6의 로그 위생 확인 방법은 실제 도구가 검증하지 못하는 것이고, 판 리소스 읽기 기술 선택은 저장소의 Jackson 격리 규약(J139)과 어긋난다. 이 두 건은 구현자가 추측해야 하므로 승인 전에 고치는 것을 권한다. Critical은 없고 Major가 2건이라 READY로 판정한다.
