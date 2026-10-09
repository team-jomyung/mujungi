# NFR Design — U1 u1-walking-skeleton — 질문 파일

> 무중(Mujoong) · 깊이 Standard · 2026-10-09 (클라우드 세션)
> 입력: `nfr-requirements/*.md`(NFRx.y 67개, 마스터는 각 문서의 `## 1`), `functional-design/{rules,entities,functional-spec}.md`, `inception/contract-design/contract-summary.md`(5 · 6 · 7절), `inception/domain-design/components.md`, ADR_2S(ADR-002 · 004 · 005 · 008 · 010 · 019).
> 규모: 질문 12개(Q1 ~ Q12) · 준하 제안(가정) 12건(J73 ~ J84). 3.2가 J72까지 썼으므로 이어서 번호를 매긴다.
>
> **누가 답했나.** 사람 지시(2026-10-09 "반복 순서는 unit-major 그대로 둬. U1의 3.1→3.4까지 진행하고 3.5 앞에서 멈춰. 이어갈 방식을 물으면 자동 진행으로")와 `docs/cloud_handoff.md` 2절에 따라 팀장(준하)의 대리가 채웠다. 모든 답은 '대리 판단(가정)'이다. 이 단계는 설계 단계이므로 클래스 이름 · 완전한 코드는 쓰지 않고 패턴 · 구조 · 경계만 정한다.

## 1. 이미 정해진 것 (확인용)

| 번호 | 정해진 것 | 근거 |
|---|---|---|
| K1 | 앱은 Core API Spring + Realtime Activity Spring 둘, 인스턴스 각 1개, 수평 확장 없음. 상태 소유는 앱별 스키마 + 앱별 DB 역할 | NFR7.4 · NFR9.7 · ADR-019 |
| K2 | 앱 사이: 내부 HTTP(제한 시간은 J26) + Outbox → HTTP → Inbox 영속 전달(재시도 1 · 2 · 4 · 8 · 16 · 30초 20회) | contract-summary.md 5 · 6절 · BR5.x |
| K3 | 세션 전이는 조건부 UPDATE로 승자 하나, 시한은 설정 키 + 주입 가능한 Clock | BR6.8 · BR8.11 · NFR6.x |
| K4 | 로그는 구조화 JSON · 필드 화이트리스트 · 전사/원음/토큰 금지, traceId 전파, 지표는 내부 엔드포인트 | NFR9.8 · NFR9.15 · NFR5.7 · J67 |
| K5 | 외부 경보 · 중앙 로그/추적 수집기 · Redis는 두지 않는다 | J67 · J68 · ADR-002 |

## 2. 정할 것

### Q1. 음성 중계 경로의 성능 설계 (스레드 · 버퍼 · DB 접근)

A. 세션마다 직렬 실행 단위(가상 스레드 + 편지함)를 하나 둬서 한 세션의 프레임 · 공급자 이벤트 · 시계 · 전이를 순서대로 처리한다(락 없이 직렬화). 입력 프레임 대기열은 상한 50개(1초), 출력 대기열은 `device.output-queue-max`(20초 분량), 게이트 구간 버퍼는 구간 단위다. 음성 경로 안에서는 DB를 동기로 부르지 않고 전사 저장은 세션별 순서 보존 비동기 기록기(상한 200건, 200ms 배치)로 한다. 정적 참조 데이터(승인 문구 · 정책 버전)만 프로세스 안 캐시(TTL 10분 · 1분)를 쓰고 기기 상태 · 세션 상태는 캐시하지 않는다
B. 요청마다 스레드 풀에서 처리하고 락으로 보호한다
C. Redis 등 외부 캐시를 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. NFR7.3(세션 격리)과 BR6.8(승자 하나)을 가장 단순하게 만족하는 것은 세션당 직렬 실행이다. 음성 경로의 DB 동기 접근은 중계 증가분 0.5초(NFR5.1) 예산을 DB 지연에 맡기는 일이다. Redis는 ADR-002가 MVP 밖으로 뒀다. 수치는 시작값이고 11/2 측정으로 조정한다. **J73**.

### Q2. 앱 사이 호출의 복원력 패턴

