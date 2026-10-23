# 자유대화 상호작용 명세

## Sources

- ../requirements-analysis/requirements.md, ../user-stories/stories.md, ../practices-discovery/team-practices.md: 승인된 동작·스토리·작업 기준.
- mockups.md 및 refined-mockups-questions.md Q1-A: 기존 화면과 RP-1–RP-4.
- frontend/src/parent/features/live/LiveScreen.tsx, features/done/DoneScreen.tsx, device/features/status/StatusView.tsx: 기존 조작 경계.
- .codex/knowledge/aidlc-design-agent/component-spec-template.md: 컴포넌트 명세 형식.

## Flow — 안내·참여·일반 대화

1. 보호자 S-08에서 기존 자유대화 시작을 요청한다. 서버의 기존 인증/동의/기기·세션 조건을 따른다.
2. S-12의 준비/대기 상태와 D-19에서 승인 안내를 제공한다. 일반 AI 대화는 잠긴다.
3. 실제 현재 세션의 유효한 안내 완료를 확인한 뒤 참여 질문의 대답을 기다린다. 모델/말풍선/전송 시도는 완료 증거가 아니다.
4. 안내 도중의 조기 응답은 참여 확정으로 쓰지 않는다. 안내 완료 뒤 현재 의사를 다시 확인한다.
5. 수락은 ACTIVE로 한 번만 이동한다. 명확한 거절은 P-03의 RP-2 후 기존 홈 경로다. 불명확·무응답은 기존 대기/재안내 규칙을 사용한다.
6. 안내 리소스·전송·확인 실패는 일반 대화를 열지 않고 기술 실패/종료 경로로 분리한다. UI가 사유를 추측하지 않는다.

보호자 조회가 느리거나 끊겨도 브라우저가 참여 상태를 자체 승인하지 않는다. 새로고침/다른 탭에서는 서버의 같은 세션 상태를 다시 읽고 일반 대화를 중복 시작하지 않는다.

## Flow — 화제·정정·휴식·종료

- 질문·경험·정정이 먼저면 그 내용을 따른다. 첫 관심사 질문을 우선시키지 않는다.
- 거절한 화제에는 새 질문 없이 기다리고, 아이가 대안을 제안하면 옮긴다. 도움/재설명은 같은 정보 요구에서 한 번까지만 한다.
- 휴식/생각할 시간에는 수용하고 기다린다. 타이머는 기존 설정·제외 조건을 유지하며 새 무기한 세션 보장을 약속하지 않는다.
- 명확한 전체 음성 종료는 P-01을 열거나 재확인하지 않고 기존 종료 흐름으로 간다. 모호한 의도만 한 번 확인한다.
- 즉시 음소거는 추가 인사/모델 발화가 없다. 일반 종료의 승인 인사는 최대 한 번이며 실제 재생 여부로만 S-17의 설명을 고른다.
- 보호자 웹의 끝내기는 기존 P-01(취소 기본 초점)을 유지한다. ‘요청 전송’과 ‘기기 종료 확인’을 구분한다.

## Component — 기존 LiveScreen/StatusLine/TranscriptList 조합

| Field | Value |
|---|---|
| Component | LiveScreen, StatusLine, TranscriptList |
| Description | S-12의 서버 상태와 확인 가능한 대화를 표시 |
| Category | display / feedback / action |

### States

| State | Description | Trigger |
|---|---|---|
| loading | 확인된 시작/참여 사실 없음 | 첫 조회/조회 중 |
| waiting | 안내 또는 현재 참여를 기다림 | 서버 WAITING_CHILD |
| active | 현재 모드의 일반 대화 | 서버 ACTIVE |
| error | 기존 오류/실패 카드 | 기술 실패·요청 실패 |
| empty | 아직 보여줄 대화 없음 | items 비어 있음 |
| partial | 연결/폴링 stale·음성 구간 미확인 | 서버/클라이언트의 기존 확인 불가 상태 |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| sessionId | 기존 식별자 | yes | 없음 | 다른 세션과 섞지 않음 |
| view/status/items | 기존 LiveView | 조회 전 미확정 | 기존 로딩 | 실제 서버 상태; 새 API 필드를 여기서 확정하지 않음 |
| 안내/참여 확인·미참여 사유 | 서버의 검증된 사실 | 해당 분기에서 yes | unknown | 일반 대화 허가와 RP-2를 결정. 표현 가능한 계약은 후속 설계에서 정함 |

### Responsive Behaviour

보호자 최대 480px 한 열을 유지한다. 긴 안내/이름은 줄바꿈하고 버튼/탭을 덮지 않는다. 현재 반응형 조건과 확대 점검은 design-system-mapping.md 참조.

### Accessibility

기존 의미 있는 제목·실제 button·StatusLine의 role=status·상태를 설명하는 텍스트를 사용한다. 매 폴링마다 같은 상태를 반복 낭독하거나 원문 전체를 자동 읽지 않는다. RP-2 사유 변경은 기존 팝업의 접근 가능한 이름/초점 복귀를 유지한다.

### Usage Example

현재 `<LiveScreen sessionId={sessionId} />` 조합을 유지한다. 새 일반 대화 시작 버튼이나 강제 참여 팝업을 만들지 않는다.

