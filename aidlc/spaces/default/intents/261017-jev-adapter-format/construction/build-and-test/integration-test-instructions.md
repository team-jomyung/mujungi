# 제브 어댑터 선 형식 정정 — 통합 시험 지시서

> Standard 전략의 핵심 경계 통합 시험이다. 모두 **가짜 제브(루프백 JDK `HttpServer`)와 가짜 공급자**만 쓴다(R-F1, ADR-002). 통과는 '테스트 대역' 증거이며 실제 제브 · 실제 기기 성공이 아니다(R-M11).

## 1. 경계와 시험

| 경계 | 시험 | 확인하는 것 |
|---|---|---|
| 어댑터 ↔ HTTP(공급자 선 형식) | `JevGuardrailClientTest`(공식 형식 기준으로 다시 씀) | 경로 `/v1/systemone` · 메서드 · `Authorization: Bearer` · 본문 최상위 키 정확히 `model` · `state` · `questions`(옛 키 없음) · 라벨 8개와 `PASS` · `state` 필드 집합 · 식별자 없음 · 지시문 격리 · 공식 예시 응답 모양 |
| 판정 규칙 | 같은 시험 | `PASS` 1순위 + 문턱 이상 + 엄격 최대일 때만 `ALLOW`, 문턱 직전 · 정확히 0.5 · 직후, `PASS` 동률 → `REVIEW`, 다른 라벨 1순위 → `BLOCK`, 비정상 응답(비JSON · 배열 · null · `answers` 없음 · `type` 아님 · `choice`가 보낸 라벨 아님 · 확률 범위 밖 · 합 오차 · 여분 키 · NaN) → `INVALID_JSON` |
| 오류 · 시간 | 같은 시험 | 상태 400 · 401 · 413 · 415 · 422 · 429 · 500 · 529 · 리다이렉트 → `TRANSPORT_ERROR` · 호출 1회, 시간 초과 · 마감 경계(수동 시계), 보내기 전 거절, 키 비노출, 오류 본문 표식 비노출 |
| 정책 리소스 ↔ 기동 | `GuardrailPolicyV2Test` · `LiveContentRegistryTest` · `U8RealtimeStartupChecksTest` · `GuardrailModeStartupChecksTest` | 리소스 구조 · 라벨 8개 · 직접 인용 예외 문장 · 문턱 범위 규칙 |
| 설정 ↔ 계약 | `ConfigKeyContractTest` · `ContractFilesTest` · `U8ContractSchemasTest` · `TimeLiteralScanTest` | 새 키 `pass-threshold`와 `ai-io.md` 10.11 대조, 스키마 예시 |
| 실호출 도구 분리 | `JevLiveTaskIsolationTest` | 기본 빌드가 `jevLiveTest`를 부르지 않음, 표본 5개 한 번씩, 보고서에 원문 · 키 · 주소 없음 |
| 내부 소비자 회귀 | `GuardrailTurnIT` · `GuardrailCoordinatorTest` · `GuardrailExecutorTest` · U3 ~ U13 통합 시험 | 포트와 내부 형식은 바뀌지 않아 단언 변경 없이 통과 |
| 모듈 밖 회귀 | `:core-api:check` 전체 | 계약 · 설정 키 변경이 core-api 시험(`FlywayConfIT` · `SchemaPrivilegeIT` 등)에 번지지 않음 |

## 2. 실행 방법

```bash
sg docker -c 'cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker'
sg docker -c 'cd backend && ./gradlew :contract-model:test :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'
sg docker -c 'cd backend && ./gradlew :core-api:check -Pmujoong.test.db=docker'
sg docker -c 'bash scripts/verify.sh --mode compose'
```

## 3. 시험 데이터

- 후보 문장 · 맥락은 합성 값이다. 실제 아이 발화 · 개인정보를 쓰지 않는다.
- 가짜 키는 실행 때 만든 무작위 문자열이거나 빈 값이다. 시험 모델 이름은 합성값이고 실제 모델 이름이 아니다. 비밀 스캔 패턴과 겹치는 리터럴은 쓰지 않는다.

## 4. 이 지시서가 덮지 못하는 것

| 항목 | 이유 | 담당 |
|---|---|---|
| 실제 제브가 이 요청을 받아들이는지(고정 버전 `model` 이름 · 한국어 `criteria` 8개) | 키 없음 · R-F15 확인 기록 없음 | 사람 — `jevLiveTest`(`infra/RUNBOOK.md` 8.8) |
| 오류 본문의 실제 모양 · `confidence` 의미 | 공식 문서에 없음, 실호출 필요 | 사람 — 실호출 |
| 확률 합 허용 오차 0.001 · 문턱 0.5 | 임시값 | 사람 — 실호출 관측과 AI 성능 시험 |
| 한국어 문안의 분류 품질 · 800ms 시한 안 지연 | 실제 공급자 필요 | 사람 — 실호출 · AI 성능 시험 |
| 실제 기기 · 체감 대기 | 실기기 필요 | 사람 — `mujoong.guardrail.perceived.wait` 관측 |
