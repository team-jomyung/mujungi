# U7 웹 푸시 (u7-web-push) 구현 지시

브랜치: main에서 `unit/u7-web-push`를 새로 만든다.

같은 시기에 U5b(연습 Realtime · 연결 마무리, realtime V4)와 U6(결과와 리포트, core V5)이 각자 브랜치에서 함께 진행된다. 셋은 서로 기다리지 않는다. 병합 순서만 지킨다(core V5 = U6 → V6 = U7).

U1 ~ U4(a · b) · U5a는 main에 합쳐졌다(`docs/units/README.md`). 자유대화 한 판(기기 등록 → 홈 → 시작 → 참여 확인 → 음성 → 끝내기 → 기록 저장)은 CI 끝-끝 검증을 통과한다.

## 배경
- 보호자는 아이 활동 중에 앱을 닫아 둘 수 있다. 음성 연결 문제 · 기기 상태 미확인 · 연결 문제로 끝남 · 활동 완료 네 가지를 폰 알림으로 받고, 누르면 지금 상태 화면으로 간다(FR7.10 · US1.7).
- 10/13 결정으로 U7은 Should에서 **Must**로 올랐고, 활동 완료 푸시가 더해졌다(`docs/change-history.md` "결과 · 리포트 · 푸시에 동훈ver 설계 반영").
- 시연(11/5)은 Android Chrome에서 장애 1종과 완료 1종을 보인다. iOS는 보장하지 않는다(NFR10).

## 범위
`unit-of-work.md` U7 절, `bolt-plan.md` B6, `components.md` Notification이 정본이다. 만들 것:

- **Notification(Core, `kr.mujoong.core.notification`)**
  - 구독 저장 · 해제: `PUT /api/push-subscription` `{endpoint, keys{p256dh, auth}, platform}` → 204, `DELETE /api/push-subscription` → 204. 보호자당 구독 여러 개(폰 · 브라우저마다)를 허용하되 endpoint로 유일.
  - 사건 감지 4종과 발송 요청 기록. 사건마다 발송 요청 행 1건(세션 id + 사건 id 유일 키). 서버 재시작 · 재시도 · 같은 사건 재도착에도 1건(AC1.7.5 · AC1.7.8).
  - 실제 발송: VAPID 서명 Web Push(표준 `web-push` 프로토콜, RFC 8291 · 8292). 라이브러리를 쓰면 Java 21 · Spring Boot 4와 맞고 유지되는 것으로 고르고 `docs/units/u7/design.md`에 이유를 적는다. 410 · 404(구독 만료)는 그 구독을 지운다. 그 밖 실패는 재시도 뒤 FAILED로 남긴다.
  - 발송 요청 · 실패 · 전달 상태를 구분해 기록한다. 실제 전달(폰에 떴는지)은 보장하지 않는다(FR7.10).
  - 알림 본문에 아이 이름 · 발화 원문 · 대화 내용 · 점수 · 기록 정리 완료 여부를 넣지 않는다. 본문은 `{type, activityType, sessionId, sentAt}`뿐이다(contract-summary 3절).
- **사건 4종(FR7.10 표 · AC1.7.1 ~ 1.7.3 · 1.7.7)**

  | type | 조건 | 재료 |
  |---|---|---|
  | VOICE_PROBLEM | 진행 중 기술적 일시 정지가 기록됨(AC6.6.1) | SessionStateChanged의 `technicalPause`(U4b 편차 (U4b-c)) |
  | DEVICE_STATUS_UNKNOWN | Core가 기기 상태 보고를 `mujoong.timing.core.device-status-stale`(30s, B6)보다 오래 못 받음 | `core_device.reported_at` · 진행 중 세션 사본, 기존 StaleEvaluator와 같은 판정 |
  | ENDED_BY_CONNECTION | 기술 문제로 종료 확인됨(ABORTED · DISCONNECT) | SessionStateChanged 종료 상태 + endReason DISCONNECT |
  | ACTIVITY_COMPLETED | ACTIVE를 거친 세션이 CHILD_STOP · GUARDIAN_STOP · SILENCE로 ENDED(confirmed) | SessionStateChanged + 사본의 `started_at`(ACTIVE 도달 표식) · `activity_type` |

  - 완료 푸시는 CLOSING · ENDED_UNCONFIRMED · DECLINED · 준비 취소 · FAILED · ABORTED에는 보내지 않는다. 기술 문제 종료는 ENDED_BY_CONNECTION 하나만. 세션 종료 뒤 도착한 오래된 장애 사건은 보내지 않는다. 앱 닫힘 · 앱 연결만의 장애로는 만들지 않는다.
  - 감지는 앱 실행 여부에 의존하지 않는다 — Core Inbox 처리기(SessionStateChanged)와 주기 작업(DEVICE_STATUS_UNKNOWN)에서 한다. `ActivityRecorded`는 메모리 사건(AFTER_COMMIT)이라 쓰지 않는다(U4a review-1 4절).
