# Infrastructure Design — U1 u1-walking-skeleton — 질문 파일

> 무중(Mujoong) · 깊이 Standard · 2026-10-09 (클라우드 세션)
> 입력: `nfr-design/*.md`(설계 요소 · 설정 키 · 논리 컴포넌트 9개), `nfr-requirements/*.md`, `functional-design/*.md`, `inception/domain-design/components.md`, ADR_2S(ADR-002 · 006 · 008 · 010), 요구사항 제약(EC2 1대 · Docker Compose · Nginx · HTTPS).
> 규모: 질문 12개(Q1 ~ Q12) · 준하 제안(가정) 12건(J85 ~ J96). 3.3이 J84까지 썼으므로 이어서 번호를 매긴다.
>
> **누가 답했나.** 사람 지시(2026-10-09 "반복 순서는 unit-major 그대로 둬. U1의 3.1→3.4까지 진행하고 3.5 앞에서 멈춰. 이어갈 방식을 물으면 자동 진행으로")와 `docs/cloud_handoff.md` 2절에 따라 팀장(준하)의 대리가 채웠다. 모든 답은 '대리 판단(가정)'이다. 이 단계는 설계 단계이므로 IaC 코드 · 완전한 설정 파일은 쓰지 않는다(3.5 이후). 계정 · 비밀번호 · API 키 값은 어디에도 쓰지 않는다.

## 1. 이미 정해진 것 (확인용)

| 번호 | 정해진 것 | 근거 |
|---|---|---|
| K1 | 배포는 AWS EC2 1대(서울) + Docker Compose + Nginx(HTTPS · WSS)이고 쿠버네티스 · 관리형 DB는 쓰지 않는다 | ADR-006 · requirements.md 제약 |
| K2 | 앱은 Core API Spring + Realtime Activity Spring + Next.js 한 앱(/parent · /device), PostgreSQL 물리 1개(core · realtime 스키마), Neo4j(공통 지식)가 상시 컨테이너 후보다. 마이그레이션은 일회성 단계 | ADR-006 2.2 · J84 |
| K3 | 원격 환경은 HTTPS가 전제다(`/device` 마이크 Secure Context). localhost는 개발에서 예외 | ADR-006 3.1 · NFR9.1 |
| K4 | 서버는 필요할 때만 켤 수 있어야 하고 Stop/Start 뒤에도 DB 데이터가 유지되어야 한다 | ADR-006 1절 |
| K5 | 외부 경보 · 중앙 수집기 · Redis는 두지 않는다. 가용성 프로브는 호스트 밖(NFR4.8), 배포 시작 · 끝 시각은 파일에 남긴다 | J67 · J68 · RD-8 |
| K6 | 트렁크 기반 · 병합 시 시연(스테이징 성격) 환경에 자동 배포, 별도 prod 환경은 이번 범위에 없다 | org.md Way of Working · Deployment |

## 2. 정할 것

### Q1. 컴퓨트 · OS · 디스크

A. 서울 리전 EC2 1대, 시작 크기 `t3.large`(2 vCPU · 8 GiB, 측정 뒤 `t3.xlarge`로 올릴 수 있음), Ubuntu Server 24.04 LTS, Docker Engine + Compose v2 플러그인. 디스크는 gp3 루트 30GB + 데이터 볼륨 50GB(별도 EBS, PostgreSQL · 백업 · 인증서 · Neo4j가 쓰는 `/var/lib/mujoong`)이고 인스턴스를 Stop/Start 해도 유지된다. Neo4j는 Compose에 서비스로 정의하되 U1에서는 프로필로 꺼 둔다(쓰는 유닛이 켠다)
B. 소형 인스턴스(t3.small)로 시작해 올린다
C. 컨테이너 서비스(ECS Fargate)를 쓴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. JVM 둘 + PostgreSQL + Next.js + (후속) Neo4j를 한 호스트에서 돌리고 음성 중계와 동시 세션 2개를 재야 하므로 8GiB가 최소선이다(ADR-006도 8GB급을 후보로 둠). small은 힙 두 개만으로 여유가 없다. Fargate는 EC2 1대 · Compose 제약과 NFR7.4의 단일 인스턴스 전제를 흔든다. 크기는 11/2 실측으로 조정한다. **J85**.

