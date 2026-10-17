# U11 단위 보고서 — 판정 턴 기반 (첫 완성본)

> 작업 기록 `261016-u11-judgment-turn`. 설계는 `design.md`, 지시서는 `brief.md`.
> 이 문서의 시험 결과는 증거 종류를 나눠 적는다(R-M11): **테스트 대역**(가짜 공급자 · 가짜 판정기 · 루프백 Luna) · **합성 대화** · **실제 API [실호출-합성]** · **실제 기기**. 대역의 성공을 실공급자 · 실기기 성공으로 적지 않는다.

## 1. 무엇이 들어갔나

### PR1 (main 병합, #28) — 선 형식 정비 · 키 통일
- 실제 GPT-Live 형식으로 세션 시작 · 지시 · 수신 해석을 바꿨다. 서버가 보내는 type은 `session.start` · `session.input_audio.append` · `session.instructions.append` · `session.close` 넷뿐이다.
- 응답 id를 서버 **출력 세대**로 바꿨다(`OutputGeneration`). 모든 서버 발화는 지시 `append` 하나이고 세션당 직렬 큐가 순서를 지킨다.
- 새 말의 시작은 확인 사건과 전사 시간선으로 찾고(`new-speech-margin` 700ms), 입력 전사는 조각을 묶어 `input-transcript-quiet`(1500ms)로 확정한다.
- 키를 `OPENAI_API_KEY` 하나로 통일했고 realtime Luna는 키 + `mujoong.luna.enabled`로 켠다.
- 합성 말소리 입력 실호출 도구 `gptLiveTest`(격리 작업), 가짜 공급자 세 곳을 같은 형식으로 맞췄다.

### PR2 (작업 중) — 판정 턴
- `judgment` 패키지: 판정 포트 · Luna 구현 · 보류 구현 · 결과 검증 · 지시문 `prompt-v1` · `JudgmentTurn` · 판정 기록(저장소 · 비동기 기록기 · 삭제 참여자 · realtime V5).
- 실행기 연결: 출력 보류 · 전사 모으기 · 생각 중 유지 · 복원, 지시 한 번, J1 · J6 결과표, 확장 능력 `requestJudgment` · `announceRoleSwitch` · `setThinking`과 사건, 장애 · 이탈 회복.
- 판정 시한 `live-budget` 2s → 4s(계약 편차 (U11-b)).
- 공급자가 없으면 턴을 열지 않고 지금 흐름이 그대로 돈다. 다른 표에 `NO_PROVIDER` 기록 한 건만 남는다.

## 2. 시험 결과

| 증거 종류 | 내용 | 결과 |
|---|---|---|
| 테스트 대역 | realtime-activity 전체 시험 1,471개(U11 태그 시험 클래스 28개 포함, 모듈 전체 실행) | 통과, 실패 0 (7절) |
| 테스트 대역 | PR2 새 시험: 판정 포트 · 검증 · 지시문 · 설정 · 턴 단위 시험(`JudgeOutcomeValidatorTest` 8 · `LunaJudgeClientTest` 13 · `JudgmentInstructionsTest` 7 · `JudgmentConfigurationTest` 3 · `JudgmentTurnTest` 18) | 통과 |
| 테스트 대역 | 판정 기록 · 설정 · 구조: `JudgmentLiveRecorderTest` 8 · `JudgmentLiveRepositoryIT` 5 · `JudgmentLiveSchemaIT` 5 · `JudgmentSettingsTest` 5 · `JudgmentPackageArchTest` 2 | 통과 |
| 테스트 대역 | 실행기 시험: `JudgmentTurnExecutorTest` 9 · `JoinJudgmentExecutorTest` 8 · `StopJudgmentExecutorTest` 11 · `JudgmentExtensionPortTest` 9 · `JudgmentFaultExecutorTest` 7 · `JudgmentNoProviderRegressionTest` 4 | 통과 |
| 테스트 대역 | 기존 U3 · U4b · U5b 실행기 · 연습 시험 | **단언 변경 없이** 통과(Luna 없음 회귀, 3절) |
| 합성 대화 | 가짜 공급자 Node 서버를 쓰는 끝-끝 단계(`verify.sh` C안) | **아직 돌리지 않음**(Build and Test) |
| 실제 API [실호출-합성] | GPT-Live 합성 말소리 입력 3회(2026-10-17, 사용량 99 · 97 · 239초) — 선 형식 · 시간표 · 지속성 관측(`design.md` 8절) | 관측 완료. 판정 Luna 실호출은 `lunaReachTest`로 지연만 확인(약 2.6 · 2.9초, 이 값 때문에 시한을 4초로 올림) |
| 실제 기기 | — | **없음** |

커버리지(로컬 근거, `backend/coverage-scope/u11.txt` 44개 운영 파일): 모듈 전체 시험으로 재면 줄 **92.0%**, `u11` 태그 시험만으로 재면 **67.0%**. 낮은 쪽은 `SessionExecutor`(59%) · `StartSessionHandler`(0%) 같은 앞 유닛 코드가 목록에 있고 그 시험의 태그가 `u3` · `u4b` · `u8`이기 때문이다. CI가 막는 범위 하한은 U5b · U7뿐이다.

## 3. Luna 없음 회귀