A. 모든 내부 HTTP에 연결 0.5초 · 응답 제한(J26)을 두고, 재시도는 멱등 호출에만 한다(시작: 시간 초과에 한해 같은 sessionId로 1회). Core → Realtime 호출에는 단순 회로 차단기(연속 3회 실패 → 5초 열림, 그동안 시작 요청은 즉시 503)를 둔다. 상태 조회 실패는 Core 사본으로 답한다(12 stale 규칙). 두 앱의 DB 풀 · 스레드는 서로 분리하고 Outbox 발송은 전용 스케줄러 스레드 하나로 격리한다. 별도 라이브러리 없이 작은 내부 구현으로 시작하고 3.5에서 확정한다
B. 회로 차단기 없이 제한 시간 · 재시도만 쓴다
C. 서비스 메시(사이드카)를 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. Realtime이 죽어 있을 때 시작 클릭마다 3초씩 기다리게 하는 것보다 즉시 503이 낫고(BR6.7의 사용자 경험), 열림 구간이 5초로 짧아 복구를 늦추지 않는다. C는 EC2 1대에는 과하다. **J74**.

### Q3. 보안 설계의 구체 선택

A. CSRF: 서버 세션 기반 토큰을 `GET /api/session/csrf`로 받아 변경 요청에 `X-CSRF-TOKEN` 헤더로 보낸다(403 CSRF_TOKEN_INVALID를 받으면 한 번 다시 받아 재시도). 필터 순서: 로깅/traceId → 속도 제한 → 인증(세션 또는 기기 쿠키 또는 내부 토큰) → CSRF → 인가(소유권). Nginx: TLS 종료, HTTP → HTTPS 301, 보안 헤더, `/internal/**` 403, `client_max_body_size 64k`, WSS 업그레이드에 `proxy_read_timeout 75s`(heartbeat 5초 × 15), `X-Forwarded-For`를 `$remote_addr`로 덮어쓰기, `/api/device/bootstrap`에 `limit_req`(앱 속도 제한의 앞 방어선). 비밀은 서버의 환경 파일(권한 600)에서 Compose `env_file`로 주입하고 앱 관리 포트(번호는 3.4가 정한다 — 계약의 Realtime 앱 포트 8081과 겹치지 않게)는 호스트에 게시하지 않는다. 기기 토큰은 256비트 무작위 + SHA-256 저장 + `MessageDigest.isEqual`
B. 쿠키 이중 제출(double submit) CSRF를 쓴다
C. 보안 설정을 앱 코드에만 두고 Nginx는 프록시만 한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. BR8.8이 '취득 경로는 NFR Design이 정한다'고 넘겼고, 서버 세션 토큰은 쿠키를 읽는 JS를 요구하지 않는다(HttpOnly 유지). Nginx 앞 방어선(속도 · 크기 · 내부 경로)은 앱 버그와 독립이라 방어가 겹친다. 75초는 heartbeat 5초 간격에 비해 넉넉하고 40초 끊김 판정보다 길어 Nginx가 먼저 연결을 끊지 않는다. **J75**.

### Q4. 세션 · 연결 상태 소유와 확장 제약 설계

A. Realtime 안에 소켓 레지스트리(deviceId → 연결)와 세션 레지스트리(sessionId → 세션 실행 단위)를 메모리에 둔다. 영속 진실은 DB(세션 행 · 연결 행)이고 메모리 값은 그 사본 + 시계다. 재시작하면 BR6.13이 DB를 정리하고 메모리는 비어 시작한다. 단일 인스턴스를 깨는 지점(소켓 레지스트리 · 세션 시계 · 속도 제한 카운터 · 묘비)을 `scalability-design.md`에 표로 남기고 수평 확장이 필요해지면 다시 설계한다. 동시 활성 세션 상한 설정(`mujoong.session.max-active`, 기본 4)을 두고 넘으면 시작을 423이 아닌 503으로 거절한다 — 이것은 계약에 없으므로 쓰지 않고 지표로만 본다
B. 소켓 레지스트리를 DB 표로 둔다
C. 메시지 브로커를 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 상한 설정(`max-active`)은 계약에 없는 거절 코드를 만들게 되어 쓰지 않기로 하고(지표로만 본다), 단일 인스턴스 전제를 문서로 못 박는다. B는 40초 시계 · 4001 교체가 DB 왕복에 묶여 BR3.7의 잠금 교체와 부딪힌다. 브로커는 ADR-010이 DB Outbox/Inbox로 결정했다. **J76**.

