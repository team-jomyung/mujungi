# Contract Design 질문지

> 근거: `../units-generation/unit-of-work.md` · `unit-of-work-dependency.md`(단위 U8~U15, 통합 지점 7곳), `../domain-design/components.md`, `../requirements-analysis/requirements.md`, 계약 정본 `contracts/README.md`(소유 · 더하기만 안전 규칙) · `ai-io.md` · `events.asyncapi.yaml` · `schemas/event-payloads.schema.json`, 코드 `realtime/session/PracticeSessionExtension.java`.
> 사람이 골라야 하는 것만 개별 질문으로 묻고, 계약 정본 규칙 · 코드 사실로 정해지는 것은 맨 아래 가정 묶음으로 확인받는다.

## Q1. 판정 턴 기반(U11)과 새 연습 흐름(U12) 사이 확장점을 어떤 모양으로 둘까 (Domain Design ADR-005 · 검토 지적 R-05, Units Generation 지적 R-01)

지금 `PracticeSessionExtension`은 들어오는 시점 5개(`onActive` · `onChildUtterance` · `onExtensionEvent` · `onSettle` · `practiceRecord`)를 두고, 연습 쪽은 **능력 호출**(`PracticeOutput`의 `playApproved` · `instructProvider` · `requestResponse` · `holdOutput` · `schedule` 등)로 출력하며 상태 변화는 `Signal`(NONE / NEXT_GOAL / LAST_GOAL)로만 돌려준다. CLOSING · 정산 창에서는 능력 호출을 거절한다. 승인된 ADR-005는 "다음 행동 지시를 돌려준다"고 적었다.

A. 지금 능력 호출 모델을 유지하고 넓힌다. 포트 · 능력 · 신호 타입은 U11이 소유하고 U12는 구현만 한다. 새 흐름에 필요한 능력(판정 요청 · 역할 전환 알림 · 버린 출력 전사 받기 등)과 금지 능력을 더한다. ADR-005의 '지시 반환'은 '능력 호출'로 읽는다 — 장점: 기존 U5b 계약 · 시험 · CLOSING 거절 규칙을 그대로 쓴다, 빌드 의존은 U12 → U11 한 방향 / 단점: 연습 단위 시험에 능력 대역(가짜 `PracticeOutput`)이 필요하다 (추천)
B. ADR-005대로 '다음 행동 지시를 값으로 돌려주는' 모델로 바꾼다 — 장점: 연습 흐름이 순수 함수에 가까워 시험이 쉽다 / 단점: 기존 확장점 계약을 깨는 변경이라 레거시 U5b 경로와 실행부를 함께 고쳐야 한다
X. Other (please specify)

[Answer]: A

## Q2. Luna · 제브 구조화 입출력 형식을 어디에 정본으로 둘까

Luna는 JSON Schema 구조화 출력으로 부른다(FR1.1). 이번에 생기는 AI 입출력은 상황 생성 · 자체 검토 · 진행 중 판정(J1~J6) · 목표 후보 · 새 씨앗 · 기록용 재판정(바뀜) · 제브 내부 형식이다. 지금 `contracts/ai-io.md`는 산문으로 형식을 적고, `contracts/schemas/`는 HTTP · 이벤트 본문만 담는다.

A. `contracts/schemas/`에 AI 입출력마다 JSON Schema 파일(`ai-scenario-output.schema.json` 등)을 두고, `ai-io.md`는 규칙 · 금지 필드 · 실패 처리만 적으며 스키마를 가리킨다. `ContractFilesTest`가 파싱 · 예시 검증을 하고, 코드는 같은 스키마로 공급자에 구조화 출력을 요청한다 — 장점: 공급자에 보내는 스키마와 계약이 한 벌이라 어긋나지 않는다, 계약 대조 시험에 걸린다 / 단점: 스키마 파일이 7개 이상 늘고, 프롬프트 버전과 스키마 버전을 함께 관리해야 한다 (추천)
B. `ai-io.md`에 산문 · 필드 표로만 적고, 실제 JSON Schema는 각 앱 코드 자원(`prompts/…`)에 둔다 — 장점: 파일이 적고 앱마다 자유롭다 / 단점: 계약과 실제 스키마가 어긋나도 시험이 잡지 못한다
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

아래는 계약 정본 규칙 · 코드 사실로 정한 가정이다. 하나라도 다르면 B를 골라 주면 개별 질문으로 바꾼다.

1. **소유 · 검토:** 계약은 제공 단위가 소유하고 소비 단위가 검토한다(`contracts/README.md` 소유 규칙). 여러 단위가 함께 쓰는 계약(시간 값 · 오류 코드 · 비밀 이름 · 버전 리소스 형식 · AI 입출력 규칙)은 U8이 소유한다.
2. **더하기만 안전:** 새 필드 · 새 enum 값(`AFTER_REASK` 등) · 새 경로는 더하기로 넣는다. 받는 쪽은 모르는 필드를 무시하고 모르는 값은 '알 수 없음'으로 처리한다. 경로 버전은 두지 않는다. 바뀌는 곳마다 `deviations.md`에 한 행을 더하고 스펙에 표시한다.
3. **기록 캡처 이벤트 발행 주체:** `SessionRecordCaptured`는 지금처럼 세션 실행부(U11)가 발행한다. 새 연습 기록 사실은 U12가 기존 확장점 `practiceRecord` 시점으로 넘긴다(Units Generation 지적 R-02 해소). 새 형식 기록은 이미 있는 `snapshotVersion`을 올려 구분하고, 더하기만 하므로 봉투 `schemaVersion`은 올리지 않는다(FR7.5).
4. **대본 조회 내부 HTTP:** `/internal/practice/scenarios`는 U10이 소유하고, `practice-scenario.schema.json`에 새 대본 필드를 더한다. 제한 시간 · 재시도는 지금 내부 HTTP 설정을 따른다.
5. **외부 공급자:** Luna(OpenAI Responses) · 제브 · GPT-Live · Neo4j는 우리가 소비하는 외부 경계다. 공급자 형식은 어댑터 안에서만 내부 형식으로 바꾼다. 이 시스템이 새로 여는 공개 API는 없다. 보호자 웹(`/parent`)에 가는 변경은 결과 시점 값 · 목표 후보 필드의 더하기뿐이고, 27 칩은 서버가 접은 값을 보내므로 27 화면은 바뀌지 않는다.
6. **경계별 실패 규칙**(요구사항 · 스토리에서 옴)
   - Luna 판정: 2초, 재시도 없이 보류.
   - Luna 생성 · 검토: 스키마 1회 재요청, 전체 15초.
   - 제브: 800ms, 재시도 없이 막음.
   - 그래프: 2초, 넘으면 기본 경로.
   - 결과 작업: 추가 3회(5 · 15 · 30초).
   - 시간 값은 `mujoong.timing.*` 세 곳에 같은 커밋으로 둔다(R-M5).
7. **GPT-Live 선 형식:** FR12.2 실호출 전까지 바꾸지 않는다(ADR-007). 이번 계약에는 지시 주입 형식만 적는다.
8. **앱 안 포트:** core 안 포트(담은 목표 읽기 U14 → U10, 그래프 조회 U15 → U10 · U14)와 realtime 안 포트(판정 · 제브 · 콘텐츠 카탈로그)는 HTTP가 아니라 Java 포트 계약이다. 계약 요약에는 공유 스키마 블록으로 적고, `contracts/` 파일은 만들지 않는다.

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions
