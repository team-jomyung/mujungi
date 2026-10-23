## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-21T04:22:06Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | frontend/src/parent/features/done/useDoneScreen.ts > useDoneScreen, 82행 trusted | CLOSING+PLAYING을 받은 뒤 브라우저가 offline이 되면 인사 줄은 숨지만, online 복구만으로 error=null인 기존 캐시를 다시 신뢰한다. 새 진행 응답이 오기 전에도 이전 PLAYING으로 인사 줄이 돌아온다. 실제 훅 함수를 읽기 전용 대역에서 같은 view/error로 실행했을 때 offline trusted=false → online trusted=true가 관찰됐다. 이는 functional-spec.md F3.6의 이전 PLAYING 캐시 복원 금지와 다르다. 기존 offline 시험은 숨김만 단언하고 복구 뒤 새 응답 전 상태를 확인하지 않는다. | 단절을 관측하면 현재 인사 사실의 신뢰를 무효화하고, 복구 뒤 현재 귀속의 새 조회가 성공한 후에만 재생 중 표시를 허용한다. offline→online 뒤 조회가 지연·실패하는 동안 인사 줄이 계속 숨고, 새 PLAYING 응답에서만 다시 보이는 회귀를 추가한다. | New |
| R-02 | Major | frontend/src/parent/common/api/apiClient.ts > request, 166행 권한 오류 처리 | 진행 GET이 403을 반환해도 무효화는 401에만 연결돼 있어 이전 facts/items가 남는다. 실제 request를 가짜 GET 응답으로 실행했을 때 403의 invalidateLiveIdentity 호출은 0회, 401은 1회였다. useLivePolling은 오류 뒤에도 query.data를 반환하고 presentDone의 stopQuote는 trusted와 무관하므로, 이전에 받은 종료 인용이 계속 표시되는 경로가 남는다. 캐시/화면 영향은 해당 소스 추적으로 확인했다. 이는 현재 U17 security-design.md S1 13행의 인증/권한 거절 시 캐시 제거·로그인 세대 무효화와 NFR1.1의 권한 실패 및 401/403 검증 기대를 충족하지 않는다. | 진행 조회의 최종 권한 거절에서 해당 세션 캐시·요청 귀속과 민감한 이전 표시를 무효화한다. 기존 CSRF 토큰 갱신 재시도 의미는 보존한다. 유효한 인용/말풍선을 먼저 받은 뒤 GET 403을 반환하는 회귀에서 이전 표시가 사라지고 늦은 이전 응답도 복구하지 못함을 확인한다. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/code-generation/traceability.json > coverage의 OK target | sensor-traceability가 invalid_targets 46개로 실패한다. 예를 들어 AC1.2.1의 세미콜론으로 이어 붙인 여러 경로는 단일 파일명으로 해석되며, NFR3.1·NFR4.1 등에는 파일 경로 대신 문서 절/실행 설명이 들어 있다. 실제 소스 부재를 뜻하지는 않지만 자동 추적 대조가 성립하지 않는다. | 각 OK target을 실제 존재하는 workspace-relative 구현 또는 시험 파일 한 개로 정리하고, 보조 파일·문서 절·실행 결과 설명은 justification에 옮긴 뒤 추적 센서의 invalid_targets가 0인지 확인한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| aidlc engine sensor-traceability | FAIL: invalid_targets=46; gaps·orphans·누락·invalid_entries는 빈 배열 | 위 Minor 발견의 형식 문제다. 25개 AC·10개 BR·15개 상세 NFR·6개 머리 NFR의 행 자체는 존재한다. |
| aidlc engine sensor-required-sections | PASS: 계획 7개, 시험 지시 4개, 코드 요약 4개 H2 | 완성된 검토 산출물의 기본 문서 구조를 충족한다. |
| source-manifest·기능 범위·바이트 대조 | PASS: claim 31개, 운영 16개, 기능 범위 16개, 누락 0, 기록 SHA-256 불일치 0 | 공통 apiClient 전체를 포함해 실제 수정 운영 파일이 기능 범위에 들어 있다. 각 claim은 표시·귀속·기기 수명과 그 시험/측정에 관련된다. |
| git diff --check, 31개 claim 한정 | PASS: 종료 0 | 검토 범위의 공백 오류가 없다. |
| 읽기 전용 Node/TypeScript VM 대역 | 종료 0; offline→online의 trusted false→true, GET403 무효화 0회/GET401 1회 | 위 두 Major의 핵심 분기를 실제 소스 함수로 실행했다. 외부 의존은 대역이며 DOM·실브라우저 재현이 아니다. 캐시 잔존과 인용 표시 연결은 소스로 추적했다. 파일·서버·브라우저를 생성하지 않았다. |
| 구현 및 기존 시험 diff 대조 | 선택 객체·현재 응답·정확한 인용·중단 수명 경계 확인 | 낮은 상태판 방지와 같은 판/null 갱신, 공개 CHILD final·명시적 uncertain=false 인용, stop/reset/fail 이전 수명 무효화가 구현됐다. 기존 잘못된 기본 인용/무응답/인사 기대는 명시적 사실과 부재 사례로 강화됐고, 확인한 diff에서 기존 시험 삭제·skip·하한 완화는 없다. |
| 기록된 프런트 검증 증거 | 전체 83파일/1049시험, 기능 27파일/312시험, 신규 51시험; LINE 3515/3566=98.56%, 기능 763/770=99.09%; lint/typecheck/build PASS | baseline-results.md·verification-results.md의 실행 기록을 검토했다. 이번 reviewer가 전체 검사를 재실행한 결과는 아니다. 위 두 미검증 경합을 이 수치가 해소하지 않는다. |

### Summary

Critical 0개·Major 2개·Minor 1개로 정해진 판정 기준상 READY다. 단일 advisory 의견으로서, 사람은 연결 복구 뒤 이전 인사 사실의 재사용과 403 뒤 이전 민감 표시 잔존을 승인 전에 함께 판단해야 한다.

NFR5.2의 실제 320px/200%·브라우저 키보드·기기 조작/회전과 NFR3.3의 전체 verify.sh·실제 대역 서버 통합은 명시적으로 Deferred다. jsdom 성공을 해당 증거로 대체하지 않았으며 브라우저·Chromium·GUI·Next 서버는 재실행하지 않았다. U16 전사 귀속 지적과 운영 승인 음성 부재도 별도 미해결로 유지한다.
