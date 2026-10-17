# U14 프런트 호환 경계

## 구성

새 화면·컴포넌트·상태는 만들지 않는다. RecordDetail27 등 27 화면 코드는 유지한다. frontend/src/parent/api/results.ts의 기존 API 파서만 새 후보 displayEvidence를 기존 화면 모델의 reasonText에 넣는 최소 어댑터로 확장한다. 레거시 reasonText fallback은 유지한다. API에 내부 reasonText를 노출하는 근거로 쓰지 않는다.

## 검증

새 후보와 레거시 후보를 각각 같은 표시 모델로 읽는 시험, 누락/잘못된 근거 필드의 안전한 파싱 시험, 기존 27 회귀를 실행한다. timing-defaults.json의 재시도 값 변경은 양 앱·계약과 함께 하고 기존 timing 대조 시험을 그대로 통과시킨다. 요구사항 FR4.1/NFR5와 functional-spec.md D4/D5를 따른다.

