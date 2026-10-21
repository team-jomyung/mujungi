## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-21T05:11:30Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | frontend/src/parent/features/done/useDoneScreen.ts > useDoneScreen, 82행 trusted | CLOSING+PLAYING을 받은 뒤 브라우저가 offline이 되면 인사 줄은 숨지만, online 복구만으로 error=null인 기존 캐시를 다시 신뢰한다. 새 진행 응답이 오기 전에도 이전 PLAYING으로 인사 줄이 돌아온다. 실제 훅 함수를 읽기 전용 대역에서 같은 view/error로 실행했을 때 offline trusted=false → online trusted=true가 관찰됐다. 이는 functional-spec.md F3.6의 이전 PLAYING 캐시 복원 금지와 다르다. 기존 offline 시험은 숨김만 단언하고 복구 뒤 새 응답 전 상태를 확인하지 않는다. | 단절을 관측하면 현재 인사 사실의 신뢰를 무효화하고, 복구 뒤 현재 귀속의 새 조회가 성공한 후에만 재생 중 표시를 허용한다. offline→online 뒤 조회가 지연·실패하는 동안 인사 줄이 계속 숨고, 새 PLAYING 응답에서만 다시 보이는 회귀를 추가한다. | Resolved (reviewer) |
| R-02 | Major | frontend/src/parent/common/api/apiClient.ts > request, 166행 권한 오류 처리 | 진행 GET이 403을 반환해도 무효화는 401에만 연결돼 있어 이전 facts/items가 남는다. 실제 request를 가짜 GET 응답으로 실행했을 때 403의 invalidateLiveIdentity 호출은 0회, 401은 1회였다. useLivePolling은 오류 뒤에도 query.data를 반환하고 presentDone의 stopQuote는 trusted와 무관하므로, 이전에 받은 종료 인용이 계속 표시되는 경로가 남는다. 캐시/화면 영향은 해당 소스 추적으로 확인했다. 이는 현재 U17 security-design.md S1 13행의 인증/권한 거절 시 캐시 제거·로그인 세대 무효화와 NFR1.1의 권한 실패 및 401/403 검증 기대를 충족하지 않는다. | 진행 조회의 최종 권한 거절에서 해당 세션 캐시·요청 귀속과 민감한 이전 표시를 무효화한다. 기존 CSRF 토큰 갱신 재시도 의미는 보존한다. 유효한 인용/말풍선을 먼저 받은 뒤 GET 403을 반환하는 회귀에서 이전 표시가 사라지고 늦은 이전 응답도 복구하지 못함을 확인한다. | Resolved (reviewer) |
| R-03 | Minor | aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/code-generation/traceability.json > coverage의 OK target | sensor-traceability가 invalid_targets 46개로 실패한다. 예를 들어 AC1.2.1의 세미콜론으로 이어 붙인 여러 경로는 단일 파일명으로 해석되며, NFR3.1·NFR4.1 등에는 파일 경로 대신 문서 절/실행 설명이 들어 있다. 실제 소스 부재를 뜻하지는 않지만 자동 추적 대조가 성립하지 않는다. | 각 OK target을 실제 존재하는 workspace-relative 구현 또는 시험 파일 한 개로 정리하고, 보조 파일·문서 절·실행 결과 설명은 justification에 옮긴 뒤 추적 센서의 invalid_targets가 0인지 확인한다. | Resolved (reviewer) |
| R-04 | Major | frontend/src/parent/common/api/liveUpdates.ts > readCurrentLiveView, 100–104행 catch와 귀속 검증 순서 | GET403의 invalidateDeniedSession이 인증 세대·sessionId·요청 발행 순서·취소 검사보다 먼저 실행된다. 따라서 이전 세대의 요청이 늦게 403으로 끝나면 새 세대가 같은 세션에 대해 정상 수신한 캐시까지 지운다. 읽기 전용 대역에서 이전 A 요청 대기 → invalidateLiveIdentity → 새 세대 A의 ACTIVE 응답 적용 → 이전 요청403 반환 순으로 실행하니, 정상 캐시 ACTIVE가 undefined로 바뀌었다. 성공 응답에는 있는 현재성 보호가 실패의 캐시 부작용에는 없어 performance-design P1의 모든 적용 경로 귀속 검증과 어긋난다. | 403의 캐시/귀속 변경 전에 해당 실패가 현재 인증 세대·세션·유효한 요청에 속하는지 검사한다. 취소·이전 세대·지난 요청의 실패는 현재 캐시를 지우거나 현재 권한 오류로 적용하지 않게 한다. 기존 현재 세션403 제거는 유지하고, 이전403이 새 세대의 동일 세션 성공 뒤 도착해도 새 캐시·사실 신뢰가 유지되는 회귀를 추가한다. | New |

