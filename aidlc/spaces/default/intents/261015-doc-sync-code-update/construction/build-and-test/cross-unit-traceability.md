# 단위 전체 추적 확인

**판정: 실패(덮이지 않은 ID 있음).** 요구사항 FR · NFR 75개와 수락 기준 AC 121개, 모두 196개 중 13개만 Code Generation 추적표(`construction/*/code-generation/traceability.json`)에 `OK`로 덮였다. 까닭은 이 기록이 U8 · U9만 구현했고 U10 ~ U15를 Delivery Planning대로 건너뛰었기 때문이다(사람 선택 2026-10-16, 지시서로 넘김).

## 1. 덮인 ID (13)

| ID | 단위 | 대상 파일 |
|---|---|---|
| FR6.10 | U8 | `backend/realtime-activity/src/main/resources/guardrail/policy-v1.json` |
| FR10.1 | U8 | `backend/realtime-activity/src/main/java/kr/mujoong/realtime/content/LiveContentRegistry.java` |
| FR10.2 | U8 | `infra/seed/004-u8-reference.sql` |
| FR11.1 | U8 | `contracts/timing.md` |
| FR11.2 | U8 | `contracts/ai-io.md` |
| FR11.3 | U8 | `infra/bin/infra-validate` |
| AC2.3.3 | U9 | `backend/core-api/src/test/java/kr/mujoong/core/luna/LunaConfigurationTest.java` |
| AC2.3.4 | U9 | `backend/test-support/src/main/java/kr/mujoong/testsupport/luna/LunaResponsesStub.java` |
| AC2.4.1 | U9 | `backend/core-api/src/test/java/kr/mujoong/core/arch/LunaArchTest.java` |
| AC2.4.2 | U9 | `backend/core-api/src/test/java/kr/mujoong/core/luna/adapter/LunaAdapterBoundaryTest.java` |
| AC2.4.3 | U9 | `backend/core-api/src/main/java/kr/mujoong/core/luna/adapter/LunaResultTextProvider.java` |
| AC4.2.3 | U8 | `contracts/schemas/practice-record-v2.schema.json` |
| AC4.2.4 | U8 | `backend/contract-model/src/test/java/kr/mujoong/contract/U8ContractSchemasTest.java` |

## 2. 덮이지 않은 ID와 맡을 곳

| 묶음 | ID | 맡을 곳 |
|---|---|---|
| Luna 공급자 연결 | FR1.1 · FR1.2 · FR1.3 · FR1.4 | **U9가 구현함.** U9 Code Generation 추적표는 FR를 업무 규칙(BR1 ~ BR9)으로 풀어 BR → 파일로만 적어 FR ID 행이 없다(FD 추적표에서 FR1.x → BR 연결). 실질적으로 덮였으나 이 확인 기준(FR ID 직접 행)으로는 미충족 |
| 상황 생성 · 자체 검토 | FR2.1 ~ FR2.6, AC3.1.x · AC3.2.x 일부 | U10(지시서) |
| 진행 중 판정 | FR3.1 ~ FR3.7, AC1.x 일부 | U11 |
| 연습 상태 기계 · 출력 보류 | FR4.1 ~ FR4.8 · FR5.1 ~ FR5.4, AC1.1 ~ AC1.10 | U12 · U11 |
| 제브 가드레일 | FR6.1 ~ FR6.9, AC2.1.x · AC2.2.x · AC2.3.1 · AC2.3.2 · AC2.5.x | U13 |
| 결과 · 목표 후보 | FR7.1 ~ FR7.7 · FR8.1 ~ FR8.5, AC4.1 ~ AC4.6(4.2.3 · 4.2.4 제외) | U14 |
| 지식 그래프 | FR9.1 ~ FR9.7, AC5.x | U15 |
| 마이그레이션 번호 규칙 | FR11.4 | 마이그레이션을 더하는 단위(U11 ~ U15) |
| 실호출 · 실기기 검증 | FR12.1 ~ FR12.4 | FR12.1은 U9가 `lunaLiveTest`를 준비했으나 키가 없어 실행하지 않음. 나머지는 마지막 볼트 또는 통합 작업 기록 |
| 상위 NFR | NFR1 ~ NFR7 | 각 단위 NFR Requirements의 NFRx.y로 풀림(U8 · U9는 Met, 2절 요약 참조). 상위 ID 직접 행은 없음 |
| 그 밖의 AC | 위 표에 넣지 않은 나머지 AC | 해당 스토리의 단위 |

