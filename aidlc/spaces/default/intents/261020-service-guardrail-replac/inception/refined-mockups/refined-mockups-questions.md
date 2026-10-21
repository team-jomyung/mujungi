# Refined Mockups Questions

## Sources

- [desc] Initial description: 현재의 gpt-live 가드레일에서 무중_서비스용_가드레일로 교체하고 싶어
- [scope] Workflow-selected scope: classic / Standard. 이번 기록에는 rough-mockups가 없으며 그 내용을 가정하지 않는다.
- [R1] ../requirements-analysis/requirements.md: 자유대화 지시 및 충돌 흐름 조정, 매 세션 안내/참여, 종료·사실 안내·기존 기능 보호.
- [R2] ../user-stories/stories.md: 2역할·8스토리·40AC. US1.2, US1.3, US1.6, US2.1 중심의 상태 표현.
- [memory:M1] ../practices-discovery/team-practices.md: 기존 구조/기능 이름/문구 모듈 규칙, 새 전면 재서식 없음.
- [S1] docs/screen-map.md, knowledge/documents/10_화면설계_v1.5j/화면설계_v1.5j_문구표.md: S-08/S-12/S-17, P-01/P-02/P-03, D-19 기존 화면·문구 ID. 원본은 변경하지 않는다.
- [C1] frontend/src/parent/features/live/{LiveScreen,StatusLine}.tsx, shared/copy/{ko,activity}.ts: 참여 대기·끝내기·P3 미참여 안내.
- [C2] frontend/src/parent/features/done/DoneScreen.tsx, shared/copy/activity.ts: CLOSING의 고정 인사 설명, childStopDetail의 기본 종료 표현 인용.
- [C3] frontend/src/device/features/status/StatusView.tsx, device/device.css 및 shared/styles/tokens.css: 기존 캐릭터·상태 라벨·켜기/끄기·스타일 토큰.

## Q1 — 기존 화면 안에서의 최소 상태·문구 정합성

기존 S-08(준비) → S-12(참여 대기/진행) → S-17(마무리/끝), P-01(보호자 끝내기 확인), D-19(기기 상태) 레이아웃과 조작을 유지한다. 아이의 명확한 음성 종료와 보호자 웹의 끝내기 확인 팝업은 별개이며, 보호자 P-01은 유지한다. 새 설정/기억/정책 대시보드나 새 보호자 동의 화면은 만들지 않는다.

다음 필수 개정 제안을 이 화면 안에서 반영할까요?

| 제안 | 대상 | 현재 표현/동작 | 제안 | 근거 |
|---|---|---|---|---|
| RP-1 | S-12 승인 안내 음성/안내 말풍선, C.S-12.11.4 | 짧은 초대 예시 | 새 서비스의 AI·기록/보호자 열람·선택권 안내와 참여 질문으로 맞춘다. 말풍선 존재나 서버 전송은 실제 청취 완료를 뜻하지 않는다 | FR2, US1.2 |
| RP-2 | P-03, C.P-03.15.1 | 아이가 대답하지 않아 시작하지 못했어요 | 명확한 거절은 ‘아이가 시작하지 않기로 했어요’. 무응답은 기존 문구 유지. 안내 실패/기술 오류는 거절·무응답으로 꾸미지 않고 기존 기술 실패 경로로 분리 | FR2.4, US1.3 |
| RP-3 | S-17, C.S-17.72.5 | 고정 ‘무중아 그만할래’를 종료 발화처럼 인용 | 해당 표현이 실제로 확인된 때만 인용. 확인된 표현이 없으면 아이가 끝내자고 했다는 사유·시각만 표시하고 고정 인용을 생략 | FR4.1, FR5.1, US1.6, US2.1 |
| RP-4 | S-17, C.S-17.73.4 | 무중이 마무리 인사를 하고 있어요 | 승인된 인사 재생이 확인된 경우에만 기존 줄 사용. 즉시 음소거·인사 없음/미확인이면 이 줄을 숨기고 ‘마무리하고 있어요’ 상태와 기기 확인 대기를 구분 | FR4.1–FR4.3, US1.6 |

- A. 기존 화면 유지 + 필수 문구·상태 매핑 반영 (추천)
- B. 변경 범위 조정 — 바꾸거나 제외할 제안과 이유를 알려 주세요
- X. Other (please specify)

[Answer]: A. 기존 화면 + 필수 문구·상태 반영 (Recommended)

## 적용 기본안

- 새 레이아웃/상호작용 유형 대신 기존 상태 줄·말풍선·사유 카드·확인 팝업을 사용한다. 새 모달/위저드/정책 표시 UI를 만들지 않는다.
- 준비/대기/진행/마무리/끝과 loading/empty/error/stale·연결 복구를 구분한다. 확인되지 않은 상태를 성공으로 보이지 않는다.
- 기존 반응형 CSS·토큰과 copy 모듈을 재사용한다. 작은 화면·긴 한국어·200% 확대·키보드/초점·상태 낭독·색 외 표시를 확인하며, 이번 변경 표면을 WCAG 2.1 AA 기준으로 점검한다. 서비스 전체 준수 인증을 주장하지 않는다.
- 참여 안내/음성 중단/기기 확인의 정확한 계약·필드·기술 오류 경로는 Domain/Contract/Functional Design에서 정한다. 새 필드가 필요한데 기존 계약으로 표현할 수 없으면 그 변경을 명시한다.

## Assumptions & Open Questions

- 원본 화면설계 문구표는 수정하지 않고 RP 제안으로 남긴다. 적용 시 기존 copy 모듈에서 근거를 추적한다.
- 이전 User Stories 검토 R-01(종료 판단 요청 정책의 대역 검증)은 열린 상태로 승인됐다. 화면 변경만으로 해결됐다고 하지 않으며 후속 설계로 넘긴다.
- 새 제품 요구사항·시간값·저장 정책·원음 재생·연습 기능·외부 공급자를 추가하지 않는다.
