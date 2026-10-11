# 교차 유닛 추적 — 최종 덮임 확인 (3.6 Build and Test Step 10)

> 3.6 Build and Test Step 10 산출물 · 2026-10-11 · 단독 실행(`--single`) · 대화 언어 한국어
>
> 이것은 Construction 단계 경계 확인이 아니라 이 단계 안의 확인이다. 판정 규칙(단계 정의 Step 10): `requirements.md`의 모든 FR · NFR과 `stories.md`의 모든 세 구간 AC가, stage-level 또는 유닛별 `code-generation/traceability.json` 중 적어도 한 곳에서 status `OK`이고 그 target 파일이 저장소에 있어야 덮인 것이다. 덮이지 않은 ID는 모두 이 단계의 발견으로 승인 게이트에 올린다.
> 입력: `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md`, `inception/user-stories/traceability.json`(FR · NFR → 스토리), `inception/units-generation/traceability.json`(스토리 → 유닛), `construction/u1-walking-skeleton/code-generation/traceability.json`. stage-level `construction/code-generation/traceability.json`은 없다.

## 1. 판정

**실패.** 열거한 335개 중 덮인 것은 AC 56개뿐이고 279개가 덮이지 않았다.

| 종류 | 열거 | 덮임(OK + 대상 파일 있음) | 안 덮임: U1 추적에 Deferred | 안 덮임: 추적에 없음 | 안 덮임 합계 |
|---|---|---|---|---|---|
| FR | 48 | 0 | 0 | 48 | 48 |
| NFR | 12 | 0 | 0 | 12 | 12 |
| AC | 275 | 56 | 27 | 192 | 219 |
| 합계 | 335 | 56 | 27 | 252 | 279 |

- **왜 실패인가.** 추적 파일은 U1 code-generation 것 하나뿐이다. U2 ~ U7은 3.1 ~ 3.5를 하지 않았다(사람 지시: U1만, U2 3.1 시작 금지). 그래서 U2 ~ U7이 맡는 AC 219개가 덮이지 않았고, 이 결과는 U2 ~ U7 Construction이 끝나기 전에는 바뀌지 않는다. U1이 맡은 스토리(US2.2 · US6.1)의 AC 13개는 모두 덮였다.
- **FR · NFR 묶음 ID.** U1 code-generation 추적은 AC · BR · NFRx.y(U1 nfr-requirements의 세분 ID)로 추적하고 FR 번호와 NFR1 ~ NFR12 묶음 번호를 쓰지 않는다. 규칙대로 같은 ID를 찾으므로 FR 48 · NFR 12는 문자 그대로 0개 덮임이다. 4절에 참고 열(스토리 AC 중 U1 덮임 수, NFRx.y 덮임 수)을 두었지만 판정에는 쓰지 않았다 — 예를 들어 FR2.1은 스토리 AC 13개가 모두 U1에서 덮였어도 FR2.1 자체가 추적에 없어 '안 덮임'이다.
- **대상 파일.** U1 추적의 OK 항목 192개는 target 파일이 모두 저장소에 있다(없는 것 0).

## 2. 방법

| 항목 | 규칙 |
|---|---|
| FR 열거 | `### FRn` 묶음 7개 + `**FRn.m …**` 하위 41개 = 48 |
| NFR 열거 | 비기능 요구 표의 `NFR1` ~ `NFR12` = 12 |
| AC 열거 | `stories.md`의 세 구간 ID `ACn.m.k` 전부 = 275 |
| 추적 파일 | stage-level `construction/code-generation/traceability.json`(없음) + `construction/*/code-generation/traceability.json`(U1 하나) |
| 덮임 | 같은 ID가 status `OK`이고 target 파일이 저장소 루트 기준으로 있음 |
| 소유 유닛 | AC는 그 스토리(`USn.m`)의 유닛(units-generation 추적), FR · NFR은 user-stories 추적의 스토리 → 유닛. NFR5 · NFR7 · NFR11은 user-stories 추적이 `Deferred → nfr-requirements`로 둔 것 |
| 참고 열 | 판정이 아니다. FR · NFR의 스토리에 속한 AC 중 U1에서 덮인 수, NFRn의 U1 세분 ID(NFRn.y) 수 |

계산은 이 단계의 도움 스크립트(scratchpad `bt9/xunit_trace.py`, 결과 `xunit.json`)가 했고 아래 표는 그 결과를 그대로 옮겼다.

## 3. 소유 유닛별 안 덮인 수

| 소유 유닛 | 안 덮인 AC | 안 덮인 FR(소유에 걸린 것) | 안 덮인 NFR(소유에 걸린 것) |
|---|---|---|---|
| U1 | 0 | 3 | 0 |
| U2 | 61 | 12 | 4 |
| U3 | 14 | 8 | 3 |
| U4 | 58 | 15 | 6 |
| U5 | 31 | 12 | 1 |
| U6 | 49 | 13 | 5 |
| U7 | 6 | 2 | 3 |
| nfr-requirements | 0 | 0 | 3 |

- FR · NFR은 소유 유닛이 여럿일 수 있어 각 유닛 칸에 겹쳐 셌다(합이 60보다 크다). U1 칸의 FR 3개(FR2 · FR2.1 · FR5)는 1절의 'FR 번호 미추적' 때문이다.
- `nfr-requirements` 행은 user-stories 추적이 스토리 대신 NFR 요구 단계로 넘긴 NFR5 · NFR7 · NFR11이다. U1에서는 이들의 세분 ID(NFR5.x 8개 중 OK 2 · NFR7.x 8개 중 OK 3 · NFR11.1 OK)가 추적되지만 묶음 ID는 없다(4절 참고 열).

## 4. FR · NFR 판정 (60)

