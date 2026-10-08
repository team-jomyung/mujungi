# functional-spec.md (초안) · Unit `ai-goals-results` · AI · 목표 · 연습 · 결과 (Result Worker · Neo4j · 판정)

> 초안. 화면 설계 v1.5j(2026-10-08)와 개발 명세 1008에서 뽑았다. AI-DLC Edit File 모드에서 붙여넣기용이며 번호 · 절 구성은 AI-DLC가 다시 매긴다. 화면 설계가 기준이다.


## S-27 · 27 활동 기록 상세
활동 하나의 상세. 대화 기록 · 생활 팁 · 연습 목표 후보(담기). 연습이면 18에서 고른 목표 이름만 위에.

진입 조건: AUTH

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| SEG-TAB | 세그먼트 | 대화 기록 · 생활 팁 · 연습 목표 | 칸 전환 |
| BTN-ADD-GOAL | 단추 | 담기 | POST goal-selections → '담김 ✓' (이동 없음) · 다시 누르면 DELETE → 「담기」 |
| BTN-RETRY | 단추 | 다시 시도 (정리 실패) | POST result/retry |
| BTN-BACK | 단추 | ← | → 26 (보던 날 유지) |

**API**
- `GET /api/sessions/{id}/result → {transcript[], tips[], candidates[{recommendationId, behaviorKey, reason, evidence, selected}], practiceSummary?}`
- `POST /api/goal-selections {recommendationId}`

**오류**
| 상황 | 처리 |
|---|---|
| 정리 실패 | 빨간 카드 + 다시 시도 · 대화 기록은 보임 |

## S-18 · 18 내 연습 목표
담은 목표 목록(★ 즐겨찾기 · 담은 순). 줄을 눌러 고르고(여러 개) 연습 시작. 별 옆 「×」로 삭제. 비어 있으면 「자유대화 하러 가기」.

진입 조건: AUTH, CONSENT, CHILD, DEVICE, NO_SESSION

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| CHK-GOAL | 체크 | 목표 줄 누름 = 선택 토글 | 여러 개 · 0개면 「시작」 꺼짐 |
| BTN-STAR | 단추 | ☆ / ★ | PATCH favorite · ★는 맨 위 즐겨찾기 묶음 · 연습 순서 가중치 |
| BTN-DELETE | 단추 | × (별 옆 작은 단추) | DELETE goal-selections/{id} → 줄 사라짐 + 토스트 「되돌리기」 3초 → POST restore |
| BTN-START | 단추 | 선택한 N개로 연습 시작 | POST activities/practice → 12 (연습 · 참여 기다리는 중) |
| BTN-GO-FREE | 단추 | 자유대화 하러 가기 (비어 있음) | → 08 |

**API**
- `GET /api/goal-selections?childId`
- `PATCH /api/goal-selections/{id} {favorite}`
- `DELETE /api/goal-selections/{id} (소프트 삭제 · 3초 안 복구 가능)`
- `POST /api/goal-selections/{id}/restore`
- `POST /api/activities/practice {childId, goalSelectionIds[], idempotencyKey} → {sessionId}`

**검증**
| 규칙 | 어기면 |
|---|---|
| 1개 이상 선택 | 없으면 「시작」 꺼짐 |

**오류**
| 상황 | 처리 |
|---|---|
| 상황 생성 실패 | 18로 돌아와 빨간 카드 '연습 상황을 만들지 못했어요' |

## S-28 · 28 리포트 범위 고르기
리포트 범위. 빠른 선택 3개 · 달력(첫 탭 시작일, 둘째 탭 끝일, 드래그) · 활동 줄 체크 토글 · 만들기 → 만드는 중(몇 초, 화면 머무름) → 30. 날 토글 없음(시작 · 끝 탭과 손짓이 겹침).

진입 조건: AUTH, CHILD

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| SEG-QUICK | 세그먼트 | 최근 2주 · 이번 달 · 전체 · 직접 | 달력 칠함 · 목록 · N 갱신 (처음은 최근 2주) |
| CAL-RANGE | 달력 | 첫 탭 시작일 · 둘째 탭 끝일 · 드래그 · ‹ › | 직접 고르기(빠른 선택 해제) · 미래 날은 못 누름 |
| ROW-ACTIVITY | 줄(체크) | 활동 빼기 · 넣기 | 토글 · N 갱신 |
| BTN-MAKE | 단추 | 활동 N개로 리포트 만들기 | POST reports → 만드는 중(같은 화면) → READY면 30 · N=0이면 꺼짐 |
| BTN-CANCEL | 단추 | 취소 (만드는 중) | DELETE reports/{id} → 범위 화면(범위 유지) |
| BTN-RETRY | 단추 | 다시 시도 (실패) | 같은 범위로 POST |
| BTN-RANGE-AGAIN | 단추 | 범위 다시 (실패) | 범위 화면(범위 유지) |
| BTN-BACK | 단추 | ← | 범위 → 26 · 만드는 중 → 취소와 같음 · 실패 → 26 |

**API**
- `GET /api/reports/preview?childId&from&to → {sessions[{sessionId, date, type, minutes}]}`
- `POST /api/reports {childId, from, to, sessionIds[], idempotencyKey} → {reportId, status:GENERATING}`
- `GET /api/reports/{id} (폴링 2초) → {status: GENERATING|READY|FAILED}`
- `DELETE /api/reports/{id} (GENERATING일 때 취소)`

**검증**
| 규칙 | 어기면 |
|---|---|
| 활동 1개 이상 | 없으면 「만들기」 꺼짐 + 안내 |
| 끝나는 날 ≤ 오늘 | 미래 날은 누를 수 없음 |

**오류**
| 상황 | 처리 |
|---|---|
| 자료 부족 | 목록 자리에 노란 카드 '이 기간엔 활동이 없어요' |
| 만들기 실패 | 빨간 카드 · 「다시 시도」 「범위 다시」 |

## R-PDF · PDF 리포트 PDF 템플릿
리포트 PDF 템플릿. 머리(아이 이름 · 연령 · 관심사 · 기간 · 만든 날) → 날짜별 목차 → 날짜별 대화 원문(과거 → 최신). 서버가 저장된 전사로 만들며 AI 요약 · 평가가 없다.

진입 조건: 

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| DOC-HEAD | 문서 | 머리 |  |
| DOC-TOC | 문서 | 목차 | 날짜 · 활동 종류 · 시각 |
| DOC-BODY | 문서 | 본문 | 화자 이름(무중 · 아이 별명) + 말 |

**API**
- `서버 렌더: HTML 템플릿 → PDF (결과 작업 job_type=REPORT)`

**오류**
| 상황 | 처리 |
|---|---|
| 전사가 하나도 없음 | 28에서 자료 부족으로 막힘 |