- **문구 4종**(mockups §9-35와 FR7.10 표 그대로)
  - VOICE_PROBLEM "대화 중 음성 연결 문제가 발생했어요." / DEVICE_STATUS_UNKNOWN "기기 상태를 확인할 수 없어요." / ENDED_BY_CONNECTION "연결 문제로 활동이 종료됐어요." / ACTIVITY_COMPLETED 제목 "자유대화가 끝났어요"(연습이면 "연습이 끝났어요") · 본문 "앱에서 최신 상태를 확인해 주세요"
  - 문구는 서비스 워커가 type으로 고른다(서버는 type만 보낸다). 문구 정본은 `frontend/src/shared/copy/ko.ts`에 둔다.
- **화면(/parent)**
  - 서비스 워커(`frontend/public/sw.js` 또는 Next.js 관례 위치): push 수신 → 알림 표시, 알림 클릭 → 앱 열기 · 포커스 → 알림 진입 경로.
  - 알림 진입 재조회(AC1.7.4 · AC1.4.13): 푸시의 sessionId로 `GET /api/sessions/{id}`를 물어 진행 중이면 12, 끝났으면 17, 조회 실패면 04 지금 활동 칸 미확인. `/api/sessions/current`만으로는 끝난 세션이 null이라 완료 푸시 → 17이 안 된다(U4a review-1 4절) — sessionId 경로를 쓴다. 푸시가 없어도 앱 재진입(AC1.4.13)은 U4a 그대로다.
  - 구독 등록 UI: SET0(설정)에 알림 켜기/끄기 한 줄. 권한 요청 → 구독 → `PUT /api/push-subscription`. 브라우저가 지원하지 않으면 줄을 숨기거나 비활성.
- **삭제 참가자**: `DeletionParticipant`에 `ORDER_PUSH = 60`을 더하고 구독 · 발송 기록을 보호자 단위로 지운다(활동 기록 90보다 먼저).

범위 밖: 푸시 문구를 보호자가 고르는 설정, 알림 이력 화면, iOS 지원, 이메일 · SMS 대체 채널.

## 정본
기록 폴더 `R = aidlc/spaces/default/intents/261008-mujoong-service/`.

| 무엇 | 어디 |
|---|---|
| 범위 · 완료 조건 | `R/inception/units-generation/unit-of-work.md` U7 절, `R/inception/delivery-planning/bolt-plan.md` B6 |
| 요구사항 | `R/inception/requirements-analysis/requirements.md` FR7.10(표 · 금지 사항 · 시연 조건), NFR10, A10 |
| 스토리 · 수용 기준 | `R/inception/user-stories/stories.md` US1.7(AC1.7.1 ~ 1.7.8), US1.4(AC1.4.13 재진입 재조회) |
| 화면 · 문구 | `R/inception/refined-mockups/mockups.md` §9-35, 04 알림 진입(AC1.7.4), SET0 |
| 컴포넌트 · 엔터티 | `R/inception/domain-design/components.md` Notification(PushSubscription · PushDispatch), 의존 GuardianIdentity · ActivityCoordinator · DeviceRegistry |
| 계약 | `R/inception/contract-design/contract-summary.md` C6(3절 `/push-subscription`, 웹 푸시 본문, 11절 C6 VAPID 키), `contracts/parent-api.yaml`(경로를 더한다), `contracts/events.asyncapi.yaml` SessionStateChanged(U4b 편차 (U4b-c)), `contracts/timing.md`(`core.device-status-stale`) |
| 기존 코드 | Core `messaging/handler/SessionStateChangedHandler`(사본 반영, 여기에 사건 감지를 붙이거나 같은 Inbox 사건을 구독하는 두 번째 처리기를 둔다 — 한 사건 한 트랜잭션 규칙 BR5.7 유지), `activity/ActivityCopyRepository`(started_at · activity_type · status · end_reason · confirmed), `device/DeviceJdbcRepository`(reported_at · last_seen_at), `activity/SessionViewService`의 StaleEvaluator, `account/DeletionCommander` 참가자 틀, 프런트 `parent/activity/homeActivity.ts` · `parent/api/activity.ts` |

