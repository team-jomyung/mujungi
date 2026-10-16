# Build and Test 요약 — U8 · U9 범위

> 범위: 이 기록의 Construction은 U8 · U9만 구현했다. U10 ~ U15는 Delivery Planning(`bolt-plan.md`)대로 건너뛰고 지시서로 넘긴다(사람 선택 2026-10-16). 시험 전략 Standard, 범위 classic.
> 증거 종류(R-M11): 테스트 대역 · 합성 자료만 있다. 실제 API · 실제 기기 증거는 없다. 목표 행은 모두 Met이다(아래 표).

## 1. 빌드 상태와 전제

- 빌드: 성공(`clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`, 커밋 `80496f2`).
- 이 PC 전제: JDK 21 · Node 22 · Docker · jq 있음. shellcheck · gitleaks는 없어서 같은 계열 공식 컨테이너로 대신 돌렸다.
- 이 PC의 `date`는 uutils coreutils 0.8.0이다. `%3N`의 자릿수를 무시하고 9자리를 낸다. 인프라 시험은 GNU `date`(`/usr/bin/gnudate`)를 PATH 앞에 두고 돌렸다.

## 2. 시험 종류

| 종류 | 파일 | 실행 |
|---|---|---|
| 단위 · 통합(단위별) | U8 · U9 `unit-test-instructions.md` | 실행함 |
| 통합(경계) | `integration-test-instructions.md` | 실행함 |
| 보안 | `security-test-instructions.md` | 실행함(shellcheck는 컨테이너) |
| 성능 | `performance-test-instructions.md` | 대역 시험만 · 실측은 다른 단위 몫 |
| 끝-끝 | `bash scripts/verify.sh` | 실행함 |

## 3. 단위별 커버리지 기대

