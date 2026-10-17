# U14 엔터티

## 엔터티 정본

```yaml
entities:
  - name: GoalCandidate
    description: 아이 활동에서 생성한 후보의 안정된 정체성
    attributes:
      - {name: candidateRef, type: opaque-id, required: true, unique: true}
      - {name: childId, type: identifier, required: true}
      - {name: sourceActivityId, type: identifier, required: true}
      - {name: currentVersion, type: integer, required: true, min: 1}
    constraints: [같은 출처 결과와 후보 정체성의 재생은 멱등]
  - name: CandidateVersion
    description: 변경되지 않는 후보 내용
    attributes:
      - {name: candidateRef, type: opaque-id, required: true, references: GoalCandidate}
      - {name: recommendationId, type: immutable-version-handle, required: true, unique: true}
      - {name: version, type: integer, required: true, min: 1}
      - {name: displayName, type: text, required: true, max: 12}
      - {name: displayEvidence, type: text, required: true, max: 40}
      - {name: targetExpression, type: text, required: true, max: 200}
      - {name: fourElements, type: FourElements, required: true}
      - {name: skillRef, type: opaque-id-or-NONE, required: true}
      - {name: reasonText, type: internal-text, required: true, max: 500}
      - {name: evidence, type: source-references, required: true}
      - {name: structureSignature, type: validated-structure, required: false}
    constraints: [후보와 버전의 조합 유일, 게시한 버전 불변, 새 씨앗 연결은 다음 버전]
  - name: FourElements
    description: 목표의 4요소 값 객체
    attributes:
      - {name: situation, type: nonblank-text, required: true, max: 500}
      - {name: partner, type: nonblank-text, required: true, max: 200}
      - {name: intent, type: nonblank-text, required: true, max: 500}
      - {name: expression, type: nonblank-text, required: true, max: 500}
  - name: SavedGoal
    description: 보호자가 고른 불변 버전의 스냅샷
    attributes:
      - {name: selectionId, type: identifier, required: true, unique: true}
      - {name: guardianId, type: identifier, required: true}
      - {name: childId, type: identifier, required: true}
      - {name: kind, type: enum, required: true, allowed: [CANDIDATE, LEGACY_BEHAVIOR]}
      - {name: candidateRef, type: opaque-id, required: false, references: CandidateVersion}
      - {name: candidateVersion, type: integer, required: false, min: 1}
      - {name: snapshot, type: immutable-goal-values, required: true}
      - {name: status, type: enum, required: true, default: ACTIVE, allowed: [ACTIVE, DELETED]}
    constraints: [CANDIDATE는 후보와 버전이 필요, LEGACY_BEHAVIOR는 기존 행동키와 버전 필요, 활성 선택은 아동과 후보 정체성별 하나, 같은 불변 핸들 담기 멱등]
  - name: SceneResult
    description: 실제 시작한 장면의 기록용 판정
    attributes:
      - {name: resultId, type: identifier, required: true}
      - {name: goalSelectionId, type: identifier, required: true, references: SavedGoal}
      - {name: sceneNo, type: integer, required: true, min: 1}
      - {name: sceneRunId, type: identifier, required: false}
      - {name: flowVersion, type: version-token, required: false}
      - {name: value, type: nullable-enum, required: false, allowed: [functional, partial, none, uncertain]}
      - {name: timing, type: nullable-enum, required: false, allowed: [IMMEDIATE, AFTER_REASK, AFTER_HELP]}
      - {name: facts, type: allowed-fact-set, required: false}
      - {name: differsFromLive, type: boolean, required: true, default: false}
    constraints: [결과와 목표 및 장면 번호 조합 유일, 새 형식은 sceneRunId로 입력과 일대일 대응]
relationships:
  - {from: GoalCandidate, to: CandidateVersion, cardinality: one-to-many, direction: owner-to-version}
  - {from: SavedGoal, to: CandidateVersion, cardinality: many-to-one, direction: selection-to-pinned-version}
  - {from: SceneResult, to: SavedGoal, cardinality: many-to-one, direction: result-to-goal}
```

## 요약과 소유권

PracticeGoal은 후보·버전·담은 목표와 근거를 소유한다. ResultPipeline은 결과·장면 판정·전달용 pending 정본을 소유한다. U15는 씨앗을 소유하며 후보 버전/선택에 직접 쓰지 않는다. 기존 레거시 엔터티와 저장 필드는 계속 읽고 쓸 수 있게 보존한다. 요구사항 FR1.1~FR4.2와 functional-spec.md D1/D3/D6의 경계를 따른다.

