## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-15T16:06:26Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/units-generation/unit-of-work-dependency.md > 의존 그래프 · 간선과 근거 · YAML `u12-practice-conversation` / `u11-judgment-turn` | components.md에는 SessionRuntime(U11) → PracticeConversation(U12)(확장점 호출, 22~34줄)과 PracticeConversation(U12) → LiveJudgment(U11)가 둘 다 있다. 단위로 옮기면 U11 ↔ U12 쌍방향인데, 단위 그래프에는 U12 → U11 한 방향만 있고 반대 방향이 왜 간선이 아닌지 어디에도 적혀 있지 않다. 순환을 끊은 곳은 U14 → U12 하나만 설명한다. 확장점 포트(`PracticeSessionExtension`)를 U11이 소유하고 U12는 구현만 한다면 빌드 시점 간선은 U12 → U11 하나라는 설명이 필요하다(ADR-005). 이 설명이 없으면 U11 Functional Design이 U12 구현 타입을 참조하는지 알 수 없다. | '간선과 근거'에 'U11 → U12는 런타임 주입일 뿐 빌드 시점 의존이 아니다. 확장점 포트 · 지시 타입은 U11 소유, U12는 구현'을 명시하고, U11 경계 책임에 포트 소유를 적는다. | New |
| R-02 | Major | unit-of-work.md > U8 책임(기록 캡처 이벤트) · U12 책임 마지막 항목; unit-of-work-dependency.md > 통합 지점 `SessionRecordCaptured`; components.md > SessionRuntime depends_on ResultPipeline(event) | `SessionRecordCaptured`를 내보내는 주체가 문서마다 다르다. components.md는 SessionRuntime(U11)이 발행한다고 한다. U8은 'U12가 내보내고'라 하고, 통합 지점 표는 'U12 · U11 → U14'라 쓴다. 새 기록 사실(장면 노출 · 도움 · 생략 사유)은 U12 소유 엔티티이고 발행기는 U11이므로, U12의 사실이 U11 발행기로 넘어가는 경로(확장점 출력 능력)가 필요하다. 이것은 U11 → U12 쪽 계약이며, U12 → U11 간선에 얹혀 있어 위 순환과 겹친다. 이 경로가 정해지지 않으면 U14가 기댈 계약을 U8이 쓸 수 없다. | 이벤트 발행 주체를 하나로 못 박는다(권장: 발행은 U11 SessionRuntime, 사실 제공은 U12 확장점 출력). U8 · U12 · 통합 지점 표를 같은 말로 고친다. | New |
| R-03 | Minor | unit-of-work.md > U11 담는 컴포넌트 `OutputGate`, U13 담는 컴포넌트 `OutputGate`의 제브 단계 | OutputGate가 U11과 U13 양쪽에 담겨 있어 '컴포넌트 13개가 한 단위에 하나씩'이 아니다. 합계는 맞지만(8+2+1+4+1+1+2+1=13 중 OutputGate 중복) 같은 파일을 두 단위가 고친다. 주 소유는 U11이고 U13은 단계를 더한다고 구분하는 것이 안전하다. | 컴포넌트 표에 OutputGate 주 소유 단위(U11)와 U13이 더하는 부분을 구분해 적는다. | New |
| R-04 | Minor | unit-of-work-dependency.md > 동시에 만들 수 있는 단위 | (1) 실제로 병렬 가능한 쌍은 U12 ‖ U13, U11 ‖ U15, U13 ‖ U14 등 몇 개뿐인데 'U11 · U12 · U13이 SessionExecutor · OutputGate를 함께 고친다'는 문장은 셋이 서로 병렬인 것처럼 읽힌다. U11 → U12, U11 → U13 때문에 U12와 U13만 진짜 충돌 쌍이다. (2) 'core: U14 · U10'도 U10이 U14에 기대므로 병렬이 아니다. (3) 'U8이 끝나면 U9를 만든다'는 순서 추천이다. 이 단계 경계(위상만)를 넘는다. 또 U8 → U9 → U15 → U14 → U10 → U12의 직렬 사슬이 길어 2~3명 병렬 이득이 제한적이라는 점이 드러나지 않는다. | 병렬 가능 쌍을 정확히 적고(충돌 쌍은 U12 ‖ U13만), 순서 추천 문장을 위상 서술로 바꾼다. 사슬 길이는 Delivery Planning으로 넘긴다고 적는다. | New |
| R-05 | Minor | unit-of-work.md > U8 종류 `spec` + 담는 컴포넌트 `ApprovedContentCatalog`; Assumptions & Open Questions | U8을 `spec`으로 두었지만 realtime 런타임 코드(리소스 적재 · 세션 고정)를 담는다. `spec` 기준 설계 문서만 받으면 이 코드의 Functional Design이 빠질 수 있다. 가정 2로 수락됐으나 위험은 남는다. 또 U8이 U9~U15 전부의 뿌리라 U8 PR 지연이 전체를 막는다. | 승인 때 이 위험을 인지하고, Construction에서 U8에도 ApprovedContentCatalog용 설계(service 수준)를 요구하거나 이 컴포넌트를 U11로 옮길지 정한다. | New |
| R-06 | Minor | unit-of-work-story-map.md > US1.2 · US1.5 '함께 걸치는 단위' U13; unit-of-work-dependency.md 간선 표 | stories.md '의존과 순서'는 US1.2 · US1.5가 U13 없이는 소리가 안 난다고 한다. 단위 간선은 U12 → U13이 없어 U12 인수 시험이 U13 전에는 끝-끝으로 못 돈다. 설명(구현 메모)은 있으나 의존 문서에는 '인수 의존' 표시가 없다. | 의존 문서에 'U12 인수(US1.2 · US1.5)는 U13 이후에야 끝-끝 확인 가능'을 비간선 주석으로 한 줄 더한다. | New |
| R-07 | Minor | units-generation/traceability.json > upstream_ids | upstream_ids가 US 25개뿐이다. U8의 FR10 · FR11과 단위 밖 FR12는 traceability에 없다. 프로젝트 규칙(Corrections)은 묶음 머리 FR와 N/A 행도 넣으라고 한다. | FR10 · FR11 → U8, FR12 → 단위 밖(N/A)을 추가한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| 수동 검증: YAML 간선 vs mermaid vs 간선 표 vs 텍스트 대체 | 일치(15개 간선, 8개 단위) | 모든 표현이 같은 간선을 말하고 있다. |
| 수동 검증: YAML 순환 검사 | 순환 없음(위상 순서 U8 → U9 → U15 → U14 → U10, U11 → U12 · U13 존재) | YAML만 보면 DAG다. 컴포넌트 간선 대비 U11 ↔ U12 쌍방향은 R-01 참조. |
| 수동 검증: kind 값 | spec / library / service만 사용 | 허용 값 안이다. |
| 수동 검증: 컴포넌트 13개 대비 | 13개 모두 어떤 단위에든 포함, OutputGate만 중복 | R-03. |
| 수동 검증: 스토리 25개 대비 | 모두 단위 하나에 대응, traceability와 일치 | 이상 없음. |
| 수동 검증: 저장소 이름 충돌 | `u8`~`u15` 태그 · 범위 파일 · 브랜치 기존 사용 없음 | 충돌 없음. |

### Summary

단위 경계와 YAML 간선은 서로 일치하고 순환도 없다. 다만 컴포넌트 간선 대비 U11 ↔ U12 쌍방향 의존과 `SessionRecordCaptured` 발행 주체 불일치(R-01 · R-02)를 승인 전에 정리하면 Functional Design에서 같은 질문이 반복되지 않는다. 나머지는 서술 정리 수준이다.