## Component — 기존 DoneScreen 사유/마무리 표시

| Field | Value |
|---|---|
| Component | DoneScreen 및 기존 presentation |
| Description | 종료 원인·인사 재생·기기 확인을 사실대로 구분 |
| Category | display / feedback / navigation |

### States

| State | Description | Trigger |
|---|---|---|
| loading | 확인된 결과가 없으므로 임의 종료 사유를 채우지 않음 | 첫 조회 |
| closing | 마무리와 기기 확인 대기 | 기존 CLOSING |
| ended | 확인된 종료 사유와 결과 정리 상태 | 기존 종료 상태 |
| unconfirmed | 종료는 처리했지만 기기 멈춤 미확인 | 기존 확인 만료 |
| partial/error | 읽기 실패/연결/결과 작업 실패 | 기존 오류·폴링 상태 |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| view.status/종료 사유/시각 | 기존 LiveView | 확인된 때 | unknown | 인과 사유와 기기 확인을 구분 |
| 승인 인사 실제 재생 사실 | 검증된 재생 상태 | 인사 줄 노출 때 | unknown | 단순 ‘인사를 보냄’은 재생 중 증거가 아님 |
| 확인된 종료 표현 | 확인된 값 또는 없음 | no | 없음 | 없으면 인용 생략. 고정 기본값으로 대체하지 않음 |

### Responsive Behaviour

기존 카드/줄/버튼을 사용한다. 긴 인용은 줄바꿈·기존 허용 범위로 처리하고 안전한 텍스트 렌더링을 유지한다. 새 상세 기록 팝업을 만들지 않는다.

### Accessibility

상태/사유·미확인을 색 외 텍스트로 표시한다. 기록 정리의 동일 문장을 매번 낭독하지 않는다. 기존 홈/기록 이동·키보드 조작·초점 규칙을 유지한다.

### Usage Example

RP-3은 `확인된 표현 없음 → 인용 줄 생략`, RP-4는 `승인 인사 재생 확인 없음 → 인사 줄 생략`이다. 내부 필드 이름/스키마를 새로 확정한 예제가 아니다.

## Component — 기존 StatusView와 음성 경로

| Field | Value |
|---|---|
| Component | StatusView, 기기 음성 재생/입력 경로 |
| Description | 캐릭터·상태·켜기/끄기와 실제 음성 안내 |
| Category | display / action / feedback |

### States

off/mic-request/mic-denied/connecting/standby/listening/thinking/speaking/closing/reconnecting은 현재 StatusDisplay를 유지한다. 새 ‘동의 완료’ 표정을 만들거나 재생 그림으로 안내 완료를 추정하지 않는다.

### Props / Inputs

display·label·subLabel·onTurnOn·onTurnOff는 기존 Props를 유지한다. 안내·참여·재생 완료의 권위는 서버/기기 계약에 있으며 display가 대신 승인하지 않는다.

### Responsive Behaviour

기존 캐릭터 clamp 및 구석 끄기 버튼을 유지한다. 가로/세로·짧은 높이에서 라벨/버튼 겹침을 확인한다.

### Accessibility

장식 캐릭터는 aria-hidden, 상태는 텍스트와 polite 알림, 켜기/끄기는 실제 button이다. 기존 72px 최소 조작과 visible focus, reduced-motion을 유지한다. 음성 끼어들기/종료와 버튼 끄기를 실제 출력 중단 경계에서 검증한다.

### Usage Example

기존 `<StatusView display={display} label={label} subLabel={subLabel} onTurnOn={onTurnOn} onTurnOff={onTurnOff} />` 인터페이스를 우선 유지한다.

## 실패·경합과 후속 설계 인계

| 항목 | 요구되는 구분/보호 | 다음 단계 |
|---|---|---|
| 안내 완료 | 세션·재생 ID·정책 버전에 연결된 실제 완료만 인정, 중복/지난 확인 무시 | Domain/Contract/Functional Design |
| 참여 거절·무응답·기술 실패 | UI 제목/이동을 사유에 맞춤. 불명확 입력은 UI가 유추하지 않음 | Domain/Contract Design의 기존 필드 적합성 확인 |
| 인사 실제 재생·확인된 종료 표현 | 확인 없으면 인사/인용 줄을 숨기는 보수적 매핑. 정보 제공을 위한 계약 변경이 필요하면 명시 | Domain/Contract/Functional Design |
| 종료/끼어들기 뒤 늦은 결과 | 음성/화면 완료로 과거 세대 일반 출력을 다시 열지 않음 | Functional Design, Build and Test |
| User Stories R-01 | 실제 종료 판단 요청에 등록 표현 외 전체 종료·인용·화제 거절·모호한 종료 구분 정책이 전달되는지 대역 요청 경계 검사로 명시 | Functional Design의 규칙/시험 계획, Code Generation/Build and Test. 이 화면 설계만으로 해결되었다고 하지 않음 |

## Assumptions & Open Questions

새 입력/출력 필드 이름과 기술 오류 코드는 이 단계에서 발명하지 않는다. 기존 계약으로 사실을 전달할 수 없으면 필요한 변경·호환성과 시험을 후속 계약 설계에서 확정한다. 기존 UI 전체 접근성·현행 API/원격 상태의 합격 인증은 하지 않는다.
