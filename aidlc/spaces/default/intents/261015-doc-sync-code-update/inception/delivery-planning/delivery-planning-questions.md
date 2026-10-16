# Delivery Planning 질문지

> 근거: `../units-generation/unit-of-work.md` · `unit-of-work-dependency.md` · `unit-of-work-story-map.md`(단위 U8~U15), `../contract-design/contract-summary.md`(계약 15개 · 열린 질문 7개 · 받아들인 위험 R-01~R-09), `../requirements-analysis/requirements.md`, `../user-stories/stories.md`, `../domain-design/components.md`, `../refined-mockups/mockups.md`, 팀 규칙 `team.md`, 팀 방식 문서 `docs/units/README.md`(유닛마다 지시서 · 담당자 PC별 작업 기록).
> 이 단계는 **볼트**(한 번의 구현 묶음: 단위 하나 이상을 설계 · 코드 · 시험까지 끝내 실제로 돌아가는 결과를 내는 작업 한 바퀴)의 순서와 담당을 정한다. 단위 사이 의존(무엇이 무엇에 기대는지)은 앞 단계가 정했고, 여기서는 그 안에서 무엇을 먼저 할지를 정한다.
> 사람이 골라야 하는 것만 개별 질문으로 묻고, 팀 규칙 · 앞 단계 사실로 정해지는 것은 맨 아래 가정 묶음으로 확인받는다.

## Q1. 무엇부터 만들까 (순서 전략)

의존 그래프상 U8(계약) → U9(Luna 호출부)는 모두가 기대므로 맨 앞이다. 그 뒤로 realtime 줄(U11 판정 턴 → U12 새 연습 흐름 · U13 제브 게이트)과 core 줄(U15 그래프 → U14 목표 · 결과 → U10 상황 생성)이 갈린다. 가장 큰 불확실성은 ① GPT-Live가 서버 쪽 음성 전송을 지원하는지(FR12.2, ADR-007), ② 제브 실제 응답 형식, ③ realtime의 큰 파일(`SessionExecutor` · `OutputGate`)을 세 단위가 고치는 충돌이다.

A. 위험 먼저 — U8 · U9 다음에 realtime 줄의 U11(판정 턴)을 가장 먼저 하고, 그 첫 작업으로 GPT-Live 실호출 확인(FR12.2)을 짧게 당겨 서버 중계가 되는지부터 본다. core 줄은 병렬로 진행한다 — 장점: 설계 전체를 뒤집을 수 있는 위험(ADR-007)을 가장 먼저 확인한다 / 단점: 실호출에 키 · 계정 준비가 먼저 필요하다 (추천)
B. 가치 먼저 — 보호자가 바로 보는 결과 · 목표(U14)와 상황 생성(U10)을 먼저 끝내고 realtime은 뒤에 한다 — 장점: core만으로 보이는 변화가 빨리 나온다 / 단점: 가장 큰 위험(서버 중계 가능 여부)을 늦게 알게 된다
C. 점수로 정하기(WSJF: 가치 + 시급성 + 위험 감소를 크기로 나눈 점수 순) — 장점: 근거가 숫자로 남는다 / 단점: 점수 매기기에 시간이 들고, 결과는 A와 거의 같을 가능성이 크다
X. Other (please specify)

[Answer]: A

## Q2. 볼트 하나를 얼마나 크게 할까

팀은 브랜치를 짧게 쓰고(보통 당일 ~ 1일) 단위마다 PR 하나로 `main`에 합친다(team.md).

A. 단위 하나 = 볼트 하나(볼트 8개). 브랜치 · PR · 범위 파일이 단위와 1:1이다 (추천)
B. 작은 단위끼리 묶는다 — U8 + U9를 한 볼트로(공통 기반), 나머지는 단위 하나씩(볼트 7개). 기반 PR이 한 번에 들어가 뒤 단위가 빨리 시작한다 / 기반 PR이 커진다
C. 줄 단위로 크게 묶는다(기반 · realtime · core 볼트 3개) — 단계 수는 줄지만 PR이 너무 커진다
X. Other (please specify)

[Answer]: A

## Q3. Construction을 누가 어떻게 할까 (담당 방식)

