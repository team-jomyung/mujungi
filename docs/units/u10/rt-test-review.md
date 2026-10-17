# 기존 RT 삭제 시험의 입력 순서 보완 제안

## 관측

U10 변경 뒤 전체 백엔드 검증 두 번에서 기존 `RealtimeDeletionHandlerIT`가 각각 OUTPUT_END 대기, 원음 파일 2개 대기에서 실패했다. 해당 클래스만 같은 조건으로 분리 실행했을 때는 4개 모두 통과했다. 상세 로그와 한계는 `validation-notes.md`에 남긴다. RT 운영 코드와 이 시험은 main 대비 변경하지 않았다.

두 사례는 `device.sendAudio(new byte[960])` 직후 별도 연결의 `env.provider.emitTurn(...)`을 실행한다. 전자는 비동기 기기 전송이며 후자와의 처리 순서가 보장되지 않는다.

## 제안하는 한정 변경

파일: `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/RealtimeDeletionHandlerIT.java`

다음 두 시험의 `sendAudio` 바로 뒤, `emitTurn` 앞에 아래 한 줄씩을 추가한다.

- `withoutALiveSessionEverythingOfTheGuardianIsGoneAndCountsArePublishedOnce`
- `aFileThatCannotBeDeletedFailsTheAttemptAndTheRetryFinishesWithTheFullCount`

```java
awaitTrue(() -> !env.provider.audioHashes().isEmpty(), "아이 오디오 공급자 수신");
```

기존 `awaitTrue`의 10초 한도를 그대로 쓴다. 기존 삭제 결과·정확한 파일 수·재시도·멱등 단언과 시한을 바꾸거나 제거하지 않는다. RT 운영 코드, 공용 가짜 공급자 및 다른 시험은 바꾸지 않는다.

`InputRelay.send`는 `AudioTap.childFrame`을 먼저 호출하고 그 뒤 공급자로 입력을 전송한다. `FakeProviderWsServer.audioHashes()`는 실제 `session.input_audio.append`를 받은 경우에만 추가된다. 따라서 이 조건은 파일 버퍼의 flush 여부에 기대지 않고 선행 입력이 공급자까지 전달되었음을 확인한다. 근본 원인이 이것만이라고 확정하지 않으며, 변경 뒤 해당 클래스와 전체 검증으로 판단한다.

## 승인 범위

U10 지시서의 다른 단위 시험 변경 제한에 대한 별도 한정 예외 제안이다. 공통 시험 4파일에 대한 기존 승인을 RT에 확대 해석하지 않는다. 승인 전에는 적용하지 않는다.

[Answer]: RT 시험 입력 순서 보완 허용
