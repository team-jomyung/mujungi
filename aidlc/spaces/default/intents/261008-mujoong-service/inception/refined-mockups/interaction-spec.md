# 무중 상호작용 명세

> Refined Mockups(2.5) · 2026-10-09 · 입력: mockups.md(같은 폴더), `../user-stories/stories.md`(AC), `../requirements-analysis/requirements.md`(NFR6 시간 규칙 · NFR10 브라우저), 화면 설계 v1.5j(화면과 분기 · 문구표), 개발 명세 1008 화면별 명세(오류 표), docs/aidlc_drafts/inception/refined-mockups/interaction-spec.md 초안
> 화면별 단추 · 분기는 stories.md의 AC가 기준이므로 여기서는 반복하지 않고, 상호작용 수준의 규칙(로딩 · 오류 · 토스트 · 전환 · 타이밍 · 초점)과 컴포넌트 명세만 적는다. 컴포넌트 명세 형식은 `.claude/knowledge/aidlc-design-agent/component-spec-template.md`.

## 1. 공통 규칙

| 규칙 | 내용 | 출처 |
|---|---|---|
| 로딩 | 스켈레톤 없음. 요청 중인 단추는 흐리게(비활성) + 라벨 유지("등록하고 있어요" 같은 진행 글이 있으면 라벨 교체). 화면 전체 블록 없음. 5초 넘으면 토스트 "연결이 원활하지 않아요" + 「다시 시도」 | 개발 명세 공통 규칙 · 디자이너 지식 |
| 오류 | API 실패 · 네트워크 끊김은 화면 아래 토스트(글 + 아이콘, 색만 아님) + 「다시 시도」. 입력 오류는 해당 칸 바로 아래 글. 원시 오류 코드는 보이지 않는다 | 개발 명세 · AC1.3.5 |
| 토스트 | 아래 중앙, 4초 자동 사라짐. 조작이 달린 토스트(「되돌리기」 · 「다시 시도」)는 최소 3초이며 초점이 가면 사라지지 않는다(B25). 한 번에 하나, 새 토스트는 이전 것을 대체 | AC4.1.6 · 4.1.13 |
| 탭 잠금 | 대화 기기 칸이 안정이 아니면 자유대화 · 연습 탭 잠김(흐림 + aria-disabled). 누르면 이유 토스트(mockups §9-6) → 05. 진행 중 활동이 있으면 두 탭은 12로 | AC1.4.9~1.4.11 |
| 폴링 | 12 진행 화면만 2초 폴링(A2). 다른 화면은 진입 시 1회 조회. 폴링 응답은 상태 · 종료 사유 · confirmed · since 전사 조각(AC3.2.17). 탭이 뒤로 가면 폴링 멈춤, 돌아오면 즉시 1회 + 재개 | A2 · AC3.2.17 |
| 끝내기 | 「이야기 끝내기」/「연습 끝내기」 → P1 → 17 마무리 중 → 정리 중 → 정리 끝. 30초 안에 기기 확인 없으면 ENDED_UNCONFIRMED(보호자 단추 없음) | AC3.3.x |
| 자동 종료 · 종료 표현 | 공백 4분 경고 카드, 6분 SILENCE. 아이 종료 표현(기본 + 추가 ≤5)이면 CHILD_STOP | AC3.2.8 · AC6.4.4 |
| 연결 끊김 | 마지막 기기 메시지 뒤 10초 → 12 점선 + "대화 연결을 다시 잇고 있어요"; 40초 → P2 → 17(연결 끊김)(B10) | AC3.2.10 · AC6.6.x |
| 로그인 유지 · 401 | 어느 화면이든 401이면 토스트 뒤 01. 진행 중 세션은 영향 없음(기기–서버). 재로그인 → 04 | AC1.1.9 |
| 알림 진입 | 푸시 누름 → 04 재조회 → 12 / 17 / 04 미확인 | AC1.7.4 |
| 날짜 · 시각 | Asia/Seoul. 시각은 "hh:mm"(24시간), 날짜는 "m월 d일" · "m/d" · "yyyy년 m월"(문구표 꼴). 기간은 "m/d ~ m/d" | AC5.1.7 · 5.3.12 |
| 동의 개정 | 약관 버전 개정 시 재동의(OUTDATED)는 범위 밖(AC1.1.1 비고) — 구현하지 않음 | B(US) |
| 뒤로가기 | 머리 「←」와 기기 뒤로가기는 같은 동작. 팝업 · 시트가 열려 있으면 먼저 닫힘 | 화면과 분기 |
| 시간값 | NFR6 시간 계약 한 곳에서 읽고 시험에서 오버라이드(AC3.2.19). 화면은 값을 하드코딩하지 않는다 | NFR6 |