팀은 지금까지 공통 인셉션 기록 하나를 만든 뒤, 유닛마다 지시서(`docs/units/<유닛>/brief.md`)를 쓰고 **담당자가 자기 PC에서 유닛용 작업 기록을 새로 만들어** 구현했다(`docs/units/README.md`). 승인 증빙이 PC마다 따로라 남의 기록은 이어 갈 수 없다. 이 작업은 2~3명이 병렬로 하려 한다.

A. 팀 방식 그대로 — 이 기록은 Inception까지 끝낸다. 볼트마다 지시서(`docs/units/u8` ~ `u15/brief.md`)를 이 단계 뒤에 써서 담당자에게 넘기고, 각 담당자가 자기 PC에서 새 작업 기록으로 Construction을 한다. 이 기록의 Construction 단계는 빼거나, 이 PC 담당자의 볼트만 이어서 한다(Q4에서 정함) — 장점: 지금까지 쓴 방식이라 바로 병렬이 된다 / 단점: 지시서 작성이 한 번 더 필요하다 (추천)
B. 이 세션에서 한 사람이 AI와 함께 U8 ~ U15를 차례로 모두 한다(지금 설정: 단위 하나씩 설계 → 코드 → 확인) — 장점: 기록 하나로 끝까지 추적된다 / 단점: 병렬이 되지 않는다
C. AI-DLC의 팀 담당 모드(같은 작업 기록 안에서 팀마다 단위를 맡고 따로 승인) — 장점: 기록이 하나다 / 단점: 팀이 써 본 적 없고, PC마다 승인 증빙이 따로라 다른 PC에서 이어 쓰기 어렵다(팀 경험)
X. Other (please specify)

[Answer]: A

## Q4. (Q3이 A일 때) 이 작업 기록의 Construction은 어떻게 할까

A. 이 PC 담당자가 맡는 볼트(예: 공통 기반 U8 · U9)만 이 기록에서 이어서 Construction 하고, 나머지 볼트는 지시서로 넘긴다 — 장점: 기반 볼트를 바로 시작해 다른 사람이 기다리는 시간이 준다 / 단점: 이 기록의 Construction 대상 단위를 줄이는 계획 조정이 한 번 필요하다 (추천)
B. 이 기록의 Construction 단계를 모두 빼고, 모든 볼트를 지시서로 넘긴다(이 PC 담당자도 새 기록으로 시작) — 장점: 모두가 같은 방식 / 단점: 기반 볼트 시작이 늦어진다
C. Q3이 A가 아니다(해당 없음)
X. Other (please specify)

[Answer]: A

## Q4-1. (후속) 이 PC 담당자가 이 기록에서 이어서 할 볼트는 무엇인가

Q4-A에서 '이 PC 볼트만 이어서'를 골랐다. 이 기록에 남길 Construction 대상 단위를 정해야 계획 조정(나머지 단위 제외)과 지시서 대상을 나눌 수 있다. Q1-A(위험 먼저)에 따르면 U8 · U9 다음 첫 위험 확인은 U11(판정 턴, GPT-Live 실호출 확인 포함)이다.

A. U8 · U9(공통 기반)만 — 끝나면 바로 PR로 합쳐 다른 담당자가 시작한다. U11부터는 지시서로 넘긴다
B. U8 · U9 · U11(공통 기반 + 첫 위험 확인)까지 — 서버 중계 가능 여부를 이 PC에서 직접 확인한다 / 이 기록이 길어지고 realtime 줄 담당이 U11을 기다린다
C. realtime 줄 전체(U8 · U9 · U11 · U12 · U13) — 같은 파일을 고치는 단위를 한 사람이 차례로 맡는다(Units Generation 설명) / 이 기록이 가장 길다
X. Other (please specify)

[Answer]: A

## Q5. 외부에서 준비돼야 하는 것 중 이미 준비된 것은 무엇인가 (select all that apply)

볼트가 막히지 않으려면 아래가 준비돼야 한다. 준비 안 된 항목은 담당 · 예상 시점 · 막히면 어떻게 할지를 외부 의존 지도에 적는다.

