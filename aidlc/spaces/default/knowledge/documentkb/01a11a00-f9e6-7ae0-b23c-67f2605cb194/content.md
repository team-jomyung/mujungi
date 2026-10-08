① 개정: Monorepo의 backend 안에서 Core/Realtime 두 실행·빌드 단위, 최소 공유 계약·앱별 스캔/스케줄러와 CI 확인 범위를 연결했다. 문서 연결의 현재 완료 상태를 정리했다.
② 대체: 이전 ADR 묶음의 단일 Spring 실행 전제는 대체된 이전 선택으로 남겼으며 기존 루트 구조·Git/PR·민감자료 규칙과 결정 상태는 보존했다. 문서 반영을 아직 예정으로 적은 표현은 현재 상태로 대체했다.
③ 남은 TBD: 두 앱의 실제 Gradle 프로젝트/모듈 경로·산출물·공유 계약 배치와 스캔/스케줄러·CI 구현; 기존 저장소 공개 범위와 팀 Git/PR 합의. 문서 연결 완료와 구분해 유지한다.

# ADR-001 저장소·Git·협업 기본값

- 상태: **Proposed**
- 관련: [ADR-002 기술 기본값](ADR-002-technology-defaults.md)
- 확정 전 확인: 저장소 공개 범위와 팀의 Git·PR 운영 규칙 합의

## Context

프론트엔드, 백엔드, AI POC, 문서를 동시에 개발한다. 기능 하나가 여러 영역을 함께 바꿀 수 있으므로 팀원은 같은 저장소 구조와 Git 규칙을 사용해야 한다. AI-DLC, Claude Code, Codex도 동일한 프로젝트 규칙을 참조해야 한다.

실제 아동 자료와 인증 정보가 개발 자료에 섞일 수 있다. 특히 저장소를 공개하면 잘못 커밋한 자료가 외부에 노출되므로 저장소 공개 여부와 자료 반입 규칙을 분리해 관리할 필요가 있다.

## Decision

### 저장소 구조

GitHub Organization의 **Monorepo**를 사용한다. 루트에는 다음 디렉터리를 둔다.

```text
backend/
frontend/
ai/
docs/
```

`project/project/backend`처럼 프로젝트 폴더를 중첩하지 않는다. `ai/`의 운영 서버 여부는 [ADR-002](ADR-002-technology-defaults.md)에서 정한다.

### 두 실행 앱의 저장소·빌드 영향

audit `ref/mujung-architecture-update-audit-v1.1.md` §2·§3·§7을 적용하여 `backend/` 안의 **Core API Spring과 Realtime Activity Spring을 별도 실행·빌드 단위**로 다룬다. 실행 특성·상태 소유권의 선택은 [ADR-009](ADR-009-execution-boundary.md), 앱별 모듈/의존 방향은 [ADR-007](ADR-007-module-boundaries.md), 기술 후보는 [ADR-002](ADR-002-technology-defaults.md), 배포 산출물은 [ADR-006](ADR-006-demo-deployment.md)에 연결한다. Monorepo와 위 루트 디렉터리 이름을 유지한다.

- 각 앱의 Boot 진입점·실행 설정·빌드 산출물을 구분한다. 실제 Gradle 프로젝트/모듈 경로·산출물 이름·공통 버전 관리 방식은 TBD이며 이 문서에서 새 폴더명을 확정하지 않는다.
- 공유 코드는 최소 계약·타입·시간·오류로 제한한다. Entity·Repository·도메인 Service·Boot 전체 설정을 공통 라이브러리에 넣어 두 앱이 같은 소유 데이터를 수정하는 경로를 만들지 않는다.
- 앱별 component/entity/repository scan과 scheduler 활성 범위를 분리한다. Core의 Result Worker, 각 앱의 Outbox Sender/Inbox Processor가 다른 앱 기동 때문에 중복 활성화되지 않도록 빌드 의존과 실행 설정을 확인한다. 별도 상시 Worker Spring은 추가하지 않는다.
- CI는 두 앱의 빌드·해당 앱 테스트와 계약/의존 경계를 확인한다. 실제 Task 이름·선택 실행·캐시/산출물 전달 설정은 TBD이며 기존 최소 리뷰 1명·CI 통과 조건을 유지한다.
- 앱별 비밀값·DB 쓰기 권한·기기 설정을 저장소나 공유 라이브러리에 하드코딩하지 않는다. 구체 주입·마이그레이션 실행은 ADR-006의 책임이다.

### 공개 범위와 자료

