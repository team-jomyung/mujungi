# Jev 출력 단위별 검사 개편 — 진행 기록

기록일: 2026-10-21 (Asia/Seoul)
상태: 구현 및 검증 진행 중. 사용자 요청으로 중단하며 2026-10-22에 이어서 수행한다.

## 작업 방향과 기존 작업 처리

사용자는 GPT-Live의 전체 발화가 모일 때까지 기다리는 Jev 검사로 인한 출력 지연을 줄이기 위해 다음을 요청했다.

- Jev 검사를 욕설·비속어·직접적인 모욕 표현 여부로 축소한다.
- GPT-Live 출력 단위마다 검사하고, 통과한 단위부터 출력한다.
- 기존 서비스 가드레일 교체 작업은 완료 처리하고, 이번 개편은 AI-DLC 절차 없이 Codex로 직접 수행한다.

기존 작업 `aidlc/spaces/default/intents/261020-service-guardrail-replac/`은 사용자의 완료 확인을 근거로 완료 처리했다. 이는 이번 개편의 테스트 완료를 의미하지 않는다. 이번 작업의 AI-DLC 단계나 새 intent는 만들지 않았다.

## 현재 구현한 변경

1. `backend/realtime-activity/src/main/resources/guardrail/policy-v3.json`
   - 기본 정책을 `jev-guardrail-v3`으로 전환했다.
   - 선택지를 `PROFANITY`와 `PASS`로 축소했다.
   - 조각 경계에서 나뉜 표현을 직전 출력과 이어 판단하도록 지시한다.
   - 아이의 발화나 이전 맥락 자체를 차단 근거로 삼지 않도록 했다.
   - 이전 v1/v2 리소스는 이력으로 보관한다.

2. `gate/TimedOutputBuffer.java`, `gate/OutputGeneration.java`
   - 전사 조각의 `start_ms`/`end_ms`와 누적 PCM 바이트 시계로 출력 단위를 나눈다.
   - PCM 프레임이 전사 경계를 넘으면 바이트 단위로 분리한다.
   - 전사 종료 시각까지 음성이 도착한 단위만 게이트에 넘긴다.
   - 지시 직후 기존 음성을 버리는 여유 구간에서 빠진 전사는 첫 검사 단위에 합쳐 검수한다.
   - 시간선 역전·누락·과도한 버퍼를 거부하며, 전사 없는 꼬리는 출력하지 않는다.

3. `session/SessionExecutor.java`, `session/GuardrailCoordinator.java`
   - 시간 정보가 있는 새 발화는 전체 발화 종료나 추가 무음 대기를 거치지 않고 단위별 검사에 들어간다.
   - Jev 검사는 순서대로 수행하며, 검사 중 뒤따르는 단위는 상한이 있는 대기열에 보관한다.
   - 스트리밍 요청에는 현재 조각과 직전에 통과한 출력의 마지막 64자만 보낸다.
   - BLOCK/REVIEW/검사 실패와 대기열 초과 시 남은 응답을 무효화하는 경로를 추가했다.
   - 전사가 음성보다 먼저 올 경우 기존 `mujoong.timing.judgment.response-ready` 시한 안에서 대응 음성을 기다린다.
   - 시간 정보가 없거나 음성부터 시작해 조각 대응을 확인할 수 없는 경우 기존 무음 구간 검사 경로를 유지한다.
   - 생성형 연습 발화에도 스트리밍을 적용하고, 연습 완료 사실은 전체 발화 종료 및 모든 단위의 검사·재생 완료 뒤 한 번만 알리도록 했다.
   - 승인 원문 재생, 1차 출력 사전, 송신 직전 권한 확인은 유지한다.

4. 정책 로더·결과 스키마·계약 문서
   - `LiveContentResources`, `GuardrailSupport`, `JevQuestion`의 기본 정책을 갱신했다.
   - 과거 v2 정책을 읽는 호환 테스트는 유지했다.
   - `contracts/schemas/guardrail-result.schema.json`에 `PROFANITY`를 추가했고 기존 범주는 이력 호환용으로 남겼다.
   - `contracts/ai-io.md`에 현재 v3 정책과 출력 단위별 처리 방식을 기록했다.

5. 검증 중 발견한 기존 컴파일 오류
   - `ProviderRequestFactory`의 정책 사용 경로가 정의되지 않은 `MAX_AGE`를 참조하고 있었다.
   - 서비스 정책 경로 전용 상한 `SERVICE_MAX_AGE = 12`를 정의했다. 기존 다른 경로의 나이 처리 방식은 유지한다.

6. 통합 테스트 공급자 시간선
   - `backend/test-support/src/main/java/kr/mujoong/testsupport/provider/FakeProviderWsServer.java`에서 지시 응답 앞에 700ms 채움 음성을 넣으면서 전사 종료 시각에는 이를 포함하지 않는 불일치를 확인했다.
   - 전사 종료 시각에 채움 길이도 포함하도록 수정했다. 이 수정의 최종 통합 테스트는 아직 완료하지 못했다.

## 테스트 상태 — 최종 통과로 간주하지 말 것

