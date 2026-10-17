# U13 제브 게이트 (u13-jev-guardrail) 구현 지시

realtime 줄의 둘째 볼트(B5)다. realtime 줄은 **U11 → U13 → U12** 순서로 한 담당자가 차례로 한다. 셋 다 `SessionExecutor` · `OutputGate`를 고치기 때문이다(`docs/units/README.md` 「U8 ~ U15 문서 동기화 볼트」). core 줄(U15 → U14 → U10)은 다른 담당자가 같은 시기에 진행한다. 파일이 겹치지 않는다.

> 이 지시서는 U11이 `main`에 병합된 뒤(PR #28 · #29) 새로 썼다. 앞 볼트 U11의 사실(선 형식 · 출력 세대 · 판정 턴)은 `docs/units/u11/design.md` · `report.md`가 정본이다.

## 시작 전 상태
- U8 · U9 · U11(PR1 · PR2)이 `main`에 합쳐졌다(PR #21 · #28 · #29, `main`의 `6a78536`).
- 브랜치: `main`에서 `unit/u13-jev-guardrail`을 딴다.
- 마이그레이션: **realtime V6**(`V6__u13_jev_guardrail.sql`, 표 `guardrail_event`). realtime은 지금 V5까지다(`V5__u11_judgment_turn.sql`). U12 = V7이 뒤를 잇는다.
- 시간 값: `mujoong.timing.guardrail.jev-budget` = 800ms는 이미 realtime `application.yml`에 있다(U8). 새 시간 값을 더하면 R-M5의 세 곳을 같은 커밋에서 고친다.
- 비밀 이름: `JEV_API_KEY`는 U8이 비밀 이름 목록 전부에 넣었다(realtime만 받음). 새 비밀을 만들면 R-M9의 목록 전부에 함께 넣는다.

## 첫 작업: 위험 확인
설계를 흔들 수 있는 가장 큰 위험은 **제브 실제 응답 형식을 아직 모른다**는 점이다(요구사항 열린 질문 3, C-DS8 · `contract-summary.md` 열린 점).
- 계정 승인 뒤 API 문서로 확정되기 전에는 `decision` · `categories` 7개 · `policyFlags` · `reasonCode` · `policyVersion`의 **내부 형식**만 고정하고, 공급자 응답 → 내부 형식 변환은 어댑터 한 곳에 가둔다(FR6.2).
- U11이 한 것처럼 실호출로 형식을 확정하는 일은 사람이 키를 넣고 직접 한다. 시험 · CI · `verify.sh`에는 넣지 않는다(R-F1). 합성 자료만 쓰고, 키를 넣기 전에 `infra/RUNBOOK.md` 단계 4의 R-F15 확인 기록을 남긴다(R-F13 · R-F15). 결과는 `[실호출-합성]`으로 따로 보고한다.
- 실호출 없이도 어댑터 변환의 **형식 가정**을 시험 하나로 못 박아 둔다. 실호출이 가정과 다르면 어댑터와 그 시험만 고친다.
- 제브가 켜진 실호출 전에도 서버 동작(차단 · 시간 초과 · 늦은 ALLOW 무효 · 원문 일치)은 가짜 제브로 모두 시험할 수 있어야 한다.

## 범위
### 이 볼트가 하는 것 (U13, FR6.1 ~ FR6.10)
1. **제브 포트 · 어댑터 · 대역 · 비활성 모드** (FR6.1 · FR6.2 · FR6.9 · FR6.10)
   - 새 포트 `GuardrailClient`를 둔다. 기존 `EvaluationProvider` · `GateDecision`과 섞지 않는다(ADR-006). 요청 출처는 `GPT_LIVE_GENERATED` 하나, 모드는 `FREE_TALK` · `PRACTICE_PEER` · `PRACTICE_COACH`.
   - 실제 어댑터: `POST https://api.typesafe.ai/v1/systemone`, 고정 모델 버전(`jev-latest` 금지), 키 `JEV_API_KEY`, 제한 시간 800ms. 주소 · 모델은 설정 기본값(도메인 값을 코드 · 문서에 쓰지 않는다: 환경 변수 이름만, R-F3).
   - 대역(1차 사전만) · 비활성 모드. 대역의 성공을 실제 검사 성공으로 표시하지 않는다(R-M11).
   - 시스템 프롬프트(C §3.3)와 분류 선택지는 버전 리소스로 두고 세션 동안 고정한다(U8 버전 리소스 규칙).
2. **게이트의 제브 단계** (FR6.3 ~ FR6.7)
   - 새 발화일 때만 `OutputGate.inspect`(구조 · 1차 사전) 뒤, `recheck` 전에 부른다. `ALLOW`일 때만 그 음성 구간을 기기로 보낸다.
   - `BLOCK` · `REVIEW` · 시간 초과 · 공급자 오류 · 잘못된 JSON · 정책 버전 불일치는 재생하지 않고, 같은 다음 행동을 1회 다시 지시한다. 또 막히면 그 장면을 정리하고 다음 단계로 간다(Q6, AC2.1.3). 그동안 기기는 '생각 중'을 유지한다. 실검사 모드에서 자동 `ALLOW`는 금지다.
   - 취소 · 중단 · 기기 연결 해제 · 출력 세대 변경 뒤에 도착한 `ALLOW`는 무효(FR6.5, AC2.1.4). 검사한 전사와 보내는 PCM은 같은 응답 · 같은 구간이어야 한다(AC2.1.5).
   - 승인 원문(승인 문구 · 세션 고정 대본)과 `PracticeSpeech.matches`의 정규화로 완전 일치하는 재생은 제브를 부르지 않는다. 출처가 불확실하면 생략을 허용하지 않는다(FR6.6, AC2.2.x).
   - 요청에는 후보 문장과 익명화한 최근 3턴만 싣는다. 식별자 · 키 · Luna 생성물 · U6 결과는 없다. 입력 맥락의 지시문은 시스템 지시 쪽에 섞이지 않는다(FR6.7, AC2.1.2 · AC2.1.6).
3. **`guardrail_event`와 계측** (FR6.8, AC2.1.7 · AC2.1.8)
   - realtime V6. 이유 코드 · 정책 버전 · 지연 · 판정만 남긴다. 전사 원문은 담지 않는다(R-F14). 비동기 기록기는 U11 `JudgmentLiveRecorder`의 관례(실행기가 DB를 기다리지 않음)를 따른다.
   - 보호자 삭제 참여자(순서 상수는 `DeletionParticipant` 목록에 맞춘다)와 `DeletionCompleted.counts`에 더할지는 Functional Design에서 정한다(C-DS2 열린 점).
   - 계측 항목은 지표 이름이 `contracts/` 표에 있어야 한다(R-M10). U11이 사용량 지표를 연결하지 않은 이유가 같다.
4. **프로필 기동 검사** (US2.3, AC2.3.1 ~ AC2.3.4)
   - demo · prod에서 제브 실검사가 꺼져 있거나 `JEV_API_KEY`가 없으면 **앱 기동이 실패**한다(새 화면 없음). `dev`(로컬 · CI · 검증 스택)에서는 대역 · 비활성 모드가 허용되고 '대역'으로 표시된다.
   - `infra/ci/image-checks.sh` · `scripts/verify/b-host.sh` · `b-stack.sh`의 demo 가드 사례가 이 규칙을 함께 확인한다.
5. **보호자 화면에서 숨김** (AC2.1.9): 제브가 막은 발화는 12 말풍선 중계 · 27 대화 기록 · PDF에 나오지 않는다. 12 중계는 `ALLOW` 뒤 재생이 시작된 구간부터 보인다.

### 이 볼트가 하지 않는 것
- U12 연습 상태 기계 · 역할 정책(되묻기 · 코치 발화의 정책 자체). US1.2 · US1.5의 끝-끝은 U12가 확인한다. 이 볼트는 `FREE_TALK` · `PRACTICE_PEER` · `PRACTICE_COACH` 모드를 받아 검사하는 길만 만든다.
- Luna 생성물(상황 · 목표 · 결과)의 검사. 그것은 제브가 아니라 Luna 자체 경로다(US2.4 · FR2.6 · FR7.7).
- GPT-Live 선 형식 변경. U11이 정한 형식 그대로 쓴다.
- 실제 아동 데이터의 외부 전송 결정(R-F15). 키를 넣는 시점은 이 볼트 밖이다.

## 정본
- 요구사항 FR6.1 ~ FR6.10 · NFR1 · NFR2 · NFR6: `aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/requirements-analysis/requirements.md`
- 스토리 US2.1 · US2.2 · US2.3 (AC2.1.1 ~ AC2.3.4): 같은 기록 `inception/user-stories/stories.md`
- ADR-006(제브는 게이트 안 한 단계, 판정 · 기존 게이트와 섞지 않음): `inception/domain-design/decisions.md`. 컴포넌트 `JevGuardrail` · `OutputGate`: `components.md`
- 계약 C-DS8(제브 가드레일) · C-DS9(승인 콘텐츠 · 버전 리소스) · 시간 값 표: `inception/contract-design/contract-summary.md`
- 구현 인계(C §3 가드레일 설계 · §8.1 회귀 시험 사례): `aidlc/spaces/default/knowledge/documents/mujungi_Luna_JEV_guardrail_implementation_handoff_v2_2026-10-15.md`
- 조원 하네스의 `guardrails/service_policy.json`과 오프라인 시험 사례 24개(`MUJUNG_SERVICE_HANDOFF_SPRING_2026-10-15.zip`): 규칙 · 시험의 **참고 입력**으로만 쓴다(ADR-007). 이 압축 파일은 라이선스(재배포 가능 여부)를 확인하기 전에는 저장소에 풀어 넣지 않는다.
- 앞 볼트: `docs/units/u11/design.md` · `report.md`(출력 세대 · 구간 봉인 · 판정 턴 보류 규칙). 제브 단계는 U11이 닫은 게이트 위에 얹는다.

## 정해 둔 값
- 제브 제한 시간 800ms(`mujoong.timing.guardrail.jev-budget`), 최근 맥락 3턴. 숫자 시간 값을 코드에 쓰지 않는다(R-F7).
- 정책 버전 · 분류 선택지는 세션 동안 고정한다.
- 계측 항목(호출 수, 제브 P50 · P95 · P99, 차단 · 시간 초과 비율, 아이 체감 대기)은 합격 기준이 없는 측정 항목이다(NFR1).
- 시험 · CI · `verify.sh`는 가짜 제브만 쓴다(R-F1). 결과는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기를 따로 적는다(R-M11).

## 다른 단위와 맞물리는 곳
1. **U11(앞)**: 출력 세대 · 구간 봉인 · 판정 턴 보류 위에 얹는다. 구간이 `segment-quiet`로 봉인된 뒤 `inspect → (제브) → recheck → send` 순서이고, 제브 대기 중에 세대가 올라가면 그 구간은 무효다. 시험의 `segment-quiet` 50ms가 CPU가 찬 CI에서 흔들린 적이 있다(U11 `RealtimeMetricsScrapeIT`, 800ms로 올림). 제브 대기가 들어가면 시험 시간 틈이 더 길어지니 같은 점을 먼저 본다.
2. **U12(뒤)**: 연습 흐름이 `announceRoleSwitch` 등으로 낸 발화가 제브에 막히면 역할을 바꾸지 않는다(AC1.5.6). 제브 우회 송신 금지는 확장점 금지 능력 목록에 이미 있다.
3. **U10 ↔ U12**는 이 볼트와 무관하다.

## AI-DLC로 진행하는 법
1. `unit/u13-jev-guardrail` 브랜치에서 아래를 입력한다.

   ```
   /aidlc --new-intent --scope refactor "U13 제브 게이트(u13-jev-guardrail) 구현. 지시 docs/units/u13/brief.md를 정본으로 한다. 인셉션 산출물은 261015-doc-sync-code-update 기록의 것을 그대로 쓴다."
   ```

2. 작업 기록 이름은 `u13-jev-guardrail`로 한다.
3. `/aidlc compose "deployment-pipeline과 deployment-execution을 빼줘"`를 입력하고 승인한다.
4. `/aidlc --depth Standard --test-strategy Standard`를 입력한다.
5. 단계: 기존 코드 파악 → Requirements Analysis → Functional Design → Code Generation → Build and Test.
   - Requirements Analysis는 FR6 · NFR2 · US2.1 · US2.2 · US2.3을 다시 묶는다('원 FR6.1'처럼 구분).
   - Functional Design의 첫 작업은 제브 요청 · 응답의 **내부 형식과 어댑터 변환 경계**, `guardrail_event` 칸, 삭제 참여 여부(C-DS2)를 코드 사실로 확인하고 정리하는 것이다. 게이트에 얹는 위치는 U11 `OutputGeneration` · 구간 봉인 코드를 읽고 정한다.
   - Functional Design 결과는 `docs/units/u13/design.md`에도 옮긴다.
   - Code Generation 계획은 이 순서로 나누고 단계마다 `feat(u13): …`로 커밋한다.
     1. V6 + `guardrail_event` + 포트 · 내부 형식 · 대역 · 비활성 모드 + 프로필 기동 검사
     2. 실제 어댑터(변환 가정 시험 포함) + 요청 구성(익명화 · 맥락 격리) + 버전 리소스
     3. 게이트 제브 단계(새 발화 / 승인 원문 분류, 차단 · 시간 초과 처리, 다시 지시 1회)
     4. 늦은 ALLOW 무효 · 전사-PCM 일치 · 보호자 화면 숨김 · 계측
   - Build and Test: `bash scripts/verify.sh --mode compose` 전체와 이 볼트의 `u13` 범위 줄 80%(`backend/coverage-scope/u13.txt`), 인프라 점검을 돌린다. 실호출 확인은 사람 몫으로 열린 항목에 남긴다.
6. 자기 기록 폴더(`aidlc/spaces/default/intents/<날짜>-u13-jev-guardrail/`)는 브랜치에 함께 커밋한다.
7. 이 PC에 `jq` · `unzip` · `shellcheck`가 필요하다(`verify.sh` 전제).

## 끝나면
- `docs/units/u13/design.md` · `report.md`를 쓰고 `docs/units/README.md` 현황표를 고친다.
- 실제 제브 응답 형식이 확정되면 `contracts/`(C-DS8)와 편차 `(U13-a)`부터 적는다.
- U12 담당 순서가 오면 `docs/units/u12/brief.md`를 새로 쓴다(U13 사실 반영).