| 단위 | 범위 줄 커버리지 하한 | 실제 |
|---|---|---|
| U8 | 80% | U8 단위 확인 때 통과(U8 `code-summary.md`) |
| U9 | 80% | core 93.86% · realtime 91.87% |
| 모듈 전체 | 80%(CI) | contract-model 98.45% · core-api 95.94% · realtime-activity 93.28% |

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| COV-MODULE | team.md Testing Posture · 두 단위 Testing Contract | 모듈 줄 80% 이상 | 98.45% · 95.94% · 93.28% | `check` JaCoCo(U9 code-summary 7절) | build-and-test | Met |
| COV-U8 | U8 code-generation-plan Testing Contract | U8 범위 줄 80% | 80% 이상 | U8 code-summary · U8 단위 확인 | build-and-test | Met |
| COV-U9 | U9 code-generation-plan Testing Contract | U9 범위 줄 80% | 93.86% · 91.87% | U9 범위 명령 | build-and-test | Met |
| SUITE-GREEN-BACKEND | Testing Contract scope_floor | 기존 시험 모두 통과 | 실패 0 | `check` · `-PincludeTags=u8,u9` | build-and-test | Met |
| SUITE-GREEN-E2E | team.md Walking Skeleton | `verify.sh` PASS | PASS(604초) | `build/verify/report.txt` | build-and-test | Met |
| SUITE-GREEN-HOSTTOOLS | Testing Contract scope_floor(인프라 시험) | `test-host-tools.sh` 37건 통과 | 37/37(GNU `date`로). 시스템 기본 uutils `date`로는 turn-metrics 2건 실패 — 시험 도구 환경 차이, 코드 결함 아님 | `test-results.md` 3 · 4절 | build-and-test | Met |
| INFRA-SHELLCHECK | team.md Testing Posture(인프라) | `test-infra-validate.sh` · shellcheck 통과 | 46건 어긋남 0, `infra-validate` 8건 PASS(shellcheck v0.10.0 컨테이너) | `test-results.md` 3절 | build-and-test | Met |
| U8-NFR2.1 | U8 security-requirements | 원문 일치는 공백 제거 완전 일치만 | 통과 | U8 `LiveContentRegistry` 시험(`@Tag("u8")`) | build-and-test | Met |
| U8-NFR2.2 | 같음 | 계획 없음 · 출처 불명 → UNKNOWN, 제브 생략 불가 | 통과 | 같음 | build-and-test | Met |
| U8-NFR2.3 | 같음 | 판 리소스 적재 실패 → 기동 거부 | 통과 | `RealtimeStartupChecks` 시험 | build-and-test | Met |
| U8-NFR2.4 | 같음 | 세션 고정 판 번호 불변 · 조회 가능 | 통과 | `LiveContentRegistry` 시험 | build-and-test | Met |
| U8-NFR3.1 | 같음 | AI 요청 스키마 닫힘 · 금지 필드 거절 | 통과 | `U8ContractSchemasTest` | build-and-test | Met |
| U8-NFR3.2 | 같음 | 아이 문자열 필드 description 치환 문구 | 통과 | 같음 | build-and-test | Met |
| U8-NFR3.3 | 같음 | ai-io.md R-F15 문장(U9가 '운영 절차'로 갱신) | 통과 | `U8ContractSchemasTest` · `U9ContractDocsTest` | build-and-test | Met |
| U8-NFR3.4 | 같음 | 새 비밀 이름 R-M9 목록 전부 · OPTIONAL | 통과 | `infra-validate`(shellcheck 외) · `verify.sh` B1 | build-and-test | Met |
| U8-NFR3.5 | 같음 | 비밀 값 · 실제 도메인 없음 | 0건 | `verify.sh` A4 · P3, gitleaks 0건 | build-and-test | Met |
| U8-NFR3.6 | 같음 | 대본 · 발화는 메모리만, 로그 없음 | 통과 | `LogCapture` 시험 · U8 ArchUnit | build-and-test | Met |
| U8-NFR3.7 | 같음 | 판 리소스 · 시드에 개인 정보 없음 | 검토 완료 | U8 검토 기록 | build-and-test | Met |
| U8-NFR6.1 | 같음 | `approvedSpeech` 없는 옛 패키지 정상 | 통과 | 세션 수신 시험 | build-and-test | Met |
| U8-NFR6.2 | 같음 | `flowVersion` 없으면 U5b 형식 | 통과 | `U8ContractSchemasTest` | build-and-test | Met |
| U9-NFR1.1 | U9 security-requirements | 시도 시한 ≤ min(마감, 목적 키) | 통과 | `CoreLunaClientTest` · `RealtimeLunaClientTest` · `LunaTransportTest`(느린 본문) | build-and-test | Met |
| U9-NFR1.2 | 같음 | 시도마다 `LUNA_CALL` 지연 | 통과 | `LogCapture` 시험 | build-and-test | Met |
| U9-NFR1.3 | 같음 | 대기 ≥ 남은 시간이면 재시도 없음 | 통과 | `CoreLunaClientTest`(429 Retry-After · 재시도 창) | build-and-test | Met |
| U9-NFR2.1 | 같음 | 실패를 성공으로 돌려주지 않음 | 통과 | 응답 분류 시험 | build-and-test | Met |
| U9-NFR2.2 | 같음 | 키 없으면 기존 실패 빈 | 통과 | `LunaConfigurationTest`, `verify.sh` 기동 로그 `DISABLED_NO_KEY` | build-and-test | Met |
| U9-NFR3.1 | 같음 | 금지 키 제거 · 잔존 시 미호출 | 통과 | `LunaInputSanitizerTest` | build-and-test | Met |
| U9-NFR3.2 | 같음 | 별칭 '아이' · 연락처 치환 | 통과(겹치는 이름 포함) | 같음 | build-and-test | Met |
| U9-NFR3.3 | 같음 | `store=false` · `temperature` 없음 | 통과(대역 요청 검사 기준) | `CoreLunaClientTest` | build-and-test | Met |
| U9-NFR3.4 | 같음 | 프로필별 주소 규칙 · 기동 거부 | 통과 | `LunaHostsTest`(두 앱) | build-and-test | Met |
| U9-NFR3.5 | 같음 | 본문 · 이름 · 키 로그 없음 · 허용 속성만 | 통과 | `LogCapture.anyLineContains`, `verify.sh` P2 | build-and-test | Met |
| U9-NFR3.6 | 같음 | 키는 env만 · 값 없음 | 0건 | `verify.sh` A4 · P3, gitleaks | build-and-test | Met |
| U9-NFR3.7 | 같음 | RUNBOOK R-F15 확인 기록 표 · ai-io.md 문장 | 있음 | `infra/RUNBOOK.md`, `U9ContractDocsTest` | build-and-test | Met |
| U9-NFR3.8 | 같음 | 패키지 경계 | 통과 | `LunaArchTest`(두 앱) | build-and-test | Met |
| U9-NFR3.9 | 같음 | 리다이렉트 금지 · 프록시 없음 · 직전 재확인 | 통과 | `LunaTransportTest`(302) | build-and-test | Met |
| U9-NFR3.10 | 같음 | 응답 1MB 상한 | 통과 | `LunaTransportTest` | build-and-test | Met |
| U9-NFR4.1 | 같음 | `LUNA_CALL` 사유 · 모델 · 판 · 지연 | 통과 | `LogCapture` 시험 | build-and-test | Met |
| U9-NFR5.1 | 같음 | 실호출 시험이 check · CI 밖 | 통과 | `verifyLunaLiveIsolation` | build-and-test | Met |
| U9-NFR5.2 | 같음 | 키 없으면 SKIPPED · 통과 아님 | SKIPPED_NO_KEY | `lunaLiveTest` 보고서 | build-and-test | Met |
| U9-NFR5.3 | 같음 | test-after · Standard · u9 80% | 충족 | COV-U9 | build-and-test | Met |
| U9-NFR5.4 | 같음 | 의존성 이동 뒤 취약점 0 | 0건 | trivy bootJar | build-and-test | Met |
| U9-NFR6.1 | 같음 | 기존 인터페이스 · 게이트웨이 회귀 0 | 회귀 0 | 기준선 대비 `check` | build-and-test | Met |

