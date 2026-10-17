# U13 단위 보고서 — 제브 게이트 (첫 완성본)

> 작업 기록 `261017-u13-jev-guardrail`. 설계는 `design.md`, 지시서는 `brief.md`.
> 이 문서의 시험 결과는 증거 종류를 나눠 적는다(R-M11): **테스트 대역**(가짜 제브 · 가짜 공급자 · 루프백 서버) · **합성 대화** · **실제 API [실호출-합성]** · **실제 기기**. 대역의 성공을 실공급자 · 실기기 성공으로 적지 않는다.
> **이 단위에서 실제 제브는 한 번도 부르지 않았다.** 키를 넣지 않았고(R-F13 · R-F15), 시험 · CI · 검증은 가짜만 쓴다(R-F1).

## 1. 무엇이 들어갔나

- **제브 포트와 실제 어댑터**: `guardrail` 패키지(포트 · 대역 · 비활성 구현 · 요청 구성 · 호스트 · 모델 규칙 · `JevGuardrailClient`). 호출 1회 · 재시도 없음 · `jev-budget` 800ms · 허용 호스트 상수 · 키는 호출 때마다 환경에서 읽음.
- **게이트 단계**: `OutputGate`를 바꾸지 않고 실행기 쪽 협력 객체 `GuardrailCoordinator`가 `inspect`와 `recheck` 사이에 비동기로 이어 붙인다. 유효한 `ALLOW`만 기기로 나가고, 막히면 한 바이트도 나가지 않으며 턴당 한 번 재지시한다. 대기열 1칸, 늦은 `ALLOW` 무효 판정, 검사 안전 타이머(`GUARDRAIL_CHECK`).
- **승인 원문 생략**: 레지스트리 · 연습 승인 발화와 원문이 같으면 제브를 부르지 않고 `APPROVED_VERBATIM_SKIPPED` 한 행을 남긴다.
- **기록 · 삭제 · 계측**: realtime V6 `guardrail_event`(전사 원문 칸 없음), 비동기 기록기, 보호자 삭제 참여자(185), `mujoong.guardrail.*` 지표 5종(`design.md` 5절).
- **연습 확장점 알림**: `GuardrailBlocked` 이벤트(정의 + 대역 시험까지, 운영 호출자는 U12).
- **모드 · 기동 점검**: `mujoong.guardrail.jev.mode`(REAL · STUB · OFF). demo · prod는 REAL + 키 + 허용 호스트 + 고정 버전 모델이 아니면 기동을 거부한다.
- **환경 · 빌드**: `render-env`가 `JEV_API_KEY` · `JEV_BASE_URL` · `JEV_MODEL`을 demo 필수로 받아 `realtime.env`에만 쓴다. `image-checks.sh` ④의 demo 더미 기동에 실행 때 만든 가짜 키 · 허용 호스트 형식의 주소 · 가짜 고정 버전 모델을 쓰고, 제브 키가 없으면 기동을 거부하는지도 확인한다. `backend/coverage-scope/u13.txt`.
- **실호출 준비**: `jevLiveTest`(`:realtime-activity`). 기본 `test` · `check` · CI · `verify.sh`에 안 들어가고(루트 `verifyJevLiveIsolation` + `JevLiveTaskIsolationTest`), 키는 셸 env로만 읽는다. `infra/RUNBOOK.md` 8.8.

## 2. 시험 결과

| 증거 종류 | 내용 | 결과 |
|---|---|---|
| 테스트 대역 | `cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker`: realtime-activity 시험 **1,641개**(클래스 186개, 정적 검사 · `verifyJevLiveIsolation` · 모듈 JaCoCo 하한 포함) | 통과, 실패 0 · 건너뜀 0 |
| 테스트 대역 | U13 태그 시험 **169개**(클래스 19개): 기동 점검 · 대역 · 요청 구성 · 어댑터(가짜 제브 서버) · 협력 객체 · 실행기 연결 · 보관 행 · 승인 원문 생략 · 계측 · 안전 타이머 · 연습 알림 · 기록 · 패키지 구조 · `jevLiveTest` 분리 · 한 턴 통합(`GuardrailTurnIT`) | 통과 |
| 테스트 대역 | 기존 U3 · U4b · U5b · U8 · U11 시험 | 단언 변경 없이 통과(V6 사실 · 새 열거값 반영 외 느슨해진 단언 없음) |
| 테스트 대역 | 인프라: `infra-validate`(Docker 권한은 `sg docker`) 8건 · `test-infra-validate.sh` 46건 · `test-host-tools.sh` 42건(제브 키 · 주소 · 모델 누락 · http 주소 · `-latest` 모델 거절 5건 새로 추가) | 모두 통과 · 어긋남 0 |
| 합성 대화 | `verify.sh` C안(기기 시뮬레이터 + 가짜 공급자 한 턴) | **돌리지 않음**(Build and Test에서 돌린다) |
| 실제 API [실호출-합성] | 제브 | **없음.** `jevLiveTest`를 키 없이 돌려 `SKIPPED_NO_KEY`(호출 안 함, 통과 아님)까지만 확인했다 |
| 실제 기기 | — | **없음** |