| ID | 판정 | 스토리(user-stories 추적) | 소유 유닛 | 참고: 스토리 AC 중 U1 덮임 | 참고: U1 추적의 NFRx.y(전체 · OK · 대상 있음) |
|---|---|---|---|---|---|
| FR1 | 안 덮임(추적에 없음) | US1.1, US1.2, US1.3, US1.4, US1.5, US1.6 | U2 | 10/58 | — |
| FR1.1 | 안 덮임(추적에 없음) | US1.1 | U2 | 3/10 | — |
| FR1.2 | 안 덮임(추적에 없음) | US1.2 | U2 | 0/9 | — |
| FR1.3 | 안 덮임(추적에 없음) | US1.2 | U2 | 0/9 | — |
| FR1.4 | 안 덮임(추적에 없음) | US1.3 | U2 | 0/10 | — |
| FR1.5 | 안 덮임(추적에 없음) | US1.4, US1.5 | U2 | 7/20 | — |
| FR1.6 | 안 덮임(추적에 없음) | US1.6 | U2 | 0/9 | — |
| FR2 | 안 덮임(추적에 없음) | US2.1, US2.2, US2.3, US6.1 | U1, U2 | 22/35 | — |
| FR2.1 | 안 덮임(추적에 없음) | US2.2, US6.1 | U1 | 13/13 | — |
| FR2.2 | 안 덮임(추적에 없음) | US2.1, US1.4 | U2 | 16/29 | — |
| FR2.3 | 안 덮임(추적에 없음) | US1.4, US2.1, US3.1, US4.1 | U2, U4, U5 | 23/51 | — |
| FR2.4 | 안 덮임(추적에 없음) | US2.3 | U2 | 0/8 | — |
| FR3 | 안 덮임(추적에 없음) | US3.1, US3.2, US3.3, US3.4, US3.5, US3.6 | U4 | 16/56 | — |
| FR3.1 | 안 덮임(추적에 없음) | US3.1 | U4 | 7/9 | — |
| FR3.2 | 안 덮임(추적에 없음) | US3.2 | U4 | 0/20 | — |
| FR3.3 | 안 덮임(추적에 없음) | US3.3, US3.6 | U4 | 9/21 | — |
| FR3.4 | 안 덮임(추적에 없음) | US3.6 | U4 | 0/12 | — |
| FR3.5 | 안 덮임(추적에 없음) | US3.4, US3.5, US3.2 | U4 | 0/26 | — |
| FR4 | 안 덮임(추적에 없음) | US4.1, US4.2, US4.3 | U5, U6 | 0/28 | — |
| FR4.1 | 안 덮임(추적에 없음) | US4.1 | U5 | 0/13 | — |
| FR4.2 | 안 덮임(추적에 없음) | US4.2 | U5 | 0/10 | — |
| FR4.3 | 안 덮임(추적에 없음) | US4.2, US5.2 | U5, U6 | 0/19 | — |
| FR4.4 | 안 덮임(추적에 없음) | US4.3 | U6 | 0/5 | — |
| FR5 | 안 덮임(추적에 없음) | US6.1, US6.2, US6.3, US6.4, US6.5, US6.6 | U1, U3, U4, U5 | 13/53 | — |
| FR5.1 | 안 덮임(추적에 없음) | US6.2 | U3 | 8/13 | — |
| FR5.2 | 안 덮임(추적에 없음) | US6.3, US3.5 | U4 | 0/12 | — |
| FR5.3 | 안 덮임(추적에 없음) | US6.4 | U3 | 0/9 | — |
| FR5.4 | 안 덮임(추적에 없음) | US6.4 | U3 | 0/9 | — |
| FR5.5 | 안 덮임(추적에 없음) | US6.5 | U5 | 0/8 | — |
| FR5.6 | 안 덮임(추적에 없음) | US6.5 | U5 | 0/8 | — |
| FR5.7 | 안 덮임(추적에 없음) | US6.6, US3.4 | U4 | 0/12 | — |
| FR6 | 안 덮임(추적에 없음) | US5.1, US5.2, US5.3, US5.4, US5.5 | U6 | 0/44 | — |
| FR6.1 | 안 덮임(추적에 없음) | US5.1 | U6 | 0/10 | — |
| FR6.2 | 안 덮임(추적에 없음) | US5.2 | U6 | 0/9 | — |
| FR6.3 | 안 덮임(추적에 없음) | US5.2 | U6 | 0/9 | — |
| FR6.4 | 안 덮임(추적에 없음) | US5.3 | U6 | 0/14 | — |
| FR6.5 | 안 덮임(추적에 없음) | US5.4, US5.5 | U6 | 0/11 | — |
| FR7 | 안 덮임(추적에 없음) | US3.2, US3.3, US3.6, US4.2, US5.2, US6.3, US6.4, US6.6, US1.7 | U3, U4, U5, U6, U7 | 9/93 | — |
| FR7.1 | 안 덮임(추적에 없음) | US3.2, US3.3, US3.6, US6.6, US1.4 | U2, U4 | 16/65 | — |
| FR7.2 | 안 덮임(추적에 없음) | US6.3, US6.4, US6.5 | U3, U4, U5 | 0/26 | — |
| FR7.3 | 안 덮임(추적에 없음) | US6.4, US5.5 | U3, U6 | 0/13 | — |
| FR7.4 | 안 덮임(추적에 없음) | US5.2, US4.3 | U6 | 0/14 | — |
| FR7.5 | 안 덮임(추적에 없음) | US4.2, US6.5 | U5 | 0/18 | — |
| FR7.6 | 안 덮임(추적에 없음) | US4.2 | U5 | 0/10 | — |
| FR7.7 | 안 덮임(추적에 없음) | US5.2 | U6 | 0/9 | — |
| FR7.8 | 안 덮임(추적에 없음) | US3.2, US6.2, US6.4 | U3, U4 | 8/42 | — |
| FR7.9 | 안 덮임(추적에 없음) | US3.2, US6.6 | U4 | 0/29 | — |
| FR7.10 | 안 덮임(추적에 없음) | US1.7 | U7 | 0/6 | — |
| NFR1 | 안 덮임(추적에 없음) | US5.5, US6.4, US5.2, US3.6, US1.7, US6.3 | U3, U4, U6, U7 | 0/49 | 3 · 2 · 2 |
| NFR2 | 안 덮임(추적에 없음) | US3.5, US6.3, US6.4, US1.7, US1.4, US4.1 | U2, U3, U4, U5, U7 | 7/55 | 1 · 0 · 0 |
| NFR3 | 안 덮임(추적에 없음) | US6.4, US3.1, US5.2 | U3, U4, U6 | 7/27 | 2 · 2 · 2 |
| NFR4 | 안 덮임(추적에 없음) | US6.6, US3.3, US3.6 | U4 | 9/30 | 16 · 13 · 13 |
| NFR5 | 안 덮임(추적에 없음) | Deferred → nfr-requirements | nfr-requirements | — | 8 · 2 · 2 |
| NFR6 | 안 덮임(추적에 없음) | US3.2, US3.3, US6.3, US6.6, US2.1, US3.1 | U2, U4 | 25/70 | 5 · 5 · 5 |
| NFR7 | 안 덮임(추적에 없음) | Deferred → nfr-requirements | nfr-requirements | — | 8 · 3 · 3 |
| NFR8 | 안 덮임(추적에 없음) | US5.3 | U6 | 0/14 | — |
| NFR9 | 안 덮임(추적에 없음) | US1.1, US5.2, US3.3 | U2, U4, U6 | 12/28 | 15 · 14 · 14 |
| NFR10 | 안 덮임(추적에 없음) | US5.4, US1.7 | U6, U7 | 0/13 | 4 · 1 · 1 |
| NFR11 | 안 덮임(추적에 없음) | Deferred → nfr-requirements | nfr-requirements | — | 1 · 1 · 1 |
| NFR12 | 안 덮임(추적에 없음) | US1.6 | U2 | 0/9 | 4 · 4 · 4 |

