# 포트 · 내부 주소 (ports.md)

> 소유: U1(B). 계약 8절(C12 · C13)의 실행 단위와 infra-spec D-12 · N-2의 망 이름을 코드가 읽는 키로 옮긴다(J109).
> 실제 도메인은 쓰지 않는다. 바깥 호스트는 `SERVICE_HOST` 자리표시자다(J98).

## 1. 포트

| 이름 | 포트 | 컨테이너 · 프로세스 | 호스트 게시 | 비고 |
|---|---|---|---|---|
| core-api 서비스 | 8080 | core-api | 아니오 | 보호자 · 기기 REST(`/api/**`), 내부(`/internal/**`) |
| realtime 서비스 | 8081 | realtime(realtime-activity) | 아니오 | 기기 WSS(`/ws/device`), 내부(`/internal/**`) |
| 관리(Actuator) | 9090 | core-api · realtime 각각 | 아니오(컨테이너 루프백 127.0.0.1만) | 노출 `health,prometheus`. Nginx는 프록시하지 않는다(`/actuator/` 403). 호스트에서 두 앱을 함께 돌릴 때는 프로세스마다 `MANAGEMENT_SERVER_PORT`를 달리 준다 |
| web | 3000 | web(Next.js standalone) | 아니오 | `/parent` · `/device` · `/healthz`. `HOSTNAME=0.0.0.0` · `PORT=3000` |
| PostgreSQL | 5432 | postgres | 아니오(demo · ci). dev Compose만 127.0.0.1에 게시 | backend 망, `scram-sha-256`(pg_hba는 `PG_HBA_ALLOWED_CIDR` 한 대역, J135) |
| Nginx | 80 · 443 | nginx | 예(demo의 유일한 게시) | 검증 스택(compose.ci)은 443을 `127.0.0.1:8443`에만 게시(J108 · J136) |
| 가짜 공급자 | 8090 | fake-provider(dev · ci 전용, `tools/fake-provider/`) | 아니오 | 앱 이미지에 넣지 않는다(J129 · SD-25) |

## 2. 망 이름 · 내부 주소

| 호출 | 기본 주소 | 설정 키 | 환경 변수(Spring 이완 바인딩) | 비고 |
|---|---|---|---|---|
| Core → Realtime(시작 · 끝내기 · 진행 조회 · 이벤트) | `http://realtime:8081` | `mujoong.internal-http.realtime-base-url` | `MUJOONG_INTERNALHTTP_REALTIMEBASEURL` | backend 망 서비스 이름 `realtime`(N-3) |
| Realtime → Core(토큰 확인 · 상태 보고 · 이벤트) | `http://core:8080` | `mujoong.internal-http.core-base-url` | `MUJOONG_INTERNALHTTP_COREBASEURL` | core-api의 backend 망 별칭 `core`(infra-spec D-12) |
| 두 앱 → PostgreSQL | `jdbc:postgresql://postgres:5432/mujoong` | `spring.datasource.url` | `SPRING_DATASOURCE_URL` | DB 이름 `mujoong`(J117). 역할: core-api `core_app`, realtime `realtime_app` |
| Realtime → 공급자 | (demo · prod 프로필 파일에만) | `mujoong.provider.url` | `MUJOONG_PROVIDER_URL` | 허용 호스트 목록 · 형식은 `ai-io.md` 2절(J109 · J141) |

- 대상 주소는 설정으로 고정한 서비스 이름과 포트이고 사용자 입력으로 URL을 만들지 않는다(SSRF 없음, SD-8).
- host 모드 검증(J133)은 `SPRING_DATASOURCE_URL` · `MUJOONG_INTERNALHTTP_REALTIMEBASEURL` · `MUJOONG_INTERNALHTTP_COREBASEURL`을 127.0.0.1 주소로 준다.

## 3. 바깥 경로(Nginx, 참고)

`/api/**` → core-api 8080, `= /ws/device` → realtime 8081(업그레이드), `^~ /media/approved/` → 승인 음성 정적 파일(.wav GET · HEAD),
`/` · `/_next/static/` → web 3000, `/internal/` · `/actuator/` → 403(BR8.9 · SD-3). 경로 허용 목록 정본은 infra-spec 3.2와 `infra/nginx/`다.
