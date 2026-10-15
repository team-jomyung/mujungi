# Project-Level Rules

> Project-specific specialisation and corrections. Loaded after `org.md` and
> `team.md` as strict-additive guidance; contradictions with broader policy
> are rejected. Populated by practices-discovery and the self-learning loop.
>
> Use sparingly: most teams don't need a project layer. Reach for it
> only when this specific project needs stable, durable guidance beyond the
> team practice (for example, package-specific release checks or an additional
> regression suite for a legacy component).

## Way of Working

<!-- Project-specific specialisation. Example: -->
<!-- This monorepo requires package-scoped branch names and a package owner -->
<!-- review in addition to the team's normal merge policy. -->

## Walking Skeleton

<!-- Project-specific specialisation. Example: -->
<!-- The walking skeleton must exercise the legacy service adapter as well -->
<!-- as the new service boundary. -->

## Testing Posture

<!-- Project-specific specialisation. -->

## Guard Policy

<!-- Project-specific. Mode: strict, relaxed, or off. Strict here holds for every intent and cannot be changed from chat. A section under the retired Change Control heading, written by an earlier release, is still read. -->

## Deployment

<!-- Project-specific specialisation. -->

## Code Style

<!-- Project-specific specialisation. -->

## Tech Stack

<!-- Technology choices locked for this project. -->

## Decided

<!-- Decisions made in earlier stages that should not be re-asked. -->
<!-- Format: DECIDED: [decision] (Stage [slug], [date]) -->

## Scope Overrides

<!-- Custom scope rules for this project. -->

## Forbidden

<!-- Populated by practices-discovery affirmation gate. -->
<!-- Format: NEVER [behavior] (affirmed [date]) -->
<!-- Example: NEVER throw exceptions across service layer boundaries (affirmed 2026-05-17) -->

- NEVER 시험 · CI · 검증 명령에서 실제 유료 AI 공급자를 부르지 않는다. 가짜 공급자를 쓴다. — R-F1 (affirmed 2026-10-15)

- NEVER 시험을 통과시키려고 커버리지 하한(모듈 · 유닛 범위 줄 80%)이나 기존 시험 단언을 낮추지 않고, 계약 대조 시험 · ArchUnit 시험을 고치거나 `@Disabled` · `it.skip` · `-PexcludeTags` 등으로 건너뛰게 하지 않는다. — R-F2 (affirmed 2026-10-15)

- NEVER 계정 · 비밀번호 · API 키 · 토큰 값 · 실제 도메인을 코드 · 설정 · 시험 · 문서 · 커밋 · 로그에 쓰지 않는다. 저장소에는 환경 변수 · 매개변수 이름만 둔다. — R-F3 (affirmed 2026-10-15)

- NEVER 프런트 `/parent`와 `/device` 영역이 서로를 import하게 하지 않고, 공용 계층(`shared`)이 둘 중 어느 쪽도 import하게 하지 않는다. — R-F4 (affirmed 2026-10-15)

- NEVER core-api와 realtime-activity가 서로의 코드를 직접 참조하거나 상대 앱의 DB 스키마 · 표를 읽게 하지 않는다. 두 앱은 내부 HTTP · 이벤트 계약으로만 통신한다. — R-F5 (affirmed 2026-10-15)

- NEVER 운영 코드에서 시스템 시각을 직접 읽지 않는다(`Instant.now()` · `System.currentTimeMillis()` 등). `MujoongClock`을 주입받는다. — R-F6 (affirmed 2026-10-15)

- NEVER 코드에 숫자 시간 값을 쓰지 않는다. 시간 값은 `mujoong.timing.*` 설정 키에서만 읽고, 예외는 `TimeLiteralScanTest` 허용 목록에 근거와 함께 둔다. — R-F7 (affirmed 2026-10-15)

- NEVER 운영 코드가 시험 코드나 시험 지원 모듈(`test-support`)을 참조하게 하지 않는다. — R-F8 (affirmed 2026-10-15)

