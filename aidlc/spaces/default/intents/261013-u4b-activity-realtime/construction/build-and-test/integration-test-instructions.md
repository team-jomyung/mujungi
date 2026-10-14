# U4b 통합 시험 지시 — 활동 세션 Realtime

> Build and Test(3.6) · 시험 전략 Standard: 핵심 경계의 통합 시험과 유닛 사이 상호작용을 확인한다. 명령은 저장소 루트 기준이고, DB 시험은 Docker 또는 호스트 PostgreSQL 16이 필요하다(`build-instructions.md` 2절).

## 1. 시험 프레임워크와 설정

- JUnit 5 · AssertJ, Spring Boot 시험 컨텍스트
- DB: `test-support`의 `MujoongPostgres`(Testcontainers 또는 `-Pmujoong.test.db=local`)
- 시험 환경 도우미: `RealtimeItEnvironment` · `InternalApi` · `FakeProviderWsServer` · `DeviceSimulatorClient` · `LogCapture`
- 시간: `application-test.yml`의 넉넉한 기본값 + U4b 통합 시험 클래스의 짧은 덮어쓰기(U4b-J26). sleep 없이 상태를 기다린다.

## 2. 경계별 통합 시험

| 경계 | 시험 클래스 | 확인하는 것 | 관련 ID |
|---|---|---|---|
| Realtime ↔ DB 스키마 | `RealtimeSchemaIT` · `ResultInputSnapshotRepositoryIT` · core-api `FlywayConfIT` · `SchemaPrivilegeIT` | V3 표 · 색인 · 권한, 유일 · FK RESTRICT · hash CHECK, 판 · 표 수 | FR7.1 · FR11.1 · BR8.5 |
| Core → Realtime 내부 API | `SessionViewU4bIT` · `RealtimeInternalApiIT` · `SessionInternalControllerU4bTest` | 끝내기 응답표 · 진행 조회 technicalPause · silenceWarning, Core 취소 202 | FR1.x · FR6.1 · FR11.2 · NFR8 |
| 기기 · 공급자 ↔ 세션 끝-끝 | `SessionLifecycleU4bIT` | WAITING_CHILD → 참여 응답 → ACTIVE → 대화, `session.response.create` 1회 | FR2.x · NFR5 |
| 세션 → Outbox 이벤트 | `SessionRecordU4bIT` | 종료 경로마다 스냅숏 1 · SessionRecordCaptured 1, 이벤트 스키마(guardianId 포함), 늦은 전사 정산 창 안팎 | FR7.x · BR8.x · NFR8 |
| Core 이벤트 → Realtime 삭제 | `RealtimeDeletionHandlerIT` · `RealtimeVoiceDeletionParticipantIT` | 강제 정지 · 순서 값 참가자 · DeletionCompleted 1회 · 재시도 멱등 · 삭제 뒤 0건 | FR8.1 · BR9.x · NFR9 |
| 유닛 사이(U1 · U3 회귀) | `-PincludeTags=u1` · `u3` 전체 | 새 활성화 경로(WAITING_CHILD)와 종료 의도 경로가 기존 흐름을 깨지 않음 | NFR7 · FR11.3 |
| 시스템 끝-끝 | `bash scripts/verify.sh --mode host` | 시드 001 → 002 → 003, C1 ~ C12(C8 참여 응답), 격리 · 로그 위생 · 비밀 | FR11.4 · NFR4 · NFR7 |

## 3. 실행 명령

```
cd backend && ./gradlew --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u4b -PcoverageScope=u4b
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1
cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u3
cd backend && ./gradlew --no-daemon :core-api:test --tests 'kr.mujoong.core.db.FlywayConfIT'
cd backend && ./gradlew --no-daemon check
bash scripts/verify.sh --mode host
```

- Docker가 없으면 Gradle 명령에 `-Pmujoong.test.db=local`을 붙인다.
- 흔들리던 시험(FR11.2)은 같은 명령에 `--tests 'kr.mujoong.realtime.web.RealtimeInternalApiIT' --rerun-tasks`로 5회 돌린다.
- 검토 지적 R-04: `--tests 'kr.mujoong.realtime.*RealtimeMetricsScrapeIT' --rerun-tasks`로 따로 돌려 흔들림을 본다.

## 4. 커버리지 기대값

- u4b 범위 줄 커버리지 80% 이상(`jacocoTestCoverageVerification -PcoverageScope=u4b`)
- 모듈 전체 줄 커버리지 80% 이상(`check`의 범위 없는 검증)
- 하한은 낮추지 않는다.

## 5. 시험 데이터 · 환경

- 시험마다 새 UUID로 세션 · 보호자 · 기기를 만든다. 공유 가변 데이터는 없다.
- 통합 시험 DB는 시험 클래스마다 격리하거나 정리한다(기존 방식).
- 끝-끝 host 모드는 임시 로컬 클러스터 · 생성 비밀을 쓰고 끝나면 지운다(`KEEP=1`이면 스택만 남김).
- compose 전용 항목(이미지 · Compose 병합 설정 · Nginx · 망 격리 · 이미지 기동 거부)은 host 모드에서 `NOT_VERIFIED(host)`로 남고, PR CI의 images · skeleton 작업이 확인한다.
