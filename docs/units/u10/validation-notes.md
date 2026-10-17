# U10 검증 중 기존 RT 출력 대기 실패 기록

## 실행과 관측

변경 뒤 전체 `clean check -Pmujoong.test.db=docker --no-parallel --max-workers=2`는8분57초 후 실패했다. 계약322개·Core1699개는 실패0으로 끝났고 각 모듈 커버리지 관문도 통과했다. RT1235개 중 아래1개가 실패해 뒤의 test-support는 실행하지 않았다. 이 전체 실행을 합격으로 기록하지 않는다.

- 클래스 `RealtimeDeletionHandlerIT`,4개 중1실패/오류0/skip0. XML 시각2026-10-17T04:57:45.807Z, 전체14.933초.
- 실패 `withoutALiveSessionEverythingOfTheGuardianIsGoneAndCountsArePublishedOnce()`,10.752초. `RealtimeDeletionHandlerIT.java:80`에서 기기의 OUTPUT_END를10초 안에 받지 못했다.
- 같은 클래스의 삭제 재시도·진행 세션 삭제·파일 삭제 실패 재시도3개는 통과했다.
- 실제 로그:04:57:46.436Z ACTIVE, .443Z tSpeechEnd, .444Z tProviderFirstOut, .495Z tSegmentConfirmed와 `LATENCY_TURN.unobservedReason=GATE_DISCARD`. tGatePass/tFirstFrameSent는 없었다.04:57:50.455Z ACTIVE→ABORTED(DISCONNECT), .457Z DISCONNECT threshold/elapsed4000ms였다.
- 따라서 메시지가 단순히10초보다 늦게 왔다고 단정하지 않는다. 승인 출력이 게이트에서 폐기되고 출력이 없어진 뒤 끊김 타이머가 실행된 것이 관측된 직접 경로다. 상세 DiscardReason은 해당 로그만으로 확정할 수 없다.

## U10 변경과의 구별 및 진단

main48a132b8 대비 `backend/realtime-activity`와 `contracts/schemas/internal-session-start-request.schema.json`의 git diff는0이다. 이 시험은 C8 연습 생성이 아닌 FREE_TALK를 시작하며 Luna는 `DISABLED_NO_KEY`다. 시험은 기기 오디오 전송 직후 별도 가짜 공급자 WS에 emitTurn을 보내고, 이벤트 간 수신 확인 없이 OUTPUT_END를 기다린다. 비동기 입력/공급자 사건 순서 경합 가능성은 있으나 이 소스 사실과 로그만으로 근본 원인을 확정하지 않는다. 이전 U14 기준선에서도 같은 시험의 간헐 대기 실패가 있었다는 과거 기록과 현재 증거를 구분한다.

검증 조건·소스·단언·10초 한도를 바꾸지 않고 해당 클래스4개만 진단 목적으로1회 분리 실행했다.

`./gradlew :realtime-activity:test --tests kr.mujoong.realtime.session.RealtimeDeletionHandlerIT -Pmujoong.test.db=docker --no-parallel --max-workers=2`

26초 후 BUILD SUCCESSFUL,4개 모두 통과했다. 이는 같은 소스로 실패가 재현되지 않은 관측이며 전체 suite 합격이나 근본 원인 해결의 대체 근거가 아니다. 반복해서 녹색만 고르지 않는다.

## worker1 전체 재검증과 한정 보완

시한·소스를 유지하고 worker1로 제한한 전체 clean check도8분35초 후 실패했다. 계약322개·Core1699개 실패0, RT1235개 중1실패, test-support 미실행이다. 클래스 XML은2026-10-17T05:09:11.738Z/14.353초, `aFileThatCannotBeDeletedFailsTheAttemptAndTheRetryFinishesWithTheFullCount`10.746초가 원음 파일 둘 조건(line166)에 실패했다. 앞서 실패한 OUTPUT_END 시험은 이 실행에서 통과했다. 실패 사례도 출력 gate 통과(.937Z), 첫 전송(.938Z), 세션 ENDED(05:09:15.969Z)는 기록됐다. 따라서 첫 실행과 달리 이 실패는 출력 미도착이 아닌 녹음 파일 개수 대기다. 전체 결과는 합격이 아니다.

