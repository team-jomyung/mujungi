# 변경 이력

결정이 바뀐 경위는 이 문서에만 적는다. 다른 문서는 현재 상태만 적는다.

## 2026-10-13 · U5a 리뷰 1차 반영(main 병합 포함)

**배경**
- U5a는 U2가 main에 들어오기 전에 갈라져 임시 계정 어댑터로 구현됐다. U4a(main 포함)를 합치면서 리뷰 1차(`docs/units/u5a/review-1.md`) 11건을 반영했다.

**바뀐 것**

| 항목 | 지금 | 이전 |
|---|---|---|
| 계정 · 아이 읽기 | `U2PracticeAccountAdapter`(GuardianWriteLock · ChildProfileService). 포트 메서드는 모두 추상 | 임시 `JdbcPracticeAccountAdapter`가 보호자 행을 직접 FOR UPDATE. 포트 일부가 503을 던지는 default |
| 계정 삭제 순서 | 연습 참가자 @Order 150(아이 300보다 먼저) | 순서 없음(맨 뒤) — 연습 기록이 있으면 아이 단계에서 외래 키로 멈춤 |
| 18 빈 상태 | 아이가 없는 보호자도 200 빈 목록 | 404 |
| 정의 고정(pin) | FOR KEY SHARE로 읽고 used_at은 처음 한 번만 찍음. 여러 정의는 (키, 버전) 순서로 고정 | 공용 정의 행을 FOR UPDATE로 잡고 매번 UPDATE |
| C10 정의 버전 오류 | 400 VALIDATION_FAILED | 계약 밖 500 |
| C10 요청 키 | 두 번 모두 내용 확인 실패일 때만 422로 굳힘. 같은 내용 · 저장 경합은 같은 키로 다시 시도 가능 | 어떤 이유든 두 번 실패하면 422로 굳힘 |
| 상대 역할 지시 | 공급자 출력에 칸이 없음(Core 고정 문장만) | 모델 값을 길이 · 금지어로 검사한 뒤 버림 |
| 후보 근거 확인 | 트랜잭션 밖에서 먼저 | 보호자 잠금 · 정의 잠금을 쥔 채 |
| U5a 설정 키 | application.yml(CoreProperties.timing.practice · goal, TextAiProperties, KnowledgeProperties) | `u5a-ai.properties` · `u5a-practice.properties`를 따로 읽음 |
| 계약 절 번호 | ai-io.md 7절(U5a TextAiGateway) · timing.md 9절(U5a 시간) | 6절 · 8절(main의 U6 · U2 절과 겹침) |

## 2026-10-13 · 결과 · 리포트 · 푸시에 동훈ver 설계 반영

**배경**
- 팀에 무중 AI-DLC 실행이 둘(준하ver · 동훈ver) 있었다.
- 진행이 앞선 준하ver를 기준으로 계속한다.
- 준하ver가 아직 만들지 않은 U6(결과와 리포트) · U7(웹 푸시) 영역만 동훈ver 설계를 가져온다.
- 비교표 「무중 AI-DLC 준하ver·동훈ver 비교표」의 묶음 K08 · K09 · K12 · C12에 해당한다.

**가져온 것**

| 묶음 | 지금 | 이전 |
|---|---|---|
| K08 기록용 재판정 · 결과 표시 | 연습 종료 뒤 분석 AI가 장면마다 재판정해 확정한다. 27에 끝난 이유 칸과 목표별 장면 결과 칩 · 결과 문장 · 제공한 도움을 보인다(FR4.3 · FR6.2 · FR7.6 · FR7.11) | 27은 고른 목표 이름만 보였고 판정은 어디에도 드러나지 않았다 |
| K09 상담용 리포트 | 저장 기록으로 만든 10개 항목. 인용 · 출처 · 결측 표시와 "진단 결과가 아니에요" 고지를 담는다. 30이 리포트 확인 · PDF 저장을 맡는다(FR6.4 · FR6.5 · ADR-017) | 아이 정보 + 날짜별 대화 원문 PDF(AI 없음) |
| K12 · C12 웹 푸시 | Must. 장애 3종에 활동 완료 푸시를 더했다. 완료 푸시는 참여 뒤 비기술 확정 종료에만 보낸다(FR7.10) | 장애 3종, Should |