## 정해 둔 값
- 마이그레이션: core 스키마 `V6__u7_web_push.sql`(더하기만). 표는 `core_push_subscription`(id · guardian_id · endpoint 유일 · p256dh · auth · platform · created_at · last_used_at · revoked_at)과 `core_push_dispatch`(id · guardian_id · session_id · event_type · event_key 유일(세션 id + 사건 id) · status REQUESTED|SENT|FAILED|SKIPPED · attempt_count · last_error_code · requested_at · sent_at). 열 이름은 `R/.../components.md` 엔터티 소유 표와 `docs/erd/ERD_v0.2.md` 예정 표(N5 · N6)를 따르고, 다르면 ERD를 갱신한다.
- **core V5(U6)가 먼저 들어가야 V6이 들어간다.** U6이 늦으면 V6 번호를 유지한 채 기다린다(Flyway `outOfOrder` 꺼짐). U6과 충돌할 곳은 `FlywayConfIT` 표 수 · 이력, `SchemaVersionCheck`, `DeletionParticipant` 상수(U6 = 70, U7 = 60)뿐이다.
- 기존 표에 제약을 더하는 마이그레이션은 CI 검사에 걸린다. 그런 문장이 생기면 이전 앱이 깨지지 않는 근거를 적어 `infra/migration-approvals.txt`에 한 줄을 더한다. 새 표만 만들면 걸리지 않는다.
- VAPID 키: 환경 변수 `MUJOONG_PUSH_VAPID_PUBLIC_KEY` · `MUJOONG_PUSH_VAPID_PRIVATE_KEY` · `MUJOONG_PUSH_VAPID_SUBJECT`(mailto:)로 받는다. 값은 코드 · 설정 · 시험 · 문서 · 커밋 어디에도 쓰지 않는다. 시험은 실행 때 키 쌍을 만든다(`test-support`의 TestSecrets 관례). 기동 검증(`startup` 패키지)에 '푸시 켜짐이면 세 변수 필수'를 더하고, 없으면 푸시 기능만 꺼진 채 기동한다(시연 전 환경에 키가 없을 수 있다). 공개 키는 `GET /api/push-subscription/vapid-public-key`(세션 필요)로 프런트에 준다 — 계약에 없는 경로라 `contracts/deviations.md`에 (U7-a)로 더한다.
- 시간 키(모두 `application.yml` + `CoreProperties`의 `Timing`에 `push` 묶음을 더하고, `contracts/timing.md`에 행 추가): `mujoong.timing.push.dispatch-poll`(발송 폴러 주기, 1s), `mujoong.timing.push.retry-backoff`([5s, 30s, 2m], `device.reconnect-backoff`처럼 목록), `mujoong.timing.push.send-timeout`(5s), `mujoong.timing.push.device-status-check`(DEVICE_STATUS_UNKNOWN 주기 점검, 10s). 설정 키 대조 시험(`ConfigKeyContractTest`)이 지키는 규칙 그대로.
- 시험 태그 `u7`, 커버리지 범위 `backend/coverage-scope/u7.txt` · `frontend/coverage-scope/u7.json`(U1 형식), 범위 줄 80% 이상.
- 건드리지 않는 것: `backend/realtime-activity` · realtime 스키마 · 기기 웹(`/device`) · `aidlc/` · `README.md` · `contracts/README.md`. U1 ~ U5a 시험의 단언과 커버리지 하한.
- 문서: `docs/units/u7/design.md`, `docs/units/u7/report.md`. 가정은 "준하 제안(가정) U7-J<번호>".

## 다른 유닛과 맞물리는 곳
1. **U4a(Core 세션 사본)**: 완료 푸시 자격은 사본의 `started_at`(ACTIVE 도달) · `status` · `end_reason` · `confirmed` · `activity_type`으로 판정한다. 사본에 없는 것이 필요하면 더하기만(새 열은 널 허용 · 기본값).
2. **U4b(Realtime)**: SessionStateChanged에 `technicalPause`와 CLOSING의 endReason이 실린다(편차 (U4b-c)). 기술 일시정지 기록이 VOICE_PROBLEM의 재료다. Realtime 코드는 고치지 않는다 — 부족하면 `report.md` 남은 일에 적는다.
3. **U5b(연습)**: PRACTICE 세션도 같은 사본 · 같은 사건으로 온다. `activityType`만 푸시 본문에 실으면 된다. U5b 코드에 기대지 않는다.
4. **U6**: 완료 푸시를 누르면 17로 간다. 17의 정리 상태 칸은 U6이 채운다. U7은 U6 데이터를 쓰지 않는다. 병합은 U6 다음.
5. **U2(계정 삭제)**: 참가자 등록(ORDER_PUSH = 60). 삭제 접수 뒤 도착한 사건은 보내지 않는다(보호자 행 FOR SHARE 확인 — `SessionRecordCapturedHandler`의 방식과 같게).