- NEVER `.gitleaks.toml` 허용 목록으로 실제 비밀을 덮지 않는다. 허용 항목은 가짜 값의 경로 + 줄 정규식 + 사유로만 더하고 사람 리뷰를 받는다. — R-F9 (affirmed 2026-10-15)

- NEVER GHCR에 이미 있는 이미지 태그를 덮어쓰지 않고, `latest`나 태그 없는 베이스 이미지를 쓰지 않는다. — R-F10 (affirmed 2026-10-15)

- NEVER 워크플로 `run:`에 `${{ inputs.* }}` · `${{ github.event.* }}` 같은 외부 입력을 직접 끼워 넣지 않는다. `env:`로 넘긴다. — R-F11 (affirmed 2026-10-15)

- NEVER 사용자의 수동 테스트 스택 `mujoong-manual`과 그 Docker 볼륨 · 실행 설정을 변경 · 재시작 · 삭제하지 않는다. 새 검증은 별도 Compose 프로젝트명 · 포트 · 볼륨으로 돌린다(`scripts/verify.sh`는 `mujoong-verify`를 쓴다). — R-F12 (affirmed 2026-10-15)

- NEVER 사용자에게 모델 · 키 · 엔드포인트 값을 채팅에 붙여 넣으라고 요청하지 않는다. 이 값은 승인된 환경 변수 · 비밀 저장소로만 관리한다. — R-F13 (affirmed 2026-10-15)

- NEVER 로그에 민감한 전사 · 음성 · 개인정보 · AI 요청 원문을 남기지 않는다. 식별할 수 없는 이유 코드 · 정책 버전 · 시간 · 개수만 기록한다. — R-F14 (affirmed 2026-10-15)

- NEVER 공급자 약관 · 개인정보 처리 조건과 아동 데이터 전달 허용 범위(보호자 동의 포함)를 확인하기 전에는 아동 데이터를 외부 모델에 보내지 않는다. — R-F15 (affirmed 2026-10-15)

## Mandated

<!-- Populated by practices-discovery affirmation gate. -->
<!-- Format: ALWAYS [behavior] (affirmed [date]) -->
<!-- Example: ALWAYS use Result<T,E> for fallible operations in service layer (affirmed 2026-05-17) -->

- ALWAYS PR은 필수 검사 `ci-gate`가 통과한 뒤에만 `main`에 합친다. — R-M1 (affirmed 2026-10-15)

- ALWAYS Flyway 마이그레이션은 버전 순서대로 `main`에 들어가게 병합 순서를 정하고, 이미 `main`에 있는 마이그레이션 파일 내용은 바꾸지 않는다. — R-M2 (affirmed 2026-10-15)

- ALWAYS 기존 표에 제약을 더하는 마이그레이션 문장은 이전 앱이 깨지지 않는 근거를 `infra/migration-approvals.txt`에 한 줄로 적고 리뷰를 받는다. — R-M3 (affirmed 2026-10-15)

- ALWAYS GitHub Actions 액션은 40자 커밋 SHA로, CI 도구 컨테이너 이미지(gitleaks · trivy)는 다이제스트로, 운영(런타임) 베이스 이미지는 태그와 다이제스트를 함께 고정하고, 이미지 빌드 중 원격 파일은 `ADD --checksum=sha256:…`으로만 받는다. — R-M4 (affirmed 2026-10-15)

- ALWAYS 시간 값을 바꿀 때는 두 앱 `application.yml`의 `mujoong.timing.*`, `contracts/timing.md`, `frontend/src/shared/timing/timing-defaults.json` 세 곳을 같은 커밋에서 함께 고치고, 대조 시험(`ConfigKeyContractTest` · `TimeLiteralScanTest` · `timing.test.ts` 등)이 실패하면 값을 맞춘다. — R-M5 (affirmed 2026-10-15)

- ALWAYS 새 오류 코드는 `ErrorCode` enum과 `contracts/errors.md` 표에 같은 커밋에서 더하고, 오류 응답 message에는 입력값 · 예외 메시지 · 개인정보를 담지 않는다. — R-M6 (affirmed 2026-10-15)