## 5. AC 판정 (275)

| ID | 판정 | 스토리 | 소유 유닛 | 대상 파일(덮임) 또는 추적 상태 |
|---|---|---|---|---|
| AC1.1.1 | 안 덮임(U1 추적에 Deferred) | US1.1 | U2 | Deferred: u2-guardian-account (구글 로그인 흐름 · 02 · 03 · 04 분기) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.1.2 | 안 덮임(U1 추적에 Deferred) | US1.1 | U2 | Deferred: u2-guardian-account (구글 로그인 흐름 · 02 · 03 · 04 분기) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.1.3 | 안 덮임(U1 추적에 Deferred) | US1.1 | U2 | Deferred: u2-guardian-account (구글 로그인 흐름 · 02 · 03 · 04 분기) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.1.4 | 안 덮임(U1 추적에 Deferred) | US1.1 | U2 | Deferred: u2-guardian-account (구글 로그인 흐름 · 02 · 03 · 04 분기) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.1.5 | 안 덮임(U1 추적에 Deferred) | US1.1 | U2 | Deferred: u2-guardian-account (구글 로그인 흐름 · 02 · 03 · 04 분기) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.1.6 | 안 덮임(U1 추적에 Deferred) | US1.1 | U2 | Deferred: u2-guardian-account (구글 로그인 흐름 · 02 · 03 · 04 분기) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.1.7 | 덮임 | US1.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/web/GuardianApiIT.java` |
| AC1.1.8 | 덮임 | US1.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/web/GuardianApiIT.java` |
| AC1.1.9 | 덮임 | US1.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/web/GuardianApiIT.java` |
| AC1.1.10 | 안 덮임(U1 추적에 Deferred) | US1.1 | U2 | Deferred: u2-guardian-account (구글 로그인 흐름 · 02 · 03 · 04 분기) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.2.1 | 안 덮임(추적에 없음) | US1.2 | U2 | — |
| AC1.2.2 | 안 덮임(추적에 없음) | US1.2 | U2 | — |
| AC1.2.3 | 안 덮임(추적에 없음) | US1.2 | U2 | — |
| AC1.2.4 | 안 덮임(추적에 없음) | US1.2 | U2 | — |
| AC1.2.5 | 안 덮임(추적에 없음) | US1.2 | U2 | — |
| AC1.2.6 | 안 덮임(추적에 없음) | US1.2 | U2 | — |
| AC1.2.7 | 안 덮임(추적에 없음) | US1.2 | U2 | — |
| AC1.2.8 | 안 덮임(추적에 없음) | US1.2 | U2 | — |
| AC1.2.9 | 안 덮임(추적에 없음) | US1.2 | U2 | — |
| AC1.3.1 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.3.2 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.3.3 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.3.4 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.3.5 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.3.6 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.3.7 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.3.8 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.3.9 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.3.10 | 안 덮임(추적에 없음) | US1.3 | U2 | — |
| AC1.4.1 | 안 덮임(U1 추적에 Deferred) | US1.4 | U2 | Deferred: u2-guardian-account (03 수정 · 05 화면 · 26 · 설정 시트 · 05 이동 · 폰 인터넷 줄 · 종료 표현 구역) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.4.2 | 덮임 | US1.4 | U2 | `frontend/tests/u1/parent/home-04.test.tsx` |
| AC1.4.3 | 덮임 | US1.4 | U2 | `frontend/tests/u1/parent/home-04.test.tsx` |
| AC1.4.4 | 덮임 | US1.4 | U2 | `frontend/tests/u1/parent/home-04.test.tsx` |
| AC1.4.5 | 덮임 | US1.4 | U2 | `frontend/tests/u1/parent/home-04.test.tsx` |
| AC1.4.6 | 안 덮임(U1 추적에 Deferred) | US1.4 | U2 | Deferred: u4-activity-session (04 지금 활동 구역 · 11개 상태 재진입 · 08 네 칸 저장 · 탭 이동) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.4.7 | 안 덮임(U1 추적에 Deferred) | US1.4 | U2 | Deferred: u4-activity-session (04 지금 활동 구역 · 11개 상태 재진입 · 08 네 칸 저장 · 탭 이동) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.4.8 | 안 덮임(U1 추적에 Deferred) | US1.4 | U2 | Deferred: u4-activity-session (04 지금 활동 구역 · 11개 상태 재진입 · 08 네 칸 저장 · 탭 이동) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.4.9 | 덮임 | US1.4 | U2 | `frontend/tests/u1/parent/home-04.test.tsx` |
| AC1.4.10 | 안 덮임(U1 추적에 Deferred) | US1.4 | U2 | Deferred: u4-activity-session (04 지금 활동 구역 · 11개 상태 재진입 · 08 네 칸 저장 · 탭 이동) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.4.11 | 덮임 | US1.4 | U2 | `frontend/tests/u1/parent/home-04.test.tsx` |
| AC1.4.12 | 안 덮임(U1 추적에 Deferred) | US1.4 | U2 | Deferred: u2-guardian-account (03 수정 · 05 화면 · 26 · 설정 시트 · 05 이동 · 폰 인터넷 줄 · 종료 표현 구역) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.4.13 | 안 덮임(U1 추적에 Deferred) | US1.4 | U2 | Deferred: u4-activity-session (04 지금 활동 구역 · 11개 상태 재진입 · 08 네 칸 저장 · 탭 이동) — U1 범위 밖(functional-design 추적과 같음) |
| AC1.4.14 | 안 덮임(U1 추적에 Deferred) | US1.4 | U2 | Deferred: u4-activity-session (04 지금 활동 칸의 '지금은 시작할 수 없어요' + 사유 한 줄 — U1은 대화 기기 구역만 맡고 지금 활동 칸은 U4 소관) — U1 범위 밖(functional-design |
| AC1.4.15 | 덮임 | US1.4 | U2 | `frontend/tests/u1/parent/home-04.test.tsx` |
| AC1.5.1 | 안 덮임(추적에 없음) | US1.5 | U2 | — |
| AC1.5.2 | 안 덮임(추적에 없음) | US1.5 | U2 | — |
| AC1.5.3 | 안 덮임(추적에 없음) | US1.5 | U2 | — |
| AC1.5.4 | 안 덮임(추적에 없음) | US1.5 | U2 | — |
| AC1.5.5 | 안 덮임(추적에 없음) | US1.5 | U2 | — |
| AC1.6.1 | 안 덮임(추적에 없음) | US1.6 | U2 | — |
| AC1.6.2 | 안 덮임(추적에 없음) | US1.6 | U2 | — |
| AC1.6.3 | 안 덮임(추적에 없음) | US1.6 | U2 | — |
| AC1.6.4 | 안 덮임(추적에 없음) | US1.6 | U2 | — |
| AC1.6.5 | 안 덮임(추적에 없음) | US1.6 | U2 | — |
| AC1.6.6 | 안 덮임(추적에 없음) | US1.6 | U2 | — |
| AC1.6.7 | 안 덮임(추적에 없음) | US1.6 | U2 | — |
| AC1.6.8 | 안 덮임(추적에 없음) | US1.6 | U2 | — |
| AC1.6.9 | 안 덮임(추적에 없음) | US1.6 | U2 | — |
| AC1.7.1 | 안 덮임(추적에 없음) | US1.7 | U7 | — |
| AC1.7.2 | 안 덮임(추적에 없음) | US1.7 | U7 | — |
| AC1.7.3 | 안 덮임(추적에 없음) | US1.7 | U7 | — |
| AC1.7.4 | 안 덮임(추적에 없음) | US1.7 | U7 | — |
| AC1.7.5 | 안 덮임(추적에 없음) | US1.7 | U7 | — |
| AC1.7.6 | 안 덮임(추적에 없음) | US1.7 | U7 | — |
| AC2.1.1 | 덮임 | US2.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/device/DeviceVerdictTest.java` |
| AC2.1.2 | 덮임 | US2.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/device/DeviceVerdictTest.java` |
| AC2.1.3 | 덮임 | US2.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/device/DeviceVerdictTest.java` |
| AC2.1.4 | 덮임 | US2.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/device/DeviceVerdictTest.java` |
| AC2.1.5 | 덮임 | US2.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/device/DeviceVerdictTest.java` |
| AC2.1.6 | 덮임 | US2.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/device/DeviceVerdictTest.java` |
| AC2.1.7 | 안 덮임(U1 추적에 Deferred) | US2.1 | U2 | Deferred: u2-guardian-account (03 수정 · 05 화면 · 26 · 설정 시트 · 05 이동 · 폰 인터넷 줄 · 종료 표현 구역) — U1 범위 밖(functional-design 추적과 같음) |
| AC2.1.8 | 안 덮임(U1 추적에 Deferred) | US2.1 | U2 | Deferred: u2-guardian-account (03 수정 · 05 화면 · 26 · 설정 시트 · 05 이동 · 폰 인터넷 줄 · 종료 표현 구역) — U1 범위 밖(functional-design 추적과 같음) |
| AC2.1.9 | 안 덮임(U1 추적에 Deferred) | US2.1 | U2 | Deferred: u2-guardian-account (03 수정 · 05 화면 · 26 · 설정 시트 · 05 이동 · 폰 인터넷 줄 · 종료 표현 구역) — U1 범위 밖(functional-design 추적과 같음) |
| AC2.1.10 | 덮임 | US2.1 | U2 | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/gateway/DeviceStatusPolicyTest.java` |
| AC2.1.11 | 덮임 | US2.1 | U2 | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/gateway/DeviceStatusPolicyTest.java` |
| AC2.1.12 | 안 덮임(U1 추적에 Deferred) | US2.1 | U2 | Deferred: u2-guardian-account (03 수정 · 05 화면 · 26 · 설정 시트 · 05 이동 · 폰 인터넷 줄 · 종료 표현 구역) — U1 범위 밖(functional-design 추적과 같음) |
| AC2.1.13 | 안 덮임(U1 추적에 Deferred) | US2.1 | U2 | Deferred: u2-guardian-account (03 수정 · 05 화면 · 26 · 설정 시트 · 05 이동 · 폰 인터넷 줄 · 종료 표현 구역) — U1 범위 밖(functional-design 추적과 같음) |
| AC2.1.14 | 덮임 | US2.1 | U2 | `backend/core-api/src/test/java/kr/mujoong/core/web/GuardianApiIT.java` |
| AC2.2.1 | 덮임 | US2.2 | U1 | `frontend/tests/u1/parent/register-05a.test.tsx` |
| AC2.2.2 | 덮임 | US2.2 | U1 | `backend/core-api/src/test/java/kr/mujoong/core/web/DeviceRegisterApiIT.java` |
| AC2.2.3 | 덮임 | US2.2 | U1 | `backend/core-api/src/test/java/kr/mujoong/core/web/DeviceRegisterApiIT.java` |
| AC2.2.4 | 덮임 | US2.2 | U1 | `frontend/tests/u1/parent/register-05a.test.tsx` |
| AC2.2.5 | 덮임 | US2.2 | U1 | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/web/DeviceGatewayIT.java` |
| AC2.2.6 | 덮임 | US2.2 | U1 | `backend/core-api/src/test/java/kr/mujoong/core/device/DeviceJdbcRepositoryIT.java` |
| AC2.2.7 | 덮임 | US2.2 | U1 | `backend/core-api/src/test/java/kr/mujoong/core/web/DeviceRegisterApiIT.java` |
| AC2.2.8 | 덮임 | US2.2 | U1 | `frontend/tests/u1/parent/register-05a.test.tsx` |
| AC2.3.1 | 안 덮임(추적에 없음) | US2.3 | U2 | — |
| AC2.3.2 | 안 덮임(추적에 없음) | US2.3 | U2 | — |
| AC2.3.3 | 안 덮임(추적에 없음) | US2.3 | U2 | — |
| AC2.3.4 | 안 덮임(추적에 없음) | US2.3 | U2 | — |
| AC2.3.5 | 안 덮임(추적에 없음) | US2.3 | U2 | — |
| AC2.3.6 | 안 덮임(추적에 없음) | US2.3 | U2 | — |
| AC2.3.7 | 안 덮임(추적에 없음) | US2.3 | U2 | — |
| AC2.3.8 | 안 덮임(추적에 없음) | US2.3 | U2 | — |
| AC3.1.1 | 덮임 | US3.1 | U4 | `frontend/tests/u1/parent/free-08.test.tsx` |
| AC3.1.2 | 안 덮임(U1 추적에 Deferred) | US3.1 | U4 | Deferred: u4-activity-session (04 지금 활동 구역 · 11개 상태 재진입 · 08 네 칸 저장 · 탭 이동) — U1 범위 밖(functional-design 추적과 같음) |
| AC3.1.3 | 덮임 | US3.1 | U4 | `frontend/tests/u1/parent/free-08.test.tsx` |
| AC3.1.4 | 덮임 | US3.1 | U4 | `frontend/tests/u1/parent/free-08.test.tsx` |
| AC3.1.5 | 안 덮임(U1 추적에 Deferred) | US3.1 | U4 | Deferred: u4-activity-session (04 지금 활동 구역 · 11개 상태 재진입 · 08 네 칸 저장 · 탭 이동) — U1 범위 밖(functional-design 추적과 같음) |
| AC3.1.6 | 덮임 | US3.1 | U4 | `backend/core-api/src/test/java/kr/mujoong/core/activity/ActivityStartEndIT.java` |
| AC3.1.7 | 덮임 | US3.1 | U4 | `backend/core-api/src/test/java/kr/mujoong/core/activity/SessionPackageIT.java` |
| AC3.1.8 | 덮임 | US3.1 | U4 | `backend/core-api/src/test/java/kr/mujoong/core/activity/ActivityStartEndIT.java` |
| AC3.1.9 | 덮임 | US3.1 | U4 | `backend/core-api/src/test/java/kr/mujoong/core/activity/PreparingTimeoutCheckerTest.java` |
| AC3.2.1 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.2 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.3 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.4 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.5 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.6 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.7 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.8 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.9 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.10 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.11 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.12 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.13 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.14 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.15 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.16 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.17 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.18 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.19 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.2.20 | 안 덮임(추적에 없음) | US3.2 | U4 | — |
| AC3.3.1 | 덮임 | US3.3 | U4 | `frontend/tests/u1/parent/p1-dialog.test.tsx` |
| AC3.3.2 | 덮임 | US3.3 | U4 | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/SessionLifecycleIT.java` |
| AC3.3.3 | 덮임 | US3.3 | U4 | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/SessionLifecycleIT.java` |
| AC3.3.4 | 덮임 | US3.3 | U4 | `frontend/tests/u1/parent/done-17.test.tsx` |
| AC3.3.5 | 덮임 | US3.3 | U4 | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/SessionLifecycleIT.java` |
| AC3.3.6 | 덮임 | US3.3 | U4 | `frontend/tests/u1/parent/done-17.test.tsx` |
| AC3.3.7 | 덮임 | US3.3 | U4 | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/SessionLifecycleIT.java` |
| AC3.3.8 | 덮임 | US3.3 | U4 | `backend/core-api/src/test/java/kr/mujoong/core/activity/ActivityStartEndIT.java` |
| AC3.3.9 | 덮임 | US3.3 | U4 | `frontend/tests/u1/parent/p1-dialog.test.tsx` |
| AC3.4.1 | 안 덮임(추적에 없음) | US3.4 | U4 | — |
| AC3.4.2 | 안 덮임(추적에 없음) | US3.4 | U4 | — |
| AC3.4.3 | 안 덮임(추적에 없음) | US3.4 | U4 | — |
| AC3.5.1 | 안 덮임(추적에 없음) | US3.5 | U4 | — |
| AC3.5.2 | 안 덮임(추적에 없음) | US3.5 | U4 | — |
| AC3.5.3 | 안 덮임(추적에 없음) | US3.5 | U4 | — |
| AC3.6.1 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.2 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.3 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.4 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.5 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.6 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.7 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.8 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.9 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.10 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.11 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC3.6.12 | 안 덮임(추적에 없음) | US3.6 | U4 | — |
| AC4.1.1 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.2 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.3 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.4 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.5 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.6 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.7 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.8 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.9 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.10 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.11 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.12 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.1.13 | 안 덮임(추적에 없음) | US4.1 | U5 | — |
| AC4.2.1 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.2.2 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.2.3 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.2.4 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.2.5 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.2.6 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.2.7 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.2.8 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.2.9 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.2.10 | 안 덮임(추적에 없음) | US4.2 | U5 | — |
| AC4.3.1 | 안 덮임(추적에 없음) | US4.3 | U6 | — |
| AC4.3.2 | 안 덮임(추적에 없음) | US4.3 | U6 | — |
| AC4.3.3 | 안 덮임(추적에 없음) | US4.3 | U6 | — |
| AC4.3.4 | 안 덮임(추적에 없음) | US4.3 | U6 | — |
| AC4.3.5 | 안 덮임(추적에 없음) | US4.3 | U6 | — |
| AC5.1.1 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.1.2 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.1.3 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.1.4 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.1.5 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.1.6 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.1.7 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.1.8 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.1.9 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.1.10 | 안 덮임(추적에 없음) | US5.1 | U6 | — |
| AC5.2.1 | 안 덮임(추적에 없음) | US5.2 | U6 | — |
| AC5.2.2 | 안 덮임(추적에 없음) | US5.2 | U6 | — |
| AC5.2.3 | 안 덮임(추적에 없음) | US5.2 | U6 | — |
| AC5.2.4 | 안 덮임(추적에 없음) | US5.2 | U6 | — |
| AC5.2.5 | 안 덮임(추적에 없음) | US5.2 | U6 | — |
| AC5.2.6 | 안 덮임(추적에 없음) | US5.2 | U6 | — |
| AC5.2.7 | 안 덮임(추적에 없음) | US5.2 | U6 | — |
| AC5.2.8 | 안 덮임(추적에 없음) | US5.2 | U6 | — |
| AC5.2.9 | 안 덮임(추적에 없음) | US5.2 | U6 | — |
| AC5.3.1 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.2 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.3 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.4 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.5 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.6 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.7 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.8 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.9 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.10 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.11 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.12 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.13 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.3.14 | 안 덮임(추적에 없음) | US5.3 | U6 | — |
| AC5.4.1 | 안 덮임(추적에 없음) | US5.4 | U6 | — |
| AC5.4.2 | 안 덮임(추적에 없음) | US5.4 | U6 | — |
| AC5.4.3 | 안 덮임(추적에 없음) | US5.4 | U6 | — |
| AC5.4.4 | 안 덮임(추적에 없음) | US5.4 | U6 | — |
| AC5.4.5 | 안 덮임(추적에 없음) | US5.4 | U6 | — |
| AC5.4.6 | 안 덮임(추적에 없음) | US5.4 | U6 | — |
| AC5.4.7 | 안 덮임(추적에 없음) | US5.4 | U6 | — |
| AC5.5.1 | 안 덮임(추적에 없음) | US5.5 | U6 | — |
| AC5.5.2 | 안 덮임(추적에 없음) | US5.5 | U6 | — |
| AC5.5.3 | 안 덮임(추적에 없음) | US5.5 | U6 | — |
| AC5.5.4 | 안 덮임(추적에 없음) | US5.5 | U6 | — |
| AC6.1.1 | 덮임 | US6.1 | U1 | `frontend/tests/u1/device/d0-view.test.tsx` |
| AC6.1.2 | 덮임 | US6.1 | U1 | `backend/core-api/src/test/java/kr/mujoong/core/web/DeviceBootstrapApiIT.java` |
| AC6.1.3 | 덮임 | US6.1 | U1 | `backend/core-api/src/test/java/kr/mujoong/core/web/DeviceBootstrapApiIT.java` |
| AC6.1.4 | 덮임 | US6.1 | U1 | `frontend/tests/u1/device/device-api.test.ts` |
| AC6.1.5 | 덮임 | US6.1 | U1 | `frontend/tests/u1/device/device-machine.test.ts` |
| AC6.2.1 | 덮임 | US6.2 | U3 | `frontend/tests/u1/device/t19-view.test.tsx` |
| AC6.2.2 | 덮임 | US6.2 | U3 | `frontend/tests/u1/device/mic-permission.test.ts` |
| AC6.2.3 | 덮임 | US6.2 | U3 | `frontend/tests/u1/device/device-runtime.test.ts` |
| AC6.2.4 | 안 덮임(U1 추적에 Deferred) | US6.2 | U3 | Deferred: u3-voice-conversation (T19 8상태 주입 · 다시 연결 중 · 탭 숨김 연결 유지 · 끼어들기 0.5초 · 터치 영역) — U1 범위 밖(functional-design 추적과 같음) |
| AC6.2.5 | 덮임 | US6.2 | U3 | `frontend/tests/u1/device/t19-view.test.tsx` |
| AC6.2.6 | 안 덮임(U1 추적에 Deferred) | US6.2 | U3 | Deferred: u3-voice-conversation (T19 8상태 주입 · 다시 연결 중 · 탭 숨김 연결 유지 · 끼어들기 0.5초 · 터치 영역) — U1 범위 밖(functional-design 추적과 같음) |
| AC6.2.7 | 안 덮임(U1 추적에 Deferred) | US6.2 | U3 | Deferred: u3-voice-conversation (T19 8상태 주입 · 다시 연결 중 · 탭 숨김 연결 유지 · 끼어들기 0.5초 · 터치 영역) — U1 범위 밖(functional-design 추적과 같음) |
| AC6.2.8 | 덮임 | US6.2 | U3 | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/web/DeviceGatewayIT.java` |
| AC6.2.9 | 덮임 | US6.2 | U3 | `frontend/tests/u1/device/player.test.ts` |
| AC6.2.10 | 덮임 | US6.2 | U3 | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/web/DeviceGatewayIT.java` |
| AC6.2.11 | 덮임 | US6.2 | U3 | `frontend/tests/u1/device/ws-client.test.ts` |
| AC6.2.12 | 안 덮임(U1 추적에 Deferred) | US6.2 | U3 | Deferred: u3-voice-conversation (T19 8상태 주입 · 다시 연결 중 · 탭 숨김 연결 유지 · 끼어들기 0.5초 · 터치 영역) — U1 범위 밖(functional-design 추적과 같음) |
| AC6.2.13 | 안 덮임(U1 추적에 Deferred) | US6.2 | U3 | Deferred: u3-voice-conversation (T19 8상태 주입 · 다시 연결 중 · 탭 숨김 연결 유지 · 끼어들기 0.5초 · 터치 영역) — U1 범위 밖(functional-design 추적과 같음) |
| AC6.3.1 | 안 덮임(추적에 없음) | US6.3 | U4 | — |
| AC6.3.2 | 안 덮임(추적에 없음) | US6.3 | U4 | — |
| AC6.3.3 | 안 덮임(추적에 없음) | US6.3 | U4 | — |
| AC6.3.4 | 안 덮임(추적에 없음) | US6.3 | U4 | — |
| AC6.3.5 | 안 덮임(추적에 없음) | US6.3 | U4 | — |
| AC6.3.6 | 안 덮임(추적에 없음) | US6.3 | U4 | — |
| AC6.3.7 | 안 덮임(추적에 없음) | US6.3 | U4 | — |
| AC6.3.8 | 안 덮임(추적에 없음) | US6.3 | U4 | — |
| AC6.3.9 | 안 덮임(추적에 없음) | US6.3 | U4 | — |
| AC6.4.1 | 안 덮임(추적에 없음) | US6.4 | U3 | — |
| AC6.4.2 | 안 덮임(추적에 없음) | US6.4 | U3 | — |
| AC6.4.3 | 안 덮임(추적에 없음) | US6.4 | U3 | — |
| AC6.4.4 | 안 덮임(추적에 없음) | US6.4 | U3 | — |
| AC6.4.5 | 안 덮임(추적에 없음) | US6.4 | U3 | — |
| AC6.4.6 | 안 덮임(추적에 없음) | US6.4 | U3 | — |
| AC6.4.7 | 안 덮임(추적에 없음) | US6.4 | U3 | — |
| AC6.4.8 | 안 덮임(추적에 없음) | US6.4 | U3 | — |
| AC6.4.9 | 안 덮임(추적에 없음) | US6.4 | U3 | — |
| AC6.5.1 | 안 덮임(추적에 없음) | US6.5 | U5 | — |
| AC6.5.2 | 안 덮임(추적에 없음) | US6.5 | U5 | — |
| AC6.5.3 | 안 덮임(추적에 없음) | US6.5 | U5 | — |
| AC6.5.4 | 안 덮임(추적에 없음) | US6.5 | U5 | — |
| AC6.5.5 | 안 덮임(추적에 없음) | US6.5 | U5 | — |
| AC6.5.6 | 안 덮임(추적에 없음) | US6.5 | U5 | — |
| AC6.5.7 | 안 덮임(추적에 없음) | US6.5 | U5 | — |
| AC6.5.8 | 안 덮임(추적에 없음) | US6.5 | U5 | — |
| AC6.6.1 | 안 덮임(추적에 없음) | US6.6 | U4 | — |
| AC6.6.2 | 안 덮임(추적에 없음) | US6.6 | U4 | — |
| AC6.6.3 | 안 덮임(추적에 없음) | US6.6 | U4 | — |
| AC6.6.4 | 안 덮임(추적에 없음) | US6.6 | U4 | — |
| AC6.6.5 | 안 덮임(추적에 없음) | US6.6 | U4 | — |
| AC6.6.6 | 안 덮임(추적에 없음) | US6.6 | U4 | — |
| AC6.6.7 | 안 덮임(추적에 없음) | US6.6 | U4 | — |
| AC6.6.8 | 안 덮임(추적에 없음) | US6.6 | U4 | — |
| AC6.6.9 | 안 덮임(추적에 없음) | US6.6 | U4 | — |