U13 범위 줄 커버리지(`backend/coverage-scope/u13.txt` 운영 파일 31개, `-PincludeTags=u13 -PcoverageScope=u13`): **86.1%**(1,727 / 2,006줄, 하한 80% 이상). 하한 · 범위는 낮추지 않았다. `SessionExecutor`(앞 유닛 코드가 대부분)는 목록에서 뺐다(목록 머리의 사유). 낮은 파일: `SessionMailbox` 67.1% · `PracticeSessionExtension` 68.6% · `InstructionTemplates` 69.2% · `RealtimeMetrics` 75.4% · `OutputGeneration` 77.4% — 모두 앞 유닛 코드가 섞인 공통 파일이다. CI가 막는 범위 하한은 U5b · U7뿐이다.

알려진 흔들림: 묶음 4의 전체 실행에서 `DeviceGatewayU3IT`가 부하 때문으로 보이는 일시 실패를 1회 냈고(원인 미확정), 다시 돌리자 통과했다. 묶음 5의 전체 실행은 한 번에 통과했다. CI에서 재발하면 이번 변경과의 관련성을 다시 본다.

## 3. 검증하지 못한 것

- **실제 제브**: 선 형식은 제브 공식 문서(`POST /v1/systemone`, 요청 `model · state · questions`, 응답 `answers.guardrail.choice · probabilities`)에 맞춰 정정했다(선 형식 정정 기록 `261017-jev-adapter-format`, `contracts/ai-io.md` 10.12 `(U13-c)`). `JevGuardrailClientTest`는 가짜 제브로 이 형식을 못 박을 뿐(테스트 대역 증거) 실제 제브가 이렇게 받고 답하는지는 모른다. 요청 `model`에 고정 버전 이름이 받아들여지는지(공식 예시는 `-latest`), 오류 본문 모양, 확률 합 허용 오차(0.001 임시값), 통과 문턱(0.5 임시 시험값), 한국어 문안의 분류 품질(AC2.1.10 · AC2.1.16)도 실호출 전에는 모른다.
- **실제 제브 지연**: 검사 상한 800ms가 공급자 지연보다 긴지 모른다. 짧으면 모든 검사가 `TIMEOUT` 막힘이 되어 아이가 '생각 중'에서 재지시를 받는다.
- **실제 GPT-Live 위의 재지시**: 같은 다음 행동 지시 또는 `RETRY_FREE_TURN`을 보낸 뒤 새 말이 시한 안에 시작하는지.
- **실제 아이 음성 · 실제 기기**: 체감 대기(말 끝 → 첫 소리)에 제브 대기가 얼마나 더해지는지.
- `verify.sh`(끝-끝) · `image-checks.sh` ④(이미지를 만들어 돌려야 한다, 이번에는 `bash -n` · shellcheck만 확인) · demo 배포.

## 4. 사람 몫 열린 항목: 제브 실호출

`jevLiveTest`는 준비돼 있고 실행은 사람이 한다(`infra/RUNBOOK.md` 8.8). **키를 넣기 전에 RUNBOOK 단계 4의 '제브 R-F15 확인' 기록을 먼저 남긴다 — 그 기록은 아직 비어 있다.**

```bash
cd backend
read -r -s -p 'JEV_API_KEY: ' JEV_API_KEY && export JEV_API_KEY && echo
export JEV_BASE_URL='https://<허용 호스트>' JEV_MODEL='<고정 버전 모델 이름>'
./gradlew :realtime-activity:jevLiveTest
unset JEV_API_KEY JEV_BASE_URL JEV_MODEL
cat realtime-activity/build/jev-live/report.txt
```

