# U4b 활동 세션 · Realtime 쪽 (u4b-activity-realtime) 구현 지시

브랜치: U3가 합쳐진 main에서 `unit/u4b-activity-realtime`를 새로 만든다. U4a(활동 Core · /parent 화면, core V3)는 `unit/u4a-activity-core` 브랜치에 구현이 끝나 리뷰를 기다리고, U5a(연습 Core, core V4)는 리뷰 반영 전이다(`docs/units/README.md`).

## 배경
U4b는 클라우드 세션이 설계까지 했고 구현은 하지 않았다. 설계를 정본으로 구현하고, 구현하다 설계와 달라지는 곳은 설계 문서를 고친 뒤 가정으로 남긴다.

## 정본
- `docs/units/u4b/design.md`(클라우드 세션, 2026-10-12)
  - 1절 범위 · 경계, 3절 전이표와 끝내기 응답표, 4절 시간 키, 5절 규칙 U4b-R1~R17
  - 6절 realtime V3(결과 스냅숏 표 하나), 7절 계약 편차 (U4b-a)~(U4b-c), 8절 U4a와 맞출 것
  - 9절 층별 시험 계획 L1~L7, 10절 가정 U4b-J1~J26
- U3 설계 `docs/units/u3/design.md` 5절(U3-R1~R17) · 8절(U3-J1~J25)과 U3 보고서 `docs/units/u3/report.md`
  - U4b 설계는 U3 리뷰 수정(1~3차, report 11~14절) 전에 쓰였다. 11~14절을 먼저 읽고 바뀐 동작에 맞춘다.
  - 특히 볼 곳은 둘이다.
    - StopIntentJudge: 인용 표지, 부정이 붙은 계속 표현은 종료, 종료 표현이 계속 표현보다 우선, 해제 전용 목록
    - SessionExecutor: STOP 해제, 해제 뒤 THINKING 없음, 재접속 때 가장 최근 재생 하나만 다시 보내기, `unbindSessionIf`
  - U3는 'STOP이면 출력만 막고 ACTIVE 유지'와 '적극적 계속이면 해제'를 했다. U4b에서 STOP → CLOSING(CHILD_STOP)이 되면 해제 경로의 뜻이 바뀐다. 설계 3.1 전이표와 맞춰 정리하고 U4b-J로 남긴다.

## 정해 둔 값
- 마이그레이션: realtime 스키마에 `V3__u4b_session_lifecycle.sql`(더하기만). core 스키마는 건드리지 않는다.
  - 기존 표에 제약을 더해야 하면 CI 마이그레이션 검사에 걸린다. 이전 앱이 깨지지 않는 근거를 적어 `infra/migration-approvals.txt`에 한 줄을 더하고 리뷰를 받는다.
- 시험 태그 `u4b`, 커버리지 범위 `backend/coverage-scope/u4b.txt`(U1 형식), 범위 줄 80% 이상. 프런트를 바꾸지 않으면 frontend 범위 파일은 만들지 않는다.
- 건드리지 않는 것:
  - core 스키마 · `backend/core-api` · `/parent` · `frontend/src/parent/` · 기기 웹(`/device`) · `aidlc/` · `README.md` · `contracts/README.md`
  - U1 · U3 시험의 단언과 커버리지 하한
- 문서: `docs/units/u4b/design.md`(고칠 곳만), `docs/units/u4b/report.md`(새로).

## 먼저 할 것
1. **JoinTracker 되살리기.** U3 1차 리뷰에서 '부르는 곳 없는 코드'로 지웠지만, U4b가 그대로 쓴다.
   - `git checkout 5354a32 -- backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/JoinTracker.java backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/JoinTrackerTest.java`
   - 공개 메서드(promptPlayed · reply · tick · classify · phase · prompts)를 살린 채 U4b-J4대로 두 창 시간(처음 20초, 재안내 뒤 20초)을 받게 넓힌다.
   - 범위 파일 u4b.txt에 넣는다.
2. **흔들리는 U1 시험 하나.** `RealtimeInternalApiIT.coreAbandonedClosesNonTerminalSessionsAndLeavesATombstoneForUnknownIds`는 부하가 크면 200행에서 202 대신 409를 받는다.
   - 원인: 시험 프로필 `closing-confirm` 3초 · 고아 점검 1초라, 실행기 없는 CLOSING 행이 단언 전에 먼저 닫힌다.
   - CLOSING을 손보는 김에 단언 직전에 행을 만들거나, 이 클래스만 시한을 늘린다(U3 report 9절).
3. 설계 9절 순서대로 층을 쌓는다. 시간이 모자라면 이 순서로 한다.
   - 먼저: L2 전이표 · 끝내기, L3 참여 확인, L5 결과 스냅숏 · SessionRecordCaptured, L6 삭제 처리기
   - 뒤로: L4 공백 · 끊김, L7 문서
   - 층마다 시험을 쓰고 돌린 뒤 `feat(u4b): …`로 커밋한다.

## U4a와 맞물리는 곳
- 설계 8절의 여섯 가지는 U4a에 반영돼 있다(U4a report · design 9절 대조표).
  - 시작 패키지 승인 문구 6종 v2 시드
  - WAITING_CHILD의 GUARDIAN_STOP 202
  - CLOSING의 endReason
  - SessionRecordCaptured 1회
  - 진행 조회 필드
  - 삭제 시 강제 정지
- 구현은 `contracts/`만 믿고 하고, U4a 코드에 기대지 않는다.
- 시작 패키지에 승인 문구가 없을 때 소리 없이 시계만 도는 경로(U4b-R15)는 시험으로 지킨다.

## 시험 명령(저장소 루트 기준)
- 자기 유닛: `cd backend && ./gradlew --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u4b -PcoverageScope=u4b`
- 회귀
  - `-PincludeTags=u1`(커버리지 검증 없이 :contract-model:test :core-api:test :realtime-activity:test :test-support:test)
  - `-PincludeTags=u3`(:realtime-activity:test만)
- Windows에서는 리눅스 전용 검사(infra-validate · 이미지 관문 · 스켈레톤)를 돌릴 수 없다. PR을 열어 GitHub CI로 확인한다.

## 끝나면
`docs/units/u4b/report.md`에 다음을 쓰고 main으로 PR을 연다. CI가 초록인 것을 확인한 뒤 합친다.
- 만든 것, 시험 결과(건수 · 커버리지)
- 계약 편차, 가정(U4b-J), 설계와 달라진 곳
- 남은 일