## 6. 안 덮인 요소 전체 목록

- **FR · U1(1)**: FR2.1
- **FR · U1, U2(1)**: FR2
- **FR · U1, U3, U4, U5(1)**: FR5
- **FR · U2(9)**: FR1 · FR1.1 · FR1.2 · FR1.3 · FR1.4 · FR1.5 · FR1.6 · FR2.2 · FR2.4
- **FR · U2, U4(1)**: FR7.1
- **FR · U2, U4, U5(1)**: FR2.3
- **FR · U3(3)**: FR5.1 · FR5.3 · FR5.4
- **FR · U3, U4(1)**: FR7.8
- **FR · U3, U4, U5(1)**: FR7.2
- **FR · U3, U4, U5, U6, U7(1)**: FR7
- **FR · U3, U6(1)**: FR7.3
- **FR · U4(9)**: FR3 · FR3.1 · FR3.2 · FR3.3 · FR3.4 · FR3.5 · FR5.2 · FR5.7 · FR7.9
- **FR · U5(6)**: FR4.1 · FR4.2 · FR5.5 · FR5.6 · FR7.5 · FR7.6
- **FR · U5, U6(2)**: FR4 · FR4.3
- **FR · U6(9)**: FR4.4 · FR6 · FR6.1 · FR6.2 · FR6.3 · FR6.4 · FR6.5 · FR7.4 · FR7.7
- **FR · U7(1)**: FR7.10
- **NFR · U2(1)**: NFR12
- **NFR · U2, U3, U4, U5, U7(1)**: NFR2
- **NFR · U2, U4(1)**: NFR6
- **NFR · U2, U4, U6(1)**: NFR9
- **NFR · U3, U4, U6(1)**: NFR3
- **NFR · U3, U4, U6, U7(1)**: NFR1
- **NFR · U4(1)**: NFR4
- **NFR · U6(1)**: NFR8
- **NFR · U6, U7(1)**: NFR10
- **NFR · nfr-requirements(3)**: NFR5 · NFR7 · NFR11
- **AC · U2(61)**: AC1.1.1 · AC1.1.2 · AC1.1.3 · AC1.1.4 · AC1.1.5 · AC1.1.6 · AC1.1.10 · AC1.2.1 · AC1.2.2 · AC1.2.3 · AC1.2.4 · AC1.2.5 · AC1.2.6 · AC1.2.7 · AC1.2.8 · AC1.2.9 · AC1.3.1 · AC1.3.2 · AC1.3.3 · AC1.3.4 · AC1.3.5 · AC1.3.6 · AC1.3.7 · AC1.3.8 · AC1.3.9 · AC1.3.10 · AC1.4.1 · AC1.4.6 · AC1.4.7 · AC1.4.8 · AC1.4.10 · AC1.4.12 · AC1.4.13 · AC1.4.14 · AC1.5.1 · AC1.5.2 · AC1.5.3 · AC1.5.4 · AC1.5.5 · AC1.6.1 · AC1.6.2 · AC1.6.3 · AC1.6.4 · AC1.6.5 · AC1.6.6 · AC1.6.7 · AC1.6.8 · AC1.6.9 · AC2.1.7 · AC2.1.8 · AC2.1.9 · AC2.1.12 · AC2.1.13 · AC2.3.1 · AC2.3.2 · AC2.3.3 · AC2.3.4 · AC2.3.5 · AC2.3.6 · AC2.3.7 · AC2.3.8
- **AC · U3(14)**: AC6.2.4 · AC6.2.6 · AC6.2.7 · AC6.2.12 · AC6.2.13 · AC6.4.1 · AC6.4.2 · AC6.4.3 · AC6.4.4 · AC6.4.5 · AC6.4.6 · AC6.4.7 · AC6.4.8 · AC6.4.9
- **AC · U4(58)**: AC3.1.2 · AC3.1.5 · AC3.2.1 · AC3.2.2 · AC3.2.3 · AC3.2.4 · AC3.2.5 · AC3.2.6 · AC3.2.7 · AC3.2.8 · AC3.2.9 · AC3.2.10 · AC3.2.11 · AC3.2.12 · AC3.2.13 · AC3.2.14 · AC3.2.15 · AC3.2.16 · AC3.2.17 · AC3.2.18 · AC3.2.19 · AC3.2.20 · AC3.4.1 · AC3.4.2 · AC3.4.3 · AC3.5.1 · AC3.5.2 · AC3.5.3 · AC3.6.1 · AC3.6.2 · AC3.6.3 · AC3.6.4 · AC3.6.5 · AC3.6.6 · AC3.6.7 · AC3.6.8 · AC3.6.9 · AC3.6.10 · AC3.6.11 · AC3.6.12 · AC6.3.1 · AC6.3.2 · AC6.3.3 · AC6.3.4 · AC6.3.5 · AC6.3.6 · AC6.3.7 · AC6.3.8 · AC6.3.9 · AC6.6.1 · AC6.6.2 · AC6.6.3 · AC6.6.4 · AC6.6.5 · AC6.6.6 · AC6.6.7 · AC6.6.8 · AC6.6.9
- **AC · U5(31)**: AC4.1.1 · AC4.1.2 · AC4.1.3 · AC4.1.4 · AC4.1.5 · AC4.1.6 · AC4.1.7 · AC4.1.8 · AC4.1.9 · AC4.1.10 · AC4.1.11 · AC4.1.12 · AC4.1.13 · AC4.2.1 · AC4.2.2 · AC4.2.3 · AC4.2.4 · AC4.2.5 · AC4.2.6 · AC4.2.7 · AC4.2.8 · AC4.2.9 · AC4.2.10 · AC6.5.1 · AC6.5.2 · AC6.5.3 · AC6.5.4 · AC6.5.5 · AC6.5.6 · AC6.5.7 · AC6.5.8
- **AC · U6(49)**: AC4.3.1 · AC4.3.2 · AC4.3.3 · AC4.3.4 · AC4.3.5 · AC5.1.1 · AC5.1.2 · AC5.1.3 · AC5.1.4 · AC5.1.5 · AC5.1.6 · AC5.1.7 · AC5.1.8 · AC5.1.9 · AC5.1.10 · AC5.2.1 · AC5.2.2 · AC5.2.3 · AC5.2.4 · AC5.2.5 · AC5.2.6 · AC5.2.7 · AC5.2.8 · AC5.2.9 · AC5.3.1 · AC5.3.2 · AC5.3.3 · AC5.3.4 · AC5.3.5 · AC5.3.6 · AC5.3.7 · AC5.3.8 · AC5.3.9 · AC5.3.10 · AC5.3.11 · AC5.3.12 · AC5.3.13 · AC5.3.14 · AC5.4.1 · AC5.4.2 · AC5.4.3 · AC5.4.4 · AC5.4.5 · AC5.4.6 · AC5.4.7 · AC5.5.1 · AC5.5.2 · AC5.5.3 · AC5.5.4
- **AC · U7(6)**: AC1.7.1 · AC1.7.2 · AC1.7.3 · AC1.7.4 · AC1.7.5 · AC1.7.6