합성 표본 5개(허용 기대 1 · 막힘 기대 4)를 운영 어댑터로 한 번씩 보내고, 보고서에는 표본 이름 · 판정 · 실패 종류 · 고른 라벨 · `PASS` 확률 · 사유 코드 · 지연 · 응답 `model`과 임시 문턱 표시만 쓴다(후보 문장 원문 · 키 · 주소 없음, R-F14). 결과는 `[실호출-합성]`으로 따로 적는다. `INVALID_JSON`이 나오면 응답 검증이 실제 응답과 다른 것이므로 어댑터의 변환 한 곳(`convert`)과 그 시험만 고친다.

## 5. 위험

- **demo는 키 · 주소 · 모델이 없으면 기동을 거부한다.** 이 변경이 든 SHA를 먼저 배포하면 realtime이 뜨지 않고 배포가 실패한다. **배포 전 순서**: ① 제브 R-F15 확인 기록 → ② Parameter Store `/mujoong/demo/JEV_API_KEY` · `JEV_BASE_URL` · `JEV_MODEL` 입력 → ③ 그 뒤에 이 SHA 배포. `render-env`도 셋 중 하나가 없으면 env 파일을 바꾸지 않고 실패한다(구 버전 유지). 롤백은 RUNBOOK 6.4.
- **실제 제브 키를 넣으면 아이 발화 후보 문장과 익명 최근 맥락 3턴이 외부 공급자로 나간다.** 코드가 막지 않으므로 R-F15 확인이 유일한 방어선이다.
- **검사 안전 타이머**는 제브 클라이언트가 포트 규약을 어겨 영영 완료하지 않을 때만 쓰인다(`jev-budget` 두 배).
- `SessionExecutor`(약 3,400줄)는 U12가 이어서 고친다. 호출 지점 8곳만 더했고 상태는 협력 객체에 있다. 병합 순서는 U13 → U12.
- **팀 규칙 낡음 알림**: `team.md`의 "다음 번호는 core V8 · realtime V5"는 낡았다. realtime은 지금 V6(U13)까지 있고 다음은 **V7**이다.
- `image-checks.sh` ④는 이 변경(제브 환경 변수 3개 · 키 누락 거부 확인)을 처음 돌리는 곳이 Build and Test다.

## 6. Build and Test 점검표

1. `bash scripts/verify.sh`(PR 전 한 번, 합성 대화 증거).
2. `bash infra/ci/image-checks.sh`(이미지 ④: demo 더미 기동 + 제브 키 없는 realtime이 기동 거부로 끝나는지).
3. 제브 R-F15 확인 기록 → `jevLiveTest` 실호출 → 선 형식 수용 · 확률 분포(문턱 0.5 임시값) · 지연 · 분류 관측을 `[실호출-합성]` 증거로 따로 보고. 800ms 시한이 짧으면 `jev-budget`을 정한다(R-M5: `application.yml` · `contracts/timing.md` · 프런트 기본값 세 곳 동시 수정).
4. demo 배포 순서(5절).
5. 체감 대기 지표 `mujoong.guardrail.perceived.wait`를 실호출 시험 중 관측.

## 7. 최종 확인

| 명령 | 결과 |
|---|---|
| `sg docker -c 'cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'` | 1,641개 통과, 실패 0 · 건너뜀 0, BUILD SUCCESSFUL |
| `sg docker -c 'cd backend && ./gradlew :realtime-activity:test :realtime-activity:jacocoTestReport -PincludeTags=u13 -PcoverageScope=u13 -Pmujoong.test.db=docker'` | u13 169개 통과, 범위 줄 86.1% |
| `cd backend && ./gradlew :realtime-activity:jevLiveTest` (키 없음) | `outcome=SKIPPED_NO_KEY evidence=NONE`, 건너뜀 |
| `sg docker -c 'bash infra/bin/infra-validate'` | 모두 통과(8건) |
| `sg docker -c 'bash infra/ci/test-infra-validate.sh'` | 46건 중 어긋남 0 |
| `bash infra/ci/test-host-tools.sh`(이 PC의 `date`는 GNU 9.4) | 42건 중 어긋남 0 |
| `shellcheck infra/bin/render-env infra/ci/image-checks.sh infra/ci/test-host-tools.sh` | 경고 없음 |