## 4. 준비 상태

| 항목 | 판정 |
|---|---|
| 빌드 준비 | 됨 |
| 시험 준비 | 됨(U8 · U9 범위). 인프라 시험은 GNU `date` · shellcheck 컨테이너로 모두 통과 |
| 배포 준비 | 이번 작업 범위 밖(team.md Deployment). PR 병합 전 `ci-gate`가 shellcheck · gitleaks를 돈다 |

## 5. 남은 점

- turn-metrics 2건 실패의 원인: 이 PC의 `date`가 uutils coreutils 0.8.0이라 시험 도우미 `iso_ms`의 `%3N`이 9자리(`.121000000`)를 낸다. 그래서 합성 로그 시각이 어긋났다. `turn-metrics` 자체와 U8 · U9 코드는 원인이 아니다. CI(ubuntu GNU coreutils)에는 영향이 없다.
- 같은 이유로 `infra/bin/probe-three`의 `now_ms`(`date +%s%3N`)도 이 PC에서는 19자리를 낸다. 이 PC에서 그 도구로 잰 지연 값은 믿지 않는다. 시험 도우미 · 도구를 `%3N`에 기대지 않게 고치는 일은 별도 과제로 남긴다(이번 범위 밖).
- 단위 전체 추적: 196개 중 13개만 덮임(`cross-unit-traceability.md`). U10 ~ U15 건너뜀 때문이다. U9의 FR1.1 ~ FR1.4는 업무 규칙(BR)으로 덮였으나 FR ID 행이 없다.
- 실호출(FR12.1)은 키가 없어 하지 않았다.
