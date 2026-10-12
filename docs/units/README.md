# 유닛 병렬 구현 (2026-10-12~)

U1 워킹 스켈레톤은 AI-DLC 엔진으로 끝까지 만들었다(설계 · 코드 · 시험 · 감사 기록이 `aidlc/` 아래에 있다).
U2부터는 엔진 없이, 엔진이 만든 인셉션 산출물(스토리 · 계약 · 컴포넌트 · 유닛 · 볼트 계획)과 U1 코드를 스펙으로 삼아 유닛별 작업자가 병렬로 구현한다.
엔진의 승인 증빙이 클론별이라 다른 PC로 넘어가지 않았기 때문이다(`aidlc/` 감사 기록 c63ff7e 참고).

| 유닛 | 작업자 | 브랜치 | 지시서 |
|---|---|---|---|
| U2 보호자 계정 | 조정자 PC, 구독 계정 | `unit/u2-guardian-account` | `u2/brief.md` |
| U3 음성 대화 | 본계정 클라우드 세션 | 세션 지정 브랜치 → main PR | `u3/brief-cloud.md` |
| U4a 활동 세션 Core 쪽 | 조정자 PC, 구독 계정(U2 다음) | `unit/u4a-activity-core` | 조정자 보관 |
| U5a 연습 Core 쪽 | Codex | `unit/u5a-practice-core` 또는 Codex 브랜치 → main PR | `u5a/brief.md` |
| U4b 활동 세션 Realtime 쪽 · U5b · U6 · U7 | U3 · U4가 합쳐진 뒤 | — | 추후 |

규칙: 화면 설계 v1.5j 최우선, 마이그레이션 번호는 core V2 = U2 · V3 = U4a · V4 = U5a, realtime V2 = U3 · V3 = U4b. 각 작업자는 `docs/units/<유닛>/design.md` · `report.md`를 남기고, 조정자가 리뷰해 main에 합친다.