키가 없거나 스위치가 꺼져 판정기를 쓸 수 없을 때 J1 불명확 · J6 CONFIRM의 보기 순서 · 세션 사건 순서 · 재생 문구 · 공급자 지시 수가 판정 장치 없는 흐름과 같다(`JudgmentNoProviderRegressionTest`). 같은 단언 없이 통과하는 기존 시험 묶음: `SessionExecutorU3Test` · `SessionExecutorReviewU3Test` · `SessionExecutorReview2U3Test` · `SessionExecutorReview3U3Test` · `SessionVoiceU3Test` · `SessionJoinU4bTest` · `SessionStopU4bTest` · `SessionSilenceU4bTest` · `TechnicalPauseU4bTest` · `EndRequestU4bTest` · `SessionSettleU4bTest` · `PracticeExtensionU4bTest` · `SessionExecutorFaultTest` · `practice` 패키지(U5b 연습 실행기) 시험 전부.

## 4. 검증하지 못한 것

- **실제 Luna 판정 품질**: 선택지 분류 정확도 · 지시문 `prompt-v1`의 효과. 대역 시험은 서버 동작만 본다. 분류 품질은 실호출 확인표(Build and Test)의 몫이다.
- **실제 GPT-Live 위의 판정 턴**: 지시 `append`가 판정 뒤에도 같은 모양으로 동작하는지, 말하는 중 지시의 꼬리 길이가 응답 길이 · 시점에 따라 어떻게 달라지는지.
- **실제 아이 음성 · 실제 기기**: 입력 전사 조각 간격, 마이크 · 재생 지연.
- `verify.sh`(끝-끝) · `image-checks` · demo 배포.

## 5. 위험

- `new-speech-margin` 700ms가 모자라면 낡은 꼬리 일부가 새어 나갈 수 있고, 지나치면 첫 낱말이 잘린다. 시간표 어긋남이 긴 세션에서 얼마나 커지는지 모른다.
- `input-transcript-quiet` 1500ms: 아이가 길게 쉬면 한 발화가 둘로 나뉠 수 있다.
- 지시문의 맞장구("음,") 준수와 "이번 한 번만" 문장 준수는 표본 1이다. 지속되는 지시가 쌓이면 이후 응답이 엉뚱해질 수 있다.
- 지시 단계 시계(진입 + 10초)가 실행기의 `ACK_MISSING` 두 번째 대기보다 먼저 만기될 수 있다. 판정 턴은 한 번 닫고 세대는 계속 돌며 늦게 온 새 말은 평소 게이트를 거친다.
- J6 장애 때 출력권 연습에 전사를 넘기는 시점이 기술 일시 정지 중일 수 있다(R-13).

## 6. Build and Test 점검표

1. **새 말 여유 700ms**: 실호출로 낡은 꼬리 누수 · 첫 낱말 잘림을 센다.
2. **입력 전사 조용함 1500ms**: 아이 발화 간격으로 확인(성인 합성이 아닌 음성).
3. **맞장구 준수**: 모든 지시 응답이 "음,"으로 시작하는지.
4. **"이번 한 번만" 문장 준수**를 n=1을 넘어 확인.
5. **연습 발화 원문 읽기**: 처음 600ms 안에 잘려도 되는지(원문 일치 게이트와 U13 제브 단계가 첫 낱말 누락을 어떻게 보는지).
6. `bash scripts/verify.sh`와 `image-checks`(PR2 병합 전 한 번).
7. `core-api` `ReportPdfTest`는 병렬 실행에서 `/tmp/tomcat.*` 디렉터리가 남아 실패할 수 있는 알려진 흔들림이다(`:core-api:check` 단독 재실행으로 확인).
8. **demo 배포 순서(키 이름 바꿈)**: PR1 이전 이미지는 demo에서 `PROVIDER_API_KEY`가 비면 기동을 거부한다. 롤백 대상(`deploy.log`의 마지막 `result=OK` SHA)이 PR1 이후 SHA뿐이 될 때까지 `/mujoong/demo/PROVIDER_API_KEY`와 `/mujoong/demo/OPENAI_API_KEY`를 같은 값으로 함께 두고, 그 뒤에 `PROVIDER_API_KEY`를 지운다. realtime Luna를 켜려면 R-F15 확인 기록(RUNBOOK 단계 4) 뒤 demo 설정의 `mujoong.luna.enabled`를 `true`로 바꾼다.
9. **합성 말소리 입력 실호출(`gptLiveTest`) 실행 방법**: 개발 PC에서 RUNBOOK 8.7 절차대로. 사람이 `OPENAI_API_KEY`를 환경 변수로 넣고 `GPT_LIVE_INPUT_PCM`에 24kHz 16bit 모노 원시 PCM을 지정한 뒤 `cd backend && ./gradlew :realtime-activity:gptLiveTest`. `check` · CI · `verify.sh`는 이 작업을 돌리지 않는다. 키가 없으면 건너뜀이고 통과로 보지 않는다. 결과는 `[실호출-합성]`으로 따로 보고한다.

## 7. 최종 확인 (Step 29)

| 명령 | 결과 |
|---|---|
| `cd backend && ./gradlew clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker --continue` 첫 실행: realtime-activity · contract-model · test-support 통과, core-api 3건 실패 — `FlywayConfIT` · `SchemaPrivilegeIT`는 realtime 마이그레이션 개수 단언(V4 → V5, 표 61 → 62)을 새 사실로 갱신했고(낮추지 않음), `ReportPdfTest`는 알려진 병렬 `/tmp/tomcat.*` 흔들림이다. `:core-api:check` 단독 재실행 통과 |
| `cd tools && npx vitest run` 12개 파일 131개 통과 |
| `bash infra/bin/infra-validate`(GNU date 우선 PATH) | 8건 통과(마이그레이션 expand 검사 포함) |
| `verify.sh` · `image-checks` | 돌리지 않음(Build and Test) |