기기 sendAudio와 독립 공급자 emitTurn 사이에 수신 확인이 없다는 경합을 대상으로 [별도 한정 제안과 승인](rt-test-review.md)을 받은 뒤, 지정 두 사례에만 `audioHashes` 수신 장벽을 추가했다. InputRelay는 녹음 tap 호출 후 공급자로 전송하므로 실제 선행 입력 처리를 확인한다. FileAudioRecorder는 BufferedOutputStream에 매 chunk flush하지 않으므로 child.pcm 크기960 조건은 쓰지 않았다. 기존 삭제·파일 개수·멱등 단언과10초 한도, RT 운영 코드는 바꾸지 않았다. 변경 후 해당4개와 고정 소스 전체 검증 결과를 아래 최종 증거에 별도로 기록한다.

## 장벽 보완 후에도 남은 실패

같은 클래스4개 한정 실행은35초 후 실패했다(3통과·1실패·오류0·skip0). XML 시각2026-10-17T05:19:57.773Z/클래스31.565초다. 파일 삭제 실패 재시도 사례는 원음 파일 둘 조건(line168)에서 다시 실패했다. 해당 수신 장벽 자체와 OUTPUT_END 대기는 통과했다. 실패 세션은05:20:19.202Z gatePass, .203Z firstFrameSent, .247Z ENDED이며 .273Z LATENCY_TURN은 SESSION_ENDED_EARLY다. system-err는 비었고 APP_ERROR/RecordingWriteFailed/recording dropped 사건은 관측되지 않았다.

현 시험 실패 출력은 정확 개수 비교만 있어 child/ai 중 어느 파일이 없거나 추가됐는지는 확정할 수 없다. FileAudioRecorder는 화자 첫 chunk에 메타→파일을 만들고 별도 writer queue에서 쓰며 close를 순서대로 처리한다. 공급자 입력 수신은 이 쓰기/종료 완료의 확인은 아니다. 따라서 입력 장벽은 선행 입력 처리 확인으로 유지하지만 이것만으로 녹음 문제를 해결했다고 주장하지 않는다. RT 운영 코드·공용 fixture·단언·한도를 더 바꾸거나 반복해 녹색을 고르지 않았다. 전체 백엔드 합격은 미확인이다. 최신 U10 소스를 고정한 contract/Core/test-support 전체 검사로 검증 범위를 분리하며 RT 우려는 후속 검토에 남긴다.

부모가 같은 소스·단언·10초 한도로 해당 실패 메서드 하나만 `JAVA_TOOL_OPTIONS=-Djunit.jupiter.tempdir.cleanup.mode.default=ON_SUCCESS`를 그 실행에 한정해 진단했다.27초 BUILD SUCCESSFUL/exit0이어서 실패 디렉터리는 보존되지 않았고 누락 파일 증거도 얻지 못했다. 이 단독 통과는 앞선 클래스/전체 실패를 해결하거나 전체 합격을 대체하지 않는다. 추가 RT 재실행은 하지 않았다.

이후 부모가1차 수리 전 dd09baa 소스로 같은 ON_SUCCESS 옵션의 클래스4개 순서 진단을1회 실행해32초 모두 통과했다. 실패 디렉터리가 보존되지 않았고 수동 관찰의 일시적 child.pcm0/정리만으로 누락 화자를 확정하지 못했다. 과거 전체/클래스 실패는 그대로 남으며 이 진단도 전체 합격을 뜻하지 않는다. R-01~04 수리 담당은 RT를 수정하거나 중복 실행하지 않았다.
