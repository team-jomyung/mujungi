# components.md (초안)

> 초안. 화면 설계 v1.5j(2026-10-08)와 개발 명세 1008에서 뽑았다. AI-DLC Edit File 모드에서 붙여넣기용이며 번호 · 절 구성은 AI-DLC가 다시 매긴다. 화면 설계가 기준이다.

출처: ADR_2S README · ADR-007 · ADR-009 · system-architecture.md, 화면 설계 v1.5j. 화면과 다른 점은 점검표 2절.

| 구성요소 | 책임 | 기술 | 비고 |
|---|---|---|---|
| 보호자 접점 /parent | 화면 01~30 · SET · 팝업. REST 조회 + 폴링 | Next.js 한 앱의 접점 1 | 화면 설계는 12 실시간 말풍선 · 알림 진입을 가짐(점검표 9 · 10) |
| 아이 기기 접점 /device | 켜기 · 끄기 · 상태 표시, 마이크 캡처 · 재생, WSS | Next.js 한 앱의 접점 2 | 화면 D0 · T19. 등록 코드 방식은 점검표 1 |
| Core API Spring | 계정 · 동의 · 아이 · 기기 · 활동 시작 요청 · 기록 · 목표 · 리포트 · 삭제 요청 · Result Worker(내부) | Spring Boot, Spring Security OAuth2(Google), Spring Session JDBC, Flyway | PostgreSQL core 스키마 소유 |
| Realtime Activity Spring | 세션 상태 · 기기 WSS · GPT-Live 중계(B안) · 출력 게이트 · 전사 · 연습 단계 · 판정 | Spring Boot, WebSocket | PostgreSQL realtime 스키마 소유 |
| GPT-Live 중계 | 기기 음성 → Realtime → GPT-Live → (분할 · 전사 · 검사) → 승인 음성 → 기기 | GPT-Live 주 WebSocket | 원음 저장 여부는 점검표 2 |
| PostgreSQL | core 26 + realtime 14 테이블, 앱별 스키마 · 쓰기 권한 | PostgreSQL 1대 | ERD v0.1 → 점검표 반영 뒤 v0.2 |
| Neo4j | 공통 화용 지식 그래프(행동 · 상황 · 관계 · 단서 · 지원) | Neo4j | Core knowledge 모듈이 조회 |
| 앱 간 전달 | 즉시 명령은 내부 HTTP, 유실 불가 사실은 Outbox → Inbox | 테이블 기반, Broker 없음 | ADR-010 |
| 배포 | EC2 1대 · Docker Compose · Nginx(HTTPS · WSS) | ADR-006 | HTTPS 방식 · 담당 미정 |
