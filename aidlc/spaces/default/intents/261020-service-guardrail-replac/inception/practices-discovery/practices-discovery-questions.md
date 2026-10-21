# Practices Discovery Questions

## Sources

- [memory:M1] aidlc/spaces/default/memory/team.md: 기존 다섯 승인 관례와 2026-10-19 대체 학습.
- [memory:M2] aidlc/spaces/default/memory/project.md: 기존 R-M1~11·R-F1~15.
- [scope] Workflow-selected scope: classic, Standard, 배포 단계 제외.
- [assumption] Q1의 현재 구성 설명 갱신안: .github/workflows/ci.yml · deploy.yml, commit 2e3d7b0. 파일 관측이며 원격 실행 확인이 아니다.

## Q1

이번 가드레일 교체도 아래의 기존 방식으로 진행하고, CI·배포 설명만 현재 구성에 맞게 갱신할까요?

| 영역 | 유지할 방식과 현재 확인한 차이 |
|---|---|
| 브랜치·병합 | main 기반의 짧은 작업 브랜치, 작업 커밋을 보존하는 merge commit으로 PR 병합, ci-gate 통과 |
| 끝-끝 연결 확인 | 새 최소 연결본을 만들지 않고 기존 전체 경로를 회귀 확인. “워킹 스켈레톤”은 실제 기능 전에 구성 요소 연결을 확인하는 최소 실행본이며, 이 프로젝트에는 이미 있다 |
| 시험 | 구현한 층마다 바로 시험 작성·실행, 줄 커버리지 80%, 가짜 공급자, 기존 검사 보호 |
| 배포 | 이번 작업에서는 배포 실행 제외. 기존 설명의 GHCR·SSM·호스트 Compose는 현재 파일의 ECR·ECS demo로 갱신. 현재 이미지 빌드·게시는 main 관문 통과 뒤이고, 끝-끝 CI 작업은 main 전용 임시 비차단 |
| 코드 정리 | 바꾼 파일만 서식 정리, 기능 이름 시험·파일 규칙과 최신 주석 관례 유지 |

- A. 기존 방식 유지 + 현재 구성 설명 갱신 (추천)
- B. 일부 관례 변경 — 바꿀 항목과 원하는 방식을 적어 주세요
- X. Other (please specify)

[Answer]: A. 기존 방식 유지 + 설명 갱신 (Recommended)

## Assumptions & Open Questions

- ZIP의 정책 적용 범위·JEV·연습·안내·종료·기억은 다음 Requirements Analysis에서 결정한다. 이번 질문은 제품 범위를 승인하지 않는다.
- 원격 브랜치 보호·리뷰 수·demo 환경 제한·저장소 변수·실제 클라우드 상태는 미조회다.
- Q1 응답 전 초안이며 확정·승격·단계 승인은 하지 않았다.

