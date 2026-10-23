## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-21T07:05:08Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | frontend/src/parent/features/done/useDoneScreen.ts > useDoneScreen, 82행 trusted | CLOSING+PLAYING을 받은 뒤 브라우저가 offline이 되면 인사 줄은 숨지만, online 복구만으로 error=null인 기존 캐시를 다시 신뢰한다. 새 진행 응답이 오기 전에도 이전 PLAYING으로 인사 줄이 돌아온다. 실제 훅 함수를 읽기 전용 대역에서 같은 view/error로 실행했을 때 offline trusted=false → online trusted=true가 관찰됐다. 이는 functional-spec.md F3.6의 이전 PLAYING 캐시 복원 금지와 다르다. 기존 offline 시험은 숨김만 단언하고 복구 뒤 새 응답 전 상태를 확인하지 않는다. | 단절을 관측하면 현재 인사 사실의 신뢰를 무효화하고, 복구 뒤 현재 귀속의 새 조회가 성공한 후에만 재생 중 표시를 허용한다. offline→online 뒤 조회가 지연·실패하는 동안 인사 줄이 계속 숨고, 새 PLAYING 응답에서만 다시 보이는 회귀를 추가한다. | Resolved (reviewer) |
| R-02 | Major | frontend/src/parent/common/api/apiClient.ts > request, 166행 권한 오류 처리 | 진행 GET이 403을 반환해도 무효화는 401에만 연결돼 있어 이전 facts/items가 남는다. 실제 request를 가짜 GET 응답으로 실행했을 때 403의 invalidateLiveIdentity 호출은 0회, 401은 1회였다. useLivePolling은 오류 뒤에도 query.data를 반환하고 presentDone의 stopQuote는 trusted와 무관하므로, 이전에 받은 종료 인용이 계속 표시되는 경로가 남는다. 캐시/화면 영향은 해당 소스 추적으로 확인했다. 이는 현재 U17 security-design.md S1 13행의 인증/권한 거절 시 캐시 제거·로그인 세대 무효화와 NFR1.1의 권한 실패 및 401/403 검증 기대를 충족하지 않는다. | 진행 조회의 최종 권한 거절에서 해당 세션 캐시·요청 귀속과 민감한 이전 표시를 무효화한다. 기존 CSRF 토큰 갱신 재시도 의미는 보존한다. 유효한 인용/말풍선을 먼저 받은 뒤 GET 403을 반환하는 회귀에서 이전 표시가 사라지고 늦은 이전 응답도 복구하지 못함을 확인한다. | Resolved (reviewer) |
| R-03 | Minor | aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/code-generation/traceability.json > coverage의 OK target | sensor-traceability가 invalid_targets 46개로 실패한다. 예를 들어 AC1.2.1의 세미콜론으로 이어 붙인 여러 경로는 단일 파일명으로 해석되며, NFR3.1·NFR4.1 등에는 파일 경로 대신 문서 절/실행 설명이 들어 있다. 실제 소스 부재를 뜻하지는 않지만 자동 추적 대조가 성립하지 않는다. | 각 OK target을 실제 존재하는 workspace-relative 구현 또는 시험 파일 한 개로 정리하고, 보조 파일·문서 절·실행 결과 설명은 justification에 옮긴 뒤 추적 센서의 invalid_targets가 0인지 확인한다. | Resolved (reviewer) |
| R-04 | Major | frontend/src/parent/common/api/liveUpdates.ts > readCurrentLiveView, 100–104행 catch와 귀속 검증 순서 | GET403의 invalidateDeniedSession이 인증 세대·sessionId·요청 발행 순서·취소 검사보다 먼저 실행된다. 따라서 이전 세대의 요청이 늦게 403으로 끝나면 새 세대가 같은 세션에 대해 정상 수신한 캐시까지 지운다. 읽기 전용 대역에서 이전 A 요청 대기 → invalidateLiveIdentity → 새 세대 A의 ACTIVE 응답 적용 → 이전 요청403 반환 순으로 실행하니, 정상 캐시 ACTIVE가 undefined로 바뀌었다. 성공 응답에는 있는 현재성 보호가 실패의 캐시 부작용에는 없어 performance-design P1의 모든 적용 경로 귀속 검증과 어긋난다. | 403의 캐시/귀속 변경 전에 해당 실패가 현재 인증 세대·세션·유효한 요청에 속하는지 검사한다. 취소·이전 세대·지난 요청의 실패는 현재 캐시를 지우거나 현재 권한 오류로 적용하지 않게 한다. 기존 현재 세션403 제거는 유지하고, 이전403이 새 세대의 동일 세션 성공 뒤 도착해도 새 캐시·사실 신뢰가 유지되는 회귀를 추가한다. | Resolved (reviewer) |

> R-01 Earlier decision: Accepted risk

> R-02 Earlier decision: Accepted risk

> R-03 Earlier decision: Accepted risk

> R-04 Reviewer note: frontend/src/parent/common/api/liveUpdates.ts의 readCurrentLiveView가 403의 invalidateDeniedSession 호출 전에 generation/sessionId/sequence/AbortSignal을 검사한다. 지난 실패는 aborted로 종료하며 현재 실패만 해당 세션을 제거한다. 인증 세대·최신 요청 순번·동일 세션 이탈/재진입·단독 취소의 네 회귀에서 새 성공 캐시와 hasCurrentLiveFacts가 보존되고, 현재403의 세션별 제거·늦은200 복구 금지·CSRF/401 기존 동작을 포함한 관련34시험이 직접 실행에서 통과했다.

> R-04 Earlier decision: Accepted risk

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| npx vitest run tests/parent/common/guardrail-live-updates.test.tsx tests/parent/features/done/guardrail-review-regression.test.tsx tests/parent/common/api/api-client.test.ts | PASS: 3파일34시험, 종료0 | 실제 ApiClient·QueryClient와 가짜 fetch에서 늦은403 네 경계 및 현재403·401·CSRF 보존을 확인했다. 전체 시험·빌드는 중복 실행하지 않았다. |
| aidlc engine sensor-traceability --stage code-generation --output-path aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/code-generation/traceability.json | PASS: findings_count=0, invalid_targets=[] | 현재87행의 추적 및 실제 단일 파일 target이 유효하다. |
| aidlc engine sensor-required-sections: code-generation-plan.md, code-summary.md, unit-test-instructions.md | PASS: 각 h2_count 8/6/5, findings_count=0 | 검토 대상의 필수 문서 구조가 유효하다. |
| manifest·범위·SHA-256 읽기 전용 대조 | PASS:32개 고유 claim, 운영16파일, 시험28경로 실재, listing sha256:1fafac26b9f0de78e237da0d8edbe2685599db9c558b19756b3ea0e95ec1d2bf | 요청된 동결 지문과 일치한다. 현재 coverage-summary의 전체 LINE3546/3597=98.58%, 범위16파일 합산 LINE794/801=99.12%가 기록된 전체1059/기능322시험 결과의 커버리지 수치와 일치한다. 시험 수·lint·build 종료 결과는 검증 기록으로 확인했고 독립 재실행과 구분한다. |
| git diff --check -- frontend 및 U17 code-generation 경로 | PASS: 종료0 | 변경 파일의 공백 오류가 없다. |

### Summary

이번 늦은403 수정은 성공 적용과 같은 현재성 경계를 부작용 직전에 사용하며, 검사와 제거 사이에 비동기 틈이 없다. 새 지적은 없고, 실제 브라우저·운영 말소리·전체 verify 및 대역 서버 통합의 미검증은 명시된 Deferred로 유지하므로 이 검토를 서비스 전체 검증 완료로 해석하지 않는다.
