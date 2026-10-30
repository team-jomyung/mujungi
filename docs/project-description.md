# 무중 (Mujoong) — 현재 프로젝트 설명

6~12세 아이의 화용 능력(상황과 상대에 맞게 말하기)을 집에서 돕는 AI 음성 대화 서비스다. 보호자는 웹에서 활동을 준비·관찰하고 기록을 확인하며, 아이는 기기 웹에서 AI 무중과 자유대화·연습대화를 한다. 진단이나 치료 효과 판단을 제공하는 서비스로 설명하지 않는다.

대조 기준: 2026-10-30 main `08eb7cb0`. 원래 화면 설계·일정과 구현 당시 기록은 [문서 읽는 순서](README.md)에 구분했다.

## 사용자 흐름

1. 보호자는 로그인·동의 후 아이 정보를 등록한다. 생년월일 저장 구조는 [V11 마이그레이션](../backend/core-api/src/main/resources/db/migration/core/V11__child_birth_date.sql)에 있다.
2. 기기를 등록·연결하고 자유대화 또는 연습을 시작한다. 기기와 보호자 화면은 각각 `/device`, `/parent`이며 관리자 화면은 `/admin`이다.
3. 자유대화의 결과 처리에서 목표 후보를 만들고, 유효한 자유대화 생성 목표를 연습 목록에 자동으로 담는다. 자동으로 담기는 것과 보호자가 실제 연습할 목표를 체크하는 것은 다르다. [후처리 구현](../backend/core-api/src/main/java/kr/mujoong/core/practicegoal/CandidateSeedDelivery.java)을 따른다.
4. 연습은 한 번에 목표 1개를 선택한다. 활성 즐겨찾기는 보호자별 최대 5개이고 일반 담은 목표는 페이지당 5개씩 표시한다. [시작 요청 검증](../backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticeStartRequest.java)과 [목표 목록 계약](../contracts/practice-goal-list.md)을 확인한다.
5. 연습에서는 참여 확인 후 활동 안내·새 상황·상대 역할 대화·필요한 도움·마무리를 진행한다. 세부 분기는 [연습 흐름](adaptive-practice-dialogue.md)에 있다.
6. 기록에서 활동과 결과를 보고 상담용 리포트를 만든다. 리포트의 대화 선택은 1개 이상이며 개수 상한은 없다. 화면의 10개는 페이지 크기다. [요청 스키마](../contracts/schemas/report-create-request.schema.json), [선택 화면](../frontend/src/parent/features/reports/ReportRangeScreen.tsx)을 근거로 한다.

## 서비스 구성과 책임

| 구성 | 책임·근거 |
| --- | --- |
| Core API | 보호자·기기·목표·상황 생성·결과·리포트·관리자 API. [REST 계약](../contracts/parent-api.yaml) |
| Realtime Activity | 기기 음성 연결, 실시간 대화 상태, 연습 진행과 출력 제어. [기기 WSS 계약](../contracts/device-ws.asyncapi.yaml) |
| Next.js | 보호자·아이 기기·관리자 화면. [앱 라우트](../frontend/src/app) |
| PostgreSQL | Core와 Realtime 스키마를 분리해 영속 데이터·Inbox/Outbox 관리. [현재 DB 안내](erd/README.md) |
| Neo4j | 목표·상황 생성에 필요한 지식과 관계 조회. 전체 실행은 기본 활성화하되 요청별 그래프 사용 여부는 별도 확인. [실행 안내](neo4j-runtime.md) |
| AI 연결 | GPT-Live 음성 대화, 별도 텍스트 상황 생성·검토, Decisions API 진행 판정, Jev 출력 검사. [AI 계약](../contracts/ai-io.md) |

진행 판정과 출력 안전 검사는 역할이 다르다. 서버가 판정 요청을 구성하고 결과를 검증해 다음 동작을 정한다. Jev는 생성 발화 출력 검사 경로에 있으며, 실제 사용 여부는 실행 설정과 [구성 코드](../backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailConfiguration.java)를 확인해야 한다. 로컬의 STUB 시험 결과를 실제 Jev 검증 결과로 표시하지 않는다.

앱 사이 통신에는 내부 HTTP와 영속 이벤트 Inbox/Outbox가 쓰인다. [내부 HTTP](../contracts/internal-api.yaml)와 [이벤트 계약](../contracts/events.asyncapi.yaml)은 별도 계약이다.

로컬 실행은 [Compose 구성](../infra/compose.demo.yml)과 [실키 실행 안내](live-run.md)를 따른다. 저장소에는 [ECS 서비스 Terraform 정의](../infra/terraform/ecs_services.tf)도 있으므로 모든 환경을 ‘EC2 한 대의 Compose’로 단정하지 않는다. 여기서는 실제 AWS 배포 상태를 확인하지 않았다.
