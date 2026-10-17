## Review

**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-10-17T11:54:51Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261017-u13-jev-guardrail/inception/requirements-analysis/requirements.md > FR4.3 (Q2 답 A) | "검사 중 아이가 말을 시작하면(입력 전사 조각 도착)"이 무엇을 '말 시작'으로 보는지 정하지 않았다. 코드에서 입력 전사 조각(`onInputTranscriptDelta`)은 아이 말이 끝난 뒤에도 `input-transcript-quiet`까지 계속 도착할 수 있고, U11-d는 입력 전사 확정보다 응답이 먼저 재생되는 턴을 이미 인정한다. 그대로 구현하면 같은 아이 말의 뒤늦은 전사 조각이 정상 응답의 `ALLOW`를 무효로 만들어 '다시 지시 → 또 막힘'을 불러올 수 있다. 완료 기준의 시험("검사 대기 중 입력 전사 조각을 넣고")은 이 거짓 무효를 구분하지 못한다. | 무효 조건을 "응답 생성 시작 뒤에 새로 시작한 아이 입력"처럼 구분 가능한 조건으로 쓰고(이미 진행 중이던 같은 턴의 전사 꼬리 조각은 제외 여부를 명시), 두 경우를 가르는 pass/fail 시험 기준을 더한다. 정할 수 없으면 Functional Design으로 넘기는 열린 질문에 올린다. | Resolved (reviewer) |
| R-02 | Major | requirements.md > FR3.2, FR1.1, FR5.2 | FR3.2는 두 번째 막힘 뒤 "연습은 연습 확장점에 '차단됨'을 알린다"와 그 시험을 요구하지만, FR5.2(Q5 답 A)는 지금 운영에서 제브를 부르는 것이 `FREE_TALK` 새 발화뿐이고 연습 출력 통제 중에는 호출되지 않는다고 한다. 연습 경로는 운영에서 제브 결과를 받을 수 없으므로 '차단됨' 이벤트는 호출자가 없는 코드가 되고, 완료 기준이 어떤 경로로 연습 차단을 시험하는지 불명확하다. 두 요구가 서로 어긋난 채 남아 있다(phases/inception.md의 "미해결 모순 금지"). | FR3.2의 연습 갈래를 "확장점 이벤트 정의와 단위 시험까지, 운영 호출은 U12"로 범위를 명시해 FR5.2와 맞추거나, 이벤트를 U12로 미룬다. 완료 기준에 시험이 쓰는 모드(`PRACTICE_*`를 대역으로 주입)를 적는다. | Resolved (reviewer) |
| R-03 | Major | requirements.md > FR7.1, FR1.3, FR7.2 | 'demo · prod에서 제브 실검사가 꺼져 있으면 기동 실패', '대역 · 비활성 모드'를 켜고 끄는 설정 키 이름 · 값이 어디에도 없다(`application.yml`의 `guardrail` 절과 `contracts/ai-io.md`에는 `JEV_API_KEY`와 `jev-budget`뿐). 조합 시험("프로필 × 키 × 스위치")의 입력이 정의되지 않았고, 새 설정 키는 `ConfigKeyContractTest`와 R-M10(`contracts/`) 대상이 될 수 있는데 FR8.2에도 이 계약 변경이 없다. | 모드 선택 키(이름 · 허용값 · 프로필별 기본값)를 요구사항이나 명시된 Functional Design 결정으로 적고, 키가 늘면 `contracts/`와 R-M10 정정 항목에 넣는다. | Resolved (reviewer) |
| R-04 | Minor | requirements.md > 머리말 우선순위 목록(1 ~ 4번) vs 질문 파일 Q3 · Q1 · Q2 · Q4 · Q5 | 우선순위가 "이 단계의 답"을 지시서(3번) 밑 4번에 두지만, 실제로는 Q3-C가 지시서의 "지표 이름이 `contracts/` 표에 있어야 한다" 문장을 이긴다. project.md 교정("사람이 명시적으로 다른 쪽을 고르면 그 선택을 앞세운다")과도 어긋나 읽는 사람이 어느 쪽이 정본인지 헷갈린다. | 우선순위 문장에 "이 단계에서 사람이 명시적으로 고른 답은 위 출처와 다를 때 앞선다"를 더하고, Q3-C가 지시서 문장을 바꾼다는 편차를 FR6.3에 한 줄 적는다. | Resolved (reviewer) |
| R-05 | Minor | requirements.md > FR3.2 완료 기준 | 원 AC2.1.3의 "그동안 기기는 '생각 중'을 유지"가 완료 기준에 없다. 스캔(1절)은 현재 폐기 경로가 `WAITING_FILLER` 또는 `listeningAgain`으로 가므로 `listeningAgain`에 제브 대기 조건을 더해야 한다고 적었다. 또 '또 막히면'이 세션 · 턴 · 구간 중 무엇 단위로 세는지 없다. | 완료 기준에 "제브 대기 · 다시 지시 동안 기기 상태가 THINKING"을 더하고 재지시 횟수의 단위를 정한다. | Resolved (reviewer) |
| R-06 | Minor | requirements.md > FR6.3 | 계측은 "세션이 끝날 때" 남긴다면서 완료 기준은 "가짜 제브 한 턴 뒤 지표가 올라가는" 시험이라 시점이 맞지 않는다. 지표 이름 · 종류(카운터 · 히스토그램)가 없고 설계 문서로 미뤘으므로 무엇을 합격으로 볼지 모호하다. | 시험이 확인할 항목 5가지(출처별 호출 수, P50 · P95 · P99, 차단 · 시간 초과 비율, 체감 대기)와 기록 시점을 완료 기준에 맞춘다. | Resolved (reviewer) |
| R-07 | Minor | requirements.md > FR2.1, FR2.2, FR7.2 | `jev-latest` 금지(고정 모델 버전)와 "익명화한 최근 맥락"의 익명화 규칙이 완료 기준에 시험 항목으로 없다. FR7.2의 `image-checks.sh` 더미 키는 R-F3(토큰 값 금지)과 충돌하지 않는 가짜 값의 형태 · 위치를 적어 두지 않았다. | 모델 버전 고정 시험, 익명화(식별자 필드 부재 · 이름 치환 여부) 기준, 더미 키는 `.gitleaks.toml` 규칙(R-F9)과 맞는 가짜 값임을 한 줄씩 보탠다. | Resolved (reviewer) |

