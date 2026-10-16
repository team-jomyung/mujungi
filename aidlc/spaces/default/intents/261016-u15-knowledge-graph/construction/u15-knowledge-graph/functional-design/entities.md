# U15 엔티티

## Sources

현재 기록의 inception/units-generation/unit-of-work.md · unit-of-work-story-map.md와 inception/requirements-analysis/requirements.md를 따른다. 기존 261015-doc-sync-code-update/inception/domain-design/components.md · decisions.md 및 contract-design/contract-summary.md(C-DS7·C-DS11)를 참조한다. 현재 기록의 domain-design/components.md와 stories.md는 생성되지 않았다. docs/units/u15/brief.md, 기술명세서 v0.3 §6.4·6.5·13과 실제 contracts/ai-io.md·timing.md·schemas/ai-seed-output.schema.json를 대조했다.

## Entity model

```yaml
entities:
  - name: GraphVersion
    description: 검증된 기본 씨앗 자료 판
    attributes:
      - {name: version, type: string, required: true, unique: true}
      - {name: sourceDigest, type: string, required: true}
      - {name: loadedAt, type: instant, required: true}
    constraints: [전체 입력 검증과 적재 완료 후에만 완료 판으로 공개]
  - name: ChildGraphState
    description: 아동별 그래프 초기화와 과거 쓰기 가능성 원장
    attributes:
      - {name: childId, type: identifier, required: true, unique: true, references: Child}
      - {name: guardianId, type: identifier, required: true, references: Guardian}
      - {name: baseVersion, type: string, required: false, references: GraphVersion}
      - {name: nextGeneratedNumber, type: integer, required: true, min: 1}
      - {name: graphMayExist, type: boolean, required: true, default: false}
      - {name: state, type: enum, required: true, allowed: [ACTIVE, DELETING]}
    constraints: [외부 그래프 쓰기 시도 전 graphMayExist를 내구적으로 기록, 삭제 확인 전 이력 제거 금지]
  - name: Seed
    description: 아동 범위의 검증된 기술 구조
    attributes:
      - {name: childId, type: identifier, required: true, references: ChildGraphState}
      - {name: seedId, type: string, required: true}
      - {name: origin, type: enum, required: true, allowed: [POC, GENERATED]}
      - {name: label, type: string, required: true, max: 80}
      - {name: signature, type: StructureSignature, required: true}
      - {name: invariantSentence, type: string, required: true}
      - {name: family, type: string, required: true, references: SkillFamily}
      - {name: preservationRule, type: string, required: false}
      - {name: relationship, type: enum, required: true, allowed: [FRIEND_CLOSE, FRIEND_CASUAL, CLASSMATE, CLUB_PEER, CLUB_SENIOR, CLUB_JUNIOR, TEAMMATE, TEACHER, FAMILY]}
      - {name: situation, type: enum, required: true, allowed: [FRIEND_PRIVATE, SCHOOL_GROUP, CLUB, FAMILY]}
      - {name: misreadCost, type: enum, required: true, allowed: [LOW]}
      - {name: recoverable, type: boolean, required: true, default: true}
      - {name: enabled, type: boolean, required: true, default: true}
    constraints: [childId와 seedId의 복합 유일, GENERATED는 자체 preservationRule 필수, 기본 판 변경으로 GENERATED 덮어쓰기 금지]
  - name: StructureSignature
    description: 기술 동등성을 정하는 불변 값
    attributes:
      - {name: targetSkill, type: enum, required: true, allowed: [RECOGNIZE, PRODUCE, REPAIR]}
      - {name: requiredInference, type: string, required: true}
      - {name: perspectiveStructure, type: enum, required: true, allowed: [상대의 의도 1층, 상대의 지식 상태 1층, 상대가 나를 어떻게 봤을지 2층]}
      - {name: decidableByEvidence, type: enum, required: true, allowed: [YES, NO]}
      - {name: directCheckAllowed, type: enum, required: true, allowed: [YES, NO]}
  - name: SkillFamily
    description: 7개 기본 계열과 생성 씨앗의 OTHER
    attributes:
      - {name: code, type: string, required: true, unique: true}
      - {name: name, type: string, required: true}
      - {name: preservationRule, type: string, required: true}
  - name: GenerationOperation
    description: 후보별 생성 멱등성 및 실패 복구 원장
    attributes:
      - {name: childId, type: identifier, required: true}
      - {name: activityId, type: identifier, required: true}
      - {name: candidateRef, type: string, required: true}
      - {name: seedId, type: string, required: false}
      - {name: state, type: enum, required: true, allowed: [RESERVED, PROJECTING, COMMITTED, REUSED, NONE, CLEANUP_REQUIRED]}
      - {name: reusedSeedId, type: string, required: false}
      - {name: graphTransactionId, type: string, required: false}
      - {name: validatedSeed, type: Seed, required: false}
      - {name: model, type: string, required: false}
      - {name: promptVersion, type: string, required: false}
      - {name: checkCodes, type: string-list, required: true}
      - {name: deadline, type: instant, required: true}
    constraints: [아동과 후보 참조 복합 유일, 활동당 예약과 성공 합계 최대 2, REUSED는 신규 생성 상한에 세지 않음, REUSED는 reusedSeedId 필수, PROJECTING은 복구용 validatedSeed와 graphTransactionId 필수, 원문 보관 금지, 성공한 generated_seed는 COMMITTED만]
  - name: GeneratedSeed
    description: 성공한 생성 씨앗의 복구 가능한 원본 및 출처
    attributes:
      - {name: childId, type: identifier, required: true}
      - {name: seedId, type: string, required: true}
      - {name: activityId, type: identifier, required: true}
      - {name: candidateRef, type: string, required: true}
      - {name: seed, type: Seed, required: true}
      - {name: model, type: string, required: true}
      - {name: promptVersion, type: string, required: true}
      - {name: createdAt, type: instant, required: true}
    constraints: [출처는 원장에만 저장, 결과나 후보의 삭제를 막는 외래키를 새로 만들지 않음]
  - name: DeletedSource
    description: 기록별 늦은 생성 차단 표식
    attributes:
      - {name: childId, type: identifier, required: true}
      - {name: activityId, type: identifier, required: true}
    constraints: [기록 삭제 뒤 생성 재요청 거절, 아동 삭제 완료 때 함께 삭제 가능]
relationships:
  - {from: ChildGraphState, to: Seed, cardinality: one-to-many, direction: owns}
  - {from: Seed, to: SkillFamily, cardinality: many-to-one, direction: references}
  - {from: GeneratedSeed, to: Seed, cardinality: one-to-one, direction: projects}
  - {from: GenerationOperation, to: GeneratedSeed, cardinality: one-to-zero-or-one, direction: produces}
```

## 해석과 저장 경계

기본 씨앗 원본·어휘는 공유 자료이며 아동 삭제 대상이 아니다. 구조 투영에는 허용된 필드만 포함하고 아동 식별자 외의 보호자·활동·후보 출처와 원문을 제외한다. GeneratedSeed와 GenerationOperation은 서로 다르다. 실패 복구 정보가 있어도 성공 씨앗이 존재한다는 의미가 아니다. 기존 Child·Guardian·ActivityRecord는 소유권·존재 검사의 참조 대상이지 새로 정의하는 엔티티가 아니다.

