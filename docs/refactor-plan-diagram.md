# 구조 · 이름 리팩토링 — 전체 계획 도식 (팀 공유용)

> 작성 2026-10-18 · 승인된 Code Generation 계획(`aidlc/spaces/default/intents/261018-structure-refactor/construction/code-generation/code-generation-plan.md`) 기준 · 기준 커밋 원격 `main` 570a625 · 작업 브랜치 `claude/refactor-structure`
> GitHub에서 열면 아래 mermaid 도식이 그림으로 보인다. 자세한 구조 전·후는 [`refactor-structure-before-after.md`](refactor-structure-before-after.md), 배경은 [`refactor-structure-proposal.md`](refactor-structure-proposal.md).
> **동작 · 계약 · 설정 키 · DB 스키마는 바꾸지 않는다.** 구조와 이름만 바꾼다.

## 1. 전체 로드맵 (25단계 · 7묶음)

```mermaid
flowchart TD
    A["단계 0 · 안전망<br/>Step 1~4<br/>main 기준선 · 7구간 특성화 시험<br/>필드 소유자 표 · 참조 점검표 · screen-map"]
    B["단계 1 · 프런트 이동<br/>Step 5~8<br/>보호자 화면 17 · api/components/hooks · 기기 화면<br/>(git mv + 참조 갱신)"]
    C["단계 2 · 화면 흐름 훅<br/>Step 9~11<br/>9개 화면 → use…Screen<br/>account/queries 분할 · 연습 api/copy 통합"]
    D["단계 3 · 실행기 분리<br/>Step 12~17<br/>SessionContext → EndingFlow → SessionClocks<br/>→ IdleClock → JoinFlow+판정 → 정리"]
    E["단계 4 · 이름<br/>Step 18~19<br/>설정 레코드 분리 · practice → practicelegacy"]
    F["단계 5 · 시험 · CI 이름<br/>Step 20~23<br/>tests/u* · 범위 파일 26 · Java 103 · @Tag 450 · ci.yml"]
    G["단계 6 · 주석 정리 (조건부)<br/>Step 24<br/>team.md 규칙 학습 승인 후"]
    H["마무리 · Step 25<br/>문서 · 추적성 · 비밀 스캔"]
    BT["Build and Test<br/>verify.sh 전체 + PR 1개"]

    A --> B --> C --> D --> E --> F --> G --> H --> BT
    D -. "Step 17: 사람이 판단<br/>게이트 경계 · 연습 확장점(1,454줄) 분리 여부" .-> D
    G -. "사람 승인 필요<br/>거절이면 건너뜀" .-> G
```

## 2. 단계마다 반복하는 확인 루프

```mermaid
flowchart LR
    S1["작업 커밋<br/>이동 · 추출 + 참조 갱신<br/>(로직 변경은 별도 커밋)"] --> S2["바꾼 쪽 검사<br/>gradlew check · eslint · vitest · next build"]
    S2 --> S3["bash scripts/verify.sh<br/>끝-끝 (가짜 공급자)"]
    S3 --> S4["시험 수 대조<br/>단계 전후 동일"]
    S4 --> S5["합격 기준 AC-1~9 중<br/>그 단계 몫 확인"]
    S5 --> S6["main을 브랜치로 merge<br/>(따라잡기)"]
    S6 --> S7["다음 단계"]
    S2 -- 실패 --> X["원인 확인 후 되돌림<br/>(main 기준선과 비교)"]
    S3 -- 실패 --> X
    X --> S1
```

## 3. 브랜치 · 롤백

```mermaid
gitGraph
    commit id: "main 570a625" tag: "snapshot/main-pre-refactor-20261018"
    branch claude/refactor-structure
    checkout claude/refactor-structure
    commit id: "단계 0 안전망"
    commit id: "단계 1 프런트 이동"
    commit id: "단계 2 훅 분리"
    checkout main
    commit id: "(다른 작업이 main에 병합)"
    checkout claude/refactor-structure
    merge main id: "main 따라잡기"
    commit id: "단계 3 실행기 분리"
    commit id: "단계 4 ~ 5 이름"
    checkout main
    merge claude/refactor-structure id: "PR 1개 merge commit"
```

- 작업은 통합 브랜치에서만 한다. `main`에는 직접 올리지 않고, 끝에 PR 하나(merge commit)로 합친다.
- 롤백 기준점: 원격 `snapshot/main-pre-refactor-20261018`(570a625). 병합 전에는 `main`이 그대로이고, 병합 뒤에는 PR 하나를 되돌린다.

## 4. 실행기 분리 (단계 3)

```mermaid
flowchart LR
    subgraph before ["지금: SessionExecutor 4,361줄"]
      b1["머리 619"]; b2["메시지 처리 236"]; b3["참여 확인 78"]; b4["판정 턴 260"]
      b5["연습 확장점 1,454"]; b6["공백 시계 252"]; b7["게이트 274"]; b8["끝내기 504"]; b9["시계 184"]; b10["전이 보조 282"]
    end
    subgraph after ["이후 (3.4 뒤 3,100줄 이하)"]
      a0["SessionExecutor<br/>분배 · 재전달 · 최상위 예외"]
      a1["SessionContext<br/>공유 상태 (Step 12)"]
      a2["EndingFlow (Step 13)"]
      a3["SessionClocks (Step 14)"]
      a4["IdleClock (Step 15)"]
      a5["JoinFlow (Step 16)"]
      a6["JudgmentCoordinator<br/>판정 턴 흡수 (Step 16)"]
      a7["게이트 · 연습 확장점<br/>Step 17에서 사람이 결정"]
    end
    b1 --> a1
    b8 --> a2
    b9 --> a3
    b6 --> a4
    b3 --> a5
    b4 --> a6
    b7 --> a7
    b5 --> a7
    b2 --> a0
    b10 --> a1
```