**준하ver에 맞춰 옮긴 방식**
- 동훈ver의 '장면'은 준하ver의 처음 상황(ORIGINAL) · 새 상황(NEW_SCENARIO)에 대응시켰다.
- 동훈ver의 '되묻기 뒤' 시점은 준하ver에 되묻기 단계가 없어 쓰지 않는다.
- 재판정 값은 보호자 응답에 장면 결과 칩 5종으로만 나간다. 판정 라벨 · 근거 문장은 응답에 넣지 않는다.
- 동훈ver의 29 리포트 확인 화면은 준하ver 30이 맡는다. 화면 번호는 준하ver를 유지한다.
- 동훈ver의 리포트 · 결과 상태 PENDING · FULL · PARTIAL · INSUFFICIENT · FAILED는 이렇게 나누었다.
  - 준하ver의 작업 상태(REQUESTED · RUNNING · DONE · FAILED)는 그대로 둔다.
  - 내용 수준은 별도 칸 resultLevel · level(FULL · PARTIAL · INSUFFICIENT)로 둔다.

**준하ver 값을 유지한 것**
- 리포트 60초 · 3회(NFR8)
- 결과 작업 재시도 3회(간격 5 · 15초)
- 생활 팁은 승인 목록 매칭(FR6.3)
- 30의 공유하기 · 파일 열기 · 「나중에」 버림

**새로 정한 것**
- 리포트 아이 정보는 이름 · 나이만(관심사 제외)
- 저장한 리포트는 항목 · 인용과 함께 계정 삭제까지 보존(A4)
- 결과 수준의 "내용 있는 아이 발화"는 어절 3개 이상
- 마이그레이션: core V5 = U6 · V6 = U7, realtime V4 = U5b
- 삭제 순서: U6 70 · U7 60
- 새 엔터티 SceneJudgment(core_scene_judgment) · ReportSection(core_report_section)
- 포트 ScenarioLookup · BehaviorDefinitionLookup
- record.practice의 반응 · 도움마다 scenarioId

**바꾼 문서**
- `requirements.md`: 머리말, FR4.3 · FR6.2 · FR6.4 · FR6.5 · FR7.6 · FR7.10, FR7.11(신설), NFR8, A4 · A10, 범위 밖 · 추적표
- `stories.md`: US1.7 · US4.2(AC4.2.8) · US5.2 · US5.3(AC5.3.7) · US5.4 · US5.5, 스토리 지도
- `mockups.md`: §1 F3 ~ F6, S-27 · S-28 · S-30 · R-PDF, §9-32 ~ 9-35
- `components.md`: ParentWeb · TextAiGateway · ResultPipeline · Report · Notification, 요약 · 엔터티 소유 표
- `decisions.md`: ADR-017과 ADR-018 표 10 · 17행
- `unit-of-work.md`: U6 · U7 · U5 메모 · 공통 완료 조건, `unit-of-work-dependency.md` · `unit-of-work-story-map.md`
- 볼트 문서: `bolt-plan.md` B6 · B7, `team-allocation.md`, `risk-and-sequencing-rationale.md`, `external-dependency-map.md`
- 계약: `contract-summary.md` 2 · 3 · 6 · 8 · 9 · 11절, `contracts/ai-io.md` 1절 · 6절(신설)
- `docs/units/README.md`, `docs/erd/ERD_v0.2.md`(U6 · U7 예정 표와 줄 번호 참조)

**근거(동훈ver)**
- 요구사항: FR-D02 · D05 · D06 · F09 ~ F12 · FR-A10
- 스토리: US4.4 · US4.9 · US4.10 · US5.13 · US5.14
- AI 동작 명세: §9.1 · 9.5 · 9.7 · 9.9 · §10
- 기술 명세: §7.4 ~ 7.7
- 화면 설계 v1.6: 27 · 29 · R-PDF