## 2. 전환과 타이밍

| 전환 | 방식 | 시간 |
|---|---|---|
| 탭 간 이동(04 ↔ 08 ↔ 18 ↔ 26) | 즉시 교체(애니메이션 없음) | 0 |
| 화면 밀기(04 → 03 · 05 · 05a · SET-01 · 27 · 28 · 30) | 오른쪽에서 밀어 들어오고 「←」로 되돌아감 | 200ms ease-out · `prefers-reduced-motion`이면 즉시 |
| 바텀 시트(SET0 · 02-1) | 아래에서 올라옴, 배경 어둡게, 바깥 누름 · 「←」로 닫힘 | 200ms · reduced-motion 즉시 |
| 팝업(P1 · P2 · P3 · P5 · P7) | 가운데 카드, 초점 가둠, Escape 닫기(P1 · P5 · P7만; P2 · P3는 「확인」만) | 150ms |
| 12 말풍선 추가 | 아래에 붙고 자동 스크롤(사용자가 위로 올려 보는 중이면 자동 스크롤 멈춤 + "새 말풍선" 작은 단추) | 폴링 1주기(2초) 안 |
| 17 상태 전환(마무리 중 → 정리 중 → 정리 끝) | 같은 화면 안에서 카드 글만 바뀜, 「오늘 기록 보기」 켜짐 | 서버 상태 따라 |
| 18 「×」 삭제 | 줄이 즉시 사라지고 토스트 「되돌리기」 | 토스트 ≥ 3초 · 지난 뒤 서버 반영(AC4.1.11) |
| 28 만드는 중 | 같은 화면에 "리포트를 만들고 있어요" 카드 + 「취소」, 성공 시 30으로 밀기 | 60초 타임아웃 → 실패 카드 |
| 30 저장 | 「PDF 저장」 → 다운로드 시작 → "저장했어요" 카드로 교체 | — |
| T19 상태 | 캐릭터 표정 교차 페이드 + 라벨 교체 | 150ms · reduced-motion 즉시 |

## 3. 컴포넌트 명세

### 3.1 하단 탭 바 (BottomTabBar)

| Field | Value |
|---|---|
| Component | BottomTabBar |
| Description | 홈 · 자유대화 · 연습 · 기록 네 탭. 잠금 · 진행 중 분기를 안다 |
| Category | navigation |

States: default / active(현재 탭) / locked(자유대화 · 연습 — 기기 안정 아님) / in-progress(자유대화 · 연습 → 12로 보냄) / closing(17 마무리 중 — 잠김 + 토스트 「끝나는 중이에요」).
Props: `active: 'home'|'free'|'practice'|'records'`, `lockReason?: 'none'|'unstable'|'disconnected'|'unresponsive'|'noDevice'`, `sessionState?: 세션 상태 enum`.
Responsive: 폰 세로 고정 하단(높이 56px, 안전 영역 포함). 큰 화면도 같은 위치(컨테이너 480px 안).
Accessibility: `role=tablist`, 각 탭 `role=tab` + `aria-selected`, 잠김은 `aria-disabled=true`지만 초점은 받아 누르면 이유 토스트(aria-live polite). 아이콘 + 글 라벨 둘 다 표시(색만 아님). 터치 44×44 이상.

### 3.2 상태 카드 (StatusCard) — 04 대화 기기 · 지금 활동, 03 기기 상태 줄

| Field | Value |
|---|---|
| Component | StatusCard |
| Description | 상태명 + 색 점/아이콘 + 사유 한 줄 + 보조 글(마지막 확인) + 행동 단추 |
| Category | display |

States: stable(초록) / unstable(노랑) / disconnected(빨강) / unresponsive(회색) / none(기기 없음) / activity-none / activity-running(초록) / activity-closing(B1) / activity-unknown(조회 실패).
Props: `kind: 'device'|'activity'`, `state`, `lastSeenAt?`, `childName`, `startedAt?`, `elapsedMin?`, `actionLabel`, `onAction`.
Responsive: 가로 100%.
Accessibility: 상태는 색 + 아이콘 + 상태명 글(AC1.4.15). 카드 전체가 아니라 행동 단추만 눌림. `aria-live=polite`로 상태명 변화를 읊음(재조회 시).