- 한 세션을 한 스레드로 처리하는 모델은 그대로다. 새 스레드 · 잠금을 만들지 않는다.
- 클래스를 나누면 `SingleInstanceMarkerTest`의 `REALTIME_SHARE`(SI-2 · SI-9), `TimeLiteralScanTest` 허용 목록, ArchUnit, 범위 파일을 **같은 커밋**에서 고친다.

## 5. 프런트 구조 전 · 후 (요약)

```mermaid
flowchart LR
    subgraph before ["지금"]
      p1["parent/screens/<br/>Login01 … Report30 (17)"]
      p2["parent/api · components · hooks<br/>activity · practice · push"]
      p3["device/components/<br/>D0View · T19View"]
      p4["frontend/tests/u1 … u14"]
    end
    subgraph after ["이후"]
      q1["parent/features/&lt;기능&gt;/<br/>XxxScreen · useXxxScreen · api.ts · copy.ts"]
      q2["parent/common/<br/>api · components · hooks · push · styles"]
      q3["device/features/<br/>first-boot · status · debug"]
      q4["frontend/tests/ (소스 구조를 따라감)"]
    end
    p1 --> q1
    p2 --> q1
    p2 --> q2
    p3 --> q3
    p4 --> q4
```

기능 폴더 11개: `login` · `consent` · `child` · `home` · `device` · `free` · `live` · `done` · `practice` · `records` · `reports`.

## 6. 이름이 바뀌는 것 (단계 5)

| 무엇 | 지금 → 이후 (예) | 수 |
|---|---|---|
| 범위 파일 | `backend/coverage-scope/u12.txt` → `practice-flow.txt` | 백엔드 18 + 프런트 8 |
| 시험 태그 | `@Tag("u5b")` → `@Tag("practice-legacy")` | 450개 파일 |
| Java 클래스 | `U4aActivityApiIT` → `ActivityApiIT` (겹치면 `…기능이름…`) | 103개 (겹침 20건) |
| 프런트 시험 폴더 | `tests/u5b` → 소스 구조 폴더 + 시험 파일 목록 | 74개 시험 파일 |
| CI 작업 | `u5b-scope` → `practice-legacy-scope` 계열 | ci.yml |
| 바꾸지 **않는** 것 | Flyway 파일명 (`V7__u12_…`), 설정 키, 오류 코드, 시간 값, API · 이벤트 계약 | — |

유닛 번호 → 기능 이름 대응표 정본: `functional-spec.md` 3.1절 (u1 `skeleton`, u2 `parent-account`, … u12 `practice-flow`, u13 `jev-guardrail`, u14 `goal-result`, u15 `knowledge-graph`).

## 7. 합격 기준 (기계로 확인)

| ID | 단계 | 확인 | 시작 전 → 기대 |
|---|---|---|---|
| AC-1 | 1 | 화면 번호 · 유닛 번호가 든 프런트 파일, `parent/screens` 폴더 | 17 + 1 → 0, 폴더 없음 |
| AC-2 | 5 | 유닛 번호가 든 경로 · `@Tag` · CI 줄 | 230 / 450 / 17 → 0 |
| AC-3 | 2 | 대상 9개 `*Screen.tsx`의 `router.` · `catch` · `await` | 5~21건 → 0 |
| AC-4 | 3 | `SessionExecutor` 줄 수 | 4,361 → 3.4 뒤 3,100 이하 |
| AC-5 | 모든 | 바꾼 쪽 검사 + `verify.sh`, 시험 수 동일 | 통과 |
| AC-6 | 1 · 4 | ESLint 경계 · ArchUnit | 통과, 규칙이 비지 않음 |
| AC-7 | 6 | 건드린 파일의 결정 ID 주석 수 | 기준선보다 감소 (승인된 경우) |
| AC-8 | 5 | 프런트 범위 시험 수 | `practice-legacy` 3파일 32시험, `web-push` 6파일 89시험 동일 |
| AC-9 | 5 | `hashFiles` 조건 CI 단계 | 건너뛰기 아님(`success`) |

## 8. 팀이 이미 정한 것 / 아직 사람이 정할 것

| 이미 정함 (팀 승인) | 아직 사람이 정함 |
|---|---|
| 주석 규칙은 "무엇을 왜"로 바꾼다(건드린 파일부터) | **Step 17**: 게이트 경계, 연습 확장점 분리 여부 |
| 실행기는 작은 구역부터 분리 | **Step 24**: `team.md` 규칙 4개 개정 승인(거절이면 주석 정리 생략) |
| `practice` → `practicelegacy` | 원격 `main`의 브랜치 보호가 없는 것이 의도인지(팀 확인) |
| 유닛 번호 이름은 전부 변경 | `docs/units` 이력 문서는 고치지 않고 대응표로 안내(기본값) |
| 통합 브랜치 1개 + PR 1개, 단계마다 `verify.sh` | |
| 보호자 공통은 `parent/common`, 기기는 화면만 `features` | |

## 9. 진행 상태 (AI-DLC)

| 단계 | 상태 |
|---|---|
| Reverse Engineering | 완료 |
| Requirements Analysis | 완료 |
| Functional Design | 완료 (검토 READY) |
| **Code Generation · 계획 승인** | **완료** — 코드 변경은 아직 시작하지 않음 |
| Code Generation · 실행 (Step 1~25) | 대기 |
| Build and Test | 대기 |
| 운영 단계 (배포 파이프라인 · 실행) | 이 리팩토링은 배포를 다루지 않아 건너뜀 |
