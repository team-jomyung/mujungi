# 구조 · 이름 리팩토링 — 구조 모델 (entities)

> 작업 기록 `261018-structure-refactor` · Functional Design. 이 기록은 데이터 모델이 아니라 **코드 구조**를 바꾸므로, 여기서 말하는 "엔터티"는 구조의 요소(폴더 · 협력 객체 · 이동 매핑 · 이름에 묶인 참조 · 단계)다. 현재 구조는 코드 스캔 `aidlc/spaces/default/codekb/mujungi/code-structure.md`, 팀 확인한 전·후 구조는 `docs/refactor-structure-before-after.md`가 기준이다. 아래 YAML이 정본이고 뒤의 표는 사람이 읽기 위한 요약이다.

```yaml
entities:
  - id: ENT-001
    name: FeatureFolder
    description: 보호자 쪽 한 기능을 이루는 파일들을 모은 폴더 `frontend/src/parent/features/<기능>/`
    attributes:
      - { name: feature, type: string, required: true, unique: true, constraints: "kebab-case 기능 이름, 화면 번호 · 유닛 번호 금지" }
      - { name: screens, type: list<string>, required: true, constraints: "<기능>Screen.tsx 이름" }
      - { name: screenHook, type: string, required: false, constraints: "use<기능>Screen.ts, 대상 화면 9개는 필수" }
      - { name: api, type: string, required: false, constraints: "api.ts, 서버 호출만" }
      - { name: copy, type: string, required: false, constraints: "copy.ts, 기능 전용 문구" }
      - { name: tests, type: string, required: false, constraints: "tests/parent/features/<기능>/ 아래" }
    constraints: ["한 기능만 쓰는 파일만 둔다", "다른 기능 폴더를 import하지 않는다(공통은 parent/common)"]
    relationships:
      - { to: ENT-002, cardinality: "N:1", direction: "FeatureFolder → ParentCommon import" }
      - { to: ENT-009, cardinality: "N:N", direction: "FeatureFolder는 여러 RenameMapping의 대상" }
  - id: ENT-002
    name: ParentCommon
    description: 보호자 쪽 여러 기능이 함께 쓰는 코드 `frontend/src/parent/common/`
    attributes:
      - { name: api, type: path, required: true, constraints: "apiClient · csrf · queries · types" }
      - { name: components, type: path, required: true, constraints: "두 기능 이상이 쓰는 컴포넌트만" }
      - { name: hooks, type: path, required: true, constraints: "useBackGuard 등" }
      - { name: push, type: path, required: true, constraints: "browserPush · pushWorker · useNotificationEntry" }
    constraints: ["보호자 전용이다(기기가 import하지 않는다)", "shared로 올리지 않는다(shared는 보호자 · 기기 공용만)"]
    relationships: []
  - id: ENT-003
    name: DeviceFeature
    description: 기기 쪽 화면 기능 `frontend/src/device/features/<기능>/`
    attributes:
      - { name: feature, type: enum, required: true, allowed: [first-boot, status, debug] }
      - { name: view, type: string, required: true, constraints: "FirstBootView · StatusView · DebugLog" }
    constraints: ["audio · ws · mic · power · runtime · machine · api 폴더는 옮기지 않는다"]
    relationships: []
  - id: ENT-004
    name: SessionContext
    description: 세션 실행기 구역 사이에서 공유하는 상태의 소유자(단일 스레드 모델에서만 접근)
    attributes:
      - { name: sharedFields, type: list<field>, required: true, constraints: "단계 0의 필드 목록에서 확정" }
      - { name: ownerThread, type: string, required: true, constraints: "세션 실행기 스레드" }
    constraints: ["새 스레드 · 동기화 잠금을 만들지 않는다"]
    relationships:
      - { to: ENT-005, cardinality: "1:N", direction: "협력 객체가 생성자로 받는다" }
  - id: ENT-005
    name: SessionCollaborator
    description: SessionExecutor에서 분리하는 협력 객체
    attributes:
      - { name: name, type: enum, required: true, allowed: [EndingFlow, SessionClocks, IdleClock, JoinFlow, JudgmentCoordinator] }
      - { name: sourceSection, type: string, required: true, constraints: "코드 스캔의 구역 표(줄 범위 · 줄 수)" }
      - { name: order, type: int, required: true, constraints: "1 끝내기, 2 시계, 3 공백 시계, 4 참여 확인 · 판정 흡수" }
    constraints: ["실행기 스레드에서만 호출", "분리 시 대상 시험(구역 고정 시험)이 먼저 있어야 한다"]
    relationships:
      - { to: ENT-004, cardinality: "N:1", direction: "SessionContext를 받는다" }
  - id: ENT-006
    name: DeferredSection
    description: 이번 합격 기준에 넣지 않고 마지막 단계에서 결정하는 구역
    attributes:
      - { name: section, type: string, required: true, allowed: ["연습 확장점(약 1,454줄)", "게이트(약 274줄) 중 GuardrailCoordinator 밖 부분"] }
      - { name: decisionPoint, type: string, required: true, constraints: "단계 3의 협력 객체 다섯(3.1 ~ 3.4)과 3.5 정리가 끝난 뒤" }
    constraints: ["결정 없이 옮기지 않는다"]
    relationships: []
  - id: ENT-007
    name: NameBoundReference
    description: 파일 · 패키지 · 클래스 이름을 문자열로 부르는 곳(이름을 바꾸면 깨지는 곳)
    attributes:
      - { name: location, type: path, required: true }
      - { name: form, type: string, required: true, constraints: "패키지 문자열 · 클래스 리터럴 · glob · 경로 · 작업 이름" }
      - { name: breakageSignal, type: string, required: true, constraints: "깨졌을 때 드러나는 시험 · 빌드 · CI" }
    constraints: ["단계 0의 점검표가 목록을 소유한다"]
    relationships:
      - { to: ENT-009, cardinality: "N:N", direction: "RenameMapping이 같은 커밋에서 고친다" }
  - id: ENT-008
    name: ScopeFile
    description: 커버리지 범위 파일과 그것을 부르는 CI 작업
    attributes:
      - { name: oldName, type: string, required: true, constraints: "u1…u15 · u5b-core 등" }
      - { name: newName, type: string, required: true, constraints: "기능 이름" }
      - { name: targets, type: list<path>, required: true, constraints: "이동한 경로로만 고친다, 의미 유지" }
      - { name: ciJob, type: string, required: false, constraints: "u5b-scope 등, ci-gate needs와 같은 커밋" }
    constraints: ["빈 목록 · 없는 파일이면 측정 실패(J137)는 유지", "줄 80% 하한 유지"]
    relationships:
      - { to: ENT-007, cardinality: "1:N", direction: "ci.yml 등 참조" }
  - id: ENT-009
    name: RenameMapping
    description: 옛 위치/이름에서 새 위치/이름으로의 한 줄 대응
    attributes:
      - { name: from, type: path, required: true }
      - { name: to, type: path, required: true }
      - { name: kind, type: enum, required: true, allowed: [move, rename, split, merge] }
      - { name: step, type: int, required: true, constraints: "0 ~ 6" }
      - { name: references, type: list<ref>, required: false, constraints: "ENT-007 참조" }
    constraints: ["이동과 내용 변경은 서로 다른 커밋", "충돌하는 새 이름은 접미어로 구분하고 충돌 목록에 적는다"]
    relationships: []
  - id: ENT-010
    name: MigrationStep
    description: 0 ~ 6단계 중 한 단계
    attributes:
      - { name: no, type: int, required: true, unique: true, constraints: "0 ~ 6" }
      - { name: scope, type: string, required: true }
      - { name: exitChecks, type: list<AcceptanceCheck>, required: true, constraints: "functional-spec.md의 합격 기준 표" }
      - { name: gate, type: string, required: true, constraints: "바꾼 쪽 검사 + verify.sh" }
    constraints: ["앞 단계의 합격 기준이 통과해야 다음 단계로 간다", "단계 6은 주석 규칙 개정이 기록된 뒤에만"]
    relationships:
      - { to: ENT-009, cardinality: "1:N", direction: "단계가 매핑을 소유한다" }
  - id: ENT-011
    name: AcceptanceCheck
    description: 기계로 확인하는 합격 기준 한 줄(명령 + 기대값)
    attributes:
      - { name: id, type: string, required: true, unique: true, constraints: "AC-1 ~ AC-9" }
      - { name: command, type: string, required: true }
      - { name: expected, type: string, required: true }
    constraints: ["열린 목록 대신 grep · glob 조건이나 확정 목록을 쓴다"]
    relationships: []
```

