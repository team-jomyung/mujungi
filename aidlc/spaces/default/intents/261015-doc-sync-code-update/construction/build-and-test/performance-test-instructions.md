# 성능 시험 방법 — U8 · U9 범위

U8 · U9는 자체 지연 목표가 없다(U8 · U9 NFR Requirements 가정 1). 요구사항 NFR1의 지연 값(판정 2초 · 응답 준비 10초 · 제브 800ms · U5a 15초 · 그래프 2초)은 그 값을 쓰는 단위(U10 · U11 · U13 · U15)가 실측한다.

| 확인 | 방법 | 이번 범위 |
|---|---|---|
| 시도 시한이 min(마감, 목적 키)를 넘지 않음 | U9 `CoreLunaClient` · `RealtimeLunaClient` 대역 지연 · 느린 본문 시험 | 단위 시험으로 확인 |
| Luna 호출 지연 P50 · P95 · P99 | `LUNA_CALL` `latencyMs` 수집, `lunaLiveTest` 보고서 | 실호출 없음 — 실제 API 키가 있을 때 사람이 측정 |
| 끝-끝 한 턴 지연(기존 NFR) | `verify.sh` C · P2 `LATENCY_TURN` | 기존 회귀만 확인 |

부하 · 스파이크 · 장시간 시험은 이번 범위에 없다.