## 7. 다음 단계와 해결 후보

| 원인 | 후보 | 영향 추정 |
|---|---|---|
| U2 ~ U7 미구현(AC 219 · FR · NFR 대부분) | 사람이 U2 ~ U7 Construction(3.1 ~ 3.5)을 시작하도록 정하고, 각 유닛 3.5 뒤 이 단계를 다시 돌린다 | 유닛마다 3.1 ~ 3.6 전체 — 이 단계가 추정할 범위 밖(일정 · 인력 결정) |
| FR · NFR 묶음 ID가 code-generation 추적에 없음 | ① 각 유닛 code-generation 추적에 FR · NFR 묶음 ID 행(대상: 덮는 AC · 시험 파일)을 더함 ② 이 확인이 스토리 매핑(FR → US → AC)으로 파생 판정하도록 규칙을 바꿈(프레임워크 · 사람 결정) | ① 유닛당 0.5 ~ 1시간, 위험 낮음 ② 규칙 변경은 이 단계가 정할 수 없음 |
| U1 추적의 Deferred AC 27개(U2 · U3 · U4 몫으로 넘김) | 소유 유닛 Construction에서 덮는다 | 위 첫 줄에 포함 |

단독 실행이라 코드 생성으로 되돌아가는 점프는 하지 않는다. 이 발견은 승인 게이트(단독 실행 요약)에 그대로 올린다.
