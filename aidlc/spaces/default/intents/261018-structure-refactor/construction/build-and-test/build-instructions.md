# 빌드 지침 — 구조 · 이름 리팩토링

> 작업 기록 `261018-structure-refactor` · 범위 `refactor`. 이 작업은 파일 이동 · 이름 변경 · 분리뿐이라 새 의존성이나 빌드 방식이 없다. 아래는 이 PC에서 실제로 돌린 방법이다.

## 준비물

| 항목 | 값 |
|---|---|
| JDK | 21 (확인: OpenJDK 21.0.12) |
| Node | 22 (프런트 · tools) |
| Docker + Compose | 필요(`sg docker -c '…'`로 실행). DB 시험은 Testcontainers PostgreSQL |
| 환경 변수 · 비밀 | 없음. 시험 · 검증은 가짜 공급자만 쓴다(R-F1) |

## 빌드 명령

| 대상 | 명령 |
|---|---|
| 백엔드 전체(컴파일 · 시험 · ArchUnit · JaCoCo 80% · bootJar 시험 클래스 0) | `sg docker -c 'cd backend && ./gradlew clean check -Pmujoong.test.db=docker'` |
| 프런트 | `cd frontend && npx --no-install eslint . && npx --no-install vitest run tests --coverage && npx --no-install next build` |
| tools | `cd tools && npx --no-install vitest run tests` |
| 비밀 스캔 | `bash scripts/verify/a-secrets.sh` |
| 전체 끝-끝(위를 모두 포함 + 기동 · 3종 호출 · 기기 시뮬레이터 한 턴) | `sg docker -c 'bash scripts/verify.sh --mode compose'` |

## 빌드 확인

- `verify.sh`가 `build/verify/report.txt`에 단계별 PASS와 마지막 `RESULT PASS`를 쓴다.
- `verifyBootJarHasNoTestClasses`가 두 bootJar에 시험 클래스가 0건인지 본다.

## 문제 해결

- **`verify.sh`가 백엔드 단계에서 간헐 실패한다**: 같은 PC에 다른 Compose 스택(`mujoong-live-*`)이 떠 있으면 그 스택의 healthcheck가 `/tmp/runc-process…` 파일을 만들거나 DB 연결 자원을 나눠 써서 `ReportPdfTest`(임시 파일 0개 단언)와 `RealtimeLogHygieneIT`가 실패할 수 있다. 해당 시험만 따로 돌리면 통과한다. 다른 스택을 멈추거나 건드리지 말고(R-F12) 다시 돌린다.
- **`date`의 `%3N`이 9자리로 나온다**: 이 PC의 `date`는 uutils라서 인프라 시험(`test-host-tools.sh`) · 지연 측정 도구를 돌릴 때는 GNU date를 앞에 둔다. 이 PC에는 지금 `/usr/bin/gnudate`가 없어 이 시험은 이번에 돌리지 않았다.
- **`verify.sh`는 도는 동안 저장소를 고치면 `T` 단계가 실패한다**(감사 기록 제외). 돌리는 동안 파일을 쓰지 않는다.
