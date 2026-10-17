# U14 목표 · 결과 요구사항

## 범위와 기준

Initial description: U14 목표 · 결과(u14-goal-result) 구현. 지시 docs/units/u14/brief.md를 정본으로 한다. 인셉션 산출물은 261015-doc-sync-code-update 기록의 것을 그대로 쓴다.

Workflow-selected scope: refactor. 깊이·시험 전략 Standard. 새 업무 요구를 만들지 않고 원 기록의 FR7·FR8과 US4.1~US4.6을 구현 단위로 다시 묶는다. U15가 포함된 `unit/u14-goal-result`에서 V9를 추가한다. U10·U12 구현, 27 화면 변경, 배포·병합은 이번 작업에 포함하지 않는다.

## Functional Requirements

| ID | 요구와 합격 기준 | 원 요구·스토리 |
|---|---|---|
| FR1.1 | PracticeGoal이 후보와 담은 목표의 유일한 쓰기 소유자가 된다. 보호자가 담으면 후보 ID·버전·4요소·목표 표현·skillRef가 고정된다. 이후 후보가 갱신돼도 이미 담은 목표는 바뀌지 않는다. 자동 담기는 없다. | 원 FR8.1·FR8.4, AC4.6.1·4.6.5, ADR-002 |
| FR1.2 | SavedGoalReader.find(childId, selectionId)가 C-DS10의 SavedGoalView 또는 empty를 반환한다. 새 CANDIDATE와 기존 LEGACY_BEHAVIOR를 구분하고 다른 아이의 목표는 반환하지 않는다. 기존 목표를 계속 읽고 사용할 수 있다. | 원 FR8.4, AC4.6.4, C-DS10 |
| FR2.1 | Luna GOAL_CANDIDATES가 아이의 유효 전사·경험 구조·장면 기록으로 후보를 생성한다. 고정 행동 목록에서 고르지 않고 연령은 입력하지 않는다. 최대 3개, 이름 12자·화면 근거 40자 한도를 지킨다. 후보가 없으면 빈 목록이다. | 원 FR8.1·FR8.5, AC4.5.1·4.5.4·4.5.7 |
| FR2.2 | 4요소(situation/partner/intent/expression) 전부와 저장 전사에 글자 단위로 일치하는 출처 근거가 필요하다. 출처 불명·빈 요소·없는 skillRef·금지 표현 후보는 버린다. 화면 근거는 인용과 분리하고 OutputChecker/classify로 검사한다. skillRef는 해당 아이 U15 seedSummaries의 ID 또는 NONE만 허용한다. | 원 FR8.1·FR8.2·FR7.7, AC4.5.2·4.5.3·4.5.6·4.5.8 |
| FR2.3 | 자유대화 결과 저장 후 NONE 후보를 U15 새 씨앗 생성에 전달한다. 원문으로 임의 상수 구조를 만들지 않고 검증된 구조 추상화를 전달한다. 실패·비활성·시간 초과는 NONE을 유지하고 이미 완료된 결과 상태를 바꾸지 않는다. 중복 실행은 동일 후보를 중복 생성하지 않는다. | 원 FR8.3, AC5.1.1~AC5.1.3 |
| FR3.1 | 새 결과는 AFTER_REASK를 저장·API·리포트에서 보존한다. 27 표시용 칩만 DONE_IMMEDIATE로 접는다. 단순 재발화 확인은 시점을 바꾸지 않는다. 안전하지 않거나 빈 목표 표현은 전달로 대체한다. | 원 FR7.1, AC4.1.1~4.1.5, ADR-003 |
| FR3.2 | flowVersion=practice-conversation-v1인 practice-record-v2를 읽고 실제 시작한 장면만 처리한다. 장면 수를 1·2로 고정하지 않는다. 각 사실·변형 생략·종료 사유를 구분하며 모르는 사실은 무시한다. 없는 장면이나 도움 없음은 실패가 아니다. flowVersion이 없으면 기존 U5b 경로를 사용하고 snapshotVersion 의미를 바꾸지 않는다. | 원 FR7.2·FR7.3·FR7.5, AC4.2.1~4.2.4·4.3.1 |
| FR3.3 | REJUDGMENT는 실제 장면별 value/timing을 검증하고 AFTER_REASK를 허용한다. 진행 중 값과 다르면 재판정 값을 사용하고 differs_from_live는 내부에만 저장한다. 중복·누락·다른 장면 출력은 잘못된 AI 응답으로 처리한다. | 원 FR7.4, AC4.1.4 |
| FR3.4 | 결과 작업은 최초 1회와 추가 최대 3회로 총 4회, 지연 5·15·30초를 쓴다. 각 JOB_ATTEMPT 안에서는 AI 재시도를 겹치지 않는다. attempt_count와 attempt_no 제약을 함께 확장한다. 최종 실패의 수동 재요청·임대·기존 잠금·중복 방지 동작은 유지한다. | 원 FR7.6·FR7.7, AC4.4.1·4.4.2 |
| FR3.5 | 과거 U5b와 새 형식 결과가 섞인 기간 리포트를 정확히 집계한다. 리포트는 표시 칩을 근거로 시점을 역추정하지 않는다. 과거 결과의 원래 값·문장은 유지한다. | 원 FR7.5, AC4.3.1·4.3.2 |
| FR4.1 | 기존 REST 경로를 유지하고 timing·displayEvidence 등 필요한 값을 제공한다. 새 후보의 내부 reasonText·fourElements·skillRef는 응답하지 않는다. 기존 27 화면 코드를 바꾸지 않으며 결과 파서의 최소 호환 변환은 허용한다. 엄격 스키마의 필드 제거·필수 변경은 깨는 변경으로 명시한다. | 원 FR8.5, AC4.5.5·4.5.6, C-DS12·Contract Design R-01 |
| FR4.2 | 아동·계정·기록 삭제 시 새 후보·버전·선택·사실·판정·재생용 임시 데이터도 기존 삭제 참여자 안에서 지운다. ORDER_RESULT=70, ORDER_PRACTICE=150과 guardian→job→report 잠금 순서를 유지한다. 철회·삭제와 경쟁하는 작업은 자료를 되살리지 않는다. | C-DS2·Domain Design R-02, 기존 삭제 계약 |

