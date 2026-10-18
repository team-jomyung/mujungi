# Reverse Engineering Timestamp

## 이번 실행 기록 (261018-structure-refactor)

| 항목 | 값 |
|---|---|
| 날짜 | 2026-10-18 |
| 커밋 | 570a625 (전체 해시 570a625d261e0edb4984f7466b865707faa47e83, 원격 `main`과 같음) |
| 브랜치 | claude/refactor-structure |
| 인텐트 | 261018-structure-refactor |
| 범위 · 깊이 | scope refactor, 깊이 Minimal, 브라운필드, 단일 저장소(프로젝트 루트) |
| 스캔 방식 | 전체 재스캔(Full rescan) — 9개 산출물을 이번 실행 기준으로 통째로 새로 썼다(부분 병합 아님) |
| 방법 | 개발자 읽기 전용 스캔(handoff `inception/reverse-engineering/developer-scan.md`) + 아키텍트 합성. 앱 · 시험 · 빌드 · 외부 공급자는 실행하지 않았다 |

- **스캔 깊이에 대한 정직성 노트**: 이번 스캔은 구조 · 이름 정리 의도에 맞춰 이름 · 경로 문자열 참조와 안전망을 집중해 읽었다. 핸드오프의 Scan Coverage가 "깊게 분석"과 "훑기만"을 나눠 적었고, `core-api` 내부, `contracts` 본문, `docs` 본문, `infra` 스크립트 내부, 엔드포인트 수는 깊게 읽지 않았다(`shallow.paths`). 그럼에도 스냅샷과 요청 범위가 저장소 전체(`./`)이고 이번 실행이 전체 재스캔이므로 요청된 breadth에 따라 `kind: full`로 기록한다.
- **증거 구분(R-M11)**: 이번 실행의 증거는 정적 코드 읽기와 `wc` · `grep` 개수뿐이다. 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공은 어느 것도 이번 실행의 증거가 아니다.
- **미확인 사항**: `code-quality-assessment.md` 마지막 절 참조(순서 고정 시험 유무, `Live12` 시험 위치, 필수 검사 이름).

## Scope of Analysis

```yaml
scope_version: 1
kind: full
intent: 261018-structure-refactor
fingerprint: f665b2152492424e9336f2bb10bd93b43d9ee85e
analyzed:
  paths:
    - ./
  components:
    - backend/contract-model
    - backend/core-api
    - backend/realtime-activity
    - backend/test-support
    - backend/build-logic
    - frontend
    - tools
    - infra
    - scripts
    - contracts
    - ci
shallow:
  paths:
    - backend/core-api/
    - backend/contract-model/
    - backend/test-support/
    - infra/
    - ci/
    - scripts/verify/
    - scripts/live/
    - tools/
    - contracts/
    - docs/units/
    - .github/workflows/deploy.yml
    - frontend/src/app/
    - frontend/src/device/
```