### Q5. Outbox/Inbox 발송 · 처리와 기동 · 종료 설계

A. 발송 폴러는 앱당 스케줄러 스레드 하나가 `SELECT ... FOR UPDATE SKIP LOCKED`로 PENDING 20건을 id 순으로 집어 직렬 발송한다(500ms 주기). Inbox 처리는 응답 뒤 비동기 호출 + 재호출 폴러(선점 UPDATE). 기동 순서: PostgreSQL → 마이그레이션 일회성 작업 → Realtime → Core → Next.js → Nginx, 각 readiness가 통과해야 다음으로. 정상 종료(SIGTERM): 새 연결 거절 → 열린 WSS에 1001 → 진행 중 세션은 BR6.13 규칙에 맡김 → 폴러 정지 → 30초 유예 뒤 종료. 마이그레이션은 배포의 단일 단계(일회성 컨테이너)이고 두 앱은 `validate`만 한다
B. 앱 시작 때 두 앱이 각자 마이그레이션한다
C. 종료 때 진행 중 세션을 끝까지 기다린다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. ADR-002가 '두 앱의 자동 시작이 같은 변경을 경쟁 적용하는 방식을 두지 않는다'고 했다. SKIP LOCKED는 앱이 하나여도 재시작 겹침 순간의 이중 발송을 막는 값싼 보험이다. C는 세션이 최대 수 분이라 배포 중단 60초(NFR4.10)를 넘긴다. **J77**.

### Q6. 관측 설계: 로그 · 지표 이름 · 점검

A. 로그 필드는 `ts · level · traceId · component · event · ownerHash` + 사건별 허용 필드 표(전사 · 원음 · 토큰은 필드 자체가 없다)이고, 로그 인코더 설정과 ArchUnit/로그 샘플 시험이 위반을 막는다. 지표 이름은 `mujoong_` 접두사(예: `mujoong_session_ended_total{reason}`, `mujoong_timeout_fired_total{type}`, `mujoong_relay_added_seconds`, `mujoong_event_delivery_seconds`, `mujoong_devices_connected`, `mujoong_outbox_oldest_pending_seconds`)이고 관리 포트(번호는 3.4가 정한다 — 계약의 Realtime 앱 포트 8081과 겹치지 않게)의 `/actuator/prometheus`로만 읽는다. 외부 가용성 프로브는 호스트 cron 스크립트가 `/api/health`를 1분마다 부르고 결과를 로그 파일에 남긴다. 대시보드는 두지 않고 점검표(수동)로 읽는다
B. Prometheus + Grafana를 Compose에 함께 둔다
C. 로그만 남긴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. Prometheus 형식은 쓰되 수집기를 두지 않으면 EC2 1대에 운영 부담이 없고 11/2 측정은 엔드포인트를 스크립트로 긁어 계산할 수 있다. 수집기를 나중에 붙일 때 앱 변경이 없다. **J78**.

### Q7. 논리 컴포넌트와 장애 도메인

A. 컨테이너 단위 논리 컴포넌트 9개: `nginx`(진입 · TLS · 정적 음성), `web`(Next.js), `core-api`, `realtime-activity`, `postgres`, `migrate`(일회성), `certbot`(인증서 갱신, 호스트 볼륨 공유), `backup`(cron 논리 백업), `probe`(호스트 cron 프로브 — 컨테이너 아님). 장애 도메인 표: postgres 장애 = 전체 쓰기 중단(앱 readiness 실패 → 502), realtime 장애 = 음성 세션만 중단(보호자 조회는 정상), core 장애 = 보호자 화면 중단(진행 중 음성 세션은 DB 쓰기가 Realtime에 있으므로 유지되지만 Outbox 전달이 쌓임), web 장애 = 화면 정적 껍데기만 중단(API는 정상), nginx 장애 = 모두. 폭발 반경을 설계에 표로 남긴다
B. 모놀리식 컨테이너 하나에 넣는다
C. 앱마다 별도 DB 서버를 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 구성은 ADR-006의 Compose 토폴로지와 같고 장애 도메인 표는 3.4 인프라 설계(구성 · 모니터링 · 배포 순서)의 입력이다. C는 EC2 1대 · PostgreSQL 1대 제약과 어긋난다. **J79**.

