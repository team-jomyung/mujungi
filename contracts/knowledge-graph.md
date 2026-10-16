# U15 지식 그래프 내부 계약

## 범위와 호출자

U15는 기존 core-api의 내부 포트다. 공개 HTTP API나 realtime 계약을 추가하지 않는다. 기존 KnowledgeGraphLookup.findRelatedBehaviorKeys는 공개 행동키 조회이며 호환을 유지한다. 아동용 서비스는 guardianId·childId를 요구하고 소유권·현재 동의·삭제 상태를 확인한 뒤 childId 필수 어댑터에 위임한다.

| 기능 | 입력 | 출력/실패 |
|---|---|---|
| initializeChild | 보호자·아동 | 멱등 초기화, 장애 시 다음 조회 재시도 |
| seedSummaries | 보호자·아동 | 활성 씨앗 목록, 오류/꺼짐/2초 초과 시 빈 목록 |
| skillContext | 보호자·아동·seedId | 구조 5필드·불변 문장·보존 조건·관계/자리·판, NONE/없음은 빈 값 |
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

기존 mujoong.knowledge.neo4j.enabled 기본 false와 mujoong.timing.knowledge.graph-budget 2s를 유지한다. 생성 독립 스위치 mujoong.knowledge.seed-generation.enabled 기본 true는 그래프 조회와 독립적이며 false이면 생성 호출/쓰기를 금지한다. 실제 AI 키 없는 기동과 가짜 공급자 검증을 지원한다. 새 비밀 이름은 추가하지 않는다.

기존 최소 ai-seed-output 스키마를 받는 공통 Luna 호출 경로는 호환 분기로 유지한다. 서버 업무 검사는 코드 생성에서 구현한다. 이 문서만으로 새 기능 구현 완료를 의미하지 않는다.

## U15 v2 정확한 출력 분기

정본은 schemas/ai-seed-output.schema.json이다. 각 객체는 추가 필드를 금지한다.

- GENERATED: outcome, skillName, invariantSentence, signature(필수 5필드), family, preservationRule, relationship, situation, misreadCost=LOW, recoverable=true 모두 필수다. 불변 문장 상한은 60자다.
- DUPLICATE: outcome=DUPLICATE와 duplicate_of만 받는다. 동일 아동 활성 목록 재검증 후 REUSED/reusedSeedId로 보관하며 새 생성 한도에서는 제외한다.
- NONE: outcome=NONE과 none_reason(NO_VALID_SEED/UNSAFE_CONTENT/INSUFFICIENT_CONTEXT)만 받는다. 원문 사유는 받지 않는다.
- 호환: outcome 없는 기존 skillName/invariantSentence 2필드 모양은 U9의 기존 계약 검증을 위해 유지한다. U15는 이를 완전한 생성 결과로 수락하지 않고 내용 실패로 처리한다. v2 프롬프트는 위 세 분기만 요청한다.

모든 필드와 열거 값은 스키마가 정본이다. 스키마 판과 seed-generation.v2 프롬프트 판을 함께 배포하며 실제 원문 겹침·권한·활동 한도·중복 판정은 서버의 업무 검사다.