## Non-Functional Requirements

| ID | 검증할 조건 |
|---|---|
| NFR1 | 태그 u14의 단위·PostgreSQL 통합 시험, U14 범위 줄 80% 이상, 기존 단언·모듈 커버리지 하한 유지. 전체 verify.sh와 PR CI를 확인한다. |
| NFR2 | AI는 시험에서 가짜 공급자만 사용한다. 실공급자·실기기 성공으로 보고하지 않는다. 민감 원문과 비밀을 로그·문서·커밋에 넣지 않는다. |
| NFR3 | 동의·아동 소유권을 매 읽기/쓰기 경계에서 확인한다. 지연 응답·중복 작업·삭제 경쟁에서도 타 아동 자료 접근과 삭제 자료 복구가 없어야 한다. |
| NFR4 | V1~V8을 수정하지 않고 V9만 추가한다. 기존 데이터 읽기와 구버전 앱 쓰기의 호환 근거, 변경 문장 해시를 migration 검토 자료로 남긴다. 실제 사람 확인 없이 승인자 이름을 만들지 않는다. |
| NFR5 | 시간 기본값 변경은 양 앱 application.yml·contracts/timing.md·frontend timing-defaults.json과 대조 시험을 함께 맞춘다. realtime 운영 로직과 27 화면은 수정하지 않는다. |

## 경계 사례와 제외

- 빈 후보·근거 부족·AI 잘못된 JSON·없는 씨앗: 유효 후보만 남기거나 빈 목록, 결과 작업 오류는 기존 정책으로 처리한다.
- 중복 저장·오래된 결과 응답: 아이/결과/후보 버전과 작업 임대의 정본을 확인한다.
- 그래프/씨앗 생성 장애는 독립 실패이며 결과 DONE을 뒤집지 않는다.
- 종료·동의 철회·삭제 중 외부 응답 도착: 다시 확인한 상태만 저장하고 원문을 외부 그래프에 보내지 않는다.
- 파일 입력·금액·새 사용자 화면·새 배포 환경은 이 유닛의 기능이 아니다. 현행 인증·시계·오류 봉투를 재사용한다.
- AC4.6.2·AC4.6.3 중 상황 생성/진행 판정 자체는 U10·U11·U12 소유다. U14는 C-DS10의 고정 스냅샷을 제공하고 포트 계약 시험으로 경계를 검증한다.

## Sources

- [desc] project-description.json의 처음 요청과 docs/units/u14/brief.md.
- [scope] refactor, Standard depth/test strategy; 요약 확인·계획 승인 생략은 사용자 지시로 설정됨.
- 원 기록 `261015-doc-sync-code-update/inception/requirements-analysis/requirements.md` FR7·FR8; `user-stories/stories.md` US4.1~US4.6, AC5.1.1~AC5.1.3.
- 같은 기록의 `units-generation/unit-of-work.md` U14, `delivery-planning/bolt-plan.md` B6, `domain-design/components.md`·`decisions.md`, `contract-design/contract-summary.md` C-DS2/6/10/12.
- 최신 코드 근거: `aidlc/spaces/default/codekb/mujungi/business-overview.md`, `architecture.md`, `code-structure.md`, 이 기록의 `inception/reverse-engineering/developer-scan.md`.
- 실제 형식 정본은 contracts/schemas 및 contracts/README.md. 구체적인 충돌 해결·편차는 Functional Design에서 문서화한다.

## Assumptions & Open Questions

None. 기존 정본과 사용자 위임 안에서 기술 선택은 설계에서 근거·대안을 기록한다. DB 문장별 사람 검토는 구현이 구체화된 뒤 별도 실제 증거로 다루며 완료한 것으로 간주하지 않는다.
