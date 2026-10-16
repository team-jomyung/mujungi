## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-16T01:09:24Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/nfr-requirements/security-requirements.md > NFR3.4 (허용 호스트) · 위협표 S/D | 허용 호스트 검사가 기동 때 설정 주소에만 걸린다. 공급자가 3xx로 다른 호스트로 보내는 경우(리다이렉트), 프록시, 응답 본문 크기 상한은 요구에 없다. 위협표 S(위장)는 "주소 바꿔치기는 기동 거부"만 적는다. JDK HttpClient의 리다이렉트 기본값은 NEVER지만 요구로 고정되지 않아, 구현이 followRedirects를 켜면 아동 데이터가 허용 호스트 밖으로 나갈 수 있다. 응답 크기 상한이 없어 비정상 응답이 메모리를 채울 수 있다(D 위협) | NFR3.4(또는 새 NFR3.x)에 "리다이렉트를 따르지 않고 3xx는 실패로 돌려준다"와 "응답 본문 최대 바이트 상한"을 추가하고, 루프백 대역 3xx 모드 단위 시험을 확인 방법으로 적는다 | New |
| R-02 | Major | security-requirements.md > 1절 "코드가 막지 않는 위험" · Assumptions & Open Questions 1번 / contracts/ai-io.md:284 (10.2 R-F15) | Q2-B 선택은 존중한다. 위험 문단과 NFR3.7이 이를 정직하게 적은 것도 확인했다. 다만 키 투입 전 확인의 증거를 어디에 남기는지가 "담당자가 정한다"로 열려 있어, 코드에 스위치가 없는 상태에서 유일한 방어선이 확인 가능한 기록 없이 남는다. 또 ai-io.md:284는 지금 "'확인됨' 상태를 코드에서 판정하는 방법은 U9가 정한다(BR5.5)"라고 쓰여 있어 U9의 '판정하지 않음'과 모순이며, 갱신 책임이 NFR3.7에만 있고 R-M10(계약 동시 수정) 단계로 이어지는 추적이 없다 | (a) RUNBOOK 확인 항목의 최소 형태(확인자 · 날짜 · 약관 판 · 동의 범위 결론을 남기는 줄)를 이 단계에서 정하거나 Code Generation 계획의 명시 단계로 넘긴다. (b) ai-io.md:284 문장 수정을 R-M10 단계로 계획에 넣는다 | New |
| R-03 | Minor | security-requirements.md > NFR3.2 · 위협표 I(노출) | 아이 이름 치환은 호출자가 준 별칭 표에 있는 이름만 대상이다. 전사 · 발화 자유 문장에 섞인 표 밖 이름(친구 · 가족 이름 등)은 그대로 공급자로 나가며, 이 잔여 위험이 "코드가 막지 않는 위험"에 적혀 있지 않다 | 잔여 위험으로 한 줄 추가하고, 별칭 표를 채우는 책임이 호출 단위(U10 · U11 · U14)에 있음을 명시한다 | New |
| R-04 | Minor | security-requirements.md > NFR1.1 · 위협표 D · 3절 | 429 · 5xx 재시도에서 Retry-After 처리와 백오프가 마감 안에서 어떻게 다뤄지는지 NFR 수준 요구가 없다(429는 일시 실패로 분류한다고만 적힘) | NFR1.1 확인 방법에 "대기 후 남은 마감이 없으면 재시도하지 않는다" 시험을 포함한다 | New |
| R-05 | Minor | tech-stack-decisions.md > 실호출 시험 행 · 버전 · 호환 / backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts:83-86, 109 | 공통 규칙은 tasks.withType<Test>().configureEach에서 -PexcludeTags/-PincludeTags로 태그를 거르고 두 값을 inputs.property로 잡는다. luna-live를 기본 test에만 고정 제외하고 lunaLiveTest는 포함 필터를 쓰는 구성은 가능하지만, lunaLiveTest가 check에 물리지 않는다는 검증과 캐시 키 영향은 문서에 없다. 설계 재검토 메모에 위험이 이미 인정돼 있다 | Code Generation 계획에 "lunaLiveTest를 check에서 제외하고 기본 test의 태그 제외는 luna-live 한정"을 검증 단계로 넣는다 | New |
| R-06 | Minor | tech-stack-decisions.md > 스키마 검증 행 · 버전 · 호환 / backend/core-api/build.gradle.kts:40, backend/realtime-activity/build.gradle.kts:32 | json-schema-validator 3.0.6은 현재 두 앱 모두 testImplementation이다(libs.versions.toml:12, 71). implementation으로 옮기면 운영 이미지에 새 전이 의존성이 들어간다. 취약점 점검 대상이 된다는 점은 적혀 있으나, 의존 이동 직후 점검을 확인하는 단계는 없다 | 계획에 "의존 이동 직후 취약점 · 이미지 점검 확인" 단계를 넣는다 | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| required-sections / upstream-coverage / linter / type-check / traceability | 이 검토 세션에서는 직접 실행하지 못했다(읽기 범위 제한). 산출물을 직접 읽어 확인했다 | traceability.json의 upstream NFR1 ~ NFR7이 모두 coverage에 있고 NFR7만 사유와 함께 N/A다. 두 문서 모두 `## Sources` · `## Assumptions & Open Questions`를 가진다. 도구 결과는 엔진 센서로 확인 바람 |

### Summary

library 단위로 기대되는 3개 산출물이 갖춰졌고 요구마다 확인 방법이 있으며, Q2-B의 R-F15 절차 방어는 위험으로 정직하게 문서화돼 있다. 사람이 weigh할 점은 허용 호스트 우회(리다이렉트) · 응답 크기 상한 요구 누락(R-01)과, 유일한 방어선인 키 투입 전 확인의 증거 형식 · ai-io.md:284 문구 정리 책임(R-02)이다. Critical은 없고 Major 2건이라 READY다.