**Public Repository 사용 여부는 아직 팀 결정 전이다.** Public으로 운영하는 경우 다음 자료를 저장하지 않는다.

- 실제 아동 개인정보
- 실제 아동 대화 및 전사 원문
- API Key, 비밀번호, 인증서 Private Key, `.env`
- 재배포 조건이 확인되지 않은 데이터셋

실제 아동 자료, 실제 대화 및 실제 전사 원문은 AI-DLC 입력으로도 사용하지 않는다.

### Git과 업무 관리

```text
기본 브랜치: main
작업 브랜치: feature/{JiraKey}-description
커밋 형식: {JiraKey} type: content
```

Pull Request는 **최소 1명 리뷰**와 **CI 통과** 후 병합한다. 업무 관리는 Jira Kanban으로 하고 GitHub와 연동한다.

### AI 개발 규칙과 산출물

저장소 루트에 `AGENTS.md`와 `CLAUDE.md`를 둔다. `AGENTS.md`를 AI 개발 규칙의 원본으로 사용하고 `CLAUDE.md`는 `@AGENTS.md`를 참조하도록 한다. AI-DLC 산출물은 `aidlc-docs/`에서 관리한다.

## Alternatives

### 대체된 이전 선택 — backend의 단일 Spring 실행 전제

이전 ADR 묶음은 `backend/`를 단일 Spring 실행으로 배치했다. 현재는 같은 Monorepo 안의 Core/Realtime 두 실행·빌드 단위로 대체한다. 이 이력은 기존 루트 구조·Git/PR 운영·AI 규칙 파일의 책임을 바꾸지 않는다.

### 프론트엔드·백엔드·AI·문서의 별도 저장소

영역별 권한과 변경 이력을 독립적으로 다룰 수 있다. 그러나 기능 하나의 변경과 공통 AI 개발 규칙을 여러 저장소에 걸쳐 관리해야 하므로, 현재 MVP의 한 저장소 구조를 선택했다.

### 중첩된 프로젝트 루트

`project/project/backend`와 같은 구조는 경로를 불필요하게 늘리고 작업 위치를 혼동시키므로 사용하지 않는다.

### 도구별로 독립된 AI 개발 규칙

각 도구에 별도 규칙을 유지하면 내용이 달라질 수 있다. 따라서 `AGENTS.md`를 원본으로 두고 Claude Code의 진입 파일이 이를 참조하도록 한다.

## Consequences

- 프론트엔드·백엔드·AI·문서 변경을 한 저장소에서 추적하고 Jira 작업과 PR을 연결할 수 있다.
- 코드 병합 전에 리뷰와 CI가 필요하므로 변경 속도에 절차가 추가된다. CI의 검사 항목은 [ADR-002](ADR-002-technology-defaults.md)를 따른다.
- 공개 저장소로 운영할 경우 민감 자료와 재배포 권한이 불확실한 데이터셋의 반입을 계속 점검해야 한다.
- `AGENTS.md`가 실제 단일 원본이 되려면 `CLAUDE.md` 참조와 두 도구의 규칙 해석을 저장소에서 확인해야 한다.
- 두 앱의 빌드·실행 설정과 최소 공유 계약을 함께 변경할 때 각 앱의 산출물·스캔/스케줄러 격리와 CI 영향을 확인해야 한다. 실제 구성·검증 증거는 후속 구현 대상이다.

## Pending

- **팀 결정:** GitHub 저장소를 Public으로 운영할지 결정한다.
- **팀 확인:** Proposed 상태의 브랜치·커밋·PR 규칙을 팀이 승인한다.
- **적용 확인:** GitHub Organization, Jira 연동, 최소 리뷰 1명과 CI 통과 조건을 저장소 설정에 반영한다.
- **적용 확인:** `AGENTS.md`, `CLAUDE.md`, `aidlc-docs/`를 초기 저장소에 배치하고 AI 도구의 참조 동작을 확인한다.
- **빌드 구현 TBD:** Core/Realtime의 실제 Gradle 프로젝트/모듈·산출물·최소 공유 계약 배치, 각 Boot의 스캔/스케줄러 활성 범위와 두 앱 CI Task/산출물 전달을 구체화한다.
- **문서 연결 상태:** First Bolt에 두 앱 빌드·개별 기동과 계약 흐름의 시험 계획을 반영했고, decision-log/README에 실행 단위·신규 ADR 목록을 반영했다. 실제 빌드·기동·계약 검증은 미실시이며 관련 구현 상세는 TBD다. 저장소·빌드 영향 조항의 적용 범위는 유지한다.
