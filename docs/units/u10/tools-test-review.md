# U11 도구 시험 수신 순서 보완안

## 관측과 원인

U10에 main 683c669를 합친 6cd526f 전체 검증에서 tools 131건 중 1건이 실패했다. 대상은 tools/tests/u11/fake-provider-wire.test.mjs의 마지막 사례다. 꼬리 채움 음성은 48000바이트여야 하나 43200바이트만 수신한 순간 검사했다. 차이는 설정된 한 조각(4800바이트)이다.

server.mjs의 emitSteps는 마지막 ws.send 직후 turn-emitted를 기록한다. 이는 송신 예약 종료이지 반대편 message 콜백 처리 완료가 아니다. 시험은 이 서버 기록만 기다린 뒤 client.received를 즉시 검사한다. 같은 파일 단독8건은 수정 없이 통과했으나 이 진단 통과로 전체 실패를 지우지 않는다.

## 정확한 수정 범위

원래 turn-emitted 대기 뒤에 아래 수신 조건을 추가한다. 기존 단언과 3000ms 대기 제한, 운영 공급자/서버 코드는 유지한다. 아직 적용하지 않은 제안이다.

```js
await waitUntil(() => {
  const turn = provider.records.find((entry) => entry.ev === "turn");
  const speech = client.received.find((entry) => entry.message.type === "session.output_transcript.delta");
  if (!turn || !speech) return false;
  const bytes = client.received
    .filter((entry) => entry.message.type === "session.output_audio.delta")
    .reduce((sum, entry) => sum + Buffer.from(entry.message.delta, "base64").length, 0);
  return bytes >= turn.outputBytes + (speech.message.start_ms + FILLER_MS) * 48;
});
```

기존 전사/음성 순서, start_ms 경계, 정확한 음성 바이트 수 단언은 그대로 실행한다. 부족한 조각은 원래 제한 안에 도착하지 않으면 실패하고, 과한 조각은 기존 정확 일치 단언에서 실패한다. 단순 sleep·시간 증가·조건 완화·시험 제외는 하지 않는다.

## 예상 영향과 검증

수정은 U11 시험 파일1개이며 U10 운영 코드나 RT 운영 코드는 바꾸지 않는다. 예상 작업은 코드 생성 단계의 한정 수리·검토와 전체 검증·CI를 포함해 약30~50분, 유료 API 비용0, 위험은 낮은 시험 동기화 변경이다. 환경 및 CI 대기 시간에 따라 더 걸릴 수 있다.

재검증은 해당8건, tools 전체131건, 전체 verify.sh 및 최종 PR CI다. 반복 실행 중 녹색 결과만 골라 이전 실패를 삭제하지 않는다. U10의 기존 R-05/R-06 수용은 이 새 수정 승인으로 바꾸거나 재승인 요청하지 않는다.