### Q2. 네트워크 · 접근 통제

A. 기본 VPC의 퍼블릭 서브넷에 인스턴스 1개 + Elastic IP 1개. 보안 그룹: 인바운드 80 · 443만 모든 곳에서 허용, 22는 열지 않는다. 운영 접속은 AWS Systems Manager Session Manager(인스턴스 프로필에 관리형 정책 부여)만 쓰며, 아웃바운드는 전체 허용(공급자 `wss://api.openai.com`, 이미지 저장소, 패키지 저장소, 인증서 발급). 호스트 게시 포트는 Nginx의 80 · 443뿐이고 앱 · DB · 관리 포트는 Docker 내부망에만 있다
B. SSH(22)를 팀 IP에 한해 연다
C. 별도 VPC · 사설 서브넷 · NAT를 구성한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. SSH 키 배포 · 팀원 IP 변동 관리가 필요 없고 접속 기록이 남는다. 사설 서브넷 · NAT는 단일 인스턴스 시연에 비용만 늘린다. 인바운드가 80 · 443뿐이라 NFR9.6(내부 경로 외부 차단)과 겹쳐 방어한다. **J86**.

### Q3. 도메인 · TLS

A. 팀이 쓸 수 있는 서브도메인 하나(DNS A 레코드 → Elastic IP)에 Let's Encrypt 인증서를 HTTP-01(webroot)로 발급하고, `certbot` 컨테이너가 하루 2회 갱신 확인 → 갱신되면 Nginx 재적재한다. TLS 1.2 이상 · HTTP → HTTPS 301 · HSTS 180일. 서버를 켠 직후와 시연 하루 전에 인증서 만료일 점검(스크립트)을 돌린다. **서비스 도메인이 아직 없으면 시연 전까지 사람이 준비해야 한다**(열린 질문)
B. IP 인증서를 쓴다
C. localhost 중심 시연
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 도메인 + Let's Encrypt가 가장 흔하고 자동 갱신이 검증된 경로다. IP 인증서(B)는 유효기간 · 갱신 방식 확인 비용이 크고, localhost(C)는 보호자 폰과 아이 기기가 네트워크로 분리된 실제 시연을 못 보인다(ADR-006 3.2의 단점). 도메인 확보와 Google OAuth 리다이렉트 URI 등록(U2)이 한 도메인에 걸리므로 일찍 정해야 한다. **J87**.

### Q4. Compose 토폴로지 · 자원 · 로그

A. 서비스: `nginx` · `web`(Next.js) · `core-api` · `realtime` · `postgres` · `neo4j`(프로필 off) · `migrate`(일회성, 프로필) · `certbot` · `backup`. 네트워크 둘: `edge`(nginx · web · core-api · realtime)와 `backend`(core-api · realtime · postgres · neo4j · migrate), 호스트에 게시하는 포트는 nginx 80 · 443뿐. 메모리 한도: postgres 1.5G · core-api 1.5G(JVM 힙 1G) · realtime 1.5G(힙 1G) · web 512M · nginx 128M · certbot 128M · backup 128M(neo4j 켜면 1.5G). 모든 서비스 `restart: unless-stopped`와 헬스체크, 기동 순서는 `depends_on: service_healthy`(postgres → migrate → realtime · core-api → web → nginx). 로그는 `json-file` `max-size 10m` · `max-file 5`. Compose 파일은 `compose.demo.yml`(시연)과 `compose.dev.yml`(개발)을 저장소 `infra/`에 둔다
B. 컨테이너를 하나로 합친다
C. 네트워크를 하나로 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 네트워크를 둘로 나누면 Nginx가 DB에 닿을 수 없어 NFR9.6/9.7의 방어가 하나 더 생긴다(J79 장애 도메인 표와 같은 경계). 메모리 한도 합계는 약 5.4G(Neo4j off, 켜면 6.9G)로 8GiB 안이지만 JVM 힙 외 영역과 OS 몫이 있어 한도를 명시해 OOM이 한 컨테이너에 갇히게 한다. 로그 보관 7일분은 설정만으로 보장되지 않아 용량 상한으로 둔다(RD-8). **J88**.

