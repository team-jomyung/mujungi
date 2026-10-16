# 외부 의존 지도

> **볼트**는 단위 하나를 설계 · 코드 · 시험까지 끝내 실제로 돌아가는 결과를 내는 작업 한 바퀴다.
> 근거: `bolt-plan.md`, `../contract-design/contract-summary.md`(외부 경계 C-DS4 ~ C-DS8 · C-DS15), `../requirements-analysis/requirements.md`(FR12 · 열린 항목), `../user-stories/stories.md`, 질문 · 답 `delivery-planning-questions.md`(Q5 · Q5-1).
> 키 · 계정 값은 이 문서와 저장소에 쓰지 않는다. 사람이 각 PC의 환경 변수 · 비밀 저장소에 넣는다(R-F3 · R-F13).

| # | 외부 의존 | 상태(2026-10-16) | 담당 | 쓰는 볼트 | 막히면 |
|---|---|---|---|---|---|
| E1 | OpenAI 키(GPT-6-Luna · GPT-Live 실호출) | **준비됨**(Q5 A) | 사람 — 각 담당자 PC 환경 변수 | B2 · B3 · B7 · B6(실호출 확인만) | 시험 · CI는 가짜 공급자로 계속한다. 실호출 확인표만 밀린다 |
| E2 | GPT-Live 서버 쪽 음성 전송 지원 여부 | **모름** — FR12.2로 확인 | B3 담당자 | B3 · B5 · B8 | 위험 R1 중단 조건: realtime 줄을 멈추고 설계 재검토 |
| E3 | 제브(Jev) 계정 · 키 · API 문서 | **준비됨**(Q5 B) | 사람 | B5 | 대역(1차 사전만)으로 서버 동작을 먼저 만든다. 실호출 확인만 밀린다 |
| E4 | Neo4j 개발용 인스턴스 | **준비됨**(Q5 C) | 사람 | B4(B6 · B7은 메모리 대역으로도 가능) | 메모리 대역으로 먼저 진행한다(FR9.7) |
| E5 | 압축 자료 라이선스 · 커밋(`social-comm-poc.zip` · 조원 하네스) | **결정됨: 둘 다 커밋 허용**(Q5-1 A) | 사람 | B4(씨앗 적재) · B5(가드레일 시험 참고) | — |
| E6 | 대형 그래프 원본 `graph_out`(노드 53.7만) | **없음**(요구사항 열린 항목 2) | 사람 | B4 | 씨앗 · 어휘 중심으로 적재한다. 필요하면 원본을 따로 받는다 |
| E7 | 실기기(아이 기기) 검증 담당 · 일정 | **미정**(Q5에서 고르지 않음) | 사람(정해야 함) | 통합 확인(FR12.3) | 기기 시뮬레이터 끝-끝(`verify.sh`)까지는 개발이 책임진다. 실기기 확인표만 준비해 둔다 |
| E8 | 아동 데이터 외부 전달 조건(공급자 약관 · 보호자 동의, R-F15) | **확인 필요** | 사람 | B2 · B3 · B5 · B7 실호출 | 실호출은 합성 자료로만 한다. 실제 아동 데이터 전달은 확인 전까지 막는다 |
| E9 | 팀 담당자 배정(2~3명) | **미정** | 사람 | B3 ~ B8 | 지시서를 넘길 때 정한다. 그 전까지 이 PC는 B1 · B2를 진행한다 |
| E10 | 저장소 리뷰 · `main` 병합 권한(`ci-gate` 필수 검사) | 있음(team.md) | 팀 | 전 볼트 | — |

## 메모

- E5의 결정에 따라 `social-comm-poc.zip`(이미 커밋 `b131957`에 있음)과 조원 하네스 압축 파일은 이력 정리 없이 저장소에 둔다. `knowledge/` 아래의 `Zone.Identifier` 파일은 Windows 표시 파일이라 커밋하지 않는다.
- E1 · E3이 준비됐어도 실호출 결과는 `[실호출-합성]` 증거로 따로 보고하고, 테스트 대역 성공과 섞지 않는다(R-M11).

## Sources

- `bolt-plan.md`, `risk-and-sequencing-rationale.md`(같은 폴더)
- `../contract-design/contract-summary.md`, `../requirements-analysis/requirements.md`, `../user-stories/stories.md`
- `delivery-planning-questions.md`(Q5 · Q5-1)

## Assumptions & Open Questions

- E7 · E8 · E9는 사람이 정해야 한다. Construction의 이 기록 범위(B1 · B2)는 E7 · E9 없이 진행할 수 있다.
