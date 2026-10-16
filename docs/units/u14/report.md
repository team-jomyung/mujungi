# U14 목표·결과 구현 보고

U15의 1a4ef7b 위에서 unit/u14-goal-result를 순차 개발했다. 불변 후보 버전과 담은 목표 스냅샷, SavedGoalReader, Luna 목표 후보·U15 씨앗 연결, 새 기록·재판정·결과/리포트와 REST/삭제를 구현했다.

## 만든 것

- PracticeGoal이 기존/생성 후보와 선택의 쓰기를 소유한다. 기존 진입점은 호환 façade를 유지한다. recommendationId는 불변 버전 UUID이며 이후 seed 게시가 기존 선택을 바꾸지 않는다.
- GOAL_CANDIDATES는 비식별 입력, 최대3개, 이름12/화면근거40, 정확 출처·4요소·skillRef·금지/분류 검사를 적용한다. NONE 후보 씨앗 처리는 결과 커밋 뒤 실행하고 재생/중복/철회/삭제 뒤 부활을 차단한다.
- 새 기록은 실제 시작한 장면만 처리한다. 가변 양의 장면 번호, AFTER_REASK 정본, 재판정·진행 차이의 내부 저장, 혼합 과거/새 리포트를 지원한다. 결과 작업 기본값은 총4회와5·15·30초다.
- 새 후보 공개 응답은 displayEvidence를 제공하고 내부 reasonText/fourElements/skillRef를 제외한다. 프런트 파서만 기존 화면 모델로 변환했다. 기존 27 화면은 수정하지 않았다.
- 기존 결과70/연습150 삭제 순서를 유지했다. 새 선택→버전→후보 FK 순서 삭제와 늦은 응답/재생 거절, 부분 실패 rollback을 검증했다.

## 시험 증거

| 종류 | 실제 결과 |
|---|---|
| 테스트 대역 + 실제 PostgreSQL, U14 | 18클래스147개 PASS, 실패/오류/skip0 |
| U14 변경 운영 Java26개 범위 | 줄812/912 =89.03%, 하한80% PASS |
| 실제 V8→V9·구 앱 쓰기 | migration9개 PASS, 승인된8개 제약 변경의 infra 검사 PASS |
| frontend 대역, 신규 | 8개 PASS |
| frontend 대역, 전체 | 74파일994개 PASS, 줄3414/3464 =98.55% |
| frontend lint/typecheck/production build | 모두 PASS |
| V9 공통 DB/기동 정본5클래스 | 56개 PASS, skip0 |
| 전체 backend check | 최종 rc0 PASS, 3135개/실패0/오류0/LOCAL대체3 skip |
| 모듈 전체 줄 | core96.05% · RT93.31% · contract98.52%, 모두80% PASS |
| 실제 AI/실제 기기 | 실행하지 않음 |
| 전체 compose/PR CI | 부모의 독립 검토·Build and Test 후 기록 |

backend는 원본 작업 트리와 /tmp/mujungi-u14-verification-build 출력을 사용했다. 프런트는 DrvFS worker 시작 제한으로 0개 실행한 환경 실패를 기록한 뒤 ext4 /home/pdypd/mujungi-u14-verify에서 같은 소스를 검증했다. 두 수정 파일은 LF 정규화 비교와 git diff --ignore-space-at-eol 차이 없음으로 확인했다. 실제 키·실아동 자료를 사용하지 않았다. 합성 대화는 자동 fixture이며 실제 대화 품질을 평가한 결과가 아니다.

U14 태그/전체 회귀를 중복 합산하지 않는다. 정확 명령·출력 위치와 태그 집계는 활성 기록 construction/code-generation/code-summary.md 및 verification/u14-tag-results.json에 있다. 최초 범위77% 실패 후 legacy 생명주기 PG6개를 추가하여89.03%로 통과했다.

## 계약·설계 해석과 편차

- (U14-a): 같은 GOAL_CANDIDATES 응답의 내부 선택 구조 서명으로 U15를 연결한다. 임의 상수나 추가 추상화 호출은 없다. 잘못된/없는 서명과 U15 실패는 NONE을 유지한다.
- (U14-b): strict 결과 소비자는 새 timing/displayEvidence/양의 장면 번호를 읽어야 한다. 스키마·생산부·프런트 파서를 같은 릴리스에 배포한다. legacy 응답 모양은 보존한다.
- NFR5: 결과 작업 시간 키는 Core 소유다. Core 기본값과 시간/AI 정본을 맞추고 RT/웹에 해당 키가 없음을 대조했다. 비소유 앱에는 불필요한 설정을 복제하지 않았다.
- 기존 U6 명시적3회 설정은 호환 입력으로 유지하며 운영 기본값은4회다.
- 새 provider 책임은 GoalCandidateGateway + 기존 CoreLunaClient에 모았다. v2 PG 시험은 U14ResultPipelineIT, 목표 REST는 U14ResultApiIT에서 함께 검증했다.
- 첫 전체 backend 실행은 core1546개 중9실패(rc1,391초)였다. V9와 V8 공통 정본의 차이를 확인하고 부모 판단으로 아래5개 시험의 정확 기대값/fixture만 갱신했다: CoreSchemaIT, FlywayConfIT, SchemaPrivilegeIT, CoreStartupSequenceIT, SchemaVersionCheckTest. 새3표/부분 인덱스/이력9·표총61을 반영하며 정확집합·권한·멱등·체크섬·거절/커버리지 강도는 유지했다. 이전8 거절도 추가했다. 신규 사용자 승인으로 기록하지 않는다.

V9의 실제 사람 승인은 migration-review.md와 infra/migration-approvals.txt에 있다. V1~V8은 변경하지 않았다.

두 번째 전체 backend 실행은 core1547개 실패0이었지만 RT1235개 중 기존 기기 메시지 PT10S 대기 초과1건으로 rc1(386초)이었다. 같은 클래스4개를 기한/단언 변경 없이 확인해 PASS(33초)했고, 이후 필터 없는 전체 check를 rc0(225초)로 완주했다. core/contract의 동일 소스 성공 작업은 UP-TO-DATE로 재사용하고 RT 전체는 다시 실행했다. 실패 이력을 전체 성공으로 덮어쓰지 않았다. 최종 집계는 core1547/RT1235/contract322/test-support31이며3 skip은 Docker 대신 선택하지 않은 LOCAL 시험이다.

## 남은 일

Code Generation의 네 번째 커밋 후 부모가 독립 검토, 전체 compose verify.sh, authorized branch push·PR CI를 이어간다. U15 PR 병합과 U14 병합/배포는 이 개발자가 수행하지 않았다. U10/U11/U12의 상황 생성·진행 판정은 C-DS10/U8 포트 소비 범위로 남는다.
