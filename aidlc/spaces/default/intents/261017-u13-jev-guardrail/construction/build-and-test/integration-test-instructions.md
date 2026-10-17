# U13 제브 게이트 — 통합 시험 지시서

> Standard 전략의 핵심 경계 통합 시험이다. 모두 **가짜 제브(손으로 푸는 `CompletableFuture` · 루프백 가짜 서버) + 가짜 공급자**만 쓴다(R-F1, ADR-002). 통과는 '테스트 대역' 증거이며 실제 제브 · 실제 기기 성공이 아니다(R-M11).

## 1. 경계와 시험

| 경계 | 시험 | 확인하는 것 |
|---|---|---|
| 앱 ↔ PostgreSQL (V6) | `GuardrailEventSchemaIT` · `GuardrailEventRepositoryIT` | 표 · 제약 · 색인 · FK(`ON DELETE RESTRICT`) · upsert · 삭제 참여자(순서 185) 뒤 0건 |
| 마이그레이션 | `RealtimeSchemaIT` · `PracticeMigrationIT` · `PracticeMigrationRegistrationTest` | 표 18개 · 이력 · 최대 버전 6 · V6 파일 등록 |
| 실행기 ↔ 제브 포트 ↔ 기록기 | `GuardrailTurnIT`(4) | ALLOW 전 기기로 0바이트 · ALLOW 뒤 한 턴과 승인된 행 · BLOCK 때 0바이트 + 재지시 + 막힌 행이 보호자 12 · 27 조회에 안 나옴 · 늦은 ALLOW 무효(VOIDED) · 대기열 순서 |
| 어댑터 ↔ HTTP | `JevGuardrailClientTest`(11) | 요청 모양 · 응답 변환 · 시간 초과 · 재시도 없음 · 잘못된 JSON 12종 · 정책 판 불일치 · 키 비노출 |
| 앱 기동 ↔ 프로필 규칙 | `GuardrailModeStartupChecksTest` · `GuardrailConfigurationTest` | 모드 × 프로필 × 키 × 호스트 × 모델 표의 모든 칸 |
| 기존 회귀 | `SessionVoiceU3IT` · `DeviceGatewayU3IT` · `RealtimeMetricsScrapeIT` 등 U3 ~ U11 통합 시험 | 단언 변경 없이 통과(dev · test의 STUB 모드 경로를 지나도 동일) |

## 2. 실행 방법

```bash
# U13 태그 시험만(단위 + 통합)
sg docker -c 'cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker'

# 모듈 전체(회귀 포함)
sg docker -c 'cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'

# 끝-끝(기기 시뮬레이터 한 턴, 가짜 공급자)
sg docker -c 'bash scripts/verify.sh --mode compose'
```

통합 시험 환경은 `RealtimeItEnvironment`(Testcontainers PostgreSQL)이고, 이 단위의 통합 시험은 `mujoong.gate.segment-quiet`를 운영값 800ms로 올린다(기본 시험 값 50ms는 제브 대기와 겹치는 구간 판정을 못 재현한다).

## 3. 시험 데이터

- 후보 문장 · 맥락은 합성 값이다. 실제 아이 발화 · 개인정보를 쓰지 않는다.
- 가짜 키는 실행 때 만든 무작위 문자열이거나 빈 값이다. `.gitleaks.toml` 허용 항목은 더하지 않았다(R-F9).

## 4. 이 지시서가 덮지 못하는 것

| 항목 | 이유 | 담당 |
|---|---|---|
| 실제 제브 응답 형식 · 지연 · 분류 품질(AC2.1.10 · AC2.1.16) | 키 없음 · R-F15 확인 기록 없음 | 사람 — `jevLiveTest`(`infra/RUNBOOK.md` 8.8) |
| 실제 GPT-Live 위의 재지시 동작 | 실제 공급자 필요 | 사람 — 실호출 확인 |
| 실제 기기 · 실제 아이 음성의 체감 대기 | 실기기 필요 | 사람 — 지표 `mujoong.guardrail.perceived.wait` 관측 |
| demo 배포 후 3종 호출 | 이 단위는 배포를 다루지 않는다(Q5-A) | 배포 시점 |
