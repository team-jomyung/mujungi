# 개별 음성 폐기 진단

기존 지표는 폐기 건수를 세지만 개별 AI 음성과 실패 조건을 연결하지 못했다. `OUTPUT_DISCARDED` 이벤트를 추가해 원문 없이 추적한다.

- 세션 식별: 기존 `ownerHash`와 세션 `traceId`를 사용한다. 원본 세션 UUID나 보호자 식별 정보는 새로 노출하지 않는다.
- 음성 구간 식별: `seq`는 `realtime_transcript.sequence_no`와 같다. `outputGeneration`, 첫·마지막 출력 수신 시각도 함께 남긴다. `seq=0`은 첫 조각 전 취소처럼 아직 AI 행이 없는 경우다.
- 정확한 조건: `discardReason`은 기존 게이트 사유, `discardDetail`은 실제 실패한 조건이다. 검사 순서는 바꾸지 않는다. 불완전 구간이면서 텍스트도 없으면 기존처럼 불완전 조건이 먼저 적용된다.
- 연습 위치: `practicePhase`·`practiceSub`는 폐기를 관측한 시점의 상태다. Jev 보관 행은 보관 당시 `seq`로 기록해 뒤에 열린 행으로 잘못 연결하지 않는다.
- 수치: 음성 바이트 수, 전사 글자 수, 구간 완전 여부, 기기 출력 세대와 현재 세대, 승인 대본 글자 수를 기록한다. 음성·전사·대본·예외 메시지·키는 넣지 않는다.
- 모든 사건은 INFO로 기록하며 반복 병합하지 않는다. 기존 지표·폐기·재시도·종료와 안전 검사·Jev 정책은 유지한다.

## 주요 상세 코드

| 코드 | 의미 |
|---|---|
| `INCOMPLETE_SEGMENT` | 구간이 첫 조각부터 끝까지 모였다는 근거가 없음 |
| `EMPTY_TRANSCRIPT` / `MISSING_AUDIO` / `INVALID_PCM_LENGTH` | 전사 없음 / 음성 없음 / PCM 길이 오류 |
| `APPROVED_TEXT_MISMATCH` | 승인 대본과 생성 전사가 다름 |
| `FORBIDDEN_PATTERN` | 서버 금지 표현 사전에서 일치 |
| `NO_OPEN_PRACTICE_SLOT` | 연습이 제어 중인데 해당 출력 슬롯이 없음 |
| `INPUT_SUPERSEDED_UNPLAYED` | 아이의 새 발화 때문에 재생 전 출력을 취소 |
| `GUARDRAIL_BLOCK` / `GUARDRAIL_REVIEW` / `GUARDRAIL_TIMEOUT` / `GUARDRAIL_ERROR` | Jev 미허용의 종류 |
| `GUARDRAIL_VOIDED` | 검사·대기 중 출력의 유효 조건이 바뀌어 무효화 |
| `GUARDRAIL_DISABLED` | 생성형 연습에 필요한 Jev가 꺼져 있어 차단 |
| `SESSION_INACTIVE` / `STALE_GENERATION` / `DEVICE_UNAVAILABLE` | 세션 상태 / 출력 세대 / 기기 연결 문제 |
| `AUDIO_QUEUE_FULL` / `AUDIO_SEND_FAILED` | 음성 자리 예약 실패 / 예약 후 전송 거절 |
| `PROVIDER_NO_AUDIO` / `PROVIDER_ERROR` | 공급자 음성 없음 / 응답 도중 공급자 오류 |
| `INSPECTION_EXCEPTION` / `GATE_PROCESSING_EXCEPTION` | 검사 내부 / 후속 게이트 처리 중 예외 |

## 검증과 범위

계약 모델 322개, 관련 Realtime 376개, 합계 698개 시험 통과. Core·Realtime bootJar 빌드 통과. 실제 게이트·실행기·엄격한 로그 수집기를 사용한 합성 시험으로 개별 번호 연결, 반복 사건 미병합, 원문 미노출과 기존 차단·재시도·중단 동작을 검증했다. 이전 표시 변경으로 필요한 기기 STARTED 사건도 Jev 시험 대역에 반영했다.

이 기능은 다음 연습의 원인 추적을 위한 것이다. 기존에 저장되지 않은 폐기 사유를 소급 복원하거나, 앞부분 음성 누락·이전 대화 질문 무응답·갑작스러운 종료 자체를 해결한 것으로 간주하지 않는다. 실제 기기에서의 새 연습 검증은 별도다. AWS Terraform 수정과 main 병합 없음.
