## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-15T18:47:21Z
**Iteration:** 1

이 검토는 advisory 단일 패스다. 사람이 승인 전에 따져 볼 지적을 심각도 순으로 적었다. Critical은 없고 Major 2건이다(Major 2건 이하라 READY).

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/contract-design/contract-summary.md > C-DS8 줄 245~257, 279 (`candidate`, `categories: {type: object}`, `options: array<string>`) 대 contracts/schemas/guardrail-result.schema.json · ai-judgment-request.schema.json · contracts/deviations.md U8-a | 구현이 공유 계약 요약과 세 군데에서 어긋난다. (D5) 판정 선택지를 `{code,label,definition}` 객체 배열로 바꿨다. 요약은 `array<string>`이다. (D6) `guardrail-result.categories`를 배열로 바꿨다. 요약은 `type: object`이다. (D8) 후보 필드는 요약대로 `candidate`인데 C §3.1·§3.3 프롬프트는 `candidateText`다. code-summary는 D6·D7만 deviations U8-a에 올렸고 D5·D8이 편차 표에 올랐는지는 확인되지 않았다. U9·U13은 contract-summary를 계약으로 읽고 구현하므로 객체형 `options`와 `candidate` 이름에서 어긋난다. | D5·D8을 `contracts/deviations.md`의 U8-a에 기재한다. contract-summary의 해당 줄에는 "U8 구현이 이렇게 정했다"는 인계를 남긴다(승인 문서 본문은 고치지 말고 다음 단계 산출물에 적는 팀 규칙을 따른다). D8은 U13 어댑터가 공급자 요청에서 `candidate`→`candidateText`로 이름을 바꾼다고 적는다. | New |
| R-02 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/code-generation/traceability.json > BR1.1 (status OK) 대 functional-spec 8.1 표 HELP_OFFER 줄 및 BR1.1 violation | functional-spec은 "코드에 박힌 '도움을 받아 볼까?'는 지운다"고 적었다. D19에 따르면 `RuntimePracticeExtension`의 상수와 ai-io.md 8절 문장이 남아 있다. 계획에 이 단계가 없었다는 사실과 별개로, BR1.1이 OK로 표시되어 spec 위반 조건이 해소된 것처럼 읽힌다. 시드(004)는 새 문구를 넣지만 런타임 U5b 경로는 여전히 옛 문구를 말한다. 해당 상수를 쓰는 경로가 U12 이전에 켜지면 승인되지 않은 문구가 아이에게 나간다. | BR1.1을 `OK`로 두지 말고 "시드·조립만 충족, 레거시 상수 제거는 U12로 이월" 같은 부분 충족 행으로 나눠 추적한다. U12 인계 항목에 상수 제거를 수용 조건으로 명시한다. 아니면 상수와 ai-io.md 8절 문장을 이번 단위에서 지운다. 사람이 선택한다. | New |
| R-03 | Minor | infra/seed/004-u8-reference.sql 판 행 `COACH_SWITCH`·`PEER_RETURN` 대 functional-spec 8.1 줄 147~148 및 B §3.1 D_FOCUS | spec 8.1은 COACH_SWITCH를 '잠깐, 무중이가 도와줄게'로 적었는데 시드는 B와 같은 '잠깐, 무중이 도와줄게'(마침표 없음)다. B의 D_FOCUS 지시(162행)에는 «잠깐, 무중이 도와줄게.»로 마침표가 있어서, 모델이 마침표까지 전사하면 공백만 무시하는 원문 일치가 MATCHED가 되지 않는다. 안전한 방향(가드레일 생략 안 함)으로 틀리지만 승인 문구로 관리하는 의미가 사라진다. code-summary D3은 마침표만 언급하고 spec과의 '이가' 차이는 언급하지 않았다. | spec·시드·B 세 곳 중 정본을 하나로 정하고, U12 인계에 "지시 안 문장과 시드 글자 일치(마침표 포함)를 시험으로 고정"을 넣는다. spec의 '무중이가' 표기는 정오표로 남긴다. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/code-generation/code-summary.md > 7절 및 backend/core-api/src/test/java/kr/mujoong/core/db/SeedU8ReferenceSqlIT.java | 004 SQL의 실행 자체(특히 `UPDATE ... AS old ... FROM` 구문, 002 없는 DB 적용, 멱등)와 기존 DB 시험 전부는 이 PC에서 돌지 않았다(core 157건 실패는 모두 DB 백엔드 없음으로 기준선 155 + 새 IT 2). traceability의 BR1.1·FR10.2 OK와 U8 범위 core 82.2%는 DB 시험을 뺀 수치다. 아직 SQL이 한 번도 실행되지 않았다는 점은 사람이 승인 전에 알아야 한다. | Build and Test에서 `-Pmujoong.test.db=docker` 전체와 `bash scripts/verify.sh`를 돌려 `SeedU8ReferenceSqlIT`·`U8DevSeedRunnerIT`·`SessionPackageIT`·`DevSeedRunnerIT`가 통과하는지 확인하고 결과를 별도 증거로 보고한다(R-M11). | New |
| R-05 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/code-generation/code-summary.md > D17 (SessionPackageIT·DevSeedRunnerIT 수정) | R-F2 관점에서 단언을 낮춘 변경은 아니다. `SessionPackageIT`은 정확 일치 필드 목록에 `approvedSpeech`를 더하고 code 순서까지 새로 단언하며, `DevSeedRunnerIT`는 준비 한 줄뿐이다. 다만 `SessionPackageIT`이 이제 `U8Fixtures.insertApprovedSpeech`가 넣은 행에 의존하므로 실제 004와 픽스처가 달라져도 이 시험은 잡지 못한다(잡는 것은 `SeedU8ReferenceSqlIT`뿐이다). | 사람이 D17을 승인하되 R-04의 DB 시험 실행을 조건으로 건다. 픽스처가 004의 글·UUID와 같은지는 `SeedU8ReferenceSqlIT`가 대조한다는 점을 요약에 한 줄 적는다. | New |
| R-06 | Minor | backend/core-api/src/main/java/kr/mujoong/core/identity/DevSeedRunner.java > approvedSpeech() (D10) | dev 시드는 004 4단계(002 v1 DRAFT를 RETIRED로)를 하지 않아 dev DB와 demo DB의 HELP_OFFER v1 상태가 다르다. 조회는 APPROVED 최고 판만 보므로 동작은 같다. 사유(U2SchemaIT의 002 DRAFT 7행 단언)는 타당하며 의도된 차이다. 다만 `SeedU8ReferenceSqlIT`의 대조 범위에 이 차이가 명시되어 있는지가 불확실하다. | 차이를 DevSeedRunner 주석과 요약에 그대로 두고, 대조 시험이 4단계를 뺀 비교임을 한 줄 적는다. | New |
| R-07 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/StartSessionHandler.java > start 경로 (`pinContent` 뒤 `registry.activate`·`executor.start()`) | `activated = true` 뒤 `executor.start()`가 예외를 던지면 finally의 `liveContent.release`가 실행되지 않는다. 해제는 실행기 종료 콜백에 맡겨지므로 start 실패 시 콜백이 불리지 않는 경로가 있으면 세션 콘텐츠 계획이 메모리에 남는다. 메모리 내 맵이라 영향은 작지만 BR2.2 "놓기"가 완전하지 않다. | `executor.start()` 실패 시에도 `release`가 불리도록 하거나, 불리지 않는 경로가 없음을 시험으로 보인다. | New |
| R-08 | Minor | backend/coverage-scope/u8.txt 및 code-summary 6절 | realtime 범위 커버리지가 633/788 = 80.3%로 하한 80%에 3줄 여유다. DB 시험을 뺀 수치이고 CI가 막는 범위는 U5b·U7뿐이라 u8 범위는 로컬 근거다. 하한을 낮추지 않고 시험을 더해 맞춘 점(R-F2 준수)은 확인했다. | Build and Test에서 DB 시험 포함 수치로 다시 재고, 80%를 간신히 넘는 realtime 범위는 시험을 조금 더한다. | New |
| R-09 | Minor | infra/bin/render-env (code-summary 10절) | 파일 쓰기 도구 대신 셸 python 치환으로 고친 것은 단계 지시 위반이지만 결과는 안전하다. 실행 권한은 index 100755·작업 트리 rwxr-xr-x 그대로이고, diff는 머리 주석·`OPTIONAL` 배열·`write_env` 인자 추가뿐이다. OPENAI_API_KEY는 core-api·realtime, JEV_API_KEY는 realtime에만 분배되고 `test-host-tools.sh`가 core-api.env에 JEV가 없고 다른 env 파일에 AI 키가 없음을 단언한다. | 조치 불필요. 같은 위반이 재발하지 않게 기록만 남긴다. | New |
| R-10 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u8-contracts-catalog/code-generation/code-summary.md > 10절 (커밋 2637e97 단독 상태) | 첫 커밋 단독 상태에서 `U8ContractSchemasTest` 한 건이 실패한다. 다음 커밋에서 통과하고 한 PR로 병합되므로 영향은 작다. 그러나 병합 이력에서 bisect가 이 커밋에서 멈춘다. | 병합 PR 설명에 이 사실을 한 줄 적는다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `backend/gradlew -p backend --no-daemon :contract-model:test :realtime-activity:test -PincludeTags=u8` | BUILD SUCCESSFUL (contract-model 전체와 realtime u8 태그 시험) | 계약 스키마 시험·realtime U8 단위 시험은 이 PC에서 통과(테스트 대역 기준, 실제 API·실제 기기 증거 아님). core-api DB 시험은 Docker 없음으로 돌리지 못했다(R-04) |
| source manifest 대조(`git diff fc16373 HEAD --name-only`) | manifest에 없는 변경 경로 0건 | 단위와 무관한 경로 없음. aidlc 기록은 커밋하지 않았다 |
| R-M9 비밀 이름 목록 grep (OPENAI_API_KEY / JEV_API_KEY) | ci.yml · image-checks.sh · infra-validate · render-env · log-hygiene-check · lib.sh · b-stack.sh · gen-env.sh · README.md · RUNBOOK.md 모두 포함 | R-M9 목록 전부 충족 |
| 수동 검토: R-F5·R-F6·R-F7·R-F14 | LiveContentRegistry·Resources·Configuration에 로그 호출 없음. 중복 code는 errorKind만 `appError`로 남기고 문구 원문·code 값 없음. core↔realtime 직접 import 없음(공유 계약은 JSON 스키마). 시간 값은 `mujoong.timing.*` 7키로 두 앱 yml·timing.md·설정 레코드에 같은 커밋 | 금지 규칙 위반 없음 |
| 계획 R-01 세 가지 | 004에 HELP_OFFER 멱등 UPDATE, SeedIds 고정 UUID와 004 일치(…0ab, …08a1~4, …08b1~5), `ApprovedContentCatalog.approvedSpeech`가 `latestApproved` 경로 사용 | 세 가지 모두 반영됨. 004의 실행 검증만 R-04로 남음 |
| sensors(required-sections · linter · type-check · traceability) | 직접 실행하지 않았다. traceability.json 구조는 읽었고, OK 대상 파일이 모두 manifest·저장소에 있으며 BR3.3의 `TimeLiteralScanTest`가 실제로 존재함을 확인했다 | 대상 존재는 확인. BR1.1의 OK는 R-02 참고 |

### Summary

계약·설정·조립·판 고정의 구조는 건전하고 R-F 금지 규칙 위반도 찾지 못했다. 사람이 승인 전에 따질 점은 두 가지다. 공유 계약 요약과 어긋난 D5·D8을 편차 표에 올릴지(R-01), 레거시 '도움을 받아 볼까?' 상수가 남은 채 BR1.1을 OK로 둘지(R-02)이다. 004 SQL과 DB 시험은 이 PC에서 한 번도 실행되지 않았으므로 Build and Test 전에 병합하지 말아야 한다(R-04).
