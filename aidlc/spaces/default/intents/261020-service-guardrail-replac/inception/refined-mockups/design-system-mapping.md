# 디자인 시스템 매핑

## Sources

- ../requirements-analysis/requirements.md, ../user-stories/stories.md, ../practices-discovery/team-practices.md: 승인된 범위/스토리/작업 기준.
- mockups.md 및 refined-mockups-questions.md Q1-A: 기존 화면 유지와 RP 개정 제안.
- frontend/src/shared/styles/tokens.css, frontend/src/parent/common/styles/parent.css, frontend/src/device/device.css: 실제 토큰·반응형·조작 크기.
- frontend/src/shared/copy/{ko,activity}.ts, docs/screen-map.md: 현재 문구와 화면 번호. 문서의 이전 styles 경로 대신 실제 common/styles를 따른다.

## 기존 컴포넌트 재사용

| 목적 | 기존 구현 | 이번 적용 |
|---|---|---|
| 진행/대화 | LiveScreen, TranscriptList, StatusLine | 실제 확인 범위의 상태/대화 표시, 새 완료 뱃지 없음 |
| 미참여 | NoticeDialog P-03, 기존 확인 버튼 | 거절·무응답 제목을 사실에 맞게 선택 |
| 보호자 끝내기 | ConfirmDialog P-01 | 유지, 취소 기본 초점/중복 전송 방지 |
| 마무리/끝 | DoneScreen 및 presentation | 실제 인사/확인된 표현 조건으로 줄 노출 |
| 기기 | StatusView | 캐릭터·라벨·켜기/끄기 유지 |
| 글/색/간격 | tokens.css 및 common/styles | 새 디자인 라이브러리·전체 테마 도입 없음 |

## 토큰

| 범주 | 현재 토큰/값 | 원칙 |
|---|---|---|
| 글 | --color-text-primary #1b1f2a, --color-text-secondary #626b7d | 상태 색만으로 뜻을 표현하지 않음 |
| 배경 | base/card #ffffff, page #f3f5f9 | 기존 카드/페이지 계층 유지 |
| 주요/위험 행동 | --color-brand-primary #2f5bea, --color-action-danger #c23030 | 기존 끝내기·확인 의미 유지 |
| 크기 | 본문 16px, 보조 14px, 제목 20px, 기기 라벨 28px | 예시 시안의 축소된 글자 크기를 앱에 이식하지 않음 |
| 간격/모서리 | --space-unit 4px, --radius-card 12px | 기존 CSS 배수 재사용 |
| 터치 | --touch-target-min 44px, --touch-target-device-min 72px | 신규/변경 버튼도 기존 하한 보존 |
| 글꼴 | Pretendard Variable와 기존 로컬 폴백 | 새 CDN/원격 폰트 요청 없음 |

새 색·토큰을 추가하지 않는다. 대비는 적용 배경 조합으로 검증할 항목이며 현재 수치를 측정 완료로 주장하지 않는다.

## 문구와 상태의 소유

- 기존 정본 COPY와 제안/임시 문구의 구분을 유지한다. JSX에 새 원문 문자열을 직접 넣지 않는다.
- RP-2 새 제목은 기존 미참여 문구 곁의 기능/공용 copy에서 관리하고 전역 기존 무응답 제목을 무조건 덮지 않는다.
- RP-3/4는 새 기본 문구를 채우기보다 사실을 확인할 수 없는 줄을 숨기는 매핑을 우선한다.
- RP-1 승인 텍스트·음성 자원은 같은 버전으로 추적하되 policy hash·JEV 축·세대 ID를 일반 사용자 화면에 노출하지 않는다.
- 보호자 기능과 기기 기능의 import 경계는 유지한다. 공용 copy/토큰만 기존 공통 계층을 사용한다.

## Responsive Behaviour

| 범위/점검 폭 | 기존 배치와 이번 기준 |
|---|---|
| 320–440px 및 440px 경계 | 보호자 한 열·줄바꿈. 긴 안내/이름/확인 문구가 버튼을 덮지 않도록 점검 |
| 480px 이상 | 보호자 최대 480px 한 열 유지. 새 두 열·사이드바 없음 |
| 560px 이상·높이 640px 이상 | 기존 phone-preview 조건과 미리보기 프레임 유지. 이것을 새 제품 breakpoint로 재정의하지 않음 |
| 기기 가로/세로·낮은 높이 | 기존 캐릭터 clamp(200px, 28vw, 320px), 버튼 최소 폭/높이와 구석 배치에서 겹침 확인 |
| 확대 200% | 텍스트 재흐름과 조작 접근 가능. 필요하면 스크롤, 축소 글자로 해결하지 않음 |

## Assumptions & Open Questions

이 매핑은 현재 CSS의 재사용 기준이다. 새로운 반응형 레이아웃·폰트·캐릭터 자산이나 전체 접근성 개선 프로젝트를 추가하지 않는다. 적용 중 기존 CSS와 충돌하면 바뀐 표면을 중심으로 수정하고 전체 서식 변경은 하지 않는다.
