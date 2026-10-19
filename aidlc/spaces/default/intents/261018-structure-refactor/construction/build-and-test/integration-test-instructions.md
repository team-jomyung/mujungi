# 통합 시험 지침 — 구조 · 이름 리팩토링

> 시험 전략은 Minimal이다(새 시험은 실행기 7구간 특성화 시험뿐). 이 문서는 팀 규칙(`team.md`)의 통합 · 끝-끝 시험이 이 작업에서 무엇을 보증하는지 정리한다. 모든 결과는 **테스트 대역(가짜 공급자) · 합성 대화** 기준이다(R-M11).

## 통합 시험

| 종류 | 위치 · 명령 | 이 작업에서 보증하는 것 |
|---|---|---|
| Spring Boot 통합(`*IT`) + Testcontainers PostgreSQL | `./gradlew clean check -Pmujoong.test.db=docker` | 패키지 · 클래스 이름 변경 뒤에도 앱이 기동하고 DB · 웹소켓 · 내부 HTTP 경로가 그대로 동작 |
| 계약 대조 | `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` | 설정 키 · 바인딩 prefix · 오류 코드 · 시간 값이 `contracts/`와 같음(설정 레코드 분리 뒤에도 불변) |
| 아키텍처 | ArchUnit(`core/arch` · `realtime/arch`), `PracticeFlowArchTest`, `SingleInstanceMarkerTest` | 패키지 의존 경계 · 단일 인스턴스 표식. 규칙이 빈 채로 통과하지 않음 |
| 프런트 경계 | ESLint `/parent` ↔ `/device` ↔ `shared` import 경계 | 이동 뒤에도 영역 사이 import 금지 |
| 실행기 특성화 | `SessionHarness` 기반 시험 + `SessionClocksCharacterizationTest`(4개) | `SessionExecutor` 분리 전후 처리 순서 동일 |

## 끝-끝(기기 시뮬레이터 + 가짜 공급자)

`bash scripts/verify.sh --mode compose`의 B · C 단계가 이미지를 빌드해 nginx · web · core-api · realtime · postgres · fake-provider를 띄우고 3종 호출(`/api/health` · `/device` · `/ws/device`)과 기기 시뮬레이터 한 턴(C1 ~ C12)을 돈다. P1(교차 스키마 쓰기 거부) · P2(로그 위생) · P3(비밀 값 0건) · T(작업 트리 불변)를 사후 단언으로 확인한다.

## 환경과 자료

- 상대 앱은 스텁이고, 공급자는 가짜 공급자다. 실제 유료 AI 공급자는 부르지 않는다(R-F1).
- 시험 데이터는 기존 빌더 · 픽스처를 그대로 쓴다(이름만 바뀐 공용 준비 클래스 포함).
- 같은 PC에 다른 Compose 스택이 떠 있으면 일부 시험이 간헐 실패할 수 있다(`build-instructions.md` 문제 해결).
