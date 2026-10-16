# 순서 근거와 위험

> **볼트**는 단위 하나를 설계 · 코드 · 시험까지 끝내 실제로 돌아가는 결과를 내는 작업 한 바퀴다.
> 근거: `bolt-plan.md`, `../units-generation/unit-of-work-dependency.md`(의존 그래프), `../units-generation/unit-of-work.md`, `../contract-design/contract-summary.md`(열린 질문 · 받아들인 위험), `../domain-design/decisions.md`(ADR-007), `../requirements-analysis/requirements.md`(열린 항목), `../user-stories/stories.md`, 질문 · 답 `delivery-planning-questions.md`(Q1-A 위험 먼저).

## 쓴 원칙

**위험 먼저**(Boehm의 나선형 모형: 불확실성이 가장 큰 일을 앞에 두어, 그 결과에 기대는 일이 들어가기 전에 판단을 바로잡는다)를 골랐다(Q1-A). 점수로 매기는 방식(WSJF, Reinertsen · SAFe: 가치 + 시급성 + 위험 감소를 크기로 나눈 점수)도 검토했다. 하지만 공통 기반 두 볼트가 모든 볼트의 앞에 오는 의존 구조라 결과가 같아서 점수표는 만들지 않았다.

1. **공통 기반 먼저(B1 U8 → B2 U9):** 의존 그래프상 모든 단위가 U8에, 대부분이 U9에 기댄다. 이 둘이 늦으면 모두가 기다린다. 작게 끝내 PR로 빨리 합친다.
2. **설계를 뒤집을 수 있는 위험을 그다음(B3 U11, 첫 작업 = GPT-Live 실호출 확인):** ADR-007은 서버가 음성을 중계한다는 전제 위에 서 있다. GPT-Live가 서버 쪽 음성 전송을 지원하지 않으면 판정 턴 보류(FR5) · 제브 차단(FR6) · 기기 화면 구조가 모두 바뀐다. 그래서 다른 realtime 일보다 먼저 확인한다.
3. **core 줄은 병렬(B4 U15 → B6 U14 → B7 U10):** realtime과 파일이 겹치지 않는다. core 안의 의존은 직렬이다(U10은 U14 · U15에, U14는 U15에 기댐).
4. **제브를 새 연습 흐름보다 앞에(B5 U13 → B8 U12):** 의존 그래프에는 이 순서가 없다. 그래도 제브 실제 응답 형식이 두 번째로 큰 외부 불확실성이다. 또 US1.2 · US1.5(친구 역할 되묻기 · 코치 발화)는 제브가 있어야 소리가 들린다. U12와 U13은 같은 파일을 고치므로 한 담당자가 차례로 하는데, U12는 어차피 core 줄의 U10을 기다려야 한다.

## 의존 그래프와 다른 곳

| 다른 곳 | 그래프 | 계획 | 근거 |
|---|---|---|---|
| B5(U13) → B8(U12) | U12와 U13 사이 간선 없음 | U13 먼저 | 같은 파일(`SessionExecutor` · `OutputGate`) 충돌을 피하고, US1.2 · US1.5의 끝-끝 확인이 U13 뒤에야 된다. U12는 U10을 기다리므로 늦어지는 비용이 없다 |

나머지 순서는 모두 의존 그래프의 위상 순서 안에 있다.

## 위험 목록

| # | 위험 | 가능성 | 영향 | 대응 | 걸리는 볼트 |
|---|---|---|---|---|---|
| R1 | GPT-Live가 서버 쪽 음성 전송을 지원하지 않는다(조원 예제는 WebRTC만 씀) | 중 | 치명 | B3 첫 작업으로 실호출 확인(FR12.2). 안 되면 **B3을 멈추고** 사람과 설계를 다시 정한다(ADR-007 재검토). B4 · B6 · B7(core 줄)은 영향 없이 계속한다 | B3 · B5 · B8 |
| R2 | 제브 실제 응답 형식이 계약 C-DS8 내부 형식과 크게 다르다 | 중 | 중 | 내부 형식은 고정하고 어댑터에서만 바꾼다. 실호출로 확정(키 · 문서 준비됨, Q5) | B5 |
| R3 | Contract Design에서 받아들인 Major 지적 5개(기존 계약 파일 · 코드와 충돌)가 Code Generation에서 계약 대조 시험 실패로 터진다 | 높음 | 중 | 해당 단위 Functional Design 첫 작업으로 실제 `contracts/` 파일과 대조해 바로잡는다(B1: R-02 · R-03 · R-07, B3: R-05(a) · R-06, B6: R-01 · R-09, B7: R-04, B2: R-05(b) · R-08) | B1 · B2 · B3 · B6 · B7 |
| R4 | realtime 큰 파일(`SessionExecutor` 3,388줄)을 고치다 기존 U4b · U5b 동작이 깨진다 | 중 | 높음 | 한 담당자가 차례로 고친다. 기존 시험 · `verify.sh` 끝-끝을 볼트마다 돌린다. 레거시 연습 경로는 떼지 않고 새 흐름과 공존시킨다(ADR-001) | B3 · B5 · B8 |
| R5 | 15초 상황 생성 예산(생성 + 별도 자체 검토 + 서버 검사 + 재생성)을 실제로 넘는다 | 중 | 중 | 실측해 넘으면 대안(미리 생성 · 캐시)을 보고한다(FR2.5). 예산은 늘리지 않는다 | B7 |
| R6 | 마이그레이션 병합 순서가 꼬인다(Flyway `outOfOrder` 꺼짐) | 낮음 | 높음 | 번호를 미리 나눠 주고(`bolt-plan.md`), 같은 앱은 표 순서대로만 병합한다 | B3 ~ B8 |
| R7 | 공통 기반(B1 · B2)이 늦어져 두 줄이 모두 기다린다 | 중 | 높음 | 두 볼트를 작게 유지하고 이 PC에서 바로 시작한다. 다른 담당자는 그동안 지시서 · Functional Design을 먼저 한다 | 전체 |
| R8 | 아동 데이터를 외부 모델로 보내는 조건(약관 · 보호자 동의, R-F15)을 확인하기 전에 실호출을 한다 | 낮음 | 치명 | 실호출은 합성 자료로만 한다. 각 외부 AI 경계에 R-F15 가드를 둔다(Contract Design R-08, B2에서 처리) | B2 · B3 · B5 · B7 |

## 중단 조건

- **R1이 실제로 일어나면** B3 · B5 · B8(realtime 줄)을 멈추고, 사람에게 설계 재검토(서버 중계 대신 다른 구조)를 묻는다. core 줄은 계속한다.
- 공통 계약(B1)이 기존 대조 시험을 고치지 않고는 통과하지 못하면, 시험을 고치지 않고 계약 쪽을 고친다(R-F2). 그래도 안 되면 사람에게 묻는다.

## Sources

- `bolt-plan.md`, `team-allocation.md`, `external-dependency-map.md`(같은 폴더)
- `../units-generation/unit-of-work-dependency.md`, `../units-generation/unit-of-work.md`
- `../contract-design/contract-summary.md`(4절 열린 질문, 검토 지적 R-01 ~ R-09)
- `../domain-design/decisions.md`(ADR-001 · ADR-004 · ADR-007)
- `../requirements-analysis/requirements.md`(열린 항목 1 ~ 6), `../user-stories/stories.md`('의존과 순서')
- `delivery-planning-questions.md`

## Assumptions & Open Questions

- 가능성 · 영향 등급은 이 단계의 판단이다. 실제 일정과 인원이 정해지면 지시서에서 다시 본다.
