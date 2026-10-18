# U12 새 연습 흐름 — 빌드 지침

> 작업 기록 `261017-u12-practice-flow` · 입력: `code-generation-plan.md` · `unit-test-instructions.md` · `code-summary.md`. 이 지침은 이번 단계에서 실제로 돌린 명령을 그대로 적는다.

## 사전 조건과 환경 준비

- JDK 21(`java -version` → OpenJDK 21), Node 22, Docker + Compose v2.24.4 이상(이 PC는 v5.6.0), 도커 접근은 `sg docker -c '…'`로 한다.
- 프로젝트 이름 `mujoong-verify`로만 검증 스택을 올린다. 사용자의 수동 스택 `mujoong-manual`과 그 볼륨은 건드리지 않는다(R-F12).
- 이 PC의 `date`는 uutils라 `%3N`을 무시한다. 인프라 시험이나 지연 측정 도구를 돌릴 때만 GNU date(`/usr/bin/gnudate`)를 앞에 둔다. 이번 실행에는 해당 시험이 없었다.
- 새 비밀 환경 변수는 없다(R-M9 해당 없음). 시험 · 검증은 가짜 공급자만 쓴다(R-F1).

## 빌드 명령과 확인

| 용도 | 명령 | 이번 결과 |
|---|---|---|
| 전체 회귀(권위 있는 한 번) | `sg docker -c 'bash scripts/verify.sh --mode compose'` | `RESULT PASS rc=0 total=395s` |
| 백엔드만(바꾼 쪽, 층마다) | `sg docker -c 'cd backend && ./gradlew clean check -Pmujoong.test.db=docker'` | A1 단계에서 통과(아래 결과 문서) |
| 이 유닛 시험만 | `sg docker -c 'cd backend && ./gradlew :realtime-activity:test -PincludeTags=u12 -Pmujoong.test.db=docker'` | 코드 생성 단계에서 통과(검토자 재실행 201개) |
| 이 유닛 범위 커버리지 | `sg docker -c 'cd backend && ./gradlew :realtime-activity:test :realtime-activity:jacocoTestReport -PincludeTags=u12 -PcoverageScope=u12 -Pmujoong.test.db=docker'` | 96.2%(코드 생성 단계 측정) |

`verify.sh`는 도는 동안 저장소 안에 파일을 쓰지 않는다(단계 T가 작업 트리 시작 · 끝을 비교한다). 산출물은 `build/verify/`(무시 대상)에만 남는다.

## 문제 해결

- 종료 코드 2는 전제 누락(JDK 21 · Node 22 · Docker 등)이다. 통과로 보지 않는다.
- `A1`이 실패하면 `build/verify/logs/A1*.log`에서 실패 시험 이름을 본다. 계약 대조 · ArchUnit · 범위 커버리지 시험은 낮추거나 `@Disabled`로 끄지 않는다(R-F2).
- gitleaks는 이 PC에 없어 이력 스캔이 건너뛰어진다(`A4.gitleaks NOTE`). 이력 스캔은 PR의 CI `secrets` 작업이 한다.
