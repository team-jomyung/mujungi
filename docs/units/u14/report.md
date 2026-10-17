# U14 목표·결과 구현 보고

U15의 1a4ef7b 위에서 unit/u14-goal-result를 순차 개발했다. 불변 후보 버전과 담은 목표 스냅샷, SavedGoalReader, Luna 목표 후보·U15 씨앗 연결, 새 기록·재판정·결과/리포트와 REST/삭제를 구현했다.

## 만든 것

- PracticeGoal이 기존/생성 후보와 선택의 쓰기를 소유한다. 기존 진입점은 호환 façade를 유지한다. recommendationId는 불변 버전 UUID이며 이후 seed 게시가 기존 선택을 바꾸지 않는다.
- GOAL_CANDIDATES는 비식별 입력, 최대3개, 이름12/화면근거40, 정확 출처·4요소·skillRef·금지/분류 검사를 적용한다. NONE 후보 씨앗 처리는 결과 커밋 뒤 실행하고 재생/중복/철회/삭제 뒤 부활을 차단한다.
- 새 기록은 실제 시작한 장면만 처리한다. 가변 양의 장면 번호, AFTER_REASK 정본, 재판정·진행 차이의 내부 저장, 혼합 과거/새 리포트를 지원한다. 결과 작업 기본값은 총4회와5·15·30초다.
- 새 후보 공개 응답은 displayEvidence를 제공하고 내부 reasonText/fourElements/skillRef를 제외한다. 프런트 파서만 기존 화면 모델로 변환했다. 기존 27 화면은 수정하지 않았다.
- 기존 결과70/연습150 삭제 순서를 유지했다. 새 선택→버전→후보 FK 순서 삭제와 늦은 응답/재생 거절, 부분 실패 rollback을 검증했다.

## c096283 기준 시험 증거

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

U15 PR26 선행 병합이 필요하며 U14 병합/배포는 수행하지 않는다. U10/U11/U12의 상황 생성·진행 판정은 C-DS10/U8 포트 소비 범위로 남는다. 수정 소스의 전체 로컬 검증은 아래와 같이 완료했고 branch push·PR CI를 이어간다.

## 1차 독립 검토 보완

R-01~R-03의 운영 경계를 보완했다. 빈 현대 후보도 이전 후보를 정리하고, 담기 트랜잭션은 현재 출처 결과를 확인해 callback 전의 오래된 핸들도 거절한다. 동의 철회 후 AI 없는 빈 정리/DONE·durable replay·기존 선택 보존·타 아동/삭제 경계를 실제 PostgreSQL로 검증했다. 공급자 입력의 모든 깊이 저장 UUID는 호출별 참조로 바꾸고 seed:* 연결은 유지한다. uncertain 발화는 modern 후보 요청/근거에서 제외하며 legacy 전역 읽기는 유지했다.

수정 후 관련 회귀214개 PASS, U14 태그19클래스161개 PASS(실패/오류/skip0),26파일 범위 줄837/926=90.39%다. 첫 보완 시험의 실제 기록 fixture FK1개 실패도 보존했고 올바른 부모 활동 fixture를 넣은 재실행은 통과했다. 추적성58행/대상 존재와 범위26개 전수 일치를 확인했다.

이 결과는 c096283의 전수 backend3135개와 frontend994개 증거를 대체하지 않는다. 프런트/RT 소스는 보완에서 불변이며 새 전체검사를 실행했다고 표시하지 않았다. 수정 커밋의 전체 backend/compose/PR CI는 부모가 후속 Build and Test에서 검증한다. 자세한 명령과 구분된 집계는 Code Generation 요약 및 verification/review-repair-results.json에 있다.

## d5d4d4f 전체 통합 검증

2차 독립 검토는 기존3건 모두 해결·신규 지적0으로 완료했다. 실제 사용자 구현 완료 승인 후 ext4 검증 복사본을 d5d4d4f375c3로 고정해 전체 scripts/verify.sh --mode compose를 실행했다. 최종 rc0/747초, 모든 A/B/C/P1/P2/P3/T가 PASS다.

| 증거 종류 | 결과 |
|---|---|
| 테스트 대역 + 실제 DB, 전체 backend | 3149개 실패·오류0, Docker 미선택 LOCAL3skip |
| 모듈 전체 줄 | core96.11% · RT93.34% · contract98.45%, 기존80% PASS |
| frontend 전체 대역 | 74파일994개 PASS, 줄98.55%, eslint/Next build PASS |
| tools | 11파일125개 PASS |
| 합성 기기 + 가짜 AI + 실제 Compose | migrate/seed/기동과 자유대화 한 턴 C1~C12 PASS |
| 격리·보안·불변 | 스키마 권한42501, 로그/비밀값 유출0, 작업 트리 불변 PASS |
| 최종 U14 태그·범위 | 19클래스161개 실패/오류/skip0, 837/926줄=90.39% |
| migration | 승인8해시로 infra 검사 PASS, V8→V9 호환9시험 포함 |
| 실제 AI·실제 기기 | 실행하지 않음 |
| PR CI | push 뒤 확인 중 |

첫 전체 compose는 기존 RT 삭제 시험의 PT10S와 U15 Neo4j 초기화 오류로 A1 rc1/384초였다. 해당 클래스는 원 단언·기한으로 각각4개/9개 모두 PASS했다. 모듈 동시 실행만 제한한 GRADLE_OPTS로 전체 clean 검증을 다시 실행해 위3149개와 끝-끝을 통과했다. 제품 소스·시험 단언·하한·기한은 수정하지 않았다. 실패를 없었던 것으로 합산하지 않고 construction/build-and-test/test-results.md에 구분했다. 로컬 gitleaks 이력 도구는 없으므로 이력 검사는 CI secrets에서 별도로 확인한다.

전체 집계는 활성 U14 기록 construction/build-and-test/verification/의 backend-full-results.json·compose-results.json·u14-final-results.json에 보존했다. 전체 XML을 수집한 뒤 U14 태그 필터를 실행했으므로 둘의 수와 커버리지를 서로 바꾸지 않았다. FR/NFR17개 추적 누락0이며 전체 추적58행의 대상 존재도 확인했다.
