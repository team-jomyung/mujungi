# U15 지식 그래프 내부 계약

## 범위와 호출자

U15는 기존 core-api의 내부 포트다. 공개 HTTP API나 realtime 계약을 추가하지 않는다. 기존 KnowledgeGraphLookup.findRelatedBehaviorKeys는 공개 행동키 조회이며 호환을 유지한다. 아동용 서비스는 guardianId·childId를 요구하고 소유권·현재 동의·삭제 상태를 확인한 뒤 childId 필수 어댑터에 위임한다.

| 기능 | 입력 | 출력/실패 |
|---|---|---|
| initializeChild | 보호자·아동 | 멱등 초기화, 장애 시 다음 조회 재시도 |
| seedSummaries | 보호자·아동 | 활성 씨앗 목록, 오류/꺼짐/2초 초과 시 빈 목록 |
| skillContext | 보호자·아동·seedId | SkillContext(seed, graphVersion): 동일 보호자 잠금 읽기의 씨앗 구조·보존 조건·관계/자리와 적용 판. NONE/없음은 빈 값 |
| variantPlan | 보호자·아동·seedId·기본 맥락·실제 노출 | SAME_CONTEXT/NEAR의 한 요소 변경 계획, 만들 수 없으면 빈 값 |
| generateSeed | 보호자·아동·활동·후보 참조·추상화 구조 | 같은 아동의 기존/새 seedId 또는 NONE, result_status 변경 없음 |
| deleteChild | 보호자·아동 | 해당 아동 투영/생성 원장만 삭제, 실패는 재시도 |
| deleteGeneratedSeedsForRecord | 보호자·아동·활동 | 해당 활동 생성 씨앗만 삭제, 늦은 재생성 차단 |

## 소비자 책임

U14는 결과 작업 뒤 생성 요청과 후보 skill_ref 연결을 맡는다. 목록 실패 시 생성하지 않으며 삭제된 참조는 NONE이다. U10은 실제 노출 자료를 전달하고 구조 맥락·변형 계획을 대본 생성과 보존 판정에 함께 쓴다. 조회 없음은 개인화 완료가 아니다. U15가 U14/U10의 호출 연결을 대신 구현하지 않는다.

## 생성과 저장 안전성

자유대화 NONE 후보만 생성하며 활동당 최대 2개, 후보별 멱등이다. 동일 최초 마감 안에서 기술 재시도 최대 1회, 스키마/내용 수정 공유 슬롯 최대 1회, 전체 전송 최대 3회다. 새 씨앗 불변 문장은 정규화 후 Unicode 코드포인트 60자 이내 한 문장이며 원문과 연속 5글자 이상 겹침을 거절한다. 원문은 서버 검사에만 사용한다.

그래프에는 허용 구조 필드와 childId만, 활동/후보 출처와 모델/검사 결과는 원장에만 둔다. 실패 복구용 예약은 성공 generated_seed와 구별하며 성공 원장 없는 생성 노드는 조회에 노출하지 않는다. 외부 쓰기 결과가 불명인 동안 삭제를 완료시키지 않는다. 저장 직전에 소유권·동의·삭제 상태를 다시 확인한다.

## 구성과 호환

mujoong.knowledge.neo4j.enabled 기본값은 true다. 활성화된 Core는 기존 NEO4J_AUTH와 인증된 Neo4j 초기 연결이 준비되지 않으면 기동을 거절한다. CI·비그래프 자동 시험은 false를 명시하며 그래프 조회 예산 2s는 유지한다. 생성 독립 스위치 mujoong.knowledge.seed-generation.enabled 기본 true는 그래프 조회와 독립적이며 false이면 생성 호출/쓰기를 금지한다. 실제 AI 키 없는 기동과 가짜 공급자 검증을 지원한다. 새 비밀 이름은 추가하지 않는다. 실행·배포 준비는 docs/neo4j-runtime.md를 따른다.

Neo4j HTTP 주소는 `mujoong.knowledge.neo4j.endpoint`(기본 `http://neo4j:7474`)이며 기존 `NEO4J_AUTH`를 core-api와 Neo4j 각각의 비밀 환경 파일로 전달한다. 인증값 누락·연결 실패는 활성화된 앱의 기동 실패다. 명시적 비활성 시험에서도 과거 그래프 이력이 있으면 삭제를 성공으로 처리하지 않는다.

기존 최소 ai-seed-output 스키마를 받는 공통 Luna 호출 경로는 호환 분기로 유지한다. U15는 v2 업무 검사를 별도로 적용한다.

SAME_CONTEXT의 서버 소유 표면 어휘는 화제 `그림/독서/놀이`, 장소 `교실/도서관/운동장`, 등장인물 `아이와 친구/아이와 또래/아이와 모둠원`이다. 원본 POC의 CHANGE 지시를 실행 가능한 유한 선택지로 구체화한 값이며 아동 이름·실제 장소를 입력하지 않는다. U10은 이 표면 어휘와 아동용 관계·자리 열거값으로 기준 맥락과 실제 노출을 전달한다.

## 관찰 횟수와 참고 씨앗 [편차 KG-1028-a]

자유대화에서 루나가 목표 후보에 붙인 씨앗 태그를 아이 그래프에 쌓고, 연습 장면을 처음 만들 때 소재 참고로 쓴다. 기술명세서 v0.3 §6.4의 "그래프에는 `child_id`만 둔다 · 진행 중 판정과 기록용 재판정에 들어가지 않는다"는 그대로이며, 여기에 씨앗별 횟수 · 시점만 더한다(정본 문서는 고치지 않았다).