## AI-DLC로 진행하는 법
작업 기록은 사람마다 **자기 PC에서 새로** 만든다. 승인 증빙이 PC별이어서 남의 기록은 이어 갈 수 없다.
1. 저장소를 받고 `unit/u7-web-push` 브랜치로 옮긴 뒤 아래를 입력한다.

   ```
   /aidlc --new-intent --scope refactor "U7 웹 푸시(u7-web-push) 구현. 지시 docs/units/u7/brief.md를 정본으로 한다. 인셉션 산출물은 261008-mujoong-service 기록의 것을 그대로 쓴다."
   ```

2. 안내대로 작업 기록을 만든다. 이름은 `u7-web-push`로 한다.
3. 배포 2단계를 뺀다. `/aidlc compose "deployment-pipeline과 deployment-execution을 빼줘"`를 입력하고, 확인 질문에 승인한다.
4. `/aidlc --depth Standard --test-strategy Standard`로 깊이와 시험 전략을 올린다.
5. 단계는 이렇게 남는다: 기존 코드 파악 → Requirements Analysis(U7 몫만 추림) → Functional Design → Code Generation → Build and Test.
   - Requirements Analysis는 새 업무 요구사항을 만들지 않는다. 원래 기록의 FR7.10 · US1.7을 구현 단위로 다시 묶고, 원래 ID는 '원 FR7.10'처럼 구분한다.
   - Functional Design 결과는 `docs/units/u7/design.md`에도 옮겨 둔다.
   - Code Generation 계획을 ① V6 + 구독 API + 삭제 참가자 → ② 사건 감지 4종 + 발송 기록 → ③ VAPID 발송기 + 재시도 → ④ 서비스 워커 + 알림 진입 + SET0 순으로 나누고, 단계마다 `feat(u7): …` 커밋.
6. 자기 기록 폴더(`aidlc/spaces/default/intents/<날짜>-u7-web-push/`)는 브랜치에 함께 커밋한다.

Codex처럼 AI-DLC 엔진을 쓰지 않는 작업자는 같은 순서로 직접 한다: 기존 코드 파악 → `docs/units/u7/design.md`(엔터티 · API · 규칙(AC 번호 연결) · 화면 · 시험 계획 · 가정) → 층별 구현과 시험 → `report.md`.

## 먼저 할 것
1. `docs/units/README.md`의 U7 행과 「U6 · U7 정본」을 읽는다.
2. `SessionStateChangedHandler`와 `ActivityCopyRepository.applyStateChange`를 읽어, 사본 갱신과 같은 트랜잭션에서 사건 감지를 할지(한 처리기) 따로 처리기를 둘지(같은 Inbox 사건을 두 번 소비하지 않게) 설계하고 U7-J로 남긴다. 권장: 사본 갱신 뒤 같은 트랜잭션에서 `core_push_dispatch`에 REQUESTED 행을 넣고(유일 키로 멱등), 발송은 Outbox처럼 폴러가 한다.
3. DEVICE_STATUS_UNKNOWN은 `StaleEvaluator`의 판정과 같은 값을 써서 12 화면의 '기기 상태 확인 불가'와 어긋나지 않게 한다. 진행 중 세션이 있을 때만 보내고, 세션당 한 번(사건 키 = 세션 id + "STALE").
4. 서비스 워커는 Next.js standalone 빌드(`frontend/next.config` · `scripts/verify/a-checks.sh`의 정적 자원 복사)에서도 `/sw.js`로 서빙되는지 확인한다. 안 되면 README 수정 없이 `report.md`에 적고 대안을 둔다.
5. 끝-끝: `scripts/verify.sh`는 리눅스 전용이다. Windows에서는 PR CI로 확인한다. skeleton(C1 ~ C12)은 PR에서 돌지 않고 main push · 수동 실행에서만 돈다(`gh workflow run ci.yml --ref unit/u7-web-push`). U7은 C 단계에 푸시 단언을 더하지 않는다(가짜 푸시 서버가 없다) — 대신 Core IT에서 발송 요청 행과 HTTP 호출(ContractStubServer로 푸시 서비스 대역)을 단언한다.

## 끝나면
`docs/units/u7/report.md`에 다음을 쓰고 main으로 PR을 연다. CI가 초록인 것을 확인한 뒤, U6이 먼저 합쳐졌는지 보고 합친다.
- 만든 것, 시험 결과(건수 · 커버리지)
- 계약 편차((U7-a) 등), 가정(U7-J), 설계와 달라진 곳
- 시연 확인: Android Chrome 실기에서 장애 1종 + 완료 1종이 오고 누르면 04/12/17로 가는지(B6 가설 "PWA 설치 없이 화면 꺼진 채 알림"). 못 했으면 그대로 적는다.
- 남은 일
