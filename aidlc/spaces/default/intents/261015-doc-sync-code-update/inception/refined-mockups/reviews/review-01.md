## Review

**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-10-15T08:47:05Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/refined-mockups/mockups.md > 2.5 (27 연습 기록 표시 유지) · interaction-spec.md > SceneChip 동작 규칙 | 사용자 스토리 검토 R-01(새 `AFTER_REASK` 값 대 27 표시)을 Functional Design으로 넘기기만 하고, 넘기는 선택지가 Q5-A와 부딪힌다. (b) "27에서 칩을 숨긴다"는 지금 그려지는 칩을 없애므로 AC4.1.2 · Q5-A("27 표시는 바뀌지 않는다")와 모순이다. (a)의 예시 "`AFTER_REASK` → 기존 '바로 전달'이 아닌 별도 접기 규칙"은 문장이 뜻을 이루지 못해 어떤 기존 값으로 접는지 알 수 없다. 새 값이 들어갈 때 `SceneChipMapper`가 5종 외 값에 예외를 던지고 `scene_chip` CHECK가 5종만 허용한다는 스토리 검토의 근거도 시안에 없다. 그래서 개발자가 27 동작을 시험으로 쓸 수 없다. | 27에 '되묻기 뒤' 장면이 있는 기록을 열었을 때의 기대 표시를 하나로 못 박는다(Q5-A에 맞는 것은 기존 5종 중 하나로 접기뿐이다). 접을 대상 값을 명시하고, (b)는 Q5-A 변경이므로 사람 결정이 필요한 선택지로 따로 표시한다. 저장 제약과 매퍼 수용이 마이그레이션 대상임을 한 줄 적는다. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/refined-mockups/mockups.md > 4 RP-4 (C.S-12.19.4 · C.S-12.19.5 · C.S-27.26.9 · C.S-27.26.10) 및 2.2 | RP-4는 "반영" 추천인데 바꿀 문구가 없다. 2.2의 예시 대화("무중이 블록 성을 쌓고 있어. 나도 하나 줄래?" 등)는 새로 지어낸 글이라 문구 ID에 대응하지 않는다. 본문 원칙은 "정본의 문구 ID를 그대로 쓴다"여서 어긋난다. 또 C.S-27.26.9 · .10은 27 연습 기록의 예시 글이라 "27 표시 불변(Q5-A)"과의 관계를 설명해야 한다(예시 글 교체일 뿐 레이아웃 변경이 아니라는 점). 정본 담당자가 이 표만으로 문구표를 고칠 수 없다. | RP-4에 ID별 새 문구(.4 · .5 · .6 · 27.26.9 · .10 해당분) 또는 "삭제"를 적고, 2.2 예시 글은 "예시이며 정본 문구가 아님"이라고 표시한다. 27 예시 교체가 Q5-A 위반이 아님을 한 줄 적는다. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/refined-mockups/mockups.md > 2.6 마지막 불릿 · 4 RP-5 | 목표 이름 12자 · 근거 문구 40자 · 후보 3개를 "반영" 추천 개정안으로 올렸다. 스토리(AC4.5.7, 열린 항목 4)는 이 숫자를 [기본값]으로 Functional Design에서 확인한다고 했다. 시안이 확정 값처럼 쓰고 있다. RP-5는 AC4.5.7의 칩 글 8자도 빠뜨렸다(27 비표시라 의도일 수 있으나 이유가 없다). | RP-5 · 2.6에 숫자가 [기본값]이며 FD 확인 대상임을 표시하고, 칩 글 8자를 의도적으로 뺐다면 이유를 적는다. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/refined-mockups/mockups.md > 4 RP-2 | RP-2는 v1.5j에 대응 요소가 없는 "아이 음성" 대상이고, 사람이 정한 Q6-A(대체 문구 없음)와 같은 줄에 후보 문구("잠깐, 다시 생각해 볼게")를 적었다. 개정안이 아니라 열린 항목(5절 3번)인데 RP 번호가 붙어 "개정안 7건"의 무게로 읽힌다. 제안임은 표 머리로 표시되어 있어 큰 문제는 아니다. | 후보 문구가 이번 작업에 적용되지 않는 참고임을 RP-2에 명시하거나 열린 항목으로 옮긴다. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/refined-mockups/interaction-spec.md > DeviceStatusView Props(`outputPending`) | 기기 화면에 `outputPending` 입력을 더했는데, 같은 문서의 동작 규칙 1과 mockups 2.1은 서버가 `thinking` 상태를 유지해 보낸다고 한다. 화면은 상태만 그리므로 이 prop의 출처와 필요성이 모호하다. 또 접근성 `status` 역할과 `aria-live="polite"`를 함께 적어 중복이다. | `outputPending`을 지우거나 서버 상태 전송 규칙임을 분명히 한다. `status`와 `aria-live` 중 하나로 정리한다. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/refined-mockups/accessibility-checklist.md > 1 · 2 · 5 및 interaction-spec.md > GoalCandidateCard Accessibility | (1) 17의 "10시 52분"으로 읽히게 하는 방법(시간 요소 · 읽기 문구)이 interaction-spec에 없어 점검 항목을 구현할 근거가 없다. (2) GoalCandidateCard가 `aria-pressed`와 상태에 따라 바뀌는 `aria-label`("담기"/"담김, 누르면 취소")을 함께 써서 화면 읽기 프로그램이 상태를 두 번 말할 수 있다. (3) 5의 "깜빡이지 않는다"는 자동 · 수동으로 검증할 방법이 적혀 있지 않다. (4) 27 목표 칸의 [담기] 실패와 정리 실패 상태 점검이 없다. | 시간 읽기 방식을 spec에 더하고, 버튼 이름은 고정하고 상태는 `aria-pressed`에만 맡긴다. 깜빡임 확인은 상태 전이 시험(AC1.10.4)으로 연결하고, 오류 상태 점검 항목을 더한다. | New |

### Summary

새 화면 없이 v1.5j의 화면 · 문구 ID 안에서 US1.7 · US1.10 · US2.1 · US4.1 · US4.5와 대체로 일치하고, Q5-A · Q6-A를 지키며, 개정안 RP-1~RP-7은 정본을 고치지 않는 제안으로 표시되어 있다. 단, 사용자 스토리 검토 R-01(27 표시와 `AFTER_REASK`)은 선택지가 Q5-A와 부딪히고 기대 표시가 정해지지 않아 FD가 다시 물어야 하는 상태이므로, 승인 전에 사람이 한 가지로 정하기를 권한다. 나머지는 개정안 문구의 완결성과 접근성 세부 보강이다.