- ALWAYS 취약점 예외는 `ci/security-exceptions.yml`에만 GHSA/CVE id · 사유 · 만료일과 함께 두고, 사람 리뷰 승인을 받아 `status: approved`가 된 뒤에만 유효한 예외로 본다. 승인되지 않은 항목은 만료 전이라도 지운다. — R-M7 (affirmed 2026-10-15)

- ALWAYS 배포 자격 증명은 GitHub OIDC로 환경 `demo`에 묶인 역할로만 얻고, 저장소 Actions 비밀은 0개로 두며, 워크플로 권한은 작업 단위 최소(기본 `contents: read`, `packages: write` · `id-token: write`는 그 권한이 필요한 작업에만)로 둔다. — R-M8 (affirmed 2026-10-15)

- ALWAYS 새 비밀 환경 변수를 만들면 같은 커밋에서 비밀 이름 검사 목록 전부(`ci.yml` 번들 검사, `infra/ci/image-checks.sh`, `infra/bin/infra-validate` · `render-env` · `log-hygiene-check`, `scripts/verify/`, `infra/ci/gen-env.sh`, `README.md`, `infra/RUNBOOK.md`의 Parameter Store 항목)에 함께 넣는다. — R-M9 (affirmed 2026-10-15)

- ALWAYS 설계 문서 변경이 API · 이벤트 · 오류 코드 · 시간 값 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고쳐, CI 전 작업과 계약 대조 시험이 돌게 한다. — R-M10 (affirmed 2026-10-15)

- ALWAYS 시험 · 검증 결과는 테스트 대역(가짜 공급자) · 합성 대화 · 실제 API · 실제 기기 성공 여부를 각각 별도 증거로 나눠 보고하고, 테스트 대역이나 합성 대화의 성공을 실제 공급자 · 실제 기기 성공으로 표시하지 않는다. — R-M11 (affirmed 2026-10-15)

## Corrections