### Q5. 저장소 · 백업 · 정적 승인 음성

A. PostgreSQL 데이터는 데이터 볼륨의 네임드 볼륨, 백업은 `backup` 컨테이너가 매일 03:00(KST) `pg_dump`(사용자 DB 전체, 스키마 둘 포함) + 역할 정의 덤프를 `/var/lib/mujoong/backups`에 남기고 7일 지난 파일을 지운다(RPO 24시간 — 호스트 상실 제외, RTO 60분, 복원 리허설 1회). EBS 스냅샷 · 오프호스트 복사는 이번 범위에 없다(NFR12.4 사람 결정에 묶임). 승인 음성 정적 파일은 저장소 `infra/seed-audio/`(임시 WAV, J60)에서 배포 때 `/srv/mujoong/approved-audio`로 복사하고 Nginx가 읽기 전용으로 서빙한다(개인정보 없음, 토큰 불필요)
B. EBS 스냅샷을 매일 찍는다
C. 백업하지 않는다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. J62(Q2)의 결정을 그대로 구현한다. 오프호스트 복사는 호스트 상실 때 RPO를 지키지만 삭제된 계정 데이터가 더 오래·더 많은 곳에 남아 NFR12와 더 크게 충돌하므로 사람 결정 전에는 하지 않는다. **J89**.

### Q6. 비밀 관리

A. 비밀은 AWS Systems Manager Parameter Store의 SecureString(경로 접두 `/mujoong/demo/`)에 두고, 배포 스크립트가 인스턴스 프로필 권한으로 읽어 호스트의 `/opt/mujoong/.env`(소유자 root · 권한 600)를 렌더링하고 Compose `env_file`로 주입한다. 대상: DB 소유자 · core_app · realtime_app 비밀번호, 내부 서비스 토큰, 공급자 API 키, 구글 OAuth 클라이언트 비밀(U2 전에는 비움). 값은 저장소 · 이미지 · 로그 · 문서에 없다. 회전은 시연 뒤 수동이고 내부 토큰은 두 앱을 같이 재시작한다. CI의 배포 권한은 GitHub OIDC로 짧은 수명의 역할을 맡는다(장기 액세스 키 없음)
B. `.env` 파일을 직접 서버에 두고 수동 관리한다
C. 전용 비밀 관리 서버(Vault)를 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. Parameter Store 표준 SecureString은 추가 비용 없이 접근 기록과 권한 분리를 주고, 사람이 서버에서 값을 타이핑하지 않게 한다. B는 값이 개인 PC에 남기 쉽고, C는 과하다. 값 자체는 어느 문서에도 쓰지 않는 제약을 지킨다. **J90**.

### Q7. CI 파이프라인

A. GitHub Actions. PR: ① 백엔드(Gradle 빌드 · 단위 · Testcontainers 통합 · ArchUnit 경계 · 의존성 취약점 검사) ② 프런트(설치 · lint · Vitest · 빌드 · `npm audit` high 이상 + 예외 목록) ③ 저장소 비밀 스캔 ④ Nginx 설정 문법 검사(컨테이너 안 `nginx -t`) · `docker compose config` ⑤ 이미지 빌드(푸시 없음) ⑥ 커버리지 80% 하한(org 기본, mvp). main 병합: 같은 검사 + 이미지를 GHCR에 git SHA 태그로 푸시. 검증 명령(Construction Verification Command)은 3.5 시작 때 사람이 고른다(열린 질문)
B. CI 없이 로컬 빌드만 한다
C. 별도 CI 서버(Jenkins)를 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. org 규칙이 병합 전 CI 실행 · 80% 하한을 요구한다. GHCR는 저장소와 같은 곳이라 권한 · 비용이 단순하다. 모노레포 안 세 빌드(core · realtime · web)는 경로 필터로 필요한 것만 돌릴 수 있다. **J91**.

