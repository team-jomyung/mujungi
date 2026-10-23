# Contract Design Plan and Questions

## Sources

- [desc] Initial description: 현재의 gpt-live 가드레일에서 무중_서비스용_가드레일로 교체하고 싶어
- [scope] Workflow-selected scope: classic / Standard, 기존 앱 변경, 이번 실제 배포 제외.
- [U1] ../units-generation/unit-of-work.md 및 unit-of-work-dependency.md: U16 service가 서버·공개 계약·Java 모델을, U17 ui가 프런트 타입/파싱·표시·기기 소비를 소유한다. U17은 U16에 의존한다.
- [D1] ../domain-design/components.md: 단일 actor의 안내 증거·참여·종료/기기 사실 소유, 화면은 확인된 사실만 소비한다.
- [R1] ../requirements-analysis/requirements.md: FR2, FR4, FR5, FR7 및 NFR1/2/6.
- [C1] contracts/schemas/live-view.schema.json 및 internal-session-view.schema.json: 기존 부모/내부 진행 조회, 엄격한 추가 속성 거부와 기존 상태/순번/불확실성.
- [C2] contracts/device-ws.asyncapi.yaml 및 schemas/ws-messages.schema.json: PLAY_APPROVED_CONTENT의 playbackId/contentVersionId와 PLAYBACK_MARK의 실제 완료 보고가 이미 존재한다.
- [C3] frontend/src/device/ws/deviceSession.ts: 기존 승인 콘텐츠 재생/현재 세션 매칭/중단 보고 소비 경계.

## 계약 설계 계획

- 기존 보호자 REST→Core→Realtime 내부 HTTP 조회 및 기기 WS를 유지한다. 새 이벤트 버스·신규 공급자 API를 만들지 않는다.
- 조회에는 정책 본문이나 새 전사 원문을 실어 나르지 않고, 필요한 안내/참여/인사 사실과 미확인 상태만 설계한다. 종료 표현은 기존 허용된 전사와의 안전한 참조/가용성 경계를 검토하고 없으면 생략한다.
- 실제 안내 증거는 서버가 발행한 현재 재생 ID와 승인 콘텐츠 버전·고정 정책의 내부 연결로 검증한다. 기기 주장만으로 정책 버전을 결정하거나 ACK를 재생 완료로 취급하지 않는다.
- 공개 계약 소유 U16, 소비 U17을 유지한다. 구체적 스키마·오류/마감/중복/순서 역전·actor 종료 후 조회 의미와 고정된 외부 공급자 경계를 contract-summary.md의 정식 spec 블록으로 작성한다.
- 기존 시간/재시도/저장 정책을 유지하고 새 자동 호출 루프를 만들지 않는다. 정책/콘텐츠 부재와 미확인 재생은 일반 대화를 열지 않는다.
- 현재 계약의 엄격한 스키마 때문에 필드 추가만으로 모든 구버전 소비자가 호환된다고 주장할 수 없다. 아래에서 버전 전환 범위를 확인한다.

## Q1 — 기존 클라이언트와의 호환 범위

- A. 기존 주소/메시지를 유지하고 서버·화면을 함께 갱신 (추천): 필요한 응답 필드를 추가하고 계약·Java DTO·프런트 파싱·시험을 함께 수정한다. 새 화면은 이전 응답/필드 없음에서 사실을 추측하지 않는다. 수정 전 화면까지 새 응답을 처리한다고 보장하지 않으며, 실제 배포 시 호환 조합 확인이 필요하다. 이번에는 배포하지 않는다.
- B. 구버전 클라이언트의 동시 사용까지 지원: 기존 응답을 유지하는 명시적 버전 협상/별도 버전 계약을 설계한다. 변경 범위와 구·신버전 조합 시험이 늘어난다. 새 정책의 안내 완료 조건은 구버전에 맞춰 완화하지 않는다.
- X. Other (please specify)

[Answer]: A. 기존 주소/메시지 유지 및 서버·화면 함께 갱신

## Assumptions & Open Questions

- 이미 승인한 HTTP/WS 경계와 소유를 다시 선택하지 않는다. Q1은 요구한 호환성의 구체적 지원 범위를 정한다.
- 실제 승인 안내 음성 확보 방법은 U16 Functional Design에서 확정한다. 계약 설계는 자원 부재를 성공으로 취급하지 않으며, 신규 저장/권한 정책을 자동 도입하지 않는다.
