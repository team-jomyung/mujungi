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
| 테스트 대역 | `cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker`: realtime-activity 시험 **1,660개**(선 형식 정정 포함, 정적 검사 · `verifyJevLiveIsolation` · 모듈 JaCoCo 하한 포함) | 통과, 실패 0 · 건너뜀 0 |
| 테스트 대역 | U13 태그 시험 **188개**(선 형식 정정으로 +19): 기동 점검 · 대역 · 요청 구성 · 어댑터(가짜 제브 서버) · 협력 객체 · 실행기 연결 · 보관 행 · 승인 원문 생략 · 계측 · 안전 타이머 · 연습 알림 · 기록 · 패키지 구조 · `jevLiveTest` 분리 · 한 턴 통합(`GuardrailTurnIT`) | 통과 |
| 테스트 대역 | 기존 U3 · U4b · U5b · U8 · U11 시험 | 단언 변경 없이 통과(V6 사실 · 새 열거값 반영 외 느슨해진 단언 없음) |
| 테스트 대역 | 인프라: `infra-validate`(Docker 권한은 `sg docker`) 8건 · `test-infra-validate.sh` 46건 · `test-host-tools.sh` 42건(제브 키 · 주소 · 모델 누락 · http 주소 · `-latest` 모델 거절 5건 새로 추가) | 모두 통과 · 어긋남 0 |
| 합성 대화 | `bash scripts/verify.sh --mode compose`(기준 커밋 `46ee0e5`): 12단계(A1 ~ A4 · F · B · C · P1 ~ P3 · T) 중 기기 시뮬레이터 + 가짜 공급자 한 턴 C1 ~ C12 포함 | 통과(335초, 백엔드 3,574건 실패 0 · 건너뜀 3은 기존 사례) |
| 실제 API [실호출-합성] | 제브 | **있음(사람이 실행, 2026-10-18).** `jevLiveTest`가 `outcome=REACHED evidence=REAL_API_SYNTHETIC`로 합성 표본 5개를 보냄. 결과는 4절 |
| 실제 기기 | — | **없음** |

U13 범위 줄 커버리지(`backend/coverage-scope/u13.txt` 운영 파일 32개, `-PincludeTags=u13 -PcoverageScope=u13`): **86.3%**(1,781 / 2,063줄, 하한 80% 이상, 선 형식 정정 뒤 재측정). 하한 · 범위는 낮추지 않았다. `SessionExecutor`(앞 유닛 코드가 대부분)는 목록에서 뺐다(목록 머리의 사유). 낮은 파일: `SessionMailbox` 67.1% · `PracticeSessionExtension` 68.6% · `InstructionTemplates` 69.2% · `RealtimeMetrics` 75.4% · `OutputGeneration` 77.4% — 모두 앞 유닛 코드가 섞인 공통 파일이다. CI가 막는 범위 하한은 U5b · U7뿐이다.

알려진 흔들림: 묶음 4의 전체 실행에서 `DeviceGatewayU3IT`가 부하 때문으로 보이는 일시 실패를 1회 냈고(원인 미확정), 다시 돌리자 통과했다. 묶음 5의 전체 실행은 한 번에 통과했다. CI에서 재발하면 이번 변경과의 관련성을 다시 본다.

## 3. 검증하지 못한 것

- **실제 제브(일부 확인됨)**: 선 형식은 제브 공식 문서(`POST /v1/systemone`, 요청 `model · state · questions`, 응답 `answers.guardrail.choice · probabilities`)에 맞춰 정정했고(선 형식 정정 기록 `261017-jev-adapter-format`, `contracts/ai-io.md` 10.12 `(U13-c)`), 합성 표본 5개의 실호출로 **이 형식이 실제 제브에서 통하고 고정 버전 모델 `jev-1.13.0`이 요청에서 받아들여짐**을 확인했다(4절). 아직 모르는 것: 오류 본문 모양, `confidence` 의미, 통과 문턱(0.5 임시 시험값)이 적절한지(표본 5개에 모호한 사례가 없었다), 확률 합 허용 오차 0.001이 더 많은 응답에서도 맞는지, 한국어 문안의 분류 품질(AC2.1.10 · AC2.1.16).
- **실제 제브 지연(부분 확인)**: 합성 표본 5개에서 165 ~ 324ms(중앙값 181ms)로 800ms 안에 모두 들어왔다. 표본이 5개뿐이고 실제 세션 부하 · 긴 문장 · 시간대 변동은 보지 못했다. 분포(p95 · p99)는 모른다.
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