> R-01 Reviewer note: 해결됨. FR4.3이 '새로 말을 시작'을 "검사 대상 구간의 첫 출력 조각 뒤에 새 입력 묶음이 열린 경우"로 정하고, 같은 입력 묶음의 전사 꼬리 조각은 무효 조건에서 제외한다고 명시했다. 완료 기준에 ① 꼬리 조각 도착 시 `ALLOW` 유효 · 재생 ② 새 입력 묶음 뒤 `ALLOW` 무효 · 기록 유지라는 pass/fail 시험 두 개가 있다. 정확한 신호(입력 묶음 id 등)는 열린 질문 1로 Functional Design에 넘겼다. FR3.3(출력 꼬리 조각)과는 대상이 달라 충돌하지 않는다.; Required action: 없음

> R-02 Reviewer note: 해결됨. FR3.2의 연습 갈래가 "확장점 이벤트 정의와 단위 시험까지, 운영 호출은 U12"로 범위가 정해졌고 FR5.2와 A14가 같은 문장으로 맞춘다. 완료 기준에 시험이 `PRACTICE_PEER` · `PRACTICE_COACH`를 대역으로 주입해 "차단됨"이 한 번 전달되는지 확인한다고 적었다. 모순이 남아 있지 않다.; Required action: 없음

> R-03 Reviewer note: 해결됨(사람이 정한 대로 Functional Design으로 이월). 모드 선택 키의 이름 · 허용값 · 기본값은 열린 질문 9로 올렸고, FR1.3 · FR7.1 · FR8.2가 같은 문장으로 이어진다. 키가 정해지면 `ai-io.md` 10절 · `ConfigKeyContractTest` · R-M10에 같은 커밋으로 넣는다고 FR8.2에 명시했다. FR7.1은 키 이름 없이도 "프로필 × 키 유무 × 모드" 표와 허용 · 거부 칸(`dev`만 대역 · 비활성, `demo` · `prod`는 실검사 + 키 필수)을 정해 시험 입력이 정의된다. 두 요구 사이 모순은 없다.; Required action: 없음

> R-04 Reviewer note: 해결됨. 머리말에 "이 단계에서 사람이 명시적으로 고른 답은 위 출처와 다를 때 앞선다"를 더했고, FR6.3에 Q3 답 C의 편차(지시서 vs 설계 문서 지표 이름)를 근거와 함께 적었다.; Required action: 없음

> R-05 Reviewer note: 해결됨. 완료 기준에 제브 대기 · 다시 지시 동안 `THINKING` 유지를 넣고, `listeningAgain` · `WAITING_FILLER`가 상태를 바로 풀지 않게 하는 요구를 더했다. 재지시 횟수는 턴 단위로 정했고 세부(사이에 다른 응답이 끼는 경우)는 열린 질문 10으로 남겼다.; Required action: 없음

> R-06 Reviewer note: 해결됨. 시험이 확인할 항목 ①~⑤(출처별 호출 수, P50 · P95 · P99, 차단 · 시간 초과 비율, 체감 대기)와 기록 시점(검사마다 지표, 세션 종료 시 합계 한 줄)을 완료 기준에 맞췄다. 지표 이름 · 종류는 `design.md`로 미룬다고 명시했다.; Required action: 없음

> R-07 Reviewer note: 해결됨. FR2.1에 `jev-latest` 같은 이동 별칭 기동 거부 시험, FR2.2에 익명화 기준(이름 → '아이', 식별자 필드 부재, 3턴 이하, 지시문은 맥락 필드에만, 키 본문 없음)과 시험 항목이 생겼다. FR7.2는 더미 키가 실행 때 만드는 가짜 값이며 `.gitleaks.toml` 허용 항목(R-F9)과 맞추고 비밀 스캔(A4 · P3)에 걸리지 않는다고 적었다. 더미 값의 정확한 형태 · 위치는 Functional Design 몫이라 구현 시작에 걸림돌이 아니다.; Required action: 없음

### Summary

이전 7건이 모두 요구사항에 반영됐다. 수정 과정에서 새 모순은 보이지 않았고(FR4.3 ↔ FR3.3, FR7.1 ↔ FR1.3 모두 정합), 남은 모드 키 · 입력 묶음 신호 · 재지시 세부는 열린 질문 1 · 9 · 10으로 Functional Design에 명시적으로 넘겨졌다. 엔지니어링이 되묻지 않고 시작할 수 있는 수준이라 READY로 본다.