### Q8. 설정 속성 트리와 시간 키 이름

A. 설정은 `mujoong.*` 하나의 트리로 모으고 프로필(dev · demo · prod)이 값만 바꾼다. 계약 7절의 키는 `mujoong.timing.<키>`(예: `mujoong.timing.session.closing-confirm`)로, 7절에 없는 값은 `mujoong.timing.<그룹>.<이름>`/`mujoong.limits.*`/`mujoong.retention.*`로 이름을 확정해 `reliability-design.md`의 키 표에 둔다(기본값 + 단위 + 소유 앱 + 시험이 줄이는 값). 환경 변수 매핑은 Spring 완화 바인딩(`MUJOONG_TIMING_SESSION_CLOSING_CONFIRM`)을 쓴다. 비밀은 `mujoong.secrets.*`가 아니라 표준 환경 변수 이름만 쓴다
B. 키를 코드 상수로 둔다
C. 외부 설정 서버를 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. BR8.11이 이 단계에 이름 확정을 넘겼다. 한 트리면 시험이 같은 키를 줄여 주입하기 쉽고(NFR6.2) 3.5의 `contracts/timing.md`에 더할 목록이 하나가 된다. **J80**.

### Q9. 시험 지원 설계 (대역 · 주입 · 장애 주입)

A. `Clock` 추상화(운영 = 시스템, 시험 = 가짜 시계), 공급자 대역(fake provider: 연결 거부 · 무응답 · 지연 · 정상 응답 스크립트), 기기 시뮬레이터(WSS 클라이언트: HELLO · HEARTBEAT 중단 · 늦은 ACK · 재접속), 장애 주입은 시험 프로필에서만 켜지는 훅(운영 빌드에서 빈이 없음). 앱 간 호출 대역(Core가 보는 Realtime, Realtime이 보는 Core)을 계약 스펙에서 생성한다. 부하 스크립트는 기기 시뮬레이터를 N개 띄우는 형태로 재사용한다
B. 운영 코드에 시험용 분기를 넣는다
C. 장애 주입 없이 수동 시험만 한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. NFR4의 장애 시험 7가지가 모두 주입이 필요하다. 시험용 분기를 운영 코드에 두면 NFR9(보안)와 어긋난다. 시뮬레이터를 부하 도구로 재사용하면 11/2 측정(NFR7)의 도구가 따로 필요 없다. **J81**.

### Q10. 프런트엔드 설계 (폴링 · 상태 · 오류)

A. /parent: TanStack Query로 REST를 읽고 12는 2초 `refetchInterval`(탭이 숨겨지면 멈추고 보이면 즉시 1회), 다른 화면은 진입 때 1회. 오류는 토스트 + 재시도 단추이며 전사는 로그에 남기지 않는다. /device: React 상태 기계(D0 → 등록 대기 → 등록되었어요 → T19 꺼짐 → 켜기 → 대기 …)이고 네트워크 요청은 단일 비행(한 번에 하나)이며 실패는 5초 뒤 재시도다. 두 접점은 코드 경계를 분리(/parent가 /device를 import하지 않는다, NFR11.1). 번들 목표: /device 초기 JS 150KB(gzip) 이하
B. 두 접점 모두 WebSocket 푸시로 갱신한다
C. 서버 렌더링만 쓰고 클라이언트 상태를 두지 않는다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 12의 2초 폴링은 화면 설계(A2)가 정했고 /parent에 WebSocket을 더하면 NFR11 경계가 흐려진다. 탭이 숨겨졌을 때 폴링을 멈추는 것은 서버 부하와 배터리를 줄이는 값싼 방법이다. 150KB는 시작값이다. **J82**.