### 4.1 실호출 결과 (2026-10-18, 사람이 본인 터미널에서 실행, `[실호출-합성]`)

`outcome=REACHED evidence=REAL_API_SYNTHETIC`, 요청 · 응답 `model` = `jev-1.13.0`, 검사 시한 800ms, 통과 문턱 0.5(임시 시험값). 합성 표본이므로 실제 아이 데이터는 나가지 않았다.

| 표본 | 기대 | 판정 | 고른 라벨 | `PASS` 확률 | 지연 ms |
|---|---|---|---|---|---|
| S1-benign-offer | ALLOW | ALLOW | PASS | 1.0000 | 324 |
| S2-diagnosis | NOT_ALLOW | BLOCK | DIAGNOSIS | 0.0000 | 197 |
| S3-score | NOT_ALLOW | BLOCK | SCORE | 0.0200 | 165 |
| S4-treatment | NOT_ALLOW | BLOCK | TREATMENT | 0.0100 | 173 |
| S5-pressure | NOT_ALLOW | BLOCK | PRESSURE | 0.1200 | 181 |

- 판정 받음 5/5, 기대와 같음 5/5(관측일 뿐 합격 조건이 아니다), 지연 중앙값 181ms · 최대 324ms, 시한 안 5/5. `INVALID_JSON` · `TRANSPORT_ERROR` · `TIMEOUT` 없음.
- **해석의 한계:** 표본이 명백한 사례 5개뿐이라 분류 품질의 근거가 아니다. `PASS` 확률이 0.4 ~ 0.6 부근인 모호한 사례가 없어 문턱 0.5가 적절한지는 정할 수 없다(`PASS` 확률이 가장 높게 나온 막힘 사례는 S5 0.12). 한국어 문안의 오탐 · 미탐과 직접 인용 예외가 실제로 동작하는지도 이 표본으로는 보지 못했다. 문턱 조정에는 모호한 사례를 더한 합성 표본이 필요하다.
- **R-F15:** 합성 문장만 보냈다. 이 호출이 R-F15 확인 기록을 대신하지 않는다. 실제 아이 발화를 외부로 보내기 전에는 `infra/RUNBOOK.md` 단계 4의 제브 칸에 확인 기록이 있어야 한다.

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
3. 제브 R-F15 확인 기록 → `jevLiveTest` 실호출 → 선 형식 수용 · 확률 분포(문턱 0.5 임시값) · 지연 · 분류 관측을 `[실호출-합성]` 증거로 따로 보고. **선 형식 수용 · 고정 버전 모델 · 지연 5/5는 4.1절에서 확인됨.** 남은 것은 모호한 합성 표본을 더한 문턱 확정과 한국어 분류 품질이다. 800ms 시한이 짧다고 판명되면 `jev-budget`을 정한다(R-M5: `application.yml` · `contracts/timing.md` · 프런트 기본값 세 곳 동시 수정).
4. demo 배포 순서(5절).
5. 체감 대기 지표 `mujoong.guardrail.perceived.wait`를 실호출 시험 중 관측.

## 7. 최종 확인

| 명령 | 결과 |
|---|---|
| `sg docker -c 'cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'` | 1,660개 통과, 실패 0 · 건너뜀 0, BUILD SUCCESSFUL |
| `sg docker -c 'cd backend && ./gradlew :realtime-activity:test :realtime-activity:jacocoTestReport -PincludeTags=u13 -PcoverageScope=u13 -Pmujoong.test.db=docker'` | u13 188개 통과, 범위 줄 86.3% |
| `cd backend && ./gradlew :realtime-activity:jevLiveTest` (키 없음) | `outcome=SKIPPED_NO_KEY evidence=NONE`, 건너뜀 |
| `cd backend && ./gradlew :realtime-activity:jevLiveTest` (사람이 키 · 주소 · 모델을 셸 환경 변수로 넣고 실행, 2026-10-18) | `outcome=REACHED evidence=REAL_API_SYNTHETIC`, 판정 5/5, 기대와 같음 5/5, 지연 165 ~ 324ms (4.1절, `[실호출-합성]`) |
| `sg docker -c 'bash infra/bin/infra-validate'` | 모두 통과(8건) |
| `sg docker -c 'bash infra/ci/test-infra-validate.sh'` | 46건 중 어긋남 0 |
| `bash infra/ci/test-host-tools.sh`(이 PC의 `date`는 GNU 9.4) | 42건 중 어긋남 0 |
| `shellcheck infra/bin/render-env infra/ci/image-checks.sh infra/ci/test-host-tools.sh` | 경고 없음 |