### 3.3 판정 카드 (VerdictCard) — 05

States: register(등록 없음 → 「기기 등록」) / ready(안정) / blocked(불안정 · 끊김 · 응답 없음 → 사유 문장). Props: `deviceRegistered`, `netState`, `reason`. Accessibility: `role=status`, 사유 문장은 글.

### 3.4 토스트 (Toast)

| Field | Value |
|---|---|
| Component | Toast |
| Description | 아래 중앙 알림. 조작 없음(4초) 또는 조작 하나(「다시 시도」 · 「되돌리기」, ≥3초 · 초점 시 유지) |
| Category | feedback |

Props: `message`, `action?: {label, onPress}`, `durationMs = 4000`, `kind: 'info'|'error'`. Accessibility: `role=status` + `aria-live=polite`(오류는 `assertive`), 아이콘 + 글, 조작 단추는 Tab으로 도달.

### 3.5 바텀 시트 (BottomSheet) — SET0 · 02-1

Props: `title`, `onClose`, `children`. Accessibility: `role=dialog` + `aria-modal`, 열릴 때 초점은 제목 또는 첫 조작, 닫히면 열었던 단추로 복귀, Escape · 바깥 누름 · 「←」로 닫힘, 배경 스크롤 잠금.

### 3.6 확인 팝업 (ConfirmDialog) — P1 · P2 · P3 · P5 · P7

Props: `title`, `body?`, `primary: {label, destructive?}`, `secondary?`. Accessibility: `role=alertdialog`, 초점 가둠, 기본 초점은 안전한 쪽(P1 「아니오」 · P7 「아니오」 · P5 「계속 동의하기」), 위험 단추(P7 「삭제」)는 색 + 위치 + 라벨로 구분(AC1.6.9). P2 · P3는 단추 하나(「확인」)이고 바깥 누름으로 닫히지 않는다.

### 3.7 코드 입력 (CodeInput) — 05a

| Field | Value |
|---|---|
| Component | CodeInput |
| Description | 영숫자 8칸 OTP식 입력, 4 · 4 묶음 |
| Category | input |

States: empty / partial / complete(「등록」 켜짐) / submitting(칸 잠김 · "등록하고 있어요") / error(빨간 카드 "없는 코드예요" · "이미 등록된 코드예요", 칸은 비우지 않음).
Behaviour: 자동 대문자, 입력 시 다음 칸으로, 삭제 시 이전 칸으로, 8자 붙여넣기 지원, 혼동 글자 규칙은 OQ13.
Accessibility: 칸마다 `aria-label="n번째 글자"`, 묶음 `role=group` + `aria-describedby`로 안내 문장 연결, 칸 44×44 이상, 오류는 글 + `aria-live=assertive`.

### 3.8 칩 (ChipGroup) — 08 관련 상황 · 정보 출처, 03 관심사

Props: `options`, `value`, `mode: 'single'|'multi'`(08은 single — C3, 03 관심사는 입력형 칩 ≤5개). Accessibility: single은 `role=radiogroup`, multi는 체크박스 그룹. 선택은 색 + 체크 아이콘.

### 3.9 서술형 입력 (TextArea) — 08 두 칸

Props: `label`, `maxLength=200`, `placeholder`. States: default / focus / at-limit(200자에서 입력 막힘 + "200/200" 카운터). Accessibility: 보이는 라벨 `<label for>`, 글자 수는 `aria-live=polite`.

### 3.10 말풍선 목록 (TranscriptList) — 12 · 27

| Field | Value |
|---|---|
| Component | TranscriptList |
| Description | 무중(왼쪽) · 아이(오른쪽) 말풍선, 말하는 중 꼬리, 끊김 · 미확인 구간 줄 |
| Category | display |

States: live(12, 폴링 추가) / static(27) / gap-reconnecting(점선 + "대화 연결을 다시 잇고 있어요") / gap-resolved("hh:mm ~ hh:mm 잠깐 끊김" · 27은 "끊김") / unknown-segment("hh:mm ~ hh:mm 확인되지 않은 구간") / speaking("… (말하는 중)").
Props: `items: {speaker: 'mujoong'|'child', text, at, speaking?}[]`, `gaps[]`, `live`.
Accessibility: 발화자 이름이 보조기기에 읽히도록 시각적으로 숨긴 "무중:" / "{아이}:" 접두 텍스트, 목록은 `role=log` + `aria-live=polite`(AC3.2.20), 자동 스크롤은 사용자가 위로 올리면 멈춤.

