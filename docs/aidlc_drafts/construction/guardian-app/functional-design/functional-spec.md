# functional-spec.md (초안) · Unit `guardian-app` · 보호자 앱 (Next.js /parent + Core API)

> 초안. 화면 설계 v1.5j(2026-10-08)와 개발 명세 1008에서 뽑았다. AI-DLC Edit File 모드에서 붙여넣기용이며 번호 · 절 구성은 AI-DLC가 다시 매긴다. 화면 설계가 기준이다.


## S-01 · 01 보호자 로그인
보호자가 구글 계정으로 들어온다. 로그인 상태는 유지한다.

진입 조건: 

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| BTN-GOOGLE | 단추 | 구글로 시작 | OAuth 시작 → 성공: 동의 없음 02 · 아이 없음 03 · 둘 다 있음 04 |

**API**
- `POST /api/auth/google {code} → {guardianId, hasRequiredConsent, hasChild}`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`

**오류**
| 상황 | 처리 |
|---|---|
| OAuth 취소 · 실패 | 토스트 '로그인하지 못했어요. 다시 시도해 주세요.' · 01 유지 |
| 토큰 만료 | 01 → 재로그인 → 04 (진행 중 활동은 지금 활동 칸에) |

## S-02 · 02 기록 · 동의 안내
처음 한 번 4항목 전부 필수 동의. 바꾸거나 거두는 화면은 없다(거두려면 계정 삭제).

진입 조건: AUTH

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| CHK-VOICE | 체크 | 음성 수집 · 글로 옮겨 저장 (필수) | 토글 |
| CHK-VIEW | 체크 | 보호자 열람 (필수) | 토글 |
| CHK-REPORT | 체크 | 리포트 생성 · 외부 전달 (필수) | 토글 |
| CHK-AUDIO | 체크 | 원음 보관 (필수) | 토글 |
| LNK-DETAIL-* | 링크 | 자세히 보기 | → 02-1 시트 |
| BTN-CONTINUE | 단추 | 동의하고 계속 (4개 모두 체크해야 켜짐) | PUT consents → 03 (처음 등록) |
| BTN-BACK | 단추 | ← (동의 전) | → P5 |

**API**
- `GET /api/consents → [{type,status,policyVersion,effectiveFrom}] (완료 여부 · 개정 판단)`
- `PUT /api/consents {items:[4개 GIVEN], policyVersion}`
- `GET /api/policies/current → {policyVersion, items[{type,title,collect,purpose,retention,ifDenied}]}`

**검증**
| 규칙 | 어기면 |
|---|---|
| 4항목 모두 체크 | 하나라도 비면 「동의하고 계속」 꺼짐 |

**오류**
| 상황 | 처리 |
|---|---|
| 저장 실패 | 토스트 '저장하지 못했어요' · 02 유지 |
| 약관 버전이 바뀜(개정) | 다음 실행에 02 재동의 · 거부하면 계정 삭제 안내 |

## S-02-1 · 02-1 약관 자세히 보기
항목 하나의 수집 · 목적 · 보관 · 거부 시 영향을 보여 주는 하단 시트.

진입 조건: AUTH

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| BTN-OK | 단추 | 확인 | → 02 (보던 자리) |
| AREA-OUTSIDE | 영역 | 바깥 누름 | → 02 |

**API**
- `GET /api/policies/current`

## S-03 · 03 아이 정보 (처음 등록 · 보기 · 수정)
아이 정보 한 화면. 계정당 아이 하나라 선택 · 추가 · 삭제가 없다. 처음엔 빈 입력 카드(등록), 그 뒤엔 보기 카드 + 「정보 수정」(카드 안 편집). 아이를 바꾸려면 계정 삭제(P7) 뒤 새로.

진입 조건: AUTH

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| INP-NAME | 입력 | 이름 또는 별명 | 처음 등록 · 수정 |
| INP-AGE | 입력 | 나이 | 6~12 |
| INP-INTERESTS | 입력 | 관심사 | 5개 이하 |
| BTN-CONFIRM | 단추 | 확인 | 처음: POST child → 04 · 수정: PATCH child → 03(보기) |
| BTN-EDIT | 단추 | 정보 수정 | 카드 안 편집 모드 |
| BTN-CANCEL | 단추 | 취소 (편집) | 원래 내용 |
| BTN-BACK | 단추 | ← (보기 · 수정 중) | → 04 · 처음 등록엔 없음 |

**API**
- `GET /api/child → 아이 1명 또는 404`
- `POST /api/child {displayName, age, interests[]} (이미 있으면 409 CHILD_EXISTS)`
- `PATCH /api/child {displayName, age, interests[]}`

**검증**
| 규칙 | 어기면 |
|---|---|
| 이름 또는 별명 1~10자 | 빈 값 · 11자 이상이면 「확인」 꺼짐 |
| 나이 6~12 정수 | 범위 밖이면 오류 글 |
| 관심사 5개 이하 · 각 10자 | 초과 입력 막음 |

**오류**
| 상황 | 처리 |
|---|---|
| 저장 실패 | 토스트 '저장하지 못했어요. 이전 값이 그대로예요.' · 편집 상태 유지 |

## S-04 · 04 홈 (구역 셋: 아이 · 대화 기기 · 지금 활동)
홈. 늘 같은 세 구역(아이 · 대화 기기 · 지금 활동)이고 구역마다 상태가 바뀐다. 탭 잠금 조건을 계산하는 곳.

진입 조건: AUTH, CHILD

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| LNK-CHILD-EDIT | 글자 단추 | 수정 | → 03 |
| CARD-DEVICE | 카드 | 대화 기기(안정 · 불안정 · 끊김 · 응답 없음 · 없음) | 「기기 상태 보기」 → 05 · 「기기 등록」 → 05a |
| CARD-ACTIVITY | 카드 | 지금 활동(없음 · 진행 중 · 조회 실패) | 「진행 화면 보기」 → 12 · 「다시 확인」 → 재조회 |
| BTN-GEAR | 단추 | ⚙ | → SET0 |
| TAB-FREE | 탭 | 자유대화 | 잠김 아니면 08 · 진행 중 12 · 잠김이면 이유 토스트 → 05 |
| TAB-PRACTICE | 탭 | 연습 | 잠김 아니면 18 · 진행 중 12 · 잠김이면 이유 토스트 → 05 |
| TAB-RECORDS | 탭 | 기록 | → 26 |

**API**
- `GET /api/home → {child, device{status,lastSeenAt}, currentSession{id,type,startedAt,status}, lock{reason: DEVICE_UNSTABLE|DEVICE_DISCONNECTED|DEVICE_UNRESPONSIVE|NO_DEVICE}}`
- `푸시 진입: GET /api/sessions/{id}`

**오류**
| 상황 | 처리 |
|---|---|
| 홈 조회 실패 | 토스트 + 「다시 시도」 · 마지막 값 유지 |
| 알림 진입 조회 실패 | 기기 줄에 '마지막 확인 hh:mm · 미확인' |

## S-SET0 · SET0 설정 시트
설정 시트. 로그아웃 · 계정 삭제.

진입 조건: AUTH

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| BTN-LOGOUT | 단추 | 로그아웃 | POST auth/logout → 01 |
| BTN-DELETE-ACCOUNT | 단추 | 계정 삭제 | → P7 |
| BTN-BACK | 단추 | ← · 바깥 누름 | → 04 |

**API**
- `POST /api/auth/logout`
- `DELETE /api/me → {deletionRequestId}`

**검증**
| 규칙 | 어기면 |
|---|---|
| 진행 중 세션이 있으면 계정 삭제 불가 |  |

**오류**
| 상황 | 처리 |
|---|---|
| 삭제 요청 실패 | 토스트 |

## P-05 · P5 팝업 · 동의 전 나가기
동의 전 나가기 확인. 네 항목 모두 동의하지 않으면 쓸 수 없다.

진입 조건: AUTH

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| BTN-LOGOUT | 단추 | 로그아웃 | POST auth/logout → 01 |
| BTN-STAY | 단추 | 계속 동의하기 | → 02 |

**API**
- `POST /api/auth/logout`

## P-07 · P7 팝업 · 계정 삭제 확인
계정 삭제 확인.

진입 조건: AUTH

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| BTN-DELETE | 단추 | 삭제 | DELETE /api/me → 삭제 요청 접수 → 01 |
| BTN-NO | 단추 | 아니오 | → SET0 |

**API**
- `DELETE /api/me → {deletionRequestId}`

**검증**
| 규칙 | 어기면 |
|---|---|
| 진행 중 세션이 있으면 불가 | 안내 뒤 닫힘 |

**오류**
| 상황 | 처리 |
|---|---|
| 요청 실패 | 토스트 |

## S-05 · 05 우리 집 기기 (두 줄 + 판정 카드)
우리 집 기기. 서로 독립인 두 줄(기기 등록 · 기기 네트워크 현황)과 둘을 합쳐 한 문장으로 말하는 판정 카드, 종료 표현 입구. 조합은 행별 상태 표. 보호자 폰 인터넷은 따로 보이지 않는다(공통 오류 토스트).

진입 조건: AUTH, CHILD

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| ROW-REG | 줄 | 기기 등록(없음 · 완료) |  |
| ROW-NET | 줄 | 기기 네트워크 현황(등록 뒤 표시 · 안정 · 불안정 · 끊김 · 응답 없음) |  |
| CARD-VERDICT | 카드 | 판정(기기 등록 / 시작할 준비 / 지금은 시작할 수 없어요 + 사유) | 「기기 등록」 → 05a |
| LNK-STOP-SETTING | 글자 단추 | 설정 (종료 표현) | → SET1 |
| BTN-BACK | 단추 | ← | → 04 |
| TABS | 하단 탭 | 잠김 규칙은 04와 같음 | 08 · 18 · 26 / 잠김이면 이유 토스트 |

**API**
- `GET /api/device`
- `GET /api/device/status (10초 폴링 또는 SSE)`

**오류**
| 상황 | 처리 |
|---|---|
| 상태 조회 실패 | 줄에 '확인 안 됨 · 마지막 hh:mm' |

## S-05A · 05a 기기 등록
아이 기기에 뜬 코드를 넣어 우리 집에 묶는다.

진입 조건: AUTH

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| INP-CODE | 입력 | 코드 8칸(OTP식) | 자동 대문자 · 다음 칸 자동 이동 · 붙여넣기 지원 |
| BTN-REGISTER | 단추 | 등록 | POST device/register → 05 (등록 뒤) |
| BTN-CANCEL | 단추 | 취소 | → 05 |
| BTN-BACK | 단추 | ← | → 05 (취소와 같음) |

**API**
- `POST /api/device/register {code} → {deviceId} | 404 NO_SUCH_CODE | 409 ALREADY_REGISTERED`

**검증**
| 규칙 | 어기면 |
|---|---|
| 영숫자 8칸 | 다 채워야 「등록」 켜짐 |

**오류**
| 상황 | 처리 |
|---|---|
| 없는 코드 · 다른 집 기기 | 빨간 카드 '없는 코드예요' · 코드 다시 |
| 네트워크 | 토스트 |

## S-SET1 · SET1 종료 표현 설정
아이별 종료 표현. 기본 고정 + 추가 · 수정은 같은 화면 안의 입력 칸으로.

진입 조건: AUTH, CHILD

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| LIST-EXPR | 목록 | 표현 카드 |  |
| BTN-ADD | 단추 | + 표현 추가 | 입력 칸 열림(같은 화면) |
| CARD-EXPR | 카드 | 표현 카드 누름 | 그 칸이 수정 상태로 |
| INP-EXPR | 입력 | 표현 입력 칸 | 2~20자 |
| BTN-CONFIRM-INLINE | 단추 | 확인 (입력 칸) | 목록에 반영 · 아직 저장 전 |
| BTN-REMOVE | 단추 | 삭제 | 목록에서 제거 |
| BTN-SAVE | 단추 | 저장 | PUT stop-expressions → 05 |
| BTN-CANCEL | 단추 | 취소 | → 05 |
| BTN-BACK | 단추 | ← | → 05 (취소와 같음) |

**API**
- `GET/PUT /api/child/stop-expressions {expressions[]}`

**검증**
| 규칙 | 어기면 |
|---|---|
| 2~20자 · 중복 금지 · 최대 5개 | 어기면 오류 글 · 「저장」 꺼짐 |

**오류**
| 상황 | 처리 |
|---|---|
| 저장 실패 | 토스트 |

## S-08 · 08 자유대화
자유대화 배경 네 칸(전부 선택 사항 · 비워도 시작)을 받고 시작한다. 전송 중엔 단추 꺼짐.

진입 조건: AUTH, CONSENT, CHILD, DEVICE, NO_SESSION

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| TA-KNOWN | 서술형 | 알고 있는 내용 | ≤200자 |
| CHIP-SITUATION | 칩 | 친구 · 학교 · 가족 · 학원 · 기타 | 단일 선택 |
| CHIP-SOURCE | 칩 | 직접 봄 · 아이에게 들음 · 기타 | 단일 선택 |
| TA-QUESTION | 서술형 | 궁금한 내용 | ≤200자 |
| BTN-START | 단추 | 자유대화 시작 | POST activities/free → 12 (참여 기다리는 중) |

**API**
- `POST /api/activities/free {childId, context{known, situationType, sourceType, question}, idempotencyKey} → {sessionId} | 409 SESSION_EXISTS | 423 DEVICE_OFFLINE | 403 CONSENT_MISSING`

**검증**
| 규칙 | 어기면 |
|---|---|
| 각 서술 칸 200자 | 넘으면 입력 막음 |
| 평가 · 판단 유도 입력란 없음 | FR-B02 |

**오류**
| 상황 | 처리 |
|---|---|
| 기기 응답 없음(시작 요청 뒤 20초) | 08으로 돌아와 이유 표시 |
| 동의 · 기기 조건 깨짐 | 탭 잠금 규칙으로 04 |

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

## S-26 · 26 기록 (달력)
달력(활동한 날 점) → 그날 활동 목록 → 27. 상담용 리포트 입구.

진입 조건: AUTH, CHILD

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| BTN-PREV-MONTH | 단추 | ‹ | 다른 달 |
| BTN-NEXT-MONTH | 단추 | › | 다른 달 |
| CAL-DAY | 셀 | 날 | 그날 목록 · 활동 없으면 「활동이 없어요」 한 줄 |
| ROW-ACTIVITY | 줄 | 활동 | → 27 |
| BTN-REPORT | 글자 단추 | 상담용 리포트 다운 | → 28 |

**API**
- `GET /api/records?childId&month=2026-10 → {days:[{date,count}]}`
- `GET /api/records?childId&date=2026-10-07 → [{sessionId,type,startedAt,endedAt,endReason,resultStatus}]`

**오류**
| 상황 | 처리 |
|---|---|
| 조회 실패 | 토스트 · 다시 시도 |

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

## S-30 · 30 PDF 저장 전 확인
PDF 저장 전 확인(기간 · 아이 · 대화 원문 건수) → 저장 → 공유 · 열기. 리포트에 AI 글은 없다.

진입 조건: AUTH

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| BTN-SAVE | 단추 | PDF 저장 | GET pdf → 저장 → 저장했어요 |
| BTN-SHARE | 단추 | 공유하기 | OS 공유 시트(Web Share API / 네이티브) |
| BTN-OPEN | 단추 | 파일 열기 | PDF 뷰어 |
| BTN-LATER | 단추 | 나중에 | → 26 (만든 리포트 버림) |
| BTN-RECORDS | 단추 | 기록으로 | → 26 |
| BTN-BACK | 단추 | ← | 저장 전 → 28 (범위 유지 · 리포트 버림) · 저장 뒤 → 26 |

**API**
- `GET /api/reports/{id} → {period, child, days:[{date, type, lineCount}]}`
- `GET /api/reports/{id}/pdf → application/pdf`

**오류**
| 상황 | 처리 |
|---|---|
| PDF 만들기 실패 | 토스트 · 다시 시도 |

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