### Q8. 배포 전략 · 롤백

A. 재생성(recreate) 방식: 병합 → 이미지 푸시 → 배포 작업이 OIDC로 역할을 맡아 SSM Run Command로 호스트의 배포 스크립트를 실행한다. 순서: 시작 시각 기록 → 백업 한 번 → 이미지 pull → `migrate` 일회성 실행 → realtime → core-api → web 재생성(헬스 통과 대기) → Nginx 적재 → 스모크 점검 → 끝 시각 기록(`/var/log/mujoong/deploy.log`, NFR4.8의 '계획 배포' 구간). 중단 60초 이내. 롤백은 직전 SHA 태그로 같은 스크립트를 다시 실행(앱만; DB는 expand/contract라 되돌리지 않음, 파괴적 마이그레이션 금지). 병합은 시연 환경에 자동 배포하고 별도 prod 승인 단계는 이번 범위에 없다
B. 블루-그린으로 무중단 배포한다
C. 수동 배포만 한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 소켓 레지스트리 · 세션 시계가 단일 인스턴스를 전제하므로(NFR7.4) 두 버전 동시 기동(블루-그린)은 위험하다. 중단 60초는 NFR4.10과 같다. 배포 직전 백업은 마이그레이션 실패 때 복원 지점을 만든다. **J92**.

### Q9. 모니터링 · 점검

A. 호스트 밖 프로브(팀 PC 스케줄 작업 또는 외부 무료 pinger — 선택은 사람이 한다)가 1분마다 Core health · `/device` 정적 페이지 · WSS 핸드셰이크를 호출한다. 호스트 안 cron(1분)은 앱 health · 디스크 사용률 · 인증서 남은 일수 · 마지막 백업 나이 · 지표 스냅샷(관리 포트를 컨테이너 안에서 호출)을 로그 파일에 남긴다. 기본 EC2 CloudWatch 지표(상태 검사 · CPU)는 콘솔에서 사람이 본다(경보 없음). 대시보드는 두지 않는다. 수동 점검은 시연 당일 하루 2회 점검표(Q8 기준)로 한다
B. CloudWatch 경보 + SNS 이메일을 건다
C. Prometheus + Grafana를 Compose에 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. J68(외부 경보 없음)과 같다. 호스트가 죽으면 호스트 안 스크립트는 못 보므로 프로브가 호스트 밖에 있어야 한다는 지적(NFR4.8)을 반영했다. 경보 연동(B)은 계정 · 수신자 정보가 필요해 사람 결정으로 남긴다. **J93**.

### Q10. 환경 구성

A. 세 환경: `dev`(개발자 PC, `compose.dev.yml`, Spring 프로필 dev, localhost, Nginx 선택, 시험 로그인 허용), `ci`(GitHub Actions, Testcontainers, 프로필 test), `demo`(EC2, 프로필 demo, 시험 로그인 없음, 구글 로그인은 U2). 환경별 차이는 Spring 프로필과 환경 변수 값뿐이고 이미지는 같다. prod 환경은 이번 범위에 만들지 않는다(prod 프로필은 코드에 존재하며 기동 가드만 둔다, NFR9.11)
B. dev와 demo를 같은 설정으로 쓴다
C. prod 환경까지 만든다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 같은 이미지가 환경 사이를 이동해야 시연에서 확인한 것이 곧 배포물이다. dev와 demo를 섞으면 시험 로그인이 인터넷에 열린다(NFR9.11). prod는 실제 아동 데이터 운영 승인(D-14) 뒤의 일이다. **J94**.