### 3.11 경고 카드 (WarningCard) — 12 공백 4분

Props: `title`("2분 더 말이 없으면 활동이 끝나요"), `body`("아이가 다시 말하면 그대로 이어져요."). 보호자용 안내라 NFR2 대상 아님(아이에게 보이지 않음). `role=status`.

### 3.12 달력 (Calendar) — 26 · 28

| Field | Value |
|---|---|
| Component | Calendar |
| Description | 월 달력. 26은 활동한 날 점 + 날 선택, 28은 기간 선택(첫 탭 시작 · 둘째 탭 끝 · 드래그) |
| Category | input |

Props: `month`, `mode: 'pick'|'range'`, `markedDays`, `value`, `maxDate=today`, `onPrevMonth/onNextMonth`. States: 28 range-start-only("시작 m/d · 끝나는 날을 누르세요") / range-complete / disabled-future.
Accessibility: `role=grid`, 날 칸 `role=gridcell` + `aria-label="m월 d일 · 활동 n개"`(AC5.1.10), 미래 날 `aria-disabled`, 선택 · 기간은 색 + 머리 글(요약 줄)로도 표시(AC5.3.14), 칸 44×44 이상, 화살표 키로 이동 · Enter로 선택, 드래그 없이 두 번 누르기로 같은 결과.

### 3.13 활동 줄 (ActivityRow) — 26 목록 · 28 체크 목록 · 18 목표 줄

Props: `time`, `kind`, `durationMin?`, `goalCount?`, `interrupted?`, `declined?`, `checkable?`, `checked?`. 18 목표 줄은 `title`, `meta`, `favorite`, `onToggle`, `onFavorite`, `onDelete`. Accessibility: 줄 전체 하나의 단추(26 → 27) 또는 체크박스(28); 18 줄은 세 조작(선택 · ★ · ×)이 각각 이름을 가진다(예 "삭제 — 친구에게 부탁하기")(AC4.1.13), 각 44×44.

### 3.14 세그먼트 탭 (SegmentedTabs) — 27 대화 기록 · 생활 팁 n · 연습 목표 n, 28 빠른 선택

Props: `items: {label, count?}[]`, `value`. Accessibility: `role=tablist`/`tab` + `aria-selected`, 개수는 라벨 글의 일부.

### 3.15 범위 요약 줄 (RangeSummary) — 28

Props: `from`, `to`, `count`. 문구: "m/d ~ m/d · 활동 n개" / "시작 m/d · 끝나는 날을 누르세요" / "m/d ~ m/d · 활동 0개". `role=status`.

### 3.16 진행 · 실패 카드 (JobCard) — 28 만드는 중 · 실패, 17 정리 상태, 27 정리 중 · 실패

States: running(제목 + 본문 + 선택 「취소」) / failed(제목 + 본문 + 「다시 시도」 · 「범위 다시」) / done. Accessibility: `role=status`, 실패는 아이콘 + 글.

### 3.17 기기 상태 뷰 (DeviceStateView) — T19

| Field | Value |
|---|---|
| Component | DeviceStateView |
| Description | 캐릭터 그림(상태마다 표정) + 상태 라벨 + 단추 하나 |
| Category | display |

States: off(「켜기」) / idle(대기) / listening(듣는 중) / thinking(생각 중) / speaking(말하는 중) / closing(마무리) / disconnected(연결 끊김 · "다시 연결하고 있어요") / mic-denied(마이크 안내 + 「켜기」).
Props: `state`, `childName?`, `debug?`.
Behaviour: 「켜기」 → getUserMedia(마이크) + 소리 재생 허용 → WSS 연결 → idle. Wake Lock 요청. 「끄기」 → WSS 닫음 → off(활동 중이면 DISCONNECT 경로 B16). 탭이 뒤로 가도 연결 유지 시도, 끊기면 지수 백오프 재접속(1 · 2 · 4 · 8초, 최대 40초). 상태 전달은 표정 + 음성이 1차, 글 라벨은 보호자 · 운영자용(AC6.2.13).
Accessibility: 라벨은 `aria-live=polite`, 「켜기」 · 「끄기」는 화면에서 가장 큰 터치 영역(≥ 72px), 「끄기」는 캐릭터와 떨어진 구석(그림 31.8). `?debug=1`이면 상태 로그 패널(글).

