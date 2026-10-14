## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-13T13:35:07Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/SessionExecutorFaultTest.java > anActiveCommitWhoseAnswerWasLostStillStartsTheRelayAndTheDisconnectClock (L248 ~ L268) | U1 시험이 "ACTIVE 전이는 커밋됐지만 응답을 잃은 경우"를 검증했는데, 활성화 경로가 WAITING_CHILD를 거치게 되면서 단언이 WAITING_CHILD로 바뀌었다. 이름은 여전히 "ActiveCommit"인데 이제 DEVICE_ACK → WAITING_CHILD 승자만 본다. 새로 생긴 WAITING_CHILD → ACTIVE 전이(`acceptJoin`, `SessionExecutor` L968)가 응답을 잃었을 때 `syncFromDb` → `adopt` → `afterActiveWon`(L2986)으로 started_at 이후 몫(`enterActive` · `response.create` 1회 · 확장점 `onActive`)이 한 번 실행되는지 보는 시험이 없다. 증거: `loseCommitNext` 사용처는 `InMemoryTransitions`와 `SessionExecutorFaultTest` 둘뿐이고 `*U4b*` 시험에는 없다. 기존 시험의 방어 범위가 줄었다(계획 7절 "단언을 줄이지 않고"와 어긋남). | WAITING_CHILD → ACTIVE 커밋 응답 분실 시험을 추가한다(`response.create` 정확히 1회, 릴레이 수락 켜짐, DISCONNECT 시계 걸림, 중복 실행 없음). 낡은 메서드 이름도 바로잡는다. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261013-u4b-activity-realtime/construction/code-generation/code-generation-plan.md > Step 17 · 1절 R-12 | 계획은 R-12를 "Inbox 처리 기록(처리 메모 칸)에 누적"으로 막겠다고 적고 Step 17을 `[x]`로 닫았지만 구현은 다르게 처리했다. 증거: `V1` 마이그레이션의 `realtime_inbox_event` 열 목록(L200 ~ L217)에 처리 메모 칸이 없다. 구현 근거(`DeletionRequestedHandler` Javadoc · design.md U4b-J34: 모든 DB 삭제가 Inbox 한 트랜잭션이라 재시도가 처음부터 다시 센다, audioFiles는 원음 메타 행 수)는 코드로 확인된다. 음성 참가자의 `TransactionTemplate`이 기본 REQUIRED라 바깥 Inbox 트랜잭션에 합류하고, 원음 파일은 `deleteIfExists`가 false여도 `filesGone`에 들어가 재시도 건수가 줄지 않는다. 다만 계획서 본문은 고쳐지지 않아 계획과 실제가 문서상 어긋난 채다. | 계획서 R-12 · Step 17 문구에 "메모 칸이 없어 재계산 방식으로 바꿈(U4b-J34)"을 적어 둔다. 또는 보고서에 이미 있는 편차 기록을 계획서가 가리키게 한다. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261013-u4b-activity-realtime/construction/code-generation/code-summary.md > 시험 결과 · 서두 | 이 검토 환경에서 `-Pmujoong.test.db=local` 시험이 `BackendUnavailableException`(호스트 PostgreSQL 16 `/usr/lib/postgresql/16/bin` 없음)으로 DB 시험 18건(`RealtimeDeletionHandlerIT` 4, `SessionRecordU4bIT` 6, `SessionLifecycleU4bIT` 4, `SessionViewU4bIT` 2, `RealtimeSchemaIT`, `ResultInputSnapshotRepositoryIT`)이 실행되지 않았다. 나머지 172건은 통과했다. 그래서 R-03(guardianId 스키마 검증) · R-12(재시도 건수 183행 단언) · 줄 커버리지 80.75%(여유 0.75%p)는 정적 대조로만 확인했다. 요약 서두는 "커밋 e23c042 ~ 760ad5d, 108개"라 하나 매니페스트는 e23c042 ~ f41e7bd, 110개이며 `git diff --name-only`와 정확히 일치한다(서두 수치만 낡음). | 서두의 범위 · 개수를 매니페스트(f41e7bd · 110)에 맞춘다. 80.75%는 여유가 얇으므로 Build and Test에서 같은 명령으로 재확인한다. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261013-u4b-activity-realtime/construction/code-generation/code-summary.md > 시험 결과 > realtime U1 회귀 | 최종 회귀 한 번에서 `RealtimeMetricsScrapeIT`이 117초 < 120초로 실패했고 "U1 지표 경로의 시간 흔들림"으로 정리했다. 이 시험은 U4b가 건드렸다(`env.join(sessionId)` 추가, 매니페스트 포함)고 이유가 U4b와 무관하다는 근거가 없다. 활성화 경로가 길어져 지표 시각이 밀렸을 가능성이 남는다. | 기준선(e23c042^)에서 같은 시험의 같은 값을 여러 번 돌려 흔들림이 원래 있었는지 확인해 report에 한 줄 남긴다. 아니면 원인을 U4b 변경으로 적는다. | New |
| R-05 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java > L342 ~ L346 `EVENT_CHILD_STOP_UNCLEAR` · `EVENT_CHILD_STOP_LIFTED` | FD-Q3로 "막고 유지 → 해제" 경로를 없앴으나 `CHILD_STOP_UNCLEAR` · `CHILD_STOP_LIFTED` 상수와 이를 설명하는 Javadoc("전이는 U4")이 남아 있다. 시험은 `doesNotContain(LIFTED)`로만 쓴다. 죽은 이름이 계약 · 로그 어휘로 오해될 수 있다. | 쓰이지 않으면 상수와 낡은 Javadoc을 지우거나, 호환을 위해 남기는 이유를 한 줄 적는다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `./gradlew :realtime-activity:test -PincludeTags=u4b -Pmujoong.test.db=local` (이 환경) | 190건 중 18 실패, 전부 `BackendUnavailableException`(PG16 없음) | 환경 한계다. DB 시험은 실행 못 했다. 비-DB 172건 통과. R-03 참조 |
| `jacocoTestCoverageVerification -PcoverageScope=u4b` | 위 실패로 도달하지 못함 | 80.75%는 미재현. 임계값은 `mujoong.java-conventions.gradle.kts` minimum 0.80 그대로이고 U4b 차등에서 임계값 변경 흔적 없음 |
| 매니페스트 대 `git diff --name-only e23c042^..f41e7bd` (backend · contracts · docs · tools · scripts · infra) | 110 대 110, 차이 0 | 매니페스트 정확 |
| 매니페스트 운영 `.java` 대 `coverage-scope/u4b.txt` | 빠진 운영 파일 0, 범위에만 있는 파일 0 | 범위 축소 · 부풀리기 흔적 없음 |
| traceability.json 대 `upstream_ids` | 90 대 90, 누락 0 · 초과 0, OK 대상 파일 전부 존재하고 매니페스트에 있음 | N/A 11건은 작업 방식 요구사항(project 규칙)으로 설명된 서술 대상. 허용 |
| `event-payloads.schema.json` SessionRecordCaptured required 대 `RecordCaptureService.payload` | 7개 필수(sessionId · guardianId · childId · activityType · finalStatus · snapshotId · snapshotHash) 모두 채움, `problems()` 검사 후 쓰기 | 계획 R-03 막힘 확인 |
| R-10 · R-11 코드 대조 | `practiceActive()`(ACTIVE · !settling · !terminated)가 7개 능력 전부에서 `refusePractice(…, "NOT_ACTIVE")`로 PRACTICE_EXTENSION_FAILED를 남김. `onJoinReply`가 PROMPTING 중에도 `join.reply()`로 즉시 분류 | 계획 R-10 · R-11 막힘 확인 |
| 기존 U1 · U3 시험 차등(수정 파일 13개) | 지운 단언은 FD-Q3(해제 경로 폐지) · 활성화 경로(WAITING_CHILD) 변경에 대응해 새 단언으로 교체됨. `@Disabled` · assume 추가 0 | 단언 수가 줄거나 약해진 곳은 R-01 한 곳(분실 응답 ACTIVE 경로 미검증) |

### Summary

계획의 R-03 · R-10 · R-11은 코드와 스키마 대조로 막혔음을 확인했고, R-12의 계획 이탈은 Inbox 표에 메모 칸이 없고 삭제가 단일 트랜잭션이라는 점으로 설계상 타당하다. 품질 임계값을 낮춘 흔적은 없다. 다만 활성화 경로 변경으로 U1의 "ACTIVE 커밋 응답 분실" 방어 시험이 WAITING_CHILD로 바뀌어 새 ACTIVE 전이의 같은 방어가 시험 없이 남은 것이 유일한 Major이며, Major 1건(≤2)이라 READY로 본다. DB 시험은 이 환경에서 돌지 않아 해당 주장은 정적 대조로만 확인했다.
