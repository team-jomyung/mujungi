# Units Generation — 질문 파일

> 무중(Mujoong) · 깊이 Standard · 2026-10-09 (클라우드 세션)
> 입력: `../domain-design/components.md`(컴포넌트 26개 · J1~J14, 승인), `../domain-design/decisions.md`(ADR-011~019), `../requirements-analysis/requirements.md`, `../user-stories/stories.md`, 스코프 mujoong-mvp(skeleton: on), 70_WBS_v0.5(3.x 중간 유닛), 초안 `docs/aidlc_drafts/inception/units-generation/unit-of-work.md`.
>
> **누가 답했나.** 2.6과 같이 팀장(준하)의 대리가 `docs/cloud_handoff.md` 2절 중재 규칙으로 채웠다(사람 지시 원문은 `../domain-design/domain-design-questions.md` 머리말). 대리 판단은 **준하 제안(가정) Jn**(J15부터)으로 표시해 `unit-of-work.md` 가정 표에 옮겼다. **Mode:** 대리 판단.
>
> 이 단계는 유닛과 의존 그래프(DAG)만 정한다. 무엇을 먼저 만들지(경제적 순서) · 볼트 · 담당 배정은 Delivery Planning(2.9)이 정한다.

## 1. 이미 정해진 것 (확인용)

| 번호 | 정해진 것 | 근거 |
|---|---|---|
| K1 | 컴포넌트 26개와 소유 엔터티 · 의존은 2.6에서 승인된 그대로 쓴다. 유닛은 컴포넌트를 묶을 뿐 경계를 바꾸지 않는다 | `components.md` |
| K2 | 실행 단위는 셋(Next.js 한 앱 · Core API Spring · Realtime Activity Spring) + PostgreSQL · Neo4j, EC2 1대 Docker Compose. 유닛마다 따로 배포하는 일은 없다 | 규칙 7 · ADR-019 |
| K3 | 스코프가 skeleton: on이다: 첫 유닛은 끝까지 실제로 도는 가장 얇은 조각("기기 코드 등록 → 홈 → 음성 1턴 중계 → 종료")이고, 사람이 승인한 실제 검증 명령으로 통과한 뒤에야 다음 유닛으로 간다 | 스코프 mujoong-mvp · org.md Walking Skeleton |
| K4 | 팀 5명 담당: A 계정 / B 음성 / C 연습 / D AI / E 결과. WBS v0.5의 3.x 중간 유닛(3.1 공통 활동 · 3.2 보호자 · 3.3 연결 · 음성 · 3.4 AI · 목표 · 연습 · 3.5 실제 연결)이 출발점 | project-description · 점검표 7절 |
| K5 | Must = 시연 네 경로(처음 한 번 · 자유대화 1회 · 연습 1회 · 기록 · 리포트), Should = 장애 경로 · 푸시 · 설정 · 삭제 | requirements Q6 |

## 2. 정할 것

### Q1. 유닛 경계를 무엇으로 나누나

A. 기능 조각(세로 조각): 워킹 스켈레톤 + WBS 3.x 흐름(보호자 계정 · 음성 대화 · 활동 세션 · 연습 · 결과와 리포트 · 웹 푸시). 한 유닛이 화면 · Core · Realtime을 함께 갖는다
B. 실행 단위별(Next.js · Core · Realtime 세 유닛)
C. 컴포넌트마다 한 유닛(26개)
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 담당 A~E와 WBS 3.x가 이미 흐름 단위로 나뉘어 있고, 시연 네 경로가 흐름 단위다. B는 세 유닛이 서로를 기다려 끝까지 도는 조각이 늦게 나오고, C는 Construction 설계 · 코드 단계를 26번 돌아야 한다. **J15**.

### Q2. 유닛 크기(몇 개)

A. 7개: U1 워킹 스켈레톤 · U2 보호자 계정 · U3 음성 대화 · U4 활동 세션 · U5 연습 · U6 결과와 리포트 · U7 웹 푸시
B. 초안대로 4개(보호자 앱 · 활동 세션 · 음성 기기 · AI 목표 결과) + 스켈레톤
C. 9개 이상으로 더 잘게
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 초안 4개 중 '보호자 앱'은 계정(A)과 기록 · 리포트(E)를 한데 묶어 DAG에 순환이 생긴다(보호자 화면 → 활동 → 결과 → 보호자). 2.6 컴포넌트 의존을 따라 나누면 순환 없는 최소 묶음이 7개이고, 웹 푸시(Should)를 따로 떼면 일정에서 통째로 미룰 수 있다. **J15**.

### Q3. 워킹 스켈레톤(U1)에 무엇을 넣나

A. 실제 경로만 얇게: 개발용 시험 로그인(구글 로그인은 U2) · 시드된 동의 · 아이 → 아이 기기 웹 D0 코드 발급 → 보호자 05a 코드 등록 → 기기 토큰 WSS → 04 대화 기기 구역 → 08 자유대화 시작 → 음성 1턴(아이 한 마디 → GPT-Live → 출력 게이트 → 기기 재생) → P1 끝내기 → ENDED. 실행 골격(Compose · Nginx HTTPS · Flyway 두 스키마 · Outbox/Inbox 양쪽)을 함께 세운다
B. 화면 없이 서버 골격만
C. 구글 로그인 · 동의 · 아이 등록까지 처음 한 번 전체를 넣는다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 스코프 설명("기기 코드 등록 → 홈 → 음성 1턴 중계 → 종료")을 그대로 옮겼다. 구글 OAuth는 자동 검증 명령에서 돌릴 수 없어 시험 로그인으로 바꾸되 dev 프로필에서만 켠다. B는 "실제로 도는 조각"이 아니고, C는 스켈레톤이 두꺼워진다. 기기 코드 등록(US2.2 · US6.1)은 U1에서 완성한다. **J16**.