### Q11. 비용 · 운영 시간 정책

A. 서버는 필요할 때만 켠다(팀 통합시험 · 멘토링 · 시연 때 Start, 끝나면 Stop; 데이터 볼륨 · Elastic IP 유지, 기동 시 Compose 자동 시작). 개략 비용(서울, 온디맨드, 2026-10 시점 개략이며 시연 전 가격 확인 필요): `t3.large` 시간당 약 US$0.1, 월 상시 약 US$75 ~ 85, 데이터+루트 EBS 80GB 약 US$7 ~ 8/월, 퍼블릭 IPv4 1개 약 US$3.7/월(Stop 상태에서도 과금), 따라서 시연 기간 약 1 ~ 2주 동안 US$30 이내를 가정한다. 예산 한도는 팀이 정한다(열린 질문)
B. 24시간 상시 가동한다
C. 비용을 가정하지 않는다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. ADR-006 1절이 '서버를 필요할 때만 켤 수 있어야 한다'를 요구한다. 가격 도구를 쓰지 못해 수치는 개략 값이며 확정이 아니다(운영 단계 가격 확인 항목). **J95**.

### Q12. 프로비저닝 방식(IaC)

A. 단일 인스턴스 일회성이므로 IaC(Terraform 등)는 쓰지 않는다. 대신 저장소 `infra/`가 정본이다: `compose.*.yml` · Nginx 설정 · 배포 · 백업 · 프로브 스크립트 · `RUNBOOK.md`(콘솔 또는 AWS CLI로 인스턴스 · 보안 그룹 · Elastic IP · 인스턴스 프로필 · Parameter Store · 도메인 설정을 재현하는 순서). 인스턴스를 잃어도 런북과 데이터 볼륨(스냅샷이 없으므로 백업 덤프)으로 복원한다
B. Terraform으로 인프라 전체를 코드화한다
C. 문서화하지 않는다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 7일 구현 일정에서 단일 인스턴스를 위한 IaC 작성 · 검증 비용이 이득보다 크다. 재현 가능성은 런북으로 지킨다. Environment Provisioning 단계는 이번 범위(3.4까지)에 없다. **J96**.

## 3. 답 분석

- **모호함:** 없음. 수치(인스턴스 크기 · 메모리 한도 · 디스크 크기 · 비용)는 시작값이며 11/2 실측과 시연 전 가격 확인으로 조정한다.
- **모순 점검:** (1) Q1의 8GiB와 Q4 메모리 한도 합계(약 5.4G, Neo4j off) → Neo4j를 켜면 6.9G로 70% 판정선(NFR7.2)을 넘는다 → 실측으로 JVM 힙 · postgres `shared_buffers`를 조정하거나 `t3.xlarge`로 올리는 조건을 열린 질문에 둔다. (2) Q5의 백업과 NFR12.4 충돌은 해소되지 않았고 사람 결정으로 남는다. (3) Q8 자동 배포(병합 즉시)와 Q9 수동 점검 사이에는 사람이 보는 시점이 없다 → 배포 스크립트의 스모크 점검이 실패하면 자동 롤백하지 않고 배포를 실패로 표시한다(롤백은 수동 명령). (4) Q3 도메인 미확보 위험 → 3.5/시연 전 필수 항목으로 올렸다.
- **후속 질문:** 없음. 열린 항목: ① 서비스 도메인 · 인증서 방식 확정, ② 외부 프로브 위치와 경보 연동, ③ 오프호스트 백업과 NFR12.4, ④ 예산 한도, ⑤ Neo4j를 켜는 유닛과 시점, ⑥ 8GiB 안에서 메모리 한도 합계의 실측 조정, ⑦ Construction Verification Command 선택.
