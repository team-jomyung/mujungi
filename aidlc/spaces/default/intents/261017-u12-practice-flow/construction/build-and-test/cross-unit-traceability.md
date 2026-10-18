# U12 새 연습 흐름 — 교차 유닛 추적성

> 이 작업 기록은 유닛 나누기를 건너뛴 영(0) 유닛 구성이라 `construction/code-generation/traceability.json`(단계 수준) 하나가 전부다. 사용자 스토리 단계는 실행하지 않아 `stories.md`가 없고, 수용 기준(AC)은 `requirements.md`에 인용된 것만 센다.

## 판정

**PASS (발견 있음).** 요구사항이 인용한 AC 20개는 모두 `OK`이고 대상 구현 · 시험 파일이 존재한다. FR · NFR 상위 ID는 이 기록의 관례(project.md 교정)대로 `N/A` 행으로만 추적되어 직접 `OK` 행이 없다. 아래 "발견"에 적는다.

## 항목별 커버리지

| ID 묶음 | 개수 | 상태 | 담당 | 대상 |
|---|---|---|---|---|
| AC1.1.1 · 1.1.3 · 1.1.4 · 1.1.5 | 4 | OK | 단계 수준 | 실행부 기존 구현(U4b · U11) 회귀 + `PracticeConversationTest` |
| AC1.2.1 · 1.2.2 · 1.2.3 · 1.2.5 | 4 | OK | 단계 수준 | `FlowTransitions` · `PracticeConversation` · `SessionExecutor.speakGenerated` / `sealAndGate` · `PracticeFlowIT` |
| AC1.3.1 · 1.3.2 | 2 | OK | 단계 수준 | `FlowTransitions` — `FlowTransitionsTest` |
| AC1.4.1 · 1.4.4 | 2 | OK | 단계 수준 | `FlowTransitions` — `FlowTransitionsTest` |
| AC1.5.1 · 1.5.3 · 1.5.4 · 1.5.6 | 4 | OK | 단계 수준 | `announceRoleSwitch` · `PracticeConversation` · 재지시 · `PracticeGeneratedSpeechTest` |
| AC1.6.1 · 1.6.4 | 2 | OK | 단계 수준 | `FlowTransitions` · `PracticeConversation` |
| AC1.9.1 · 1.9.3 | 2 | OK | 단계 수준 | `PracticeConversation`(시계) — `PracticeConversationTest` |
| FR1 ~ FR8 (8개) | 8 | N/A(하위 행이 추적) | 단계 수준 | 각 행의 구현 클래스 · 시험 |
| NFR1 ~ NFR8 | 8 | N/A | 단계 수준 | 구현 · 시험 이름을 대상 칸에 적음 |

추적성 파일에는 요구사항 인용 외의 AC 9개(AC1.1.2 · 1.2.4 · 1.4.2 · 1.4.3 · 1.5.2 · 1.5.5 · 1.6.2 · 1.6.3 · 1.9.2)도 `OK`로 더 들어 있다.

## 발견 (승인 화면에서 사람이 판단)

1. **FR · NFR 직접 `OK` 행 없음.** FR1 ~ FR8과 NFR1 ~ NFR8은 모두 `N/A`다. 하위 FR(FR1.1 ~ FR8.2, 30개)과 `NFR9`는 `traceability.json`에 개별 행이 없다. 이 기록의 관례(project.md 교정 "묶음 머리 ID는 N/A 행으로 둔다")를 따른 것이지만, 이 단계의 "모든 FR · NFR이 `OK`" 기준으로는 미충족이다. FR의 실체는 AC 행과 구현 대상 클래스에 있다.
2. **대상 칸이 파일 하나가 아니라 설명 문장이다.** 위 규칙은 `OK` 대상이 존재하는 파일 하나이길 요구하지만 많은 행이 "클래스 — 시험 이름" 형식의 문장이다. 문장이 가리키는 파일(`backend/realtime-activity/src/main/java/kr/mujoong/realtime/practiceflow/*` 등)은 실제로 있으나 경로 문자열 자체로는 검증되지 않는다. 코드 검토자는 OK 대상 파일의 존재를 직접 확인했다고 보고했다.
3. **AC1.1.1 · 1.1.3 · 1.1.4는 회귀 근거만.** U12가 새로 낸 단언은 없다(코드 검토 R-03).
4. **FR8의 후속 과제**(시뮬레이터 대본 확장)와 `docs/units/u12/report.md`는 없다(코드 검토 R-02).
