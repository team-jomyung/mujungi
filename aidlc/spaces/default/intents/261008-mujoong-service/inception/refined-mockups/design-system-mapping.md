# 무중 디자인 시스템 매핑

> Refined Mockups(2.5) · 2026-10-09 · 입력: mockups.md · interaction-spec.md(같은 폴더), 화면 설계 v1.5j(토큰 14개 · 컴포넌트 표는 PDF 그림에만 있음), refined-mockups-questions.md Q4
> **토큰 값은 임시다(가정 C1).** 화면 설계 PDF는 글 추출이 되지 않아 토큰 14개의 실제 값을 읽지 못했다. 이름 · 역할 · 쓰임은 정하고, 값은 팀장이 PDF에서 옮겨 아래 표를 바꾼다(OQ8). 값이 바뀌면 accessibility-checklist의 대비 측정(C9)을 다시 한다.

## 1. 원칙

- 외부 디자인 시스템(Material 등)을 도입하지 않는다. 토큰은 CSS 변수로 Next.js 한 앱의 /parent · /device가 같이 쓴다.
- 상태 색은 점 · 배경 · 테두리에만 쓰고 글은 항상 `color.text.*`를 쓴다. 상태는 색 + 상태명 글 + 아이콘으로 전한다(AC1.4.15).
- 간격은 4 · 8 · 16 · 24 · 32 · 48px 단위, 터치 영역은 44px 이상(아이 기기 단추는 72px 이상).

## 2. 토큰

| 토큰 | 역할 | 임시값 | 쓰임 |
|---|---|---|---|
| `color.brand.primary` | 주 단추 · 활성 탭 | #3B6FD4 | 「구글로 시작」 · 「동의하고 계속」 · 활성 탭 |
| `color.text.primary` | 본문 글 | #1F2430 | 모든 본문 (흰 배경 대비 4.5:1 이상) |
| `color.text.secondary` | 보조 글 | #5B6270 | 마지막 확인 시각 · 메타 줄 |
| `color.surface.base` | 화면 배경 | #FFFFFF | — |
| `color.surface.card` | 카드 배경 | #F4F6FA | 상태 카드 · 판정 카드 |
| `color.border.default` | 카드 · 입력 테두리 | #CBD1DC | UI 요소 3:1 이상 |
| `color.status.stable` | 안정 · 진행 중 점 | #2E9E5B | 04 · 05 · 지금 활동 |
| `color.status.unstable` | 불안정 · 경고 점 | #D9A400 | 05 불안정 · 12 공백 경고 카드 테두리 |
| `color.status.disconnected` | 끊김 · 실패 점 | #D64545 | 05 끊김 · 실패 카드 |
| `color.status.unresponsive` | 응답 없음 · 미확인 점 | #8A909C | 05 응답 없음 · 조회 실패 |
| `color.action.danger` | 위험 단추 | #C23030 | P7 「삭제」 · SET0 「계정 삭제」 |
| `color.fixed.expression` | 기본 종료 표현 카드 | #DCE8FB | SET-01 파란 고정 카드 |
| `space.unit` | 간격 단위 | 4px | 모든 간격은 배수 |
| `radius.card` | 카드 모서리 | 12px | 카드 · 시트 · 팝업 |

글꼴: 시스템 한글 산세리프(Pretendard 사용 시 웹폰트, PDF는 한글 폰트 내장). 본문 16px, 보조 14px, 머리 20px, 아이 기기 라벨 28px 이상.

## 3. 컴포넌트 매핑

| 컴포넌트(interaction-spec §3) | 화면 | 쓰는 토큰 |
|---|---|---|
| BottomTabBar | 04 · 05 · 08 · 12 · 17 · 18 · 26 · 27 | brand.primary(활성) · text.secondary(비활성) |
| StatusCard · VerdictCard | 04 · 05 · 03 | surface.card · status.* · radius.card |
| Toast | 전체 | text.primary 반전 · status.disconnected(오류 아이콘) |
| BottomSheet · ConfirmDialog | SET0 · 02-1 · P1~P7 | surface.base · radius.card · action.danger(P7) |
| CodeInput · TextArea · ChipGroup | 05a · 08 · 03 | border.default · brand.primary(포커스) |
| TranscriptList | 12 · 27 | surface.card(무중) · brand.primary 연한 배경(아이) |
| Calendar · ActivityRow · SegmentedTabs | 26 · 28 · 18 · 27 | brand.primary · status.stable(활동한 날 점) |
| JobCard · WarningCard | 28 · 17 · 12 | status.unstable · status.disconnected |
| DeviceStateView | T19 | §5 표 |
| ReportPdf | R-PDF | text.primary · 흑백 인쇄 가능 |

## 4. 아이콘 · 이미지

필요: 상태 아이콘(안정 ✓ · 불안정 ! · 끊김 ✕ · 응답 없음 ?), 탭 아이콘 4개, ★ ☆ ×, 「←」, 캐릭터 그림(상태별 표정 6장), 로고. **디자인 자산(아이콘 · 캐릭터 · 로고 · 폰트 파일)은 팀장 제공**(OQ8). 그 전까지 글 라벨 + 기본 기호로 대체한다.

## 5. 아이 기기 상태 ↔ 표정 ↔ 색

| 상태 | 라벨 | 표정(캐릭터) | 배경 색 | 소리 |
|---|---|---|---|---|
| 꺼짐 | — | 눈 감음 | 회색(unresponsive) | 없음 |
| 대기 | 대기 | 편안한 미소 | 연한 파랑 | 없음 |
| 듣는 중 | 듣는 중 | 귀 기울임 | 연한 초록 | 없음 |
| 생각 중 | 생각 중 | 고개 갸웃 | 연한 노랑 | 없음 |
| 말하는 중 | 말하는 중 | 입 움직임 | 연한 파랑 | 승인된 음성 |
| 마무리 | 마무리 | 손 흔듦 | 연한 파랑 | 마무리 인사 1회 |
| 연결 끊김 | 연결 끊김 · 다시 연결하고 있어요 | 물음표 | 연한 회색 | 없음 |
| 마이크 허용 안 됨 | 마이크를 허용해 주세요 | 귀를 가리킴 | 연한 노랑 | 없음 |

색은 보조이고 표정과 라벨이 1차다. 전환은 150ms 교차 페이드, `prefers-reduced-motion`이면 즉시.