A. OpenAI 키(Luna · GPT-Live 실호출용, 이 PC 환경 변수) — U9 · U11 실호출 확인, FR12
B. 제브(Jev) 계정 · 키 · API 문서 — U13 어댑터 형식 확정
C. Neo4j 개발용 인스턴스 — U15(없으면 메모리 대역으로 먼저 진행)
D. 압축 자료 2개(`social-comm-poc.zip` · 조원 하네스) 라이선스 · 커밋 여부 결정 — U15 씨앗 적재, `main` PR 전 이력 정리
E. 실기기(아이 기기) 검증 담당 · 일정 — FR12.3 실기기 확인표
X. Other (please specify)

[Answer]: A, B, C, D

## Q5-1. (후속) 압축 자료 라이선스 · 커밋은 어떻게 정했나

Q5에서 압축 자료 2개의 라이선스 결정이 준비됐다고 답했다. 결정 내용을 계획에 적어야 U15(씨앗 적재)와 `main` PR 전 정리가 정해진다. 참고: `social-comm-poc.zip`은 이미 커밋 `b131957`에 들어가 개인 원격(`personal/feat/doc-sync-code-update`)에 올라가 있고, 압축 안 메모는 일부 자료를 '재배포 불가'로 적는다.

A. 두 압축 파일 모두 저장소에 커밋해도 된다(팀 내부 저장소 · 사용 허락 받음). 이력 정리는 필요 없다
B. 저장소에는 넣지 않는다 — `.gitignore`로 빼고, 이미 들어간 `b131957`의 압축 파일은 `main` PR 전에 이력에서 뺀다. U15 적재 스크립트는 로컬 경로를 인자로 받는다
C. 조원 하네스는 커밋하고, `social-comm-poc.zip`은 B처럼 뺀다(재배포 불가 자료 때문)
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

아래는 팀 규칙 · 앞 단계 사실로 정한 가정이다. 하나라도 다르면 B를 골라 주면 개별 질문으로 바꾼다.

1. **스켈레톤 없음:** `classic` 범위라 첫 볼트를 끝-끝 조각으로 만드는 의식이 없다. 끝-끝 회귀 기준은 `bash scripts/verify.sh`이고 Build and Test에서 한 번 돈다(team.md).
2. **의존 준수:** 볼트 순서는 Units Generation의 의존 그래프를 어기지 않는다. 어기는 곳이 있으면 근거를 순서 근거 문서에 적는다.
3. **병합 순서 = 마이그레이션 번호 순서:** Flyway `outOfOrder`가 꺼져 있어, 마이그레이션 번호는 정한 병합 순서대로 미리 나눠 준다(다음 번호 core V8 · realtime V5). 마이그레이션이 없는 단위(U8 · U9)는 번호를 받지 않는다.
4. **담당 · 브랜치 이름:** 브랜치는 `unit/u8-…` ~ `unit/u15-…`, 시험 태그 `@Tag("u8")` ~, 범위 파일 `coverage-scope/u8.txt` ~ 를 쓴다(Units Generation Q2-B). PR은 merge commit, `ci-gate` 통과 뒤 병합(R-M1).
5. **받아들인 위험의 처리:** Domain Design 지적 R-01 ~ R-08, Contract Design 지적 R-01 ~ R-09는 해당 단위의 Functional Design 첫 작업으로 다룬다. 특히 Contract Design R-01 ~ R-05(기존 계약 파일과의 충돌)는 U8 · U10 · U11 · U13 · U14 Functional Design에서 실제 `contracts/` 파일과 대조해 바로잡는다.
6. **시험 · 증거:** 볼트 완료 기준에는 바꾼 쪽의 정적 · 단위 · 통합 시험 통과, 유닛 범위 줄 80%(범위 파일이 있는 단위), 가짜 공급자만 사용(R-F1), 대역 · 합성 · 실호출 · 실기기 결과를 나눠 보고(R-M11)가 들어간다.
7. **볼트 담당 표기:** Team Formation 단계를 건너뛰었으므로 담당은 'AI 개발 에이전트(aidlc-developer-agent) + 사람 담당자'로 적고, 사람 담당자 이름은 지시서를 넘길 때 정한다(Q3).

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions
