# U10 엔터티 모델

## Sources

정본은 `../../inception/requirements-analysis/requirements.md`와 `functional-design-questions.md` Q1-A다. 기존 코드 구조는 공유 CodeKB의 U10 갱신절을 따른다. 새 Unit DAG는 만들지 않는다.

## Entity model

```yaml
entities:
  - name: FrozenGoalContext
    description: 준비 시점에 고정한 선택 목표. 현재 후보 최신 버전과 분리한다.
    attributes:
      - {name: selectionId, type: UUID, required: true}
      - {name: childId, type: UUID, required: true, exposure: internal-only}
      - {name: kind, type: enum, required: true, allowed: [CANDIDATE, LEGACY_BEHAVIOR]}
      - {name: candidateRef, type: string, required: false}
      - {name: candidateVersion, type: integer, required: false, min: 1}
      - {name: legacyBehaviorKey, type: string, required: false}
      - {name: legacyBehaviorVersion, type: integer, required: false, min: 1}
      - {name: targetExpression, type: string, required: true}
      - {name: fourElements, type: object, required: true, fields: [situation, partner, intent, expression]}
      - {name: skillRef, type: string, required: true, default: NONE}
      - {name: displayName, type: string, required: true}
      - {name: savedOrder, type: integer, required: true, min: 1}
      - {name: favorite, type: boolean, required: true}
      - {name: judgmentCriteriaSnapshot, type: string, required: true, exposure: judgment-only}
    constraints:
      - CANDIDATE는 candidateRef와 candidateVersion으로 식별하고 행동 정의 목록에 등록하지 않는다.
      - LEGACY_BEHAVIOR는 실제 기존 행동 키와 버전을 사용한다.
      - 4요소와 표현은 고정한 값이며 AI가 바꾸거나 최신 후보로 치환하지 않는다.
    relationships:
      - {target: ScenarioScript, cardinality: one-to-many, direction: outbound}
  - name: ScenarioScript
    description: 승인·서버 검사 후에만 발급되는 대본
    attributes:
      - {name: scenarioId, type: UUID, required: true, unique: true}
      - {name: ownerSessionRef, type: UUID, required: true, exposure: internal-only}
      - {name: goalSelectionRef, type: UUID, required: true}
      - {name: requestKind, type: enum, required: true, allowed: [ORIGINAL, NEW_SCENARIO]}
      - {name: scriptVersion, type: string, required: false, default: null}
      - {name: scenes, type: list, required: false, items: ScenarioScene}
      - {name: selfReview, type: object, required: false, value: ScenarioReview}
      - {name: generation, type: object, required: false, value: GenerationMetadata}
      - {name: goalContext, type: object, required: false, value: FrozenGoalContext, exposure: internal-only}
      - {name: legacyProjection, type: object, required: true}
      - {name: status, type: enum, required: true, allowed: [ISSUED, EXPOSED]}
      - {name: contentFingerprint, type: string, required: true}
    constraints:
      - 과거 행은 선택 필드가 없어도 읽는다.
      - 새 대본은 첫 장면이 BASE이며 BASE가 정확히 하나다. sceneKey는 대본 안에서 유일하다.
      - 기존 필수 응답과 역할 지시는 첫 BASE에서 서버가 만든다.
      - 미승인 대본과 검토 원문은 영속 발급 대상이 아니다.
    relationships:
      - {target: ScenarioScene, cardinality: one-to-many, direction: outbound}
      - {target: ScenarioReview, cardinality: one-to-one, direction: outbound}
  - name: ScenarioScene
    description: 연습 가능한 기본 장면 또는 선택 변형
    attributes:
      - {name: sceneKey, type: string, required: true, minLength: 1, maxLength: 40}
      - {name: sceneKind, type: enum, required: true, allowed: [BASE, VARIANT]}
      - {name: sceneDescription, type: string, required: true, minLength: 1, maxLength: 8000}
      - {name: opportunitySentence, type: string, required: true, minLength: 1, maxLength: 2000}
      - {name: reexplanation, type: string, required: true, minLength: 1, maxLength: 2000}
      - {name: focusPoints, type: list, required: true, maxItems: 3}
      - {name: expressionExamples, type: list, required: true, maxItems: 3}
      - {name: relation, type: string, required: true, maxLength: 200}
      - {name: place, type: string, required: true, maxLength: 200}
      - {name: topic, type: string, required: true, maxLength: 200}
      - {name: partnerRole, type: string, required: true, authority: server-fixed}
      - {name: preservation, type: object, required: false}
    constraints:
      - 모델은 partnerRole을 생성하지 않는다.
      - focusPoints와 expressionExamples는 모델 응답에서 없으면 빈 배열로 정규화한다. 정답 예시는 코치 도움용이며 기본 장면·기회 문장에 섞지 않는다.
      - preservation은 구조 맥락이 있을 때만 쓰고 서버가 기준 스냅샷과 대조한다.
  - name: ScenarioReview
    description: 생성과 분리된 요청의 구조화 검토 결과
    attributes:
      - {name: verdict, type: enum, required: true, allowed: [APPROVE, REJECT, UNCERTAIN]}
      - {name: reasons, type: list, required: true, uniqueItems: true}
      - {name: model, type: string, required: true}
      - {name: promptVersion, type: string, required: true}
    constraints:
      - APPROVE이면 reasons는 빈 배열이다.
      - 사유는 허용 코드만이며 자유 문장은 받지 않는다.
      - 발급 응답 selfReview에는 APPROVE와 허용 메타데이터만 실린다.
  - name: GenerationMetadata
    description: 본문 없는 생성 출처
    attributes:
      - {name: model, type: string, required: true}
      - {name: promptVersion, type: string, required: true}
      - {name: graphContextUsed, type: boolean, required: true}
      - {name: kgVersion, type: string, required: false, default: null}
  - name: GenerationBudget
    description: 요청 전체가 공유하는 일회성 실행 예산
    attributes:
      - {name: originalDeadline, type: instant, required: true}
      - {name: monotonicStart, type: monotonic-tick, required: true}
      - {name: technicalRetryUsed, type: boolean, required: true, default: false}
      - {name: contentCorrectionUsed, type: boolean, required: true, default: false}
      - {name: subsetReviewUsed, type: boolean, required: true, default: false}
    constraints:
      - 마감은 최초 요청의 15초이고 재설정하지 않는다.
      - 기술 재시도와 내용 수정은 각각 최대 한 번이다.
      - 변형 제외 후 BASE만 재검토하는 요청도 동일 마감에 포함한다.
```

## Summary

담은 목표의 고정 내용과 생성 대본을 분리한다. 외부 AI에는 실제 식별자가 아닌 필요한 목표 내용·나이·정리된 관심사·최소 이전 상황만 전달한다. 요청 예산은 영속 업무 엔터티가 아니며 재시도마다 새로 만들지 않는다. 구조 맥락·변형 계획은 U15의 읽기 전용 값이고 U10은 그래프에 쓰지 않는다.