전체 목록(덮이지 않은 183개): FR1.1 FR1.2 FR1.3 FR1.4 FR2.1 FR2.2 FR2.3 FR2.4 FR2.5 FR2.6 FR3.1 FR3.2 FR3.3 FR3.4 FR3.5 FR3.6 FR3.7 FR4.1 FR4.2 FR4.3 FR4.4 FR4.5 FR4.6 FR4.7 FR4.8 FR5.1 FR5.2 FR5.3 FR5.4 FR6.1 FR6.2 FR6.3 FR6.4 FR6.5 FR6.6 FR6.7 FR6.8 FR6.9 FR7.1 FR7.2 FR7.3 FR7.4 FR7.5 FR7.6 FR7.7 FR8.1 FR8.2 FR8.3 FR8.4 FR8.5 FR9.1 FR9.2 FR9.3 FR9.4 FR9.5 FR9.6 FR9.7 FR11.4 FR12.1 FR12.2 FR12.3 FR12.4 NFR1 NFR2 NFR3 NFR4 NFR5 NFR6 NFR7 AC1.1.1 AC1.1.2 AC1.1.3 AC1.1.4 AC1.1.5 AC1.2.1 AC1.2.2 AC1.2.3 AC1.2.4 AC1.2.5 AC1.3.1 AC1.3.2 AC1.4.1 AC1.4.2 AC1.4.3 AC1.4.4 AC1.5.1 AC1.5.2 AC1.5.3 AC1.5.4 AC1.5.5 AC1.5.6 AC1.6.1 AC1.6.2 AC1.6.3 AC1.6.4 AC1.7.1 AC1.7.2 AC1.7.3 AC1.7.4 AC1.7.5 AC1.7.6 AC1.8.1 AC1.8.2 AC1.8.3 AC1.8.4 AC1.8.5 AC1.8.6 AC1.9.1 AC1.9.2 AC1.9.3 AC1.10.1 AC1.10.2 AC1.10.3 AC1.10.4 AC2.1.1 AC2.1.2 AC2.1.3 AC2.1.4 AC2.1.5 AC2.1.6 AC2.1.7 AC2.1.8 AC2.1.9 AC2.1.10 AC2.1.11 AC2.1.12 AC2.1.13 AC2.1.14 AC2.1.15 AC2.1.16 AC2.2.1 AC2.2.2 AC2.2.3 AC2.3.1 AC2.3.2 AC2.5.1 AC2.5.2 AC3.1.1 AC3.1.2 AC3.1.3 AC3.1.4 AC3.1.5 AC3.1.6 AC3.1.7 AC3.1.8 AC3.1.9 AC3.1.10 AC3.2.1 AC3.2.2 AC3.2.3 AC3.2.4 AC3.2.5 AC4.1.1 AC4.1.2 AC4.1.3 AC4.1.4 AC4.1.5 AC4.2.1 AC4.2.2 AC4.3.1 AC4.3.2 AC4.4.1 AC4.4.2 AC4.5.1 AC4.5.2 AC4.5.3 AC4.5.4 AC4.5.5 AC4.5.6 AC4.5.7 AC4.5.8 AC4.6.1 AC4.6.2 AC4.6.3 AC4.6.4 AC4.6.5 AC5.1.1 AC5.1.2 AC5.1.3 AC5.2.1 AC5.2.2 AC5.2.3 AC5.2.4