- 첫 관련 테스트 실행: 332개 중 1개 실패. 서비스 정책 경로의 나이 상한 기대값(12세)을 복원하여 수정했다.
- 다음 관련 테스트 실행: 340개 중 새 스트리밍 테스트 3개 실패. 테스트가 지시 확인 및 새 음성 시작 여유 구간을 재현하지 않던 문제를 수정했다.
- 이후 집중 검증 명령은 `BUILD SUCCESSFUL`로 끝났다(42초).

```bash
cd backend
./gradlew --no-daemon :realtime-activity:test \
  --tests '*TimedOutputBufferTest' \
  --tests '*GuardrailExecutorTest' \
  --tests '*PracticeGeneratedSpeechTest' \
  --tests '*GuardrailPolicyV3Test' \
  -Pmujoong.test.db=docker
```

이 성공 이후에도 음성보다 앞선 전사의 대기 시한, 지시 여유 구간 처리, 생성형 연습의 뒤늦은 출력 차단, 대기열 초과 테스트, v3 응답 변환 테스트 등을 추가/수정했다. 따라서 현재 최종 파일 전체가 검증된 상태는 아니다.

넓은 회귀·통합 테스트에서는 다음 문제가 나왔다.

- `GuardrailTurnIT`가 Jev 요청을 기다리다 실패했다. 위 테스트 공급자의 시간선 불일치를 수정했으나 수정 후 결과는 아직 없다.
- 실행 중 `build/test-results` 및 컴파일된 클래스 파일이 사라지는 현상과 `NoClassDefFoundError`가 발생했다. 공유 빌드 산출물 변경의 영향이 의심되지만 원인은 확정하지 않았다.
- 일부 WSS 연결 및 세션 출력 대기 테스트도 실패했다. 별도 빌드 경로에서 재현 여부를 확인해야 한다.

공용 빌드 경로 영향을 피하기 위해 아래 임시 스크립트와 별도 출력 디렉터리로 최종 검증을 시작했다.

`/tmp/mujoong-jev-streaming.init.gradle`:

```groovy
allprojects {
    layout.buildDirectory.set(file('/tmp/mujoong-jev-streaming-build/' + project.name))
}
```

```bash
cd backend
./gradlew --no-daemon :realtime-activity:test \
  --init-script /tmp/mujoong-jev-streaming.init.gradle \
  --tests 'kr.mujoong.realtime.session.*Test' \
  --tests 'kr.mujoong.realtime.gate.*Test' \
  --tests 'kr.mujoong.realtime.guardrail.*Test' \
  --tests 'kr.mujoong.realtime.content.*Test' \
  --tests 'kr.mujoong.realtime.startup.*Test' \
  --tests 'kr.mujoong.realtime.voice.*Test' \
  --tests '*GuardrailTurnIT' \
  -Pmujoong.test.db=docker
```

사용자의 중단 요청으로 진행 중이던 기존 넓은 검증과 별도 빌드 경로 검증을 모두 Ctrl-C로 중단했다(종료 코드 130). 별도 경로 검증은 빌드 초기 단계에서 중단되어 성공/실패 판정이 없다. 임시 파일이 사라졌다면 위 내용으로 다시 만든다.

`git diff --check`는 중단 직전 통과했다. 실제 Jev/GPT-Live 호출, 실제 기기 청취, 운영 지연 측정, 배포는 수행하지 않았다.

## 내일 이어서 할 일

1. 현재 변경분을 먼저 읽고, AI-DLC를 재개하지 않고 직접 작업한다.
2. 위 집중 검증을 최신 코드에 대해 다시 수행한다. 필요하면 처음부터 별도 빌드 경로를 사용한다.
3. 별도 빌드 경로에서 `GuardrailTurnIT`를 재검증하여 테스트 공급자 시간선 수정이 맞는지 확인한다.
4. 순서 보존, 음성 경계 분리, 전사 선행, 조각 경계 욕설, 대기열 초과, 끼어들기·종료 후 늦은 ALLOW 폐기, 생성형 연습 완료 1회 보장을 최종 확인한다.
5. 예외 경로(`onBeginFailed`, 대기 단위 검사 시작 실패 등)에서도 남은 스트리밍 응답이 적절히 무효화되는지 코드 리뷰한다.
6. 관련 세션·음성 회귀 테스트를 완료하고, 공유 빌드 산출물 변경 문제가 재현되는지 구분한다.
7. 테스트 결과에 맞게 이 기록과 계약 문서를 갱신한다. 실제 분류 품질과 지연 개선은 대역 테스트 결과와 구분해서 보고한다.

## 작업 트리와 보존 사항

변경분은 파일에 저장되어 있으며 이 작업으로 커밋·푸시하지 않았다.

새 파일:

- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/TimedOutputBuffer.java`
- `backend/realtime-activity/src/main/resources/guardrail/policy-v3.json`
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/gate/TimedOutputBufferTest.java`
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/guardrail/GuardrailPolicyV3Test.java`
- 이 진행 기록

기존부터 있던 `.aidlc-unit7-diagnostic/`, 문서의 `*:Zone.Identifier` 파일, 이전 감사 로그 변경은 정리하거나 삭제하지 않았다. 기존 작업 완료 처리로 변경된 AI-DLC 상태·intent 목록·감사 로그도 보존한다.
