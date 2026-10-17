# U13 제브 게이트 — 성능 시험 지시서

> Standard 전략은 성능 시험 파일을 요구하지 않고, 이 기록에는 NFR Requirements · NFR Design 산출물이 없어 측정 대상 성능 목표가 정해져 있지 않다. 이 파일은 산출 목록을 채우되 **무엇을 어디서 잰다**만 정직하게 적는다. 부하 시험은 하지 않았다.

## 1. 이 단위의 시간 목표 (모두 기존 계약 값)

| 목표 | 값 | 출처 | 검증 방법 |
|---|---|---|---|
| 제브 한 번의 호출 상한 | 800ms (`mujoong.timing.guardrail.jev-budget`) | `contracts/timing.md` | `JevGuardrailClientTest`가 수동 시계로 경계(마감 = 지금, 시한 직전 마감 799ms)를 시험. 실제 제브 지연은 모름 |
| 검사 안전 타이머 | `jev-budget`의 2배 | 구현 결정(Minor R-01: 계약 문서에 파생값 한 줄이 없음) | `GuardrailSafetyTimerTest` 4개 |
| 체감 대기(아이 말 끝 → 기기 첫 소리)에 제브가 더하는 값 | 측정 목표값 없음 | 지표 `mujoong.guardrail.perceived.wait` | 실호출 시험 때 관측 |

## 2. 실행 가능한 확인

- 지표 이름 · 태그 · 버킷 확인: `GuardrailMetricsTest` 6개, `RealtimeMetricsTest`, `RealtimeMetricsScrapeIT`.
- 대기열 1칸 · 보관 행: `GuardrailCoordinatorTest`, `TranscriptWriterHoldTest`.

```bash
sg docker -c 'cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 --tests "*GuardrailMetricsTest" --tests "*GuardrailSafetyTimerTest" -Pmujoong.test.db=docker'
```

## 3. 하지 않은 것과 이후 소유

| 항목 | 상태 | 이유 / 소유 |
|---|---|---|
| 제브 실제 지연 분포(p50 · p95 · p99) | 측정 안 함 | 실제 제브 필요. 사람이 `jevLiveTest`로 지연을 관측한 뒤 800ms 시한이 충분한지 정한다 |
| 동시 세션 부하 | 측정 안 함 | 측정 목표가 정해진 NFR이 없고, 이 계획에 `performance-validation` 단계가 없다 |

이 항목들은 `Unverified`가 아니라 **목표값이 없는 관측 항목**이다. 목표값이 생기면(예: 800ms가 짧다고 판명되어 `jev-budget`을 바꿀 때) R-M5에 따라 `application.yml` · `contracts/timing.md` · 프런트 기본값 세 곳을 같은 커밋에서 고친다.