> R-01 Reviewer note: 연결 사건이 사실·대기 요청의 세대를 무효화하고, 실제 현재 응답이 적용된 객체에만 신뢰를 부여한다. 부모의 원래 online 복구 지연 단언과 동일 PLAYING 성공·실패·낮은 상태판·단절 이전 응답 반례가 보존돼 통과했다.

> R-01 Earlier decision: Accepted risk

> R-02 Reviewer note: 현재 세션 GET403에서 해당 세션 facts/items/인용·귀속을 제거한다. 부모의 원래 인용/캐시 제거 단언과 늦은 성공 응답 차단·다른 세션/계정 캐시 보존·POST CSRF403 갱신/동일 requestId 보존 시험이 통과했다. 아래 신규 발견은 이전 세대의 늦은 실패가 현재 캐시를 지우는 별도 경합이다.

> R-02 Earlier decision: Accepted risk

> R-03 Reviewer note: 추적 87행의 모든 OK target이 단일 실재 workspace 파일이며, sensor-traceability의 invalid_targets·누락·고아 결과가 모두 빈 배열이다. 기존 56행도 유지돼 있다.

> R-03 Earlier decision: Accepted risk

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| npx vitest run tests/parent/features/done/guardrail-review-regression.test.tsx tests/parent/common/guardrail-live-updates.test.tsx tests/parent/common/api/api-client.test.ts | reviewer 직접 실행: 종료0, 3파일30시험 PASS | 부모 원래2개 단언과 보강4개, 기존 응답 순서·401·취소 및 API 경계가 통과한다. 전체 프런트/백엔드 검사를 반복하지 않았다. |
| 읽기 전용 Node/TypeScript VM 재현 | 종료0, 새 ACTIVE 캐시 → 이전403 뒤 null 출력(실제 getQueryData는 undefined) | 현재 liveUpdates.ts를 메모리에서 변환해 실제 QueryClient와 실행했다. fetchLiveView·ApiError는 대역이며 새 테스트 파일을 만들지 않았다. 예상은 새 세대 ACTIVE 유지, 관측은 새 세대 캐시 삭제다. 브라우저/서버 재현이 아니다. |
| aidlc engine sensor-traceability | PASS: findings_count=0, invalid_targets=[] 및 나머지 오류 배열도 빈 배열 | 87개 추적 행의 구조·대상을 검증했다. Deferred를 실행 성공으로 해석하지 않는다. |
| aidlc engine sensor-required-sections | PASS: 계획7개·시험 지시5개·코드 요약5개 H2 | 현재 완성 산출물의 기본 문서 구조를 충족한다. |
| manifest·동결 SHA-256·기능 범위 대조 | PASS: 32claim, 최신 지문 불일치0, 운영16파일의 범위 누락0 | 이번 수정은 기존 liveUpdates/useLivePolling/useDoneScreen 및 추가 회귀/시험 목록으로 한정된다. 나머지 이전 검토 소스는 동결 바이트와 일치한다. |
| git diff --check, manifest 32경로 한정 | PASS: 종료0 | 검토 소스의 공백 오류가 없다. |
| 기록된 현재 전체/기능 검증 근거 | 전체84파일1055시험·LINE3543/3594, 기능28파일318시험·LINE791/798, lint/build PASS | verification-results.md의 최신 Loop-back 1 기록과 현재 소스 지문을 대조했다. 이는 개발자 실행 기록이며 reviewer가 전체 검사를 다시 실행한 결과는 아니다. 신규 stale403 반례를 기존 PASS가 검증하지는 않는다. |

### Summary

기존 세 지적의 실제 수정과 회귀 보존을 확인했다. 신규 Major 1개·Critical 0개로 판정 기준상 READY이며, 이전 인증 세대의 늦은403이 현재 성공 캐시를 지우는 경합은 사람의 판단과 후속 보완이 필요하다.

실제 브라우저320px/200%·키보드/기기 조작, 운영 승인 말소리, 전체 verify.sh·대역 서버 통합은 미완료로 유지한다. 브라우저·Chromium·GUI·Next·백엔드·전체 verify·유료 공급자는 실행하지 않았고, 앱·기존 산출물·상태는 수정하지 않았다.
