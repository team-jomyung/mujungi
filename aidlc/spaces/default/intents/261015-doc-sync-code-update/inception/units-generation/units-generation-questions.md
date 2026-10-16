# Units Generation 질문지

> 근거: `../domain-design/components.md`(컴포넌트 13개) · `decisions.md`(ADR-001~007), `../requirements-analysis/requirements.md`, `../user-stories/stories.md`, 팀 규칙 `team.md`(브랜치 · 마이그레이션 순서 · 커버리지 범위 파일).
> 이 단계는 작업 단위와 의존 관계(무엇이 무엇에 기대는지)만 정한다. 무엇을 먼저 만들지는 Delivery Planning이 정한다.
> 사람이 골라야 하는 것만 개별 질문으로 묻고, 정본 · 코드 사실로 바로 정해지는 것은 맨 아래 가정 묶음으로 확인받는다.

## Q1. 작업 단위를 어떻게 나눌까 (단위 경계 · 크기)

컴포넌트는 13개(realtime 8 · core 5)이고 스토리는 25개다. 판정 턴 · 출력 보류 · 새 연습 흐름은 서로 맞물려 있고(스토리 '공통 기반' 메모), 제브 게이트는 친구 역할 되묻기가 들리려면 필요하다(US1.2 의존).

A. 기능 묶음 8개 — ① 계약 · 버전 리소스(`contracts/ai-io.md` · 시간 값 · 오류 코드 · 비밀 이름 · `ApprovedContentCatalog`) ② Luna 호출부(core · realtime 각자) ③ 상황 생성 ④ 판정 턴 기반(`LiveJudgment` · `SessionRuntime` 판정 턴 · `OutputGate` 보류) ⑤ 새 연습 흐름(`PracticeConversation`) ⑥ 제브 게이트(`JevGuardrail` + 게이트 단계) ⑦ 목표 · 결과(`PracticeGoal` · `ResultPipeline`) ⑧ 지식 그래프. 단위마다 PR 하나, 테스트 범위가 작다 / 단위 사이 의존이 많아 병합 순서 관리가 필요하다 (추천)
B. 굵게 4개 — ① 공급자 · 계약(① + ②) ② 실시간 대화(④ + ⑤ + ⑥) ③ core AI(③ + ⑦) ④ 지식 그래프. 의존이 적고 단순하다 / 실시간 대화 단위가 매우 커서(스토리 15개) 검토 · 시험이 무겁고, 한 PR이 오래 산다(팀은 짧게 사는 브랜치)
C. 앱 기준 2개 — core-api 단위 하나, realtime-activity 단위 하나. 앱 경계와 같다 / 단위가 너무 커서 병렬 작업 · 단계별 검증 이점이 없다
X. Other (please specify)

[Answer]: A

## Q2. 이번 작업 단위의 이름을 기존 팀 유닛 U1~U7과 어떻게 구분할까

팀에는 이미 U1~U7(U4a · U4b · U5a · U5b 포함)이 있다. 시험 태그 `@Tag("u1")`~`@Tag("u7")`, 커버리지 범위 파일 `backend/coverage-scope/u5b.txt` · `frontend/coverage-scope/u6.json`, 브랜치 `unit/u4b-…`가 이 이름을 쓴다. 이 단계의 형식은 단위 ID를 `U{n}`, 폴더를 `u{n}-{설명}`으로 요구한다.

A. 작업 기록 안에서는 `U1`~`U8` 형식을 쓰되, 저장소에 남는 이름(브랜치 · 시험 태그 · 범위 파일)은 접두어 `ds`(doc-sync)를 붙여 `unit/ds1-…` · `@Tag("ds1")` · `coverage-scope/ds1.txt`로 한다. 단위 문서에 대응표를 둔다 (추천)
B. 기존 번호를 이어 `U8`부터 매긴다(U8~U15). 저장소 이름도 `u8`~`u15` — 충돌은 없지만 작업 기록 형식(`U1`부터)과 번호가 어긋난다
C. 기존 유닛에 붙인다 — 바뀌는 코드가 속한 기존 유닛 이름(U4b · U5a · U5b · U6 등)으로 태그 · 범위 파일을 넓힌다. 기존 CI 범위 검사(U5b · U7)에 바로 걸린다 / 단위와 기존 유닛이 1:1이 아니라 추적이 어렵다
X. Other (please specify)

[Answer]: B

## Assumption Confirmation

아래는 정본 · 코드 사실로 정한 가정이다. 하나라도 다르면 B를 골라 주면 개별 질문으로 바꾼다.

1. **배포 모델:** 새 배포 단위는 없다. 모든 단위는 기존 core-api · realtime-activity · `contracts/` 안의 코드 조각이고, 두 앱 분리(R-F5)를 그대로 지킨다. 한 단위가 두 앱을 함께 고칠 때도 앱 사이는 내부 HTTP · 이벤트 계약으로만 잇는다.
2. **단위 종류:** 계약 · 버전 리소스 단위는 `spec`, Luna 호출부는 `library`, 나머지는 `service`다. 보호자 웹 단위는 없다(Domain Design: 27 접기는 서버 매핑).
3. **병렬 허용:** 서로 의존이 없는 단위는 동시에 만들 수 있다. 다만 병합 순서는 마이그레이션 번호 순서를 따르고(team.md, Flyway `outOfOrder` 꺼짐), 번호(core V8~ · realtime V5~)는 Delivery Planning에서 정한다.
4. **실호출 · 실기기 검증(FR12):** 단위로 두지 않고 Build and Test에서 다룬다(stories.md '스토리 밖 추적').
5. **계약 변경 위치:** 계약 문서 변경은 그 계약을 처음 쓰는 단위와 같은 PR에 넣는 것을 원칙으로 하되(R-M10), 여러 단위가 함께 쓰는 공통 계약(시간 값 · 오류 코드 · 비밀 이름 · 버전 리소스)은 계약 단위가 먼저 만든다(Q1-A일 때).
6. **스켈레톤:** 이 작업은 `classic` 범위라 스켈레톤 의식이 없다(team.md). 끝-끝 회귀 기준은 `bash scripts/verify.sh`다.
7. **Domain Design 열린 지적:** 승인 때 받아들인 위험으로 넘어온 R-01~R-08은 해당 컴포넌트가 속한 단위의 Functional Design에서 다룬다(예: R-01 · R-07은 목표 · 결과 단위, R-03 · R-04는 판정 턴 기반 단위).

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions
