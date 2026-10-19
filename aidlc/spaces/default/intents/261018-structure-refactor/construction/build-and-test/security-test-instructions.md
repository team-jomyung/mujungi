# 보안 시험 지침 — 구조 · 이름 리팩토링

> 이 작업은 인증 · 권한 · 데이터 흐름을 바꾸지 않는다. 새 보안 NFR이 없으므로 새 보안 시험은 만들지 않고, 기존 보안 검사를 이 작업이 약화시키지 않았는지만 확인한다.

## 이번에 돌린 보안 검사

| 검사 | 명령 · 단계 | 결과 |
|---|---|---|
| 비밀 형식 스캔 + 무시 규칙 | `bash scripts/verify/a-secrets.sh`(verify.sh A4) | PASS — 키 형식 9종 · 파일 3,425개, 걸림 0 |
| 비밀 값 대조 | verify.sh P3 | PASS — 값 23개 대조, 걸림 0 |
| 로그 위생 | verify.sh P2 | PASS — 비밀 값 27개 × 대상 7개 파일, 일치 0건 |
| 교차 스키마 쓰기 · 앱 역할 CREATE TABLE 거부 | verify.sh P1 | PASS — 6건 모두 SQLSTATE 42501 |
| 운영 프로필 기동 거부 | verify.sh B5 | PASS — demo-devlogin · demo-dev 구성에서 두 앱 기동 거부 |
| 접근 제어 3종 | verify.sh B4 | PASS — `/internal/x` · `/actuator/health` 403, 위조 trace 무시, raw URI 400 |
| 로그 위생 시험 | `RealtimeLogHygieneIT` | PASS(첫 실행에서 간헐 실패, 단독 재실행과 두 번째 전체 실행에서 통과, `test-results.md` 참조) |

## 한계

- gitleaks가 이 PC에 없어 **이력 스캔은 CI `secrets` 작업 몫**이다. 이번 증거는 작업 트리 grep뿐이다.
- 의존성 취약점 스캔 · 이미지 스캔(Trivy)은 CI 작업이며 이번에 로컬에서 돌리지 않았다. 이 작업은 의존성을 바꾸지 않았다.