### 3.18 PDF 템플릿 (ReportPdf) — R-PDF (서버 생성)

구성: 머리(제목 · 아이 · 기간 · 만든 날 · **"이 문서는 진단 결과가 아닙니다."**) → 목차 → 날짜별 본문(발화자 라벨 + 말) → 바닥글(제목 · 아이 · 쪽 n/N · 비진단 문장). 텍스트 PDF(선택 · 검색 가능), 한글 폰트 내장, A4 세로, 본문 11pt · 머리 16pt. 서비스 생성 텍스트는 NFR1 사전 검사(AC5.5.3).

## 4. 화면별 오류 · 로딩 처리

| 화면 | 상황 | 처리 |
|---|---|---|
| 01 | OAuth 취소 · 실패 | 토스트 "로그인하지 못했어요. 다시 시도해 주세요." · 01 유지 · 단추 다시 켜짐 |
| 01 | 인증 중 | 「구글로 시작」 잠김 + 진행 표시 |
| 02 | 동의 저장 실패 | 토스트 "저장하지 못했어요" · 02 유지(체크 상태 유지) |
| 03 | 나이 범위 밖 | 칸 아래 "나이는 6~12 사이로 넣어 주세요" · 「확인」 꺼짐 |
| 03 | 저장 실패 | 토스트 "저장하지 못했어요. 이전 값이 그대로예요." · 편집 상태 유지 |
| 04 | 홈 조회 실패 | 지금 활동 칸 '미확인' + 「다시 확인」, 대화 기기 칸은 마지막 값 + 마지막 확인 시각 |
| 04 | 잠긴 탭 누름 | 이유 토스트(mockups §9-6) → 05 |
| 05 | 상태 조회 실패 | 네트워크 줄 "확인 안 됨 · 마지막 hh:mm", 판정 카드는 blocked |
| 05a | 없는 코드 · 이미 등록 · 동시 등록 패배 | 빨간 카드(글) · 칸 유지 · 다시 입력 |
| 05a | 네트워크 | 토스트 + 「다시 시도」 |
| SET-01 | 검증 오류 | 칸 아래 글(2~20자 · 중복 · 5개) · 「저장」 꺼짐 |
| SET-01 | 저장 실패 | 토스트 "저장하지 못했어요. 다시 시도해 주세요." · 목록 유지 |
| 08 · 18 | 서버 거절(5 사유) | 화면 유지 + 사유 카드 한 줄(mockups §9-12) · 단추 다시 켜짐 |
| 08 · 18 | REQUESTED 시한 초과(B5) · 기기 응답 없음 20초 | 08 · 18로 돌아와 사유 카드 |
| 12 | 끊김 10초 | 점선 + "대화 연결을 다시 잇고 있어요"; 40초 → P2 |
| 12 | 기기 상태 확인 불가(B6) | "기기 상태를 확인할 수 없어요 · 마지막 확인 hh:mm" 줄 |
| 12 | 앱 오프라인 | "실시간 상태를 불러오지 못하고 있어요 · 마지막 확인 hh:mm" 줄, 복구 시 재조회 |
| 12 | 401 | 토스트 → 01(세션은 계속) |
| P1 | 전송 실패 · 401 | 팝업 닫히고 토스트 "끝내기 요청을 보내지 못했어요. 인터넷을 확인해 주세요." · 12 유지 |
| P1 | 이미 종료됨 | 17로 가고 실제 사유 표시 |
| 17 | 정리 실패 | 사유 카드 아래 "기록을 정리하지 못했어요" + 「기록에서 다시 시도」 |
| 18 | 삭제 반영 실패 | 줄 복구 + 오류 토스트 |
| 26 | 조회 실패 | 토스트 + 「다시 시도」 · 달력 유지 |
| 27 | 정리 중 · 실패 | 탭 자리에 JobCard(대화 기록 탭은 보임) |
| 27 | 담기 실패 | 단추 원래대로 + 토스트 |
| 28 | 활동 0건 | "이 기간엔 활동이 없어요" / "다른 기간을 골라 주세요." · 단추 꺼짐 |
| 28 | 만들기 실패 · 60초 초과 | 실패 카드 + 「범위 다시」 · 「다시 시도」 |
| 30 | 저장 실패 | 토스트 "저장하지 못했어요" + 「다시 시도」 · 30 유지 |
| 30 | Web Share 미지원 | 「공유하기」 숨김, 다운로드만(B20) |
| D0 | 서버 연결 실패 | "연결을 확인하는 중" 표시 + 자동 재시도(5초) |
| T19 | 마이크 · 소리 거부 | mic-denied 상태(문구표 57) |
| T19 | 연결 끊김 | "다시 연결하고 있어요" + 백오프 재접속, 40초 넘으면 서버가 DISCONNECT(보호자 P2) |
| T19 | 토큰 거부(RELEASED · 계정 삭제) | D0로 |