## 요약

| 엔터티 | 위치 | 이 기록에서의 역할 |
|---|---|---|
| FeatureFolder | `frontend/src/parent/features/<기능>/` | 보호자 화면 17개를 기능 11개 폴더로 모으는 대상: login · consent · child · home · device · free · live · done · practice · records · reports (+ 해당 훅 · api · copy) |
| ParentCommon | `frontend/src/parent/common/{api,components,hooks,push}` | 여러 기능이 쓰는 보호자 공통(요구사항 FR3.2의 `shared/api`를 정정, 편차 FD-D1) |
| DeviceFeature | `frontend/src/device/features/{first-boot,status,debug}` | `D0View`→`FirstBootView`, `T19View`→`StatusView`, `DebugLog` |
| SessionContext · SessionCollaborator | `kr.mujoong.realtime.session` | `SessionExecutor` 구역을 `EndingFlow` · `SessionClocks` · `IdleClock` · `JoinFlow` · `JudgmentCoordinator`로 분리 |
| DeferredSection | 연습 확장점 · 게이트 일부 | 마지막 단계에서 결정 |
| NameBoundReference · ScopeFile | ArchUnit · `REALTIME_SHARE` · `TimeLiteralScanTest` · 범위 파일 · `ci.yml` · ESLint · `vitest.config.ts` | 같은 커밋에서 고칠 참조 목록 |
| RenameMapping · MigrationStep · AcceptanceCheck | 이 기록의 `functional-spec.md` | 단계별 이동 매핑, 닫는 조건 |

기능 폴더 11개는 보호자 화면 17개에서 `DevLogin` · `LoginCallback` → `login`, `StopExpressions01` → `child`, `Register05a` → `device`, `Records26` · `RecordDetail27` → `records`, `ReportRange28` · `Report30` → `reports`로 묶은 결과다(화면 17개 → 폴더 11개, `Practice18`과 `parent/practice/*`는 `practice`). 정확한 대응은 `functional-spec.md`의 매핑 표를 따른다.