<!-- Project-specific corrections from human feedback. -->
<!-- Format: NEVER/ALWAYS [behavior] (learned [date]) -->
- 커밋 전에 git diff --cached --summary로 mode change를 확인하고, 의도하지 않은 실행 권한 변경(예: 755→644)은 git reset으로 스테이지에서 뺀다. WSL에서 Windows 도구가 같은 저장소를 건드리면 스크립트 실행 비트가 스테이지에서 빠질 수 있다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:reverse-engineering:21c43b3cc63a09b87bce07ac5367b367a9a504cf40462bf3a6d9bb0cacde1c3b -->
- 유닛 작업 기록의 Requirements Analysis는 새 업무 요구사항을 만들지 않고, 원래 기록(261008-mujoong-service)의 요구사항과 유닛 설계를 구현 단위로 다시 묶는다. 원래 ID는 '원 FR7.1'처럼 구분해 추적한다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:requirements-analysis:ea128ec96de73d6505c6d044c9a7eb830ba8ccaaee93fe4f8481daac5129448b -->
- 정본 설계가 이미 있는 유닛은 정본끼리 부딪히거나 정본에 답이 없는 것만 질문하고, 설계가 정한 시간 값 · 전이 · 가정은 다시 묻지 않는다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:requirements-analysis:a93a89e29ff30ba2472d5beec6b6075c4685e248674d7fad1e38eb87dd82250f -->
- 단계 산출물(설계 문서 · traceability.json 등)은 셸 스크립트가 아니라 파일 쓰기 도구로 저장한다. 셸로 쓰면 쓰기 기록이 남지 않아 요약 확인 뒤 산출물로 인정되지 않고 검토 요청이 거절된다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:c55237f5ad4c9ff08b9ea393b4c208f6382d1a1b9e3e0a5944f07c3ad8da86a6 -->
- 지운 코드를 되살려 쓰는 설계는 그 커밋의 실제 동작을 설계 정본으로 옮기고, 설계 문서의 표현과 다르면 코드 동작에 맞춰 결정표로 정리한다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:92b3ccb14c7efe04fe002e3d4b22d0de9ec7186fb2d7fbe173ad3ec69e4dfdcd -->
- 유닛 나누기 단계를 건너뛴 유닛 작업 기록은 설계 문서를 유닛 폴더 없이 construction/<stage>/에 쓰고, traceability의 상위 ID는 그 기록의 요구사항 FR/NFR ID로 잡는다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:d57312c6c0a27a26f0f8daf790fec4626dc5e435b09c0b41c44c090bd559e610 -->
- 다른 유닛이 붙을 확장점은 들어오는 시점만이 아니라 확장점이 낼 수 있는 출력 능력(재생 · 공급자 지시 · 시계 · 비동기 결과)과 금지 능력까지 정해, 붙는 쪽이 실행기를 고치지 않게 설계한다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:15f7831c11db5eb1678a97d9ec32253c1fcd70a5515f3890a376ff5a6b88c4d9 -->
- 시험 · 문서 정비 같은 작업 방식 요구사항은 업무 규칙(BR)을 만들지 않고 traceability에 N/A로 추적해 Code Generation 계획에서 단계로 다룬다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:8c1a6e455170c995e35e9c45a3a2745f785fe0c166c6c8c52bc64040663257d6 -->
- 세션을 넘겨 Code Generation을 개발자에게 다시 맡길 때는 계획 파일의 단계 체크박스가 비워진 채 전달되므로, 표시를 믿지 말고 커밋 기록 · 저장소 상태로 끝난 단계를 먼저 확인하라고 지시서에 적고 이어갈 단계를 명시한다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:code-generation:350b106d3beabbc7777f5fb2f8ffd2c0dd1ac9ac83c4004dfb75ffaf27c43542 -->
- 유닛 작업 기록의 코드 스캔은 지금 체크아웃이 아니라 main에서 새로 딴 유닛 브랜치를 기준으로 한다. 앞 유닛의 병합 전 중간 브랜치에서 스캔하면 다음 유닛 설계가 병합 전 코드에 묶이므로, 스캔 전에 브랜치부터 확인하고 바꾼다. (learned 2026-10-14) <!-- cid:261014-u6-result-report:reverse-engineering:96785b1b0cceb1e6c5b152a4680439b6f869e169d3192182c9c4c3f8db335614 -->
- 사람이 정본과 다른 쪽을 고르면 해당 FR에 편차로 적고, 그 결정 때문에 바뀌는 앞 유닛 계약 · 시험은 별도 FR로 묶어 Functional Design에서 목록과 까닭을 적게 한다. (learned 2026-10-14) <!-- cid:261014-u6-result-report:requirements-analysis:8e549ba092e160ec13d77a37fa116bcb1537cc183d3c20a1c9bf212f48308b43 -->
- 유닛 작업 기록의 처음 요청이 짧으면 유닛 지시서(docs/units/<유닛>/brief.md)를 Requirements Analysis의 기준 문서로 삼고, 지시서가 가리키는 원래 기록의 ID로 다시 묶는다. (learned 2026-10-14) <!-- cid:261014-u6-result-report:requirements-analysis:127a36c15ff6ae85f0bec4627ced4df27677a61fbd80b846dd9a857e5f74aa8c -->
- 유닛 작업 기록을 만들기 전에 유닛 지시서의 「AI-DLC로 진행하는 법」을 먼저 읽고, 거기 적힌 설명 문구 · 기록 이름 · 브랜치 이름 · 뺄 단계 · 깊이와 시험 전략대로 만든다. 기록 이름과 처음 요청은 만든 뒤 바꿀 수 없다. (learned 2026-10-14) <!-- cid:261014-u6-result-report:requirements-analysis:581382f6aa006fff4566f0289ebefe47f106872b668abadeef0732b9d4a87d62 -->
- 정본 부딪힘 · 빈틈이 많으면 사람 판단이 필요한 것만 개별 질문으로 묻고, 추천안이 정본 문장이나 코드 사실과 바로 맞는 나머지는 번호를 붙인 가정 목록 한 묶음(Assumption Confirmation)으로 확인받는다. (learned 2026-10-14) <!-- cid:261014-u6-result-report:requirements-analysis:642dfa9894c432cb96b9acaf1cdac49885551b013126347f1ddbf9e6fb294959 -->
- 마감이 있는 AI 작업은 여러 항목을 출력 하나로 한 번에 받고 금지 2차 분류도 작업당 한 호출로 묶어, 시도당 AI 호출 수를 줄이고 재시도 여유를 남기게 설계한다. (learned 2026-10-14) <!-- cid:261014-u6-result-report:functional-design:ce104b68af9027bb9152cb4e2dfea56f7549061651c4c800c0a9ef0cae29e500 -->
- 결과 수준은 AI 전 1단계(유효 기록)와 AI 뒤 2단계(FULL 여부)로 나눠 계산하고, 1단계가 INSUFFICIENT면 AI 없이 DONE으로 끝내 공급자 UNAVAILABLE에서도 짧은 활동이 정리 끝이 되게 한다. (learned 2026-10-14) <!-- cid:261014-u6-result-report:functional-design:bb0d1be2986b0be13da4396b71245b7f5707ec9155181d375ec6497dfc33410c -->
- traceability.json에는 요구사항의 묶음 머리 ID(FR1 등)와 '원 FR7.4' 같은 원래 기록 ID도 upstream_ids · coverage에 N/A 행("하위 행이 추적" · "이 기록 FR로 다시 묶어 추적")으로 넣는다. 자동 점검이 이 ID들도 상위 ID로 센다. (learned 2026-10-14) <!-- cid:261014-u6-result-report:functional-design:a2ece9e14d489eb99d70097a02f4cdfc66f9dda2c4cdc7fa1b7bc1d994187f57 -->
- 승인된 생활 팁은 금지 2차 AI 분류 없이 1차 사전에 걸리면 숨긴다. 사전에 없는 미묘한 표현은 팁 승인 단계에서 거른다. (learned 2026-10-14) <!-- cid:261014-u6-result-report:functional-design:0d7cfb2c213912c4a3f4cb5f456b979a83bec36221ddc9622c648875f6e87884 -->
- 모든 유닛이 main에 병합된 뒤의 로컬 실행 준비 · 통합 점검 같은 요청은 승인 대기 중인 유닛 기록에 붙이지 않고 별도 작업 기록으로 연다. (learned 2026-10-15) <!-- cid:261015-local-run-readiness:reverse-engineering:a512497f4e93448b64e4b482464edefdd1e836df7444f3a14ac761acfc0f4ab0 -->
- 실행 · 통합 관점 작업의 코드 스캔은 전체 재스캔으로 최신 사실을 우선하고, 그 때문에 얕아진 유닛 내부 경로는 해당 유닛 작업 때 집중 스캔으로 다시 깊게 본다. (learned 2026-10-15) <!-- cid:261015-local-run-readiness:reverse-engineering:0b110538d5f1979bac9e910b6f4f259391236f147b5e6c0a3f0e4b68e81bafc3 -->
- 코드 스캔 전에 현재 브랜치가 main과 같은 커밋인지 확인하고, 다른 작업 브랜치에 있으면 main과 같은 커밋 위에 이번 작업 브랜치를 새로 만든 뒤 스캔한다. 작업 트리의 aidlc/ 미커밋 변경은 그대로 따라오게 둔다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:reverse-engineering:57cffa79c4094eddc69fd1cfa7399f88eba99679c2dfa787194466f75c088b19 -->
- 다른 작업 기록이 게시했지만 커밋하지 않은 CodeKB를 전체 재스캔으로 교체할 때는 게시 전에 기존 9개 파일을 scratchpad에 백업하고 그 위치를 승인 화면에 알린다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:reverse-engineering:6bc55d4f2ff0e34577a5ef737ab01e98cea0afbf759ceb1ddf900beee6b61d7d -->
- 단계 진행 중 사람이 추가 문서를 참고하라고 하면 knowledge 목록에 등록하고 이 작업 기록에 연결한 뒤, 지금 단계의 산출물에 바로 걸리는 부분만 질문으로 확인하고 나머지는 다음 단계의 입력으로 넘긴다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:practices-discovery:cd6009a7ad2b861044875948b597fe366e5b68785dbc7d1658c364f184e5e82d -->
- 사람이 '장단점을 확인하여 직접 결정할 것'처럼 위임으로도 직접 선택으로도 읽히는 답을 하면, 대신 고르지 않고 선택지별 장단점과 추천을 표로 보여 준 뒤 최종 선택을 다시 받는다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:practices-discovery:9623a14bca247ec8420ca7a7c549645ad03f396b9cb9bd95a1655c5db4d4e223 -->
- 사람이 압축 파일 같은 자료를 넣어 주면 바로 목록을 열어, 문서가 가리키는 원본이 빠졌는지와 라이선스(재배포 가능 여부)를 확인하고 그 결과를 요구사항의 열린 질문으로 올린다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:requirements-analysis:f82fe995d0f465d84aacda954c2192ef753710b24fc74cffa89847a8210d0483 -->
- '업데이트된 문서 기준' 요청은 knowledge/documents의 git diff로 실제 변경분부터 확인하고, 새로 들어온 문서와 함께 범위를 잡는다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:requirements-analysis:1f5b0f2e78c7e0c47269b2c6d33130ad49de49e1bdd40757838ff07c535f0ee6 -->
- 문서 우선순위를 일반 규칙으로 정했더라도 사람이 개별 질문에서 다른 쪽을 명시적으로 고르면 그 선택을 앞세우고, 요구사항에 예외로 적는다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:requirements-analysis:41a9e2ae8077fd32777919547314ea7c6393744622034328db583ae769664267 -->
- 문서 · 코드 차이가 클 때는 읽기 전용 조사로 갭 보고서를 먼저 받고, 사람에게는 문서끼리의 충돌과 범위만 묻고 나머지는 가정 묶음으로 확인받는다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:requirements-analysis:b772bb7deb216c3f891073a4693173c5b976987033b2aa3620decaa87365deb3 -->
- AI 모델의 판단 품질에 기대는 인수 기준은 서버 동작([자동-대역], 가짜 공급자로 CI 합격 기준)과 분류 품질([실호출-합성], 실호출 확인표의 관측 보고)로 나눠 쓴다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:user-stories:bf956ce661b48c596140ed1b74521bbe54bb75fecd59653fb79eede47b0e3b89 -->
- 스토리로 쓰지 않는 운영 · 개발 요구는 traceability에 N/A · Deferred로 두고, stories.md의 '스토리 밖 추적' 표로 다루는 단계와 시험 항목을 잇는다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:user-stories:c6920bdc0e4682b75786fc34017a628236802c92215efe3e7a0baf00229257a1 -->
- 거친 시안(Ideation) 단계가 없는 작업 기록의 Refined Mockups는 화면 설계 v1.5j의 화면 · 상태 · 문구 ID를 시안의 뼈대로 쓰고, 바뀌는 상태 · 규칙만 그린다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:refined-mockups:a0d692cc267523578a8be3dcfc875c3655c8259b9ac28789d643287c0c3f7194 -->
- 화면 설계 개정이 필요하면 knowledge/documents의 정본을 고치지 않고, 단계 산출물에 개정 제안 표(RP-n: 대상 · 지금 정본 · 제안 · 까닭 · 추천)로 남겨 사람 승인 뒤 반영하게 한다. (learned 2026-10-15) <!-- cid:261015-doc-sync-code-update:refined-mockups:9c6ca85e7b6aaf759401127aa53bc5e64dd1c57ae4284dc648a06bc11144f2b7 -->