## 5. 아이 기기 음성 흐름 (화면 없는 상호작용 · US6.3~US6.5)

| 국면 | 기기 상태 라벨 | 음성(승인 콘텐츠 = ◎ · 생성 = ○) | 시간 규칙 |
|---|---|---|---|
| 시작 안내 | 말하는 중 | ◎ 시작 안내(자유대화 / 연습 다른 문구) → ◎ 참여 질문(예 "안녕, 무중이야. 오늘 나랑 이야기할래?") | 재생 완료 보고 뒤 20초 무응답 → ◎ 재안내 1회 → 20초 → DECLINED |
| 자유대화 | 듣는 중 ↔ 생각 중 ↔ 말하는 중 | ○ 질문 하나씩(게이트 통과분만) | 공백 4분 → (OQ9) · 6분 → SILENCE |
| 연습 · 상황 제시 | 말하는 중 | ◎ 상황 안내 + ◎ 기회 트리거 | 첫 반응 대기 20초(B15) → NOT_PERFORMED |
| 연습 · 첫 반응 | 듣는 중 → 생각 중 | (판정 5초 + 재시도 1회, 대기 중 ◎ 짧은 연결 문구 — OQ14) | — |
| 연습 · 도움 | 말하는 중 → 듣는 중 | ◎ "같이 살펴볼까?" → 동의 시 ○ Cue(정답 문장 없음, F1-b 예시) → 도움 후 반응 1회 | 동의 무응답 10초 → 거부(B15) · Cue 생성 5초 초과 → Cue 미제공(B15) |
| 연습 · 새 상황 | 말하는 중 → 듣는 중 | ◎/○ 새 상황(Cue 금지) | — |
| 종료 의도 | 생각 중 → 말하는 중 → 마무리 | 불명확하면 ◎ 확인 질문(만류 아님 · AC6.4.9) → 확정 시 ◎ 마무리 인사 1회 → 멈춤 | 보호자 끝내기도 같은 ◎ 마무리 인사 1회 · 기기 확인 30초 |
| 연결 끊김 | 연결 끊김 | 재생 중단, 재접속 성공 시 이전 응답 재재생 없음 | 40초 복구 실패 → DISCONNECT |

## 6. 반응형

| 접점 | 기준 | 규칙 |
|---|---|---|
| 보호자 /parent | 폰 세로 360~430px(Android Chrome 기준 · iOS Safari 최선) | 한 열, 컨테이너 최대 480px, 더 큰 화면은 가운데 정렬 + 양옆 여백. 하단 탭은 항상 아래. 글자 확대 200%에서 가로 스크롤 없음 |
| 아이 /device | 데스크톱 · 노트북 Chrome(1024px+) 기준, Android 태블릿 Chrome(768px+) 되면 좋음 | 가로 한 화면: 캐릭터 가운데 큼직, 라벨 아래, 「켜기」/「끄기」 한 구석. 세로 폰은 범위 밖(NFR10) |
| PDF | A4 세로 | 머리 · 목차 · 본문 · 바닥글 |

## 7. 이 명세의 가정

- C1 · C2 · C3 · C4는 mockups.md §10과 같다.
- C5: 전환 애니메이션 시간(150~200ms)과 토스트 4초는 디자이너 기본값이며 화면 설계에 없다. `prefers-reduced-motion`이면 전부 즉시.
- C6: 12 자동 스크롤은 사용자가 위로 올려 보는 동안 멈추고 "새 말풍선" 작은 단추로 돌아온다(화면 설계에 없음 · 접근성 2.2.2).
- C7: 기기 재접속 백오프 1 · 2 · 4 · 8초(최대 40초 안에서)는 임시값이며 Contract Design(OQ13)에서 확정.
