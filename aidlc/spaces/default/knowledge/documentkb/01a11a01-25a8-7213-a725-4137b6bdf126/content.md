# 읽는 순서 (knowledge/documents)

이 폴더를 통째로 `aidlc/spaces/default/knowledge/documents/`에 넣고 Claude Code에서 `/aidlc knowledge onboard aidlc/spaces/default/knowledge/documents`를 실행한다.

기준 문서는 10_화면설계_v1.5j다. 다른 문서와 다르면 화면 설계가 맞다.

그다음은 15_기술명세_AI동작_1015다. 기술명세서 v0.3과 AI 동작 명세 v0.2가 20~80의 문서(개발명세·요구사항·기능명세·ADR·ERD·WBS·점검표)와 다르면 이 두 문서를 따른다. 특히 모델 역할(GPT-Live 아동 대화 · GPT-6-Luna 진행 중 판정·J7과 그 밖의 AI 작업 · 제브 가드레일), 연습 2안 상태 기계, 판정 지점 J1~J7, 지식 그래프(Neo4j, 아이별) 사용처와 조회 방식, 가드레일 대상과 처리, 분석 AI 작업(장면 대본·재판정·목표 후보·결과 문장·생활 팁·리포트)과 프롬프트·출력 스키마는 이 두 문서가 기준이다. 두 문서에 나오는 요구사항 ID(FR-A13·F20 등)는 요구사항 명세서 v1.4의 번호다.

| 순서 | 폴더 | 무엇 | 쓰는 단계 |
|---|---|---|---|
| 1 | 10_화면설계_v1.5j | 화면 25장(PDF) · 화면과 분기(글) · 문구표 | 2.4 User Stories · 2.5 Refined Mockups · 3.1 Functional Design |
| 2 | 15_기술명세_AI동작_1015 | 기술명세서 v0.3 · AI 동작 명세 v0.2. 시스템 구조·인터페이스·데이터 모델·모델 역할·지식 그래프·AI 프롬프트와 출력 스키마. 20~80과 다르면 이 폴더가 맞다 | 2.3 · 2.6 Domain Design · 2.8 Contract Design · 3.1 · 3.2 |
| 3 | 20_개발명세_1008 | 화면별 명세 · API · 이벤트 · 상태 기계 · 테스트 · 결정 기록 · 화면↔테이블 | 2.3 · 2.6 · 2.8 · 3.1 · 3.2 |
| 4 | 30_요구사항_v1.1 | 요구사항 66건(엑셀 원본 + 글) · 사용자 시나리오 | 2.3 Requirements Analysis |
| 5 | 40_기능명세_GPT-Live | 팀 기능명세서 v1.0 · 상세기능 v2 · GPT-Live 음성 기기 요구 v0.1 | 2.3 · 3.1 · 음성 계약 |
| 6 | 50_ADR_2S | ADR 10편 + architecture 8편 + README · decision-log | 2.6 Domain Design · 2.8 Contract Design |
| 7 | 60_ERD_v0.1 | 데이터 사전(글) · PostgreSQL DDL · ERD 그림 | 3.1 entities · 2.6 |
| 8 | 70_WBS_v0.5 | WBS(글 + 엑셀) | 2.7 Units Generation · 2.9 Delivery Planning |
| 9 | 80_점검표_1008 | 네 문서가 어긋나는 21곳과 제안 | 2.3 · 2.6에서 질문에 답할 때 |