### Q11. 데이터 접근 패턴

A. 트랜잭션 경계는 서비스 계층 한 곳이고 업무 변경과 Outbox/Inbox 쓰기는 같은 트랜잭션이다(READ COMMITTED). 동시성 제어는 조건부 UPDATE(행 수 확인)와 부분 유일 색인이 기본이고 `FOR UPDATE`는 BR2.5 · BR3.7 · BR6.8처럼 규칙이 명시한 곳에만 쓴다(락 순서: 보호자 → 코드 → 옛 기기). 직렬화 실패 · 교착은 짧은 재시도(2회)로 흡수하되 멱등한 곳에만. JPA는 `ddl-auto=validate`, 읽기 쿼리는 필요한 열만 가져오는 투영, N+1은 ArchUnit/통합 시험의 쿼리 수 단언으로 막는다. 시각은 앱 `Clock`으로 채우고 DB `now()`는 정렬 기준에 쓰지 않는다
B. SERIALIZABLE을 기본으로 쓴다
C. 낙관적 락 버전만 쓴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 규칙(BR2.3 · BR5.10 · BR6.8)이 이미 조건부 UPDATE를 정했다. SERIALIZABLE은 재시도 폭증으로 지연을 흔든다. 시각을 앱 Clock으로 통일하는 것은 NFR6.4와 BR4.8의 occurred_at 의미(clock_timestamp 대신 같은 Realtime 시계)를 맞춘다. **J83**.

### Q12. DB 역할 · 스키마 · 마이그레이션 부트스트랩

A. PostgreSQL 최초 기동 때 초기화 SQL(컨테이너 init)이 스키마 `core` · `realtime`, 역할 `mujoong_owner`(마이그레이션 전용, DDL), `core_app`, `realtime_app`(각자 스키마의 DML만), 공유 읽기 없음을 만든다. 일회성 `migrate` 컨테이너가 `mujoong_owner`로 Flyway V1을 적용하고 앱은 자기 역할로 `validate`만 한다. 비밀번호는 환경 변수. Flyway 이력 표는 `public`이 아니라 `mujoong_owner` 스키마(`flyway`)에 둔다. 후속 마이그레이션은 '더하기 먼저 → 코드 → 정리' 순서(expand/contract)
B. 앱이 소유자 권한으로 자기 스키마를 직접 마이그레이션한다
C. 하나의 DB 계정을 두 앱이 공유한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. NFR9.7(교차 스키마 쓰기 거부)을 DB 권한으로 지키려면 앱 역할이 DDL 권한을 가지면 안 된다. 일회성 마이그레이션 작업은 ADR-002의 '배포의 단일 단계'와 같다. **J84**.

## 3. 답 분석

- **모호함:** 없음. 수치(큐 상한 · 유예 30초 · 75초 · 5초 열림)는 시작값이며 11/2 측정과 3.5 구현에서 조정할 수 있다.
- **모순 점검:** (1) Q4의 동시 활성 세션 상한 설정은 계약에 없는 거절을 만들므로 설계에서 뺐다(지표로만 감시). (2) Q3의 `proxy_read_timeout 75s`와 `device.disconnect 40초` — Nginx가 앱의 40초 판정보다 먼저 끊지 않도록 더 길게 잡았다. (3) Q5의 정상 종료 유예 30초와 배포 중단 60초(NFR4.10) 사이에 컨테이너 재기동 시간이 들어간다 → 30초 + 기동 20초 이내로 맞춘다. (4) Q6의 외부 프로브 cron은 컨테이너가 아니므로 3.4에서 호스트 구성으로 넘긴다. (5) Q2의 회로 차단기 열림(5초) 동안 NFR4.12의 '시작만 503'이 유지되고 조회는 영향이 없다.
- **후속 질문:** 없음. 열린 항목: ① 회로 차단기 구현(라이브러리 vs 자체)은 3.5에서 확정, ② 정적 음성 경로의 캐시 헤더 · 파일 보관 위치는 3.4, ③ 11/2 측정으로 큐 상한 조정.
