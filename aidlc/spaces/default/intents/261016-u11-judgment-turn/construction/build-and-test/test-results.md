# Build and Test 결과 — 로컬 무응답 조사

## 상태

2026-10-20 사용자 수동 테스트의 무응답을 조사했다. 단계 전체 검증은 완료하지 않았으며 성공 승인을 요청하지 않는다. 상세 증거와 수정 후보는 `voice-no-response-diagnosis.md`에 있다.

## 실행 결과

가짜 공급자 기반 `OutputGateTest` 17건과 `OutputGenerationTest` 21건, 총 38건 통과. 실패·오류·건너뜀 0건. Gradle `BUILD SUCCESSFUL`. 전체 빌드·전체 회귀·전체 커버리지 측정은 미실행이다. 원래 선택한 `SegmentBufferGenerationTest`는 현재 소스에 없어서 실행된 시험으로 세지 않았다.

## Target Verification Matrix

아래는 이번 장애 조사에 한정한 표이며 U11 전체 Testing Contract의 완료 판정을 대신하지 않는다.

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| LOCAL-VOICE-RESPONSE | 사용자 로컬 수동 테스트 보고 | 말을 걸면 생성 응답 글·소리가 확인됨 | 응답 구간 미승인 46개, 출력 전송 지표 0 | voice-no-response-diagnosis.md | build-and-test | Not Met |
| GATE-BASELINE | 현재 구간·출력 세대 회귀 시험 | 기존 단언 모두 통과 | 38/38 통과 | backend/realtime-activity/build/test-results/test/TEST-kr.mujoong.realtime.gate.OutputGateTest.xml 및 OutputGenerationTest.xml | build-and-test | Met |
| U11-FULL-VERIFICATION | construction/code-generation/code-generation-plan.md 및 unit-test-instructions.md | 전체 해당 시험·계약·커버리지·끝-끝 요구 충족 | 이번 조사에서 전체 검증 미실행 | 이번 문서의 실행 결과 | build-and-test | Unverified |

## 진단과 수정 후보

장애 위치는 출력 구간 조립·1차 검사다. 입력 전사 조각마다 열린 출력 구간을 봉인하는 코드와 폐기 시각이 맞물린다. 다만 `GATE_EMPTY` 세부 조건은 집계가 없어 확정되지 않았다. 다음 후보는 원문 없는 검사 조건 집계를 추가하고, 동일 발화 후속 조각과 AI 출력의 교차를 가짜 공급자로 재현한 뒤 입력 시작·후속 조각의 구분을 수정하는 것이다. 중단된 응답 꼬리의 승인 방지 단언은 유지한다. 응답 경계 설계 변경이 필요하면 재산정한다.

예상 작업은 약 반나절, 자동 검증 추가 API 비용 0, 위험도 중간(출력 경계 변경의 안전성 회귀). 코드 변경·실행 스택 변경은 아직 하지 않았다. 코드 생성 단계로 돌아갈지 사람의 선택을 기다린다. 현재 Loop-Back Log 항목은 0개다.