### Q4. 한 컴포넌트를 여러 유닛이 만지면 어떻게 하나

예: SessionLifecycle은 U1이 최소 경로를, U4가 상태 11개 · 시간 규칙을, U5가 연습 경로를 만든다.

A. 컴포넌트마다 "완성 유닛"을 하나 정하고(소유 엔터티는 그 유닛이 만든다), 다른 유닛은 계약을 깨지 않는 확장만 한다. 확장 지점은 Contract Design에서 계약으로 고정한다
B. 컴포넌트마다 한 유닛만 만지게 유닛을 다시 자른다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 스켈레톤이 여러 컴포넌트를 얇게 지나가는 이상 B는 불가능하다. 연습 시작 경로(ActivityCoordinator · SessionLifecycle의 연습 부분)를 U5가 확장하게 하면 U4(활동 세션)가 U5(연습)를 기다리지 않는다. **J17**.

### Q5. 화면 하나가 앞뒤 유닛의 데이터를 함께 쓰면

04 지금 활동 구역(세션 상태 = U4), 05 네트워크 줄(연결 판정 = U3), 17 정리 상태(결과 작업 = U6), 27(대화 기록 = U4 · 생활 팁과 후보 = U6 · 담기 = U5)이 그렇다.

A. 화면 틀과 그 화면의 주 스토리는 앞 유닛이 만들고, 뒤 유닛이 자기 데이터 칸을 채운다(교차 스토리로 지도에 표시). DAG 방향은 데이터 주인 쪽으로만 둔다
B. 그런 화면은 데이터가 다 모이는 마지막 유닛이 통째로 만든다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. B를 따르면 04 · 17이 마지막 유닛까지 미뤄져 시연 Must 경로가 늦게 열린다. **J18**.

### Q6. Should 기능(계정 삭제 · 웹 푸시)은 어디에 두나

requirements FR1.6은 계정 삭제가 "동의 철회의 유일한 경로이므로 Should이지만 Delivery Planning에서 앞쪽에 둔다"고 했다. 그런데 삭제는 거의 모든 데이터 주인을 부른다.

A. 계정 삭제의 접수 · 즉시 차단 · 로그아웃 · 삭제 지휘 틀은 U2에서 먼저 만들고, 뒤 유닛은 각자 "자기 데이터 삭제 참가자"를 완료 조건(DoD)으로 더한다. 웹 푸시(Notification, US1.7)는 U7 하나로 떼어 통째로 미룰 수 있게 한다
B. 계정 삭제 · 웹 푸시를 모두 마지막 유닛으로
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 삭제 지휘를 참가자 등록 방식으로 만들면 U2가 뒤 유닛을 기다리지 않아 FR1.6의 "앞쪽에"와 DAG가 함께 선다. 웹 푸시는 활동 세션(U4) 상태를 구독하므로 U4 뒤에만 올 수 있고 Should라 떼어 둔다. **J19**.

### Q7. 배포 모델

A. 공유 배포: 모든 유닛이 같은 세 실행 단위(Next.js · Core · Realtime)에 들어가고 한 번에 Compose로 올린다. 유닛은 개발 · 설계 · 시험 단위다
B. 유닛마다 독립 배포
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. K2 · ADR-019(EC2 1대 · 두 Spring 앱). **J15**에 포함.

### Q8. 유닛 사이 병행 개발

A. DAG의 방향은 지키되 같은 층의 유닛(예: U2 · U3)은 동시에 만든다. 유닛 사이 접점은 Contract Design(2.8)의 계약으로 먼저 고정하고 서로 대역(stub)으로 시험한다
B. 위상 순서대로만 하나씩
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 5명이 7일에 만들려면 병행이 필요하다(K4). 실제 순서와 볼트 묶음은 2.9에서 정한다. 이 파일은 순서를 권하지 않는다.

## 3. 계획 승인 (Step 4)

- 경계: 기능 조각(J15). 유닛 7개, 모두 kind `service`(화면 · Core · Realtime을 함께 갖는 세로 조각).
- DAG: U1 ← {U2, U3} ← U4 ← U5 ← U6, U7은 U1 · U2 · U4 뒤. 순환 없음.
- 스켈레톤 U1 = 기기 코드 등록 → 홈 → 음성 1턴 → 끝내기(J16).

[Answer]: Approve Plan — **Mode:** 대리 판단(규칙 1 · 2 · 7과 2.6 승인 결과에 어긋남 없음).

## 4. 답 분석

- **모호함:** 없음.
- **모순 점검:** FR1.6 "삭제를 앞쪽에"와 DAG(삭제가 모든 데이터 주인 뒤) 사이의 긴장은 Q6-A(참가자 등록)로 풀었다. Q4 · Q5의 확장 · 교차 규칙은 2.6의 컴포넌트 경계를 바꾸지 않는다.
- **후속 질문:** 없음. 스켈레톤의 실제 검증 명령(GPT-Live 실제 호출 포함 여부 · 시험 로그인 방식)은 Delivery Planning(2.9) · Code Generation에서 사람이 승인한다.