| 기능 | 입력 | 출력/실패 |
|---|---|---|
| recordObservations | 보호자·아동·씨앗별 (횟수, 마지막 관찰 시각) | 그 아이의 기존 씨앗 노드에 `observedCount` · `lastObservedAt`을 덮어쓴다. 목록에 없는 씨앗은 0회 · 시각 없음. 그래프가 준비되지 않았으면 쓰지 않음(false) |
| referenceSeed | 보호자·아동·연습 목표의 씨앗·연습에 고른 적 있는 목표의 씨앗 집합·쉬는 기간 | 고른 씨앗의 `seedId` · 불변 문장 하나, 없거나 2초 초과면 빈 값 |
| requestReferenceMark | 보호자·아동·seedId | 장면 저장 뒤 비동기로 `lastReferencedAt`을 남긴다. 실패는 `ReferenceMarkFailed`로만 남고 연습 결과에 영향 없음 |

- **세는 것.** `ACTIVE` 목표 후보의 현재 버전 중 자유대화(`FREE_TALK`) 활동에서 나온 후보의 `skill_ref`(`seed:*`)만 센다. 연습에서 나온 후보는 세지 않는다(참고한 씨앗이 연습 기록을 거쳐 스스로 횟수를 키우지 않게). 기간 제한 없이 누적하며, 결과 작업이 후보 등록 · 새 씨앗 연결을 마칠 때마다 PostgreSQL에서 전체를 다시 세어 덮어쓴다. 동기화 실패는 `SkillObservationSyncFailed`로 남기고 다음 결과 작업이 따라잡는다.
- **고르는 규칙.** 후보는 지금 담겨 있고 보호자가 연습에 한 번이라도 고른(그 목표로 연습 장면을 만든 적 있는) 목표들의 씨앗이다. 자유대화 목표가 자동으로 담겨도, 담기기만 하고 고른 적 없는 주제는 섞지 않는다. 연습 목표 자신의 씨앗, 관찰 2회 미만, 마지막 참고 뒤 `mujoong.timing.knowledge.reference-cooldown`(기본 7일)이 지나지 않은 씨앗은 뺀다. 관찰 횟수가 많은 순, 같으면 최근 관찰 순, 그다음 `seedId` 순으로 하나를 고른다. 고를 씨앗이 없으면 건너뛴다(지금처럼 참고 없이 만든다).
- **쓰는 곳.** 연습 장면을 처음 만들 때(`ORIGINAL`)만 상황 생성 입력 `referenceContext.invariantSentence`로 넣는다. 보존 조건 · 구조 서명 · 목표는 주지 않는다. 목표 씨앗 조회가 이미 실패했거나 그래프가 꺼져 있으면 참고를 찾지 않고, 생성 중 그래프 대체 경로로 바뀌면 참고 문장도 뺀다. 승인된 장면을 저장한 뒤에만 참고 시점을 남기며, 장면마다 참고 씨앗을 따로 기록하지 않는다.
- **저장 안전성.** 권한 · 동의 · 그래프 상태와 횟수 · 후보 목표는 짧은 PostgreSQL 트랜잭션에서 확인 · 계산하고, Neo4j 쓰기 · 읽기는 보호자 잠금 밖에서 한다(연습 저장이 그래프 응답을 기다리지 않게). 쓰기는 `MATCH`만 써서 씨앗 노드를 새로 만들지 않으므로 확인 뒤 삭제가 끼어들어도 지운 아이 그래프를 되살리지 않는다. 생성 씨앗과 달리 원장을 거치지 않는 파생 기록이며, 결과가 불명확해도 아이 삭제가 노드째 지운다. 그래프에는 횟수와 시각만 두고 원문 · 세션 · 후보 출처는 PostgreSQL에만 둔다(BR2).
- **재적재.** 기본 씨앗 적재(`replaceBase`)는 새 카탈로그에 없는 기본 씨앗만 지우고 남는 씨앗은 내용만 바꾼다. 그래서 복구용 재초기화에도 횟수 · 참고 시점이 유지되고, 카탈로그에서 빠진 씨앗의 기록만 함께 사라진다.
- **기록 단위 삭제.** 지금 운영 코드에는 활동 기록 하나만 지우는 호출(`deleteGeneratedSeedsForRecord`)이 없다. 이 호출을 연결할 때는 같은 작업에서 횟수를 다시 세어 덮어써야 한다(지운 기록의 횟수가 남지 않게).

## U15 v2 정확한 출력 분기

정본은 schemas/ai-seed-output.schema.json이다. 각 객체는 추가 필드를 금지한다.

- GENERATED: outcome, skillName, invariantSentence, signature(필수 5필드), family, preservationRule, relationship, situation, misreadCost=LOW, recoverable=true 모두 필수다. 불변 문장 상한은 60자다.
- DUPLICATE: outcome=DUPLICATE와 duplicate_of만 받는다. 동일 아동 활성 목록 재검증 후 REUSED/reusedSeedId로 보관하며 새 생성 한도에서는 제외한다.
- NONE: outcome=NONE과 none_reason(NO_VALID_SEED/UNSAFE_CONTENT/INSUFFICIENT_CONTEXT)만 받는다. 원문 사유는 받지 않는다.
- 호환: outcome 없는 기존 skillName/invariantSentence 2필드 모양은 U9의 기존 계약 검증을 위해 유지한다. U15는 이를 완전한 생성 결과로 수락하지 않고 내용 실패로 처리한다. v2 프롬프트는 위 세 분기만 요청한다.

모든 필드와 열거 값은 스키마가 정본이다. 스키마 판과 seed-generation.v2 프롬프트 판을 함께 배포하며 실제 원문 겹침·권한·활동 한도·중복 판정은 서버의 업무 검사다.
