# U12 새 연습 흐름 — 보안 시험 지침

> 보안 전용 NFR 수치 목표가 없어 별도 보안 시험 묶음은 만들지 않고, 이번 변경에 닿는 보안 규칙을 기존 검사와 시험으로 확인한 결과를 적는다. 입력: 프로젝트 규칙 R-F3 · R-F9 · R-F14 · R-F15, `code-summary.md`.

## 적용 규칙과 확인 수단

| 규칙 | 확인 수단 | 이번 결과 |
|---|---|---|
| 비밀 · 키 · 실제 도메인을 저장소에 쓰지 않는다(R-F3) | `scripts/verify/a-secrets.sh`(키 형식 9종) · P3 값 대조 | 파일 3,315개, 걸림 0 |
| `.gitleaks.toml`로 실제 비밀을 덮지 않는다(R-F9) | 이번 변경에서 `.gitleaks.toml` 무변경 | 변경 없음 |
| 로그에 전사 · 음성 · 개인정보를 남기지 않는다(R-F14) | `PracticeFlowRecordTest`의 표식 문자열 시험, `verify.sh` P2 | 표식이 로그 · 기록 · 지표 · 이벤트 어디에도 없음, 비밀 값 27개 × 7개 파일 일치 0 |
| V7 표에 대화 원문 칸이 없다(NFR2) | `PracticeFlowSchemaIT` | 원문 칸 없음 확인 |
| 두 앱 DB 격리(R-F5) | `verify.sh` P1 | 교차 스키마 쓰기 · `CREATE TABLE`이 모두 SQLSTATE 42501 |
| 아동 데이터를 확인 전에 외부 모델로 보내지 않는다(R-F15) | 코드는 가짜 제브만 연결, 실제 키 없음 | 실제 제브 연결은 확인 기록 뒤로 미룸 |

## 실행 방법과 한계

- 작업 트리 비밀 스캔: `bash scripts/verify/a-secrets.sh`(전체 `verify.sh` A4에 포함).
- gitleaks 이력 스캔은 이 PC에 gitleaks가 없어 돌지 못했다. PR의 CI `secrets` 작업이 맡는다. 로컬 증거로는 확인되지 않았다.
- SAST · DAST 도구는 이 저장소의 로컬 명령에 없다. 의존성 취약점 검사는 CI 몫이다.
