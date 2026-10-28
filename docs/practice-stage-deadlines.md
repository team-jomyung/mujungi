# 연습 음성 단계별 시간 제한

목표 안내 슬롯을 열 때부터 기기 재생 완료까지 한 번에 적용하던 20초 제한을 운영 연습 경로에서 제거했다. 기존 확장과 독립 상태 기계 시험의 기본 시간 계약은 유지하고, 실행기가 단계별 시한을 관리한다.

| 구간 | 기준과 상한 |
| --- | --- |
| 이전 음성 대기 | 요청 준비부터 기존 `instructionWait` 10초 |
| 전송 대기 | 송신 큐 진입부터 별도 10초. 취소·실패한 슬롯의 대기 작업은 실제 전송 직전에 제외 |
| 첫 출력 대기 | 실제 append 송신 완료부터 별도 10초. 먼저 도착한 정상 출력은 그대로 수집 |
| 음성 수집 | 첫 출력부터 별도 30초(`playbackExpiry + instructionWait`)의 절대 벽시계 상한. 구간 사이 800ms 조용함과 기존 PCM 20초 바이트 상한은 계속 적용 |
| 재생 시작 대기 | 안전 검사와 Jev 통과 후 기기 대기열 진입부터 별도 20초 |
| 실제 재생 | 첫 STARTED부터 별도 20초 + 기존 조용함 간격 800ms의 완료 표식 여유. 중복 STARTED는 시한을 연장하지 않음 |

수집이 끝나지 않은 음성이나 거절된 음성을 바로 재생하지 않는다. 수신 중단·절대 상한·전송 실패는 현재 슬롯에 상관된 제한적 복구로 전달한다. 서버 안전 검사, 승인 대본 대조, Jev 경로, 출력 세대 재확인, 종료·휴식 우선 처리와 전체 목표 시간 제한은 유지한다.

`PRACTICE_AUDIO_STAGE` INFO 로그는 `audioStage`, `outputGeneration`, `practicePhase`, `practiceSub`만 사용한다. 다만 출력 대기가 끝나는 단계(`REQUEST_QUEUED` · `DRAIN_TIMEOUT`)에는 진단으로 `durationMs`(대기 시작부터 걸린 ms)와 `drainBlocker`(가장 오래 막은 사유: `OUTPUT_FLOWING` 공급자 출력이 이어짐 · `OUTPUT_PENDING` 보낼 출력 조각이 남음 · `CHILD_INPUT_OPEN` 아이 입력 묶음이 열림 · `NONE` 기다리지 않음)를 더한다(JIRA-282 후속, 2026-10-27). 요청 대기/실제 전송/첫 수집/재생 대기/재생/종료와 단계별 시간 초과를 구분하며 원문·음성·키는 담지 않는다. 기존 `OUTPUT_DISCARDED`에는 수집 상한의 세부 사유 `PROVIDER_COLLECTION_TIMEOUT`을 추가했다.

## 검증

- 수정 전 재현: 이전 음성 대기 후 8.801초에 요청 전송, 수신 중 전체 20.001초에 CLOSING, Jev 요청 0·기기 출력 0.
- 같은 재현의 수정 후: 23.802초에도 ACTIVE, 안전 검사 후 Jev 대역 허용 1·기기 출력 1.
- 회귀 시험은 이전 음성 8초 대기 + 첫 응답 7초 대기 + 14.2초 음성 수집, 송신 이후 첫 출력 시한, 취소 작업의 늦은 전송 방지, 30초 수집 절대 상한, 재생 단계의 독립 시한과 중복 STARTED를 검증한다.
- 실제 GPT-Live와 실행기·현재 PracticeConversation을 연결한 합성 목표 안내가 11.8초 음성으로 생성되어 안전 검사를 거쳐 기기 출력 대기열까지 전달됐다. Jev는 시험 대역 허용이며 실제 Jev 판단이나 물리 스피커 재생 증거가 아니다.
- 확대 회귀 시험의 기존 실패 2건은 변경 전 HEAD에서도 동일하게 재현됐다: `PracticeProgressMappingTest.everyStageOnAVariantAndNextGoalPathFoldsIntoTheSixValuesAndNeverLeaksTheNewStageNames`, `PracticeExecutorIntegrationTest.addedAdviceIsDiscardedAndCannotBecomeScenarioExposure`. 이 변경의 시간 제한 경로와 구분하며 전체 시험 성공으로 보고하지 않는다.
- 로컬 비교 설정은 JEV_MODE=STUB. AWS Terraform 수정, main 병합, 원격 push 없음.
