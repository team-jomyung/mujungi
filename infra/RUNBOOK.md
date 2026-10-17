# 무중 demo 운영 RUNBOOK

> U1 3.5 Step 47 · 정본 순서는 인프라 명세(infrastructure-specification.md) 4.5, 배포 · 롤백은 cicd-pipeline.md 2.6 ~ 2.12, 점검은
> monitoring-design.md 2.5 ~ 2.9다. IaC는 쓰지 않는다(J96). 이 문서와 `infra/`가 재현 순서의 정본이고, AWS 리소스는 콘솔 또는 CLI로
> 이 순서대로 만든다. 같은 내용이 인프라 번들 이미지(`mujoong-infra`)에 들어가 호스트의 `/opt/mujoong/current/RUNBOOK.md`에도 있다.

## 0. 이 문서를 읽는 법

- **값은 쓰지 않는다.** 계정 번호 · 비밀번호 · API 키 · 토큰 값 · 실제 도메인은 이 문서 · 저장소 · 대화 · 채팅 어디에도 쓰지 않는다.
  비밀은 AWS Systems Manager Parameter Store(`/mujoong/demo/`)에만 있고, 이 문서에는 **파라미터 이름**만 있다.
- **자리표시자.** `<SERVICE_HOST>`는 서비스 도메인(10/13 팀 결정 전 임시값, J98), `<SHA>`는 배포할 커밋 SHA 40자,
  `<OWNER>`는 GitHub 저장소 소유자(소문자), `<REPO>`는 저장소 이름, `<INSTANCE_ID>`는 EC2 인스턴스 id다. 명령에 넣을 때도 기록 · 화면 공유를
  조심한다.
- **명령 위치.** `[호스트]`는 Session Manager 세션에서 `sudo -i` 뒤, `[CloudShell]`은 AWS 콘솔 CloudShell(서울 리전), `[GitHub]`는 저장소
  웹 화면이다. 호스트에는 SSH가 없다(22 닫힘, J86).
- **`docker compose`를 직접 부르지 않는다.** 호스트에서는 래퍼 `/opt/mujoong/bin/mj`를 쓴다(프로젝트 · 파일 · env 옵션을 붙이고 위험한 명령을
  거부한다). `mj config`는 `--quiet` 등과만 쓴다(env 파일 값이 화면에 펼쳐지므로).
- **로그 · 출력 붙여 넣기.** 로그를 채팅 · 이슈에 옮길 때는 사건 코드 · traceId · 건수만 옮긴다(NFR9.8).

## 1. 담당

| 역할 | 하는 일 | 비고 |
|---|---|---|
| A 계정 · 클라우드 담당 | AWS 리소스 · IAM · Parameter Store 값 입력, 최초 설치, 배포 · 롤백 판단, 인증서 · 백업 · 복원 리허설, 이 문서 갱신 | J104 · 사람 결정 3(솔로). 실명 · 계정은 쓰지 않는다 |
| 계정 관리자(팀 AWS 계정 소유 주체) | 보안 그룹 인바운드 변경, 인스턴스 유형 변경, IAM 역할 · OIDC 공급자 만들기 | 운영자 IAM에는 이 권한이 없다(infra-spec 3.5) |
| 운영자(Session Manager 접속 가능 구성원) | 인스턴스 Start · Stop, 점검표, 서비스 재시작 | MFA 필수(O-9) |
| D 담당 | 승인 음성 녹음 · 승인 방법(J102), 금지 표현 사전 v1(J101), 공급자 키 발급 | |

연락처 · 연락 수단은 저장소에 두지 않는다. **팀 연락 목록 위치: 미정(팀장이 정해 이 줄을 고친다)**. 에스컬레이션은 1차 점검자 → 2차 팀장
(P1을 15분 안에 못 풀거나 시연 중단 판단) → 3차 클라우드 담당 · 멘토(호스트 상실 · 데이터 손상 · 개인정보 유출 가능성)다
(monitoring 2.9.2).

## 2. 한눈에

### 2.1 구성

- EC2 `mujoong-demo` 1대(서울 `ap-northeast-2`, `t3.large`, Ubuntu 24.04), 보안 그룹 인바운드 80 · 443만, Session Manager만.
- Compose 프로젝트 `mujoong`(`compose.demo.yml`), 상시 컨테이너 7개: `nginx` · `web` · `core-api` · `realtime` · `postgres` · `certbot` ·
  `backup`. 일회성 `migrate`(프로필 `migrate`), 꺼진 `neo4j`(프로필 `knowledge`).
- 망 둘: `edge` 172.30.10.0/24(nginx .10 · web .11 · core-api .12 · realtime .13), `backend` 172.30.20.0/24(internal).
  호스트에 게시하는 포트는 nginx 80 · 443뿐이고 관리 포트 9090은 컨테이너 루프백에만 있다.
- 배포는 CI(GitHub Actions) → OIDC 역할 `mujoong-gha-deploy` → SSM 문서 `mujoong-deploy` → 호스트 `/opt/mujoong/bin/deploy.sh`다.

### 2.2 호스트 경로 (infra-spec 4.2)

| 경로 | 내용 | 소유 · 권한 |
|---|---|---|
| `/opt/mujoong/current` → `releases/<SHA>/` | 릴리스(번들에서 꺼냄): `compose.demo.yml` · `release.env` · `bin/` · `nginx/` · `postgres/` · `backup/` · `seed/` · `seed-audio/` · `host/` · `RUNBOOK.md` | root |
| `/opt/mujoong/bin/` | 고정 진입점(심볼릭 링크가 아닌 디렉터리): `deploy.sh` · `mj` · `host-collect.sh` | root 0755 |
| `/opt/mujoong/env/` | 서비스별 env 파일(비밀) | root 0700 · 파일 0600 |
| `/opt/mujoong/nginx/` · `/opt/mujoong/postgres/` · `/opt/mujoong/backup/` | 컨테이너가 읽기 전용으로 마운트하는 고정 경로(릴리스 링크가 아님) | root 0755 |
| `/opt/mujoong/last-ok` | 마지막 성공 배포 SHA(배포 D9 · 최초 설치가 씀) | root |
| `/var/lib/mujoong/` | 데이터 EBS: `postgres/data`(uid 999 0700) · `certbot/conf` · `certbot/www` · `last-check` | |
| `/var/lib/mujoong-backup/` | 백업 EBS: `mujoong-<UTC>.dump` · 역할 정의 덤프(7일) | uid 999 0700 |
| `/srv/mujoong/approved-audio/` | 승인 음성(`/media/approved/`) | root 0755 · 파일 0644 |
| `/var/log/mujoong/` | `deploy.log` · `power.log` · `backup.log`(30일) · `host-collect.log` · `metrics-snapshot.log`(7일) · `deploy-<id>.log` · `migrate-<id>.log`(30일) · `archive/*.log.gz`(7일 · 40개) | root 0755, `backup.log`만 uid 999 640 |

### 2.3 도구 (`/opt/mujoong/current/bin/`, 저장소 `infra/bin/`)

| 도구 | 하는 일 |
|---|---|
| `mj` | `docker compose -p mujoong --env-file <릴리스>/release.env -f <릴리스>/compose.demo.yml` 래퍼. `--scale` · 평시 `down` · 값이 펼쳐지는 `config` 거부 |
| `deploy-stage1` · `deploy-apply` | 배포 D1 ~ D3 · D3 ~ D9(두 단계 진입) |
| `smoke` | 배포 스모크 8-1 ~ 8-6(인증 없는 확인, J100) |
| `probe-three` | 3종 호출(health · device · wss). 외부 프로브 · 배포 1초 샘플러 · 스모크가 같이 쓴다 |
| `host-collect.sh` | cron 1분 수집기(`host-collect.log` · `metrics-snapshot.log`) |
| `check-since-last` | 점검표 계산(마지막 점검 이후 최댓값) |
| `log-hygiene-check` | 로그 위생(금지 이름 · 비밀 값 · 형식) 위반 건수 |
| `turn-metrics` | 한 턴 지연 로그(LATENCY_TURN · SESSION_ITEMS_SERVED)로 NFR5.1 · 5.2 · 5.3(서버 몫) 값 · 표본 규칙 판정 · 미관측 사유(점검표 9번, A-13) |
| `backup-trigger` | 배포 직전 · 정지 직전 · 기동 직후 · 수동 백업 |
| `power-mark` | `power.log`의 `POWER_READY` · `POWER_STOP` |
| `render-env` · `render-nginx` · `render-pgconf` · `infra-validate` | env 파일 렌더 · Nginx 설정 렌더 · PostgreSQL 설정 렌더 · 정적 검사 |

## 3. AWS 리소스 목록과 태그 (infra-spec 3.6)

시연 전 이 표와 콘솔을 눈으로 대조한다(IaC 대신, 드리프트 대조 기준).

| 리소스 | 이름 · 식별 | 핵심 설정 |
|---|---|---|
| EC2 인스턴스 | `mujoong-demo` | `t3.large` · Ubuntu Server 24.04 LTS 공식 AMI · 기본 VPC 퍼블릭 서브넷 · 인스턴스 프로필 `mujoong-demo-ec2` · IMDSv2 필수(응답 홉 1) · 종료 보호 · CPU 크레딧 `unlimited` · 키 쌍 없음 |
| EBS 루트 | `mujoong-demo-root` | gp3 30 GB · 암호화 · 종료 시 삭제 |
| EBS 데이터 | `mujoong-demo-data` | gp3 50 GB · 암호화 · 종료 시 삭제 안 함 · 같은 AZ · `/var/lib/mujoong` |
| EBS 백업 | `mujoong-demo-backup` | gp3 10 GB · 암호화 · 종료 시 삭제 안 함 · 같은 AZ · `/var/lib/mujoong-backup` |
| Elastic IP | `mujoong-demo-eip` | 인스턴스에 연결 |
| 보안 그룹 | `mujoong-demo-sg` | 인바운드 TCP 80 · 443 (0.0.0.0/0)만, 아웃바운드 전체 |
| IAM 역할 · 인스턴스 프로필 | `mujoong-demo-ec2` | 관리형 `AmazonSSMManagedInstanceCore` + 인라인 `infra/aws/iam-ec2-inline.json`(Parameter Store `/mujoong/demo` · `/mujoong/demo/*` 읽기만) |
| IAM OIDC 공급자 · 배포 역할 | `token.actions.githubusercontent.com` · `mujoong-gha-deploy` | 신뢰 `infra/aws/iam-gha-deploy-trust.json`, 권한 `infra/aws/iam-gha-deploy.json`, 최대 세션 1시간 |
| SSM 문서 | `mujoong-deploy` | `infra/aws/ssm-mujoong-deploy.yml`(Command, 매개변수 `sha` · `mode`) |
| Parameter Store | `/mujoong/demo/<이름>` | 4절 단계 4 |
| AWS Budgets | `mujoong-demo-monthly` | 한도 · 수신자는 사람 결정(O-4) |
| DNS A 레코드 | `<SERVICE_HOST>` → Elastic IP | TTL 300초 |

태그(모든 리소스): `Project=mujoong` · `Environment=demo` · `ManagedBy=runbook` · `Owner=<팀 이름>`. 배포 역할의 SendCommand가 인스턴스 태그
`Project` · `Environment`에 기대므로 인스턴스 태그는 빠뜨리지 않는다.

## 4. 최초 설치 (infra-spec 4.5.1 정본 순서)

최초 설치는 자동 배포 대상이 아니다(cicd 2.11). 단계마다 확인 열을 채우고 넘어간다.

### 단계 1. AWS 리소스 만들기 [CloudShell · 콘솔]

순서: 보안 그룹 → IAM 역할 · 인스턴스 프로필 → 인스턴스(루트 + 데이터 EBS + 백업 EBS, IMDSv2, 종료 보호, 태그) → Elastic IP 연결 →
OIDC 공급자 · 배포 역할 → SSM 문서. 기본 VPC가 없으면 퍼블릭 서브넷 1개와 인터넷 게이트웨이를 먼저 만든다(O-9).

```bash
# [CloudShell] 저장소의 infra/aws/ 파일을 CloudShell에 올린 뒤(파일 업로드) 그 디렉터리에서
export AWS_REGION=ap-northeast-2
# 인스턴스 역할: 인라인 정책(Parameter Store 읽기만, R4-18)
aws iam create-role --role-name mujoong-demo-ec2 --tags Key=Project,Value=mujoong Key=Environment,Value=demo \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam attach-role-policy --role-name mujoong-demo-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam put-role-policy --role-name mujoong-demo-ec2 --policy-name mujoong-demo-parameters --policy-document file://iam-ec2-inline.json
aws iam create-instance-profile --instance-profile-name mujoong-demo-ec2
aws iam add-role-to-instance-profile --instance-profile-name mujoong-demo-ec2 --role-name mujoong-demo-ec2

# GitHub OIDC 공급자(계정에 없을 때만)와 배포 역할. 신뢰 정책의 자리표시자 두 개를 실행 때만 채운다(파일에는 쓰지 않는다)
aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com
account="$(aws sts get-caller-identity --query Account --output text)"
sed -e "s/\${AWS_ACCOUNT_ID}/$account/" -e "s#\${GITHUB_REPOSITORY}#<OWNER>/<REPO>#" iam-gha-deploy-trust.json >/tmp/trust.json
aws iam create-role --role-name mujoong-gha-deploy --max-session-duration 3600 --assume-role-policy-document file:///tmp/trust.json \
  --tags Key=Project,Value=mujoong Key=Environment,Value=demo
aws iam put-role-policy --role-name mujoong-gha-deploy --policy-name mujoong-deploy-only --policy-document file://iam-gha-deploy.json
rm -f /tmp/trust.json; unset account

# SSM 문서(이름은 IAM ARN에 들어가고 대소문자를 구분한다, R4-2)
aws ssm create-document --name mujoong-deploy --document-type Command --document-format YAML \
  --content file://ssm-mujoong-deploy.yml --tags Key=Project,Value=mujoong Key=Environment,Value=demo
```

인스턴스는 콘솔에서 만든다: AMI Ubuntu Server 24.04 LTS(공식), `t3.large`, 키 쌍 **없음**, 보안 그룹 `mujoong-demo-sg`, 인스턴스 프로필
`mujoong-demo-ec2`, 메타데이터 IMDSv2 필수 · 홉 1, 종료 보호 켬, 크레딧 `unlimited`, 루트 gp3 30 GB 암호화 + 추가 볼륨 두 개(데이터 50 GB ·
백업 10 GB, 암호화, 종료 시 삭제 끔), 태그 4개. 이어서 Elastic IP를 할당해 연결한다.

| 확인 | 기준 |
|---|---|
| 콘솔과 3절 표 대조 | 이름 · 태그 · 설정이 같다 |
| Session Manager 접속 | 세션이 열린다(22 포트는 닫혀 있다) |

### 단계 2. 도메인 [DNS 등록처]

`<SERVICE_HOST>`의 A 레코드를 Elastic IP로(TTL 300초). 확인: `dig +short <SERVICE_HOST>`가 Elastic IP다. 도메인이 정해지기 전(10/13 전)에는
최초 설치를 단계 4까지만 하고 기다린다(J98).

### 단계 3. 호스트 기준선 — 패키지 · 볼륨 · 디렉터리 [호스트]

```bash
sudo -i
timedatectl set-timezone UTC
apt-get update && apt-get -y upgrade
apt-get install -y ca-certificates curl jq unzip chrony gnupg logrotate cron
systemctl enable --now chrony chrony-wait.service
# 보안 패치만 자동 적용, 자동 재부팅 끔(시연 중 재부팅 방지)
printf 'Unattended-Upgrade::Automatic-Reboot "false";\n' >/etc/apt/apt.conf.d/52mujoong-no-reboot
# SSH 끔 · 코어 덤프 수집기 끔(1.4)
systemctl disable --now ssh.socket ssh.service 2>/dev/null || true
systemctl disable --now apport.service 2>/dev/null || true; systemctl mask systemd-coredump.socket 2>/dev/null || true
swapon --show   # 빈 출력이어야 한다(스왑 없음)
```

Docker Engine(25 이상) + Compose 플러그인(2.24.4 이상): Docker 공식 apt 저장소 안내대로 설치한다(`docker-ce` · `docker-ce-cli` ·
`containerd.io` · `docker-compose-plugin`). AWS CLI v2: 공식 설치 zip을 받아 서명 확인 뒤 설치한다. 확인: `docker version` · `docker compose version` ·
`aws --version`.

데이터 · 백업 볼륨(이미 데이터가 있는 볼륨을 다시 붙이는 경우에는 **mkfs를 하지 않는다**):

```bash
lsblk -o NAME,SIZE,TYPE,MOUNTPOINT          # 50G = 데이터, 10G = 백업 장치 이름을 확인한다
mkfs.ext4 -L mujoong-data /dev/<데이터 장치>    # 새 볼륨일 때만
mkfs.ext4 -L mujoong-backup /dev/<백업 장치>    # 새 볼륨일 때만
mkdir -p /var/lib/mujoong /var/lib/mujoong-backup
printf 'UUID=%s /var/lib/mujoong ext4 noatime,nofail 0 2\nUUID=%s /var/lib/mujoong-backup ext4 noatime,nofail 0 2\n' \
  "$(blkid -s UUID -o value /dev/<데이터 장치>)" "$(blkid -s UUID -o value /dev/<백업 장치>)" >>/etc/fstab
mount -a && findmnt /var/lib/mujoong && findmnt /var/lib/mujoong-backup
```

디렉터리와 권한(1.4 · 4.2):

```bash
install -d -m 0700 -o 999 -g 999 /var/lib/mujoong/postgres /var/lib/mujoong/postgres/data /var/lib/mujoong-backup
install -d -m 0755 /var/lib/mujoong/certbot /var/lib/mujoong/certbot/conf /var/lib/mujoong/certbot/www
install -d -m 0755 /opt/mujoong /opt/mujoong/releases /opt/mujoong/bin /opt/mujoong/nginx /opt/mujoong/postgres \
  /opt/mujoong/postgres/init /opt/mujoong/backup /srv/mujoong /srv/mujoong/approved-audio /var/log/mujoong /var/log/mujoong/archive
install -d -m 0700 /opt/mujoong/env
install -m 0640 -o 999 -g root /dev/null /var/log/mujoong/backup.log
```

나머지 기준선(systemd 단위 · cron · logrotate · sysctl · `daemon.json` · 고정 진입점)은 첫 릴리스 번들의 `host/`에서 설치한다(단계 5-a).

| 확인 | 기준 |
|---|---|
| `findmnt` 두 경로 | 데이터 · 백업 EBS가 붙어 있다 |
| `chronyc tracking` | Leap status Normal, 오차 1초 이내 |
| `swapon --show` · `systemctl is-active ssh` | 빈 출력 · inactive |

### 단계 4. Parameter Store 값 [CloudShell]

이름 = 환경 변수 이름, 접두 `/mujoong/demo/`(infra-spec 3.7이 정본). 비밀은 **무작위로 만들어 바로 넣고** 값을 화면 · 파일 · 문서에 남기지 않는다.

```bash
export AWS_REGION=ap-northeast-2
umask 077; tmp="$(mktemp)"
gen() { head -c 48 /dev/urandom | base64 -w0 | tr '+/' '-_' | tr -d '=' >"$tmp"; }   # base64url 64자(내부 토큰 43자 · 해시 비밀 32바이트 하한 충족)
for name in POSTGRES_PASSWORD DB_OWNER_PASSWORD DB_CORE_PASSWORD DB_REALTIME_PASSWORD DB_BACKUP_PASSWORD \
  INTERNAL_SERVICE_TOKEN MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET; do
  gen && aws ssm put-parameter --name "/mujoong/demo/$name" --type SecureString --value "file://$tmp" \
    --tags Key=Project,Value=mujoong Key=Environment,Value=demo >/dev/null && echo "put $name"
done
# 사람이 가진 값(입력이 화면에 보이지 않게): (GHCR 패키지가 비공개면) 읽기 전용 토큰(read:packages, 만료 90일 이하)
# 공급자 키(GPT-Live 음성 중계 · Luna 판정 공용 OPENAI_API_KEY)는 이 블록에서 넣지 않는다 — 아래 'R-F15 확인' 기록을 남긴 뒤 그 항목의 명령으로만 넣는다
read -r -s -p 'GHCR_PULL_TOKEN: ' v && printf '%s' "$v" >"$tmp" && unset v && echo && \
  aws ssm put-parameter --name /mujoong/demo/GHCR_PULL_TOKEN --type SecureString --value "file://$tmp" >/dev/null
rm -f "$tmp"; unset tmp
# 비밀 아닌 값(String)
aws ssm put-parameter --name /mujoong/demo/SERVICE_HOST --type String --value '<SERVICE_HOST>'
aws ssm put-parameter --name /mujoong/demo/MUJOONG_SECURITY_ALLOWED_ORIGIN --type String --value 'https://<SERVICE_HOST>'
aws ssm put-parameter --name /mujoong/demo/ACME_CONTACT_EMAIL --type String --value '<연락용 메일 주소>'
aws ssm put-parameter --name /mujoong/demo/GHCR_PULL_USER --type String --value '<토큰을 만든 GitHub 사용자 이름>'
```

- 구글 로그인(U2): 구글 클라우드 콘솔의 OAuth 클라이언트(웹 애플리케이션)에 승인된 리디렉션 URI `https://<SERVICE_HOST>/parent/login/callback`을
  등록하고, 받은 두 값을 넣는다. 둘 다 넣거나 둘 다 넣지 않는다(하나만 있으면 렌더러가 D3를 실패시킨다). 넣지 않으면 렌더러가 빈 변수로 두고
  01 로그인은 실패 토스트로 끝난다(CD-11, Parameter Store는 빈 값을 저장할 수 없다).

  ```bash
  aws ssm put-parameter --name /mujoong/demo/GOOGLE_OAUTH_CLIENT_ID --type String --value '<구글 client id>'
  umask 077; tmp="$(mktemp)"
  read -r -s -p 'GOOGLE_OAUTH_CLIENT_SECRET: ' v && printf '%s' "$v" >"$tmp" && unset v && echo && \
    aws ssm put-parameter --name /mujoong/demo/GOOGLE_OAUTH_CLIENT_SECRET --type SecureString --value "file://$tmp" >/dev/null
  rm -f "$tmp"; unset tmp
  ```

  `GOOGLE_OAUTH_REDIRECT_URI`는 파라미터가 아니다: 렌더러가 `MUJOONG_SECURITY_ALLOWED_ORIGIN` + `/parent/login/callback`으로 만들어
  `core-api.env` · `web.env`에 같은 값을 쓴다. `GOOGLE_OAUTH_CLIENT_ID`도 두 파일에 같은 이름으로 들어가고 client secret은 `core-api.env`에만 간다.
  바꾼 뒤에는 env를 다시 렌더하고 core-api · web을 재생성해야 반영된다.
- GHCR 패키지를 공개로 두기로 정하면 `GHCR_PULL_TOKEN` · `GHCR_PULL_USER`를 만들지 않는다(배포가 로그인하지 않는다, J99).
- `NEO4J_AUTH`는 Neo4j를 켜는 유닛이 만든다.
- **(U9 · U11) `OPENAI_API_KEY`를 넣기 전 R-F15 확인 — 기록 없이는 넣지 않는다.** 확인 대상은 'Luna 판정(core · realtime) · GPT-Live 음성 중계'다.
  키 하나(`OPENAI_API_KEY`)로 GPT-Live 음성 중계(아이 음성이 공급자로 나감, demo realtime은 이 키가 없으면 기동을 거부한다)와
  Luna 판정(core-api는 키만으로, realtime은 키 + 스위치 `mujoong.luna.enabled=true`로 켜짐)이 모두 열리고, 실제 아동 대화 · 결과 자료가 공급자로
  나갈 수 있다. 코드가 막지 않으므로(편차 U9-b) 이 확인이 유일한 방어선이다.
  - [ ] 공급자 약관 · 데이터 사용 조건(학습 사용 · 보존 · 저장 끔 `store=false` 적용 범위)을 확인했다.
  - [ ] 공급자의 개인정보 처리 조건(처리 위치 · 하위 처리자 · 삭제)을 확인했다.
  - [ ] 아동 데이터의 외부 모델 전달이 보호자 동의 범위 안인지 확인했다(동의 문구 · 판).
  - [ ] 확인 대상이 Luna 판정(core · realtime)과 GPT-Live 음성 중계 둘 다임을 기록에 적었다(U11 BR12.4).
  - [ ] 아래 확인 기록 표에 한 줄을 남겼다(키 값 · 계정 정보는 적지 않는다, R-F3).

  | 확인자 | 확인 날짜(UTC) | 확인한 약관 · 처리 조건 판(또는 문서 날짜) | 환경 | 비고 |
  |---|---|---|---|---|
  | (아직 없음 — 첫 투입 전에 채운다) | | | demo | |

  demo에 키를 넣는 날의 순서(cicd-pipeline 4절):
  0. 모델 이름을 기본값(`gpt-6-luna`)이 아닌 값으로 쓰려면 먼저 `OPENAI_ANALYSIS_MODEL` · `OPENAI_JUDGE_MODEL`을 `infra/compose.demo.yml` ·
     `render-env`에 더하는 변경이 병합돼 있어야 한다(없으면 기본값으로만 동작).
  1. 위 확인 기록 표에 한 줄을 남긴다. 기록이 없으면 넣지 않는다.
  2. 아래 명령으로 Parameter Store에 `OPENAI_API_KEY`를 넣고, 같은 SHA로 `DEPLOY`를 다시 실행한다(12절 회전 표와 같은 길: core-api · realtime이
     다시 만들어진다).
  3. 기동 로그에서 core-api의 `LUNA_STARTUP` `lunaState=ENABLED`를 확인한다(8.4). realtime은 스위치가 꺼져 있으면 키가 있어도
     `lunaState=DISABLED_SWITCH_OFF`다.
  4. realtime Luna를 켜려면 1번 확인 기록 뒤에 demo 설정의 `mujoong.luna.enabled`를 `true`로 바꾸고(기본 `false`) 배포한 다음 realtime의
     `lunaState=ENABLED`를 확인한다(U11 계약 편차 (U11-c)). 확인 기록 없이는 바꾸지 않는다.
  - **롤백:** realtime Luna는 `mujoong.luna.enabled`를 `false`로 되돌려 같은 SHA로 `DEPLOY`하면 꺼진다(`DISABLED_SWITCH_OFF`). 키를 비우는 길은
    demo realtime이 기동을 거부하므로(GPT-Live 키 필수, U11 BR12.2) 쓰지 않는다. core-api Luna는 지금처럼 키만으로 켜져 키가 있는 동안 꺼지지
    않는다. 어댑터가 꺼지면 지금과 같은 실패 경로라 데이터 변경 · 마이그레이션 되돌림이 없다. 재기동만으로는 컨테이너 환경이 갱신되지 않으므로
    쓰지 않는다.
  - **키 이름 바꾸기 순서(U11 BR12.4, `PROVIDER_API_KEY` → `OPENAI_API_KEY`):** 이 절차는 적어 두기만 하고 이번 작업은 Parameter Store · 배포를
    바꾸지 않는다.
    1. 키 통일 변경(PR1)을 배포하기 전에 `/mujoong/demo/OPENAI_API_KEY`에 GPT-Live 키가 들어 있는지 확인한다(없으면 위 R-F15 확인 뒤 넣는다).
    2. PR1 이전 이미지는 demo에서 `PROVIDER_API_KEY`가 비면 기동을 거부한다. 롤백 대상(`deploy.log`의 마지막 `result=OK` SHA)이 PR1 이후 SHA뿐이게
       될 때까지 `/mujoong/demo/PROVIDER_API_KEY`를 `OPENAI_API_KEY`와 같은 값으로 함께 둔다. 호스트의 `render-env`는 PR1 이후 옛 이름을 realtime.env에
       쓰지 않으므로, PR1 이전 SHA로 되돌려야 하면 먼저 그 SHA의 `render-env`로 env를 렌더하는지 확인한다.
    3. 롤백 대상이 PR1 이후 SHA뿐이게 된 뒤에 `aws ssm delete-parameter --name /mujoong/demo/PROVIDER_API_KEY`로 지운다.
- (선택, U8) Luna 키 `OPENAI_API_KEY`(core-api · realtime이 받음, U11부터 demo realtime의 GPT-Live 키와 같은 이름)와 제브 키 `JEV_API_KEY`(realtime만 받음). 넣지 않으면 렌더러가 빈 변수로 두고
  앱은 실제 어댑터를 끈다. `OPENAI_API_KEY`는 바로 위 R-F15 확인 기록이 있을 때만 넣는다(`contracts/ai-io.md` 10.2 · 10.10).

  ```bash
  umask 077; tmp="$(mktemp)"
  read -r -s -p 'OPENAI_API_KEY: ' v && printf '%s' "$v" >"$tmp" && unset v && echo && \
    aws ssm put-parameter --name /mujoong/demo/OPENAI_API_KEY --type SecureString --value "file://$tmp" >/dev/null
  read -r -s -p 'JEV_API_KEY: ' v && printf '%s' "$v" >"$tmp" && unset v && echo && \
    aws ssm put-parameter --name /mujoong/demo/JEV_API_KEY --type SecureString --value "file://$tmp" >/dev/null
  rm -f "$tmp"; unset tmp
  ```

확인: `aws ssm get-parameters-by-path --path /mujoong/demo/ --query 'Parameters[].Name'`의 이름 목록이 3.7 표와 같다(값은 조회하지 않는다).

### 단계 5. 첫 배포 전반 [호스트]

5-a. 첫 릴리스 번들 꺼내기와 호스트 기준선 · 진입점 설치(진입 스크립트가 아직 없으므로 손으로 한다, cicd 2.11):

```bash
sudo -i
export AWS_REGION=ap-northeast-2
SHA=<SHA>; REG=ghcr.io/<OWNER>
export DOCKER_CONFIG="$(mktemp -d)"   # 임시 로그인 자격(끝나면 지운다, J99). 패키지가 공개면 로그인 두 줄은 건너뛴다
aws ssm get-parameter --name /mujoong/demo/GHCR_PULL_TOKEN --with-decryption --query Parameter.Value --output text \
  | docker login ghcr.io --username "$(aws ssm get-parameter --name /mujoong/demo/GHCR_PULL_USER --query Parameter.Value --output text)" --password-stdin
docker pull "$REG/mujoong-infra:$SHA"
install -d -m 0755 "/opt/mujoong/releases/$SHA"
cid="$(docker create "$REG/mujoong-infra:$SHA")" && docker cp "$cid:/bundle/." "/opt/mujoong/releases/$SHA/" && docker rm "$cid"
ln -sfn "releases/$SHA" /opt/mujoong/current
R=/opt/mujoong/current
install -m 0755 "$R"/host/entry/* /opt/mujoong/bin/
install -m 0644 "$R"/host/systemd/*.service /etc/systemd/system/
install -d /etc/systemd/system/docker.service.d && install -m 0644 "$R/host/docker.service.d/mujoong-mounts.conf" /etc/systemd/system/docker.service.d/
install -m 0644 "$R/host/daemon.json" /etc/docker/daemon.json
install -m 0644 "$R/host/cron.d/mujoong" /etc/cron.d/mujoong
install -m 0644 "$R/host/logrotate.d/mujoong" /etc/logrotate.d/mujoong
install -m 0644 "$R/host/sysctl.d/90-mujoong.conf" /etc/sysctl.d/ && sysctl --system >/dev/null
systemctl daemon-reload && systemctl restart docker
```

5-b(앞부분). env 파일 렌더 · 고정 경로 · 확인 · migrate:

```bash
"$R/bin/render-env" --out /opt/mujoong/env --region ap-northeast-2      # 이름만 출력된다(값 없음)
"$R/bin/render-pgconf" --out /opt/mujoong/postgres/postgresql.conf
cp -a "$R/postgres/init/." /opt/mujoong/postgres/init/
cp -a "$R/backup/." /opt/mujoong/backup/
install -m 0644 "$R"/seed-audio/*.wav /srv/mujoong/approved-audio/
/opt/mujoong/bin/mj config --quiet
"$R/bin/infra-validate" --only compose-invariants,demo-profile,secret-env-files --env-dir /opt/mujoong/env \
  --release-env "$R/release.env" --mem-limit-bytes $(( $(awk '/^MemTotal:/ {print $2}' /proc/meminfo) * 1024 * 7 / 10 ))
/opt/mujoong/bin/mj --profile migrate pull --quiet --policy missing   # 태그가 SHA · 고정 판이라 없는 이미지만 받는다
/opt/mujoong/bin/mj --profile migrate run --rm migrate     # postgres가 빈 데이터 디렉터리에서 초기화(init)된 뒤 두 위치 적용
rm -rf "$DOCKER_CONFIG"; unset DOCKER_CONFIG
```

| 확인 | 기준 |
|---|---|
| `infra-validate` | `PASS compose-invariants` · `PASS demo-profile` · `PASS secret-env-files` |
| `migrate` 종료 코드 | 0 |

### 단계 5-b. 참조 데이터 시드 [호스트]

demo에는 dev 시드가 없다. `infra/seed/`의 멱등 SQL을 `migrate`와 같은 자격(`migrate.env`)으로 backend 망의 일회성 컨테이너에서 한 번 적용한다
(R4-15, 쓰기 주체는 U2 편집 화면 전까지의 임시 규칙 O-16). 비밀번호는 env 파일로만 넘긴다(명령줄 · `-e` 금지).

```bash
PG_IMAGE="$(sed -nE 's/^[[:space:]]+image:[[:space:]]*(postgres:[^[:space:]]+).*/\1/p' "$R/compose.demo.yml" | head -n 1)"
# 시드는 번호 순서(001 → 002 → 003 → 004)로 적용한다. 검증 명령 B 단계와 같은 순서다(scripts/verify/b-stack.sh)
for seed in 001-reference.sql 002-u2-reference.sql 003-u4a-reference.sql 004-u8-reference.sql; do
  docker run --rm --network mujoong_backend --env-file /opt/mujoong/env/migrate.env -v "$R/seed:/seed:ro" --entrypoint sh "$PG_IMAGE" \
    -c 'PGPASSWORD="$DB_OWNER_PASSWORD" psql -X -q -h postgres -U mujoong_owner -d mujoong -v ON_ERROR_STOP=1 --single-transaction -f "/seed/$1"' \
    seed "$seed"
done
docker run --rm --network mujoong_backend --env-file /opt/mujoong/env/migrate.env --entrypoint sh "$PG_IMAGE" \
  -c 'PGPASSWORD="$DB_OWNER_PASSWORD" psql -X -At -h postgres -U mujoong_owner -d mujoong -c "SELECT c.content_code, count(*) FROM core.core_approved_content c JOIN core.core_approved_content_version v ON v.content_id = c.id WHERE v.status = '"'"'APPROVED'"'"' GROUP BY 1 ORDER BY 1"'
```

확인: `CLOSING|1` · `CONSENT_POLICY|1` · `WAITING_FILLER|1`(정책 버전 1, 승인 문구 2)과 002 · 003의 승인 줄, U8 004의 `COACH_SWITCH|1` ·
`D_REST_ACK|1` · `D_REST_ACK_START|1` · `HELP_OFFER|1` · `PEER_RETURN|1`(음성 없는 승인 발화, `HELP_OFFER`는 v2만 APPROVED).

### 단계 5-c. 스택 기동과 최초 인증서 발급(webroot) [호스트]

인증서가 없으면 nginx가 443을 열지 못하므로 80 전용 임시 설정으로 먼저 띄운다. 발급은 갱신과 같은 webroot 방식이다(standalone 금지, R4-14).

```bash
"$R/bin/render-nginx" --out /opt/mujoong/nginx --mode bootstrap --env-file /opt/mujoong/env/nginx.env
systemctl enable --now mujoong-compose.service mujoong-power-mark.service     # 스택 기동 → POWER_READY
/opt/mujoong/bin/mj run --rm --no-deps --entrypoint sh certbot -c \
  'certbot certonly --non-interactive --agree-tos --webroot -w /var/www/certbot -d "$SERVICE_HOST" -m "$ACME_CONTACT_EMAIL"'
"$R/bin/render-nginx" --out /tmp/nginx-https --mode https --env-file /opt/mujoong/env/nginx.env
NGX_IMAGE="$(sed -nE 's/^[[:space:]]+image:[[:space:]]*(nginx:[^[:space:]]+).*/\1/p' "$R/compose.demo.yml" | head -n 1)"
docker run --rm --network none --entrypoint nginx -v /tmp/nginx-https:/etc/nginx/mujoong:ro \
  -v /var/lib/mujoong/certbot/conf:/etc/letsencrypt:ro "$NGX_IMAGE" -t -c /etc/nginx/mujoong/nginx.conf
"$R/bin/render-nginx" --out /opt/mujoong/nginx --mode https --env-file /opt/mujoong/env/nginx.env && rm -rf /tmp/nginx-https
/opt/mujoong/bin/mj exec -T nginx nginx -s reload -c /etc/nginx/mujoong/nginx.conf
curl -fsS https://<SERVICE_HOST>/api/health
```

확인: `{"status":"UP"}`.

### 단계 5-d. 갱신 경로 리허설 [호스트]

```bash
/opt/mujoong/bin/mj run --rm --no-deps --entrypoint certbot certbot renew --dry-run
grep -E '^authenticator = webroot$' /var/lib/mujoong/certbot/conf/renewal/<SERVICE_HOST>.conf
```

확인: 종료 코드 0, renewal 설정의 authenticator가 webroot(V-4).

### 단계 6. 기동 후 점검 [호스트]

```bash
/opt/mujoong/current/bin/smoke                          # 8-1 ~ 8-6 모두 PASS
/opt/mujoong/current/bin/host-collect.sh --stdout        # ctr=7/7, readiness 200 둘, certDays 20 이상, clockSync=OK
/opt/mujoong/current/bin/backup-trigger --trigger manual   # result=OK, /var/lib/mujoong-backup에 덤프
printf '%s\n' "$SHA" >/opt/mujoong/last-ok               # 첫 CI 배포의 prevSha(롤백 대상)
/opt/mujoong/current/bin/check-since-last --record "최초 설치"
df -h / /var/lib/mujoong /var/lib/mujoong-backup
```

확인: 스모크 모두 PASS, `host-collect.log`가 분마다 한 줄씩 늘어난다, 세 볼륨 사용률 80% 미만.

### 단계 7. 복원 리허설 [호스트]

11절의 R1 ~ R8을 한 번 돌리고 합계를 RTO 60분과 비교해 기록한다.

### 단계 8. 외부 프로브 켜기 [프로브 위치]

실행 위치는 사람이 정한다(O-2, 제안: 팀 PC 스케줄 작업이 정본). Linux(또는 WSL)에서 저장소의 `infra/bin/probe-three`를 1분 간격으로:

```bash
bash infra/bin/probe-three --host <SERVICE_HOST> --interval 60 --out "probe-$(date -u +%F).log" --net-check <제3 대상 호스트>:443
```

결과 줄은 `ts · health · device · wss · …Ms · mode · net · result`이고 파일은 14일 보관한다(monitoring 2.6.2). 확인: 3종이 모두 OK인 분이 생긴다.

## 5. 기동 · 정지 (infra-spec 4.5.2)

| 방향 | 순서 | 작업 | 확인 |
|---|---|---|---|
| 기동 | 1 | 콘솔 · CLI로 인스턴스 Start(Elastic IP · 두 볼륨 유지) | running, 상태 검사 2/2 |
| 기동 | 2 | 자동: 볼륨 마운트 → chrony 동기화 대기 → Docker → `mujoong-compose`(`mj up -d --wait`) → `POWER_READY` → 보충 백업(24시간 초과 시) | `findmnt` · `chronyc tracking` |
| 기동 | 3 | 시작 점검(8.2) 약 10분, 외부 프로브 재개, `/device`에서 D0 확인 | 점검표 기록 |
| 정지 | 1 | 진행 중 세션 없음(`sessionsActive` 0), 정지 점검(8.3) | |
| 정지 | 2 | (선택) `systemctl stop mujoong-compose` — 호스트 종료가 같은 일을 한다: `POWER_STOP` → 정지 직전 백업(최근 1시간 성공이 없으면, 60초) → `mj stop` | 컨테이너 0개 |
| 정지 | 3 | 콘솔 · CLI로 인스턴스 **Stop**(Terminate 금지) | stopped |

평시에는 `docker compose down` · `docker rm`을 쓰지 않는다(json-file 로그가 지워진다). 재시작은 `mj restart <서비스>`.

## 6. 배포 · 롤백

### 6.1 자동 배포(병합)

`main`에 squash 병합 → `ci.yml`의 검사 · `images`(이미지 6종 SHA 태그 푸시) · `skeleton`(검증 명령) · `ci-gate` → `deploy.yml`.
배포 워크플로는 다음이면 **건너뛴다(실패 아님)**: 저장소 변수 `SERVICE_HOST` 미설정(J98) · `AUTO_DEPLOY=false`(동결, 자동 실행만) ·
서버 정지(D0: 인스턴스가 running이 아니거나 SSM이 1분 간격 5번 확인에도 Online이 아님, CD-10). 건너뛰면 작업 요약에 대상 SHA가 남는다.
배포 뒤 S5b(러너에서 공개 도메인 3종 호출 + 인증서 만료 20일 이상)가 돈다.

### 6.2 수동 배포 [GitHub]

Actions → `deploy` → Run workflow: `sha`(비우면 main 최신), `mode`(`DEPLOY` · `ROLLBACK`), `reason`(한 줄). 비밀 · 환경 값만 바꾼 경우에도 같은
SHA로 `DEPLOY`를 돌리면 D3이 env 파일을 다시 렌더하고 바뀐 서비스만 다시 만든다.

CI가 안 될 때는 세션에서 같은 스크립트를 직접 부른다 [호스트]:

```bash
sudo /opt/mujoong/bin/deploy.sh --sha <SHA> --mode DEPLOY --trigger MANUAL
tail -n 3 /var/log/mujoong/deploy.log
```

### 6.3 결과 읽기

- `deploy.log`(한 줄 한 사건, UTC): `DEPLOY_START` · `DEPLOY_STEP`(D2 ~ D9) · `DEPLOY_END result=OK|FAIL|ABORTED failedStep=… downtimeSec=… budget=OK|OVER`.
- 단계별 상세: `/var/log/mujoong/deploy-<배포 id>.log`, 마이그레이션 출력: `migrate-<배포 id>.log`.
- 종료 코드(deploy.sh): 0 성공 · 1 실패 · 3 거부(측정 창 실행 중 · 스택 단위 정지 · 서비스당 2개 이상 · `deploy.log` 기록 불가) · 66 현재 릴리스 없음(최초 설치) ·
  75 이미 배포 중.
- `budget=OVER`(중단 60초 초과)는 배포 실패가 아니라 기록이다(A-19).
- 끝에 `WARN 호스트 기준선 차이`가 나오면 14절대로 적용한다.

### 6.4 롤백 (cicd 2.8)

| 단계 | 할 일 |
|---|---|
| R0 판단 | 스모크 실패 또는 배포 뒤 이상. 롤백할 SHA = `deploy.log`의 마지막 `result=OK` sha(실패한 배포 `DEPLOY_START`의 `prevSha`) |
| R1 동결 | [GitHub] 저장소 변수 `AUTO_DEPLOY=false` |
| R2 실행 | Actions `deploy` 수동 실행(`mode=ROLLBACK`, `sha=<직전 성공 SHA>`) 또는 세션에서 `deploy.sh --sha <SHA> --mode ROLLBACK --trigger MANUAL` |
| R3 스크립트 | D1 → D3 → D5 ~ D9(D2 백업 · D4 migrate 건너뜀). 직전 SHA 이미지가 호스트에 남아 있으면 pull이 바로 끝난다 |
| R4 DB | 되돌리지 않는다(expand/contract, 이전 앱이 expand된 스키마와 호환) |
| R5 확인 | `smoke` · 외부 프로브 · 점검표 2번 |
| R6 정리 | 문제 PR을 `git revert` PR로 되돌리거나 고쳐 정상 흐름으로 배포한 뒤 `AUTO_DEPLOY`를 다시 켠다(`true` 또는 변수 삭제) |

롤백이 안 되는 경우: 직전 SHA 이미지가 호스트 · GHCR 모두에 없으면 그 SHA를 다시 빌드해 푸시한 뒤 롤백한다. 파괴적 마이그레이션이 이미 적용됐으면
11절 복원(합성 데이터 한정)이다. 두 앱 내부 토큰이 어긋났으면 같은 SHA로 `DEPLOY`를 다시 돌린다(두 앱이 함께 다시 만들어진다).

### 6.5 동결과 서버 정지 중 병합

- 동결(`AUTO_DEPLOY=false`): 시연 · 11/2 측정 창 · 롤백 중 · 오류 예산 75% 이상 · 마이그레이션 실패 조사 중. 수동 배포는 동결과 무관하게 된다.
- 서버가 꺼진 동안 병합되면 배포는 건너뛰어진다. 서버를 켠 뒤 시작 점검 S8(`deploy.log` 마지막 OK sha와 main 최신 비교)에서 다르면 6.2로 따라잡는다.

## 7. 마이그레이션 실패 (cicd 2.9)

D4 실패는 구 앱이 계속 돌고 서비스 중단이 없다(`DEPLOY_END result=FAIL failedStep=D4`).

| 실패 형태 | 조치 |
|---|---|
| SQL 오류 | `migrate-<배포 id>.log` 확인 → `main`에 **새 버전 파일**로 고쳐 병합 → 새 배포 |
| 체크섬 불일치(적용된 파일을 고침) | 파일을 원래대로 되돌리고 변경은 새 버전 파일로 |
| 락 대기 · 시간 초과 | 긴 트랜잭션 확인 후 재시도, 연결 한도(max_connections 100) 확인 |
| 연결 불가 | postgres 컨테이너부터 복구(`mj ps postgres` · `mj logs --since 10m postgres`) 후 재배포 |
| 일부만 적용 | 실패한 파일만 고쳐 재배포. 이력에 실패 행이 남은 드문 경우는 소유자 역할로만 수리 |

복원이 필요하다고 판단되면 D2가 만든 배포 직전 백업을 쓴다(11절, RTO 60분).

## 8. 점검

### 8.1 정기 점검(서버를 켠 날 하루 2회) [호스트]

```bash
/opt/mujoong/current/bin/check-since-last                       # [1] ~ [11]의 값과 알림
/opt/mujoong/current/bin/check-since-last --record "<결과 · 조치 한 줄>"   # 점검을 마치며 기록
/opt/mujoong/current/bin/turn-metrics                           # [9] 지연: NFR5.1 · 5.2 · 5.3(서버 몫), 표본 10 미만이면 보류
```

알림이 나오면 monitoring 2.9.1 표대로 먼저 볼 곳을 보고 9절의 절차를 쓴다. `[7]` 가용성은 프로브 위치에서 `probe-daily`(11/2 측정 몫),
`[3-2]` nginx 429 · 403 급증은 첫 점검 값을 기준선으로 사람이 판단한다.

### 8.2 시작 점검(Start 직후)

S1 콘솔 상태 검사 2/2 → S2 `chronyc tracking`(Normal · 1초 이내) → S3 `host-collect.sh --stdout`(ctr=7/7 · readiness 200 둘) →
S4 두 앱 readiness → S5 인증서 20일 이상(30일 미만이면 갱신 로그 확인, 필요하면 9절 인증서) → S6 디스크 80% 미만 → S7 `backupAgeH` 26 이하 →
S8 `deploy.log` 마지막 OK sha = main 최신 → S9 프로브 3종 OK → S10 `power.log`의 새 `POWER_READY`.

### 8.3 정지 점검(Stop 직전)

E1 `sessionsActive` 0 → E2 정기 점검 → E3 `backup.log` 최근 성공(없으면 `backup-trigger --trigger manual`) → E4 표별 행 수 기록
(어느 표든 5만 행 미만) → E5 `log-hygiene-check` 위반 0 → E6 콘솔 Stop.

### 8.4 Luna 사건 줄 (U9)

core-api · realtime은 기동 때 `LUNA_STARTUP` 한 줄(`lunaState` `ENABLED` · `DISABLED_NO_KEY` · realtime만 `DISABLED_SWITCH_OFF`)을, Luna 공급자 시도마다 `LUNA_CALL` 한 줄
(`lunaPurpose` · `lunaOutcome` · `model` · `responseModel` · `promptVersion` · `sentFields` · `latencyMs` · `attempt`)을 남긴다.
본문 · 전사 · 이름 · 키는 없다. 속성 표는 `contracts/ai-io.md` 10.8이고 `log-hygiene-check` 허용 표에도 같은 목록이 있다.

```bash
mj logs --no-log-prefix --since 1h core-api realtime 2>/dev/null | grep -E '"event":"LUNA_(STARTUP|CALL)"' | jq -c '{component, event, attrs}'
```

- 키를 넣지 않은 지금은 `LUNA_STARTUP` `DISABLED_NO_KEY`만 보이고 `LUNA_CALL`은 없다(어댑터 꺼짐 = 기존 실패 경로).
- `lunaOutcome`이 `OK`가 아닌 줄은 WARN이다. `AUTH_FAILED`가 이어지면 키를 확인하고(12절 회전), `RATE_LIMITED` · `SERVER_ERROR`가
  이어지면 공급자 상태를 본다. 호출자마다 실패 경로(판정 보류 · 결과 작업 재시도 · `SCENARIO_GEN_FAILED`)가 따로 있다.

### 8.5 Luna 실호출 시험 `lunaLiveTest` (U9, 개발 PC) [개발 PC]

실공급자를 부르는 유일한 시험이다(편차 U9-c). 기본 `test` · `check` · CI · `verify.sh`에는 들어가지 않는다. 키는 그 PC의 env로만 넣고
채팅 · 파일 · 저장소에 두지 않는다(R-F3 · R-F13). 합성 자료만 보낸다(R-F15).

```bash
cd backend
read -r -s -p 'OPENAI_API_KEY: ' OPENAI_API_KEY && export OPENAI_API_KEY && echo
./gradlew lunaLiveTest          # core-api · realtime 둘 다(하나만이면 :core-api:lunaLiveTest)
unset OPENAI_API_KEY
cat core-api/build/luna-live/report.txt realtime-activity/build/luna-live/report.txt
```

- 보고서 한 줄 = 목적 · 결과 사유 · 응답 모델 · 지연(원문 없음). 키가 없으면 `outcome=SKIPPED_NO_KEY`이고 시험은 '건너뜀'이다(통과 아님).
- 결과는 '실제 API · 합성 자료' 증거로만 적고, 기본 시험 · 끝-끝 결과(테스트 대역)와 섞지 않는다(R-M11).
- 첫 실행에서는 목적마다 요청용 스키마 사본이 거절되지 않는지(`REQUEST_REJECTED` 아님)와 `temperature` 없이 받아들여지는지를 먼저 본다.

### 8.6 Luna 응답 여부 · 지연 확인 `lunaReachTest` (U11, 개발 PC) [개발 PC]

8.5는 운영 시한(`mujoong.timing.judgment.live-budget` 2초)에 묶여 `TIMEOUT`이면 Luna가 응답하는지 알 수 없다. 이 작업은 realtime 운영
호출부를 그대로 쓰고 시도 시한만 넉넉히 줘서 합성 J6 판정 요청을 여러 번 보낸다(운영 설정은 바꾸지 않는다). 기본 `test` · `check` · CI ·
`verify.sh`에는 들어가지 않는다(루트 `verifyLunaReachIsolation`). 합성 자료만 보내고(R-F15), 키는 그 PC의 env로만 넣는다(R-F3 · R-F13).

```bash
cd backend
read -r -s -p 'OPENAI_API_KEY: ' OPENAI_API_KEY && export OPENAI_API_KEY && echo
./gradlew :realtime-activity:lunaReachTest
unset OPENAI_API_KEY
cat realtime-activity/build/luna-reach/report.md
```

- 선택 env: `LUNA_REACH_TIMEOUT_SECONDS`(1 ~ 600, 기본 60) · `LUNA_REACH_COUNT`(1 ~ 20, 기본 2 — 공급자 사용량 한도 때문에 낮게 둔다) ·
  `OPENAI_JUDGE_MODEL`(비면 운영 기본 모델). 비밀이 아니다.
- 보고서 첫 줄은 `outcome=REACHED|NOT_REACHED|SKIPPED_NO_KEY`다. REACHED는 1회 이상 OK다. 표는 회차 · 사유 · 응답 모델 · 스키마 통과 ·
  1 · 2순위 코드와 확률 · 지연 · 사용량(응답 `usage`의 토큰 숫자)이고, 요약은 성공 수 · 지연 중앙값 · 최대 · 2초 이하 비율 · 10초 이하 비율 ·
  사용량 합계다. 사용량을 받지 못한 회차(TIMEOUT 등)는 '모름'이고 과금 여부를 단정하지 않는다. 원문 · 키 · 주소는 없다.
- 운영 클라이언트는 응답 `usage`를 버리므로, 이 도구는 같은 운영 부품(정리 · 스키마 · 본문 조립 · 전송 · 응답 해석)을 같은 순서로 직접 부른다.
  사유 분류가 운영과 같은지는 기본 시험(`LunaReachIsolationTest`, 루프백 대역)이 대조한다.
- 금액은 계산하지 않는다. 단가는 OpenAI 결제 화면에서 확인한다(보고서는 사용량만 남긴다).
- 결과는 '실제 API · 합성 자료' 증거로만 따로 적는다(R-M11). 키가 없으면 `SKIPPED_NO_KEY`이고 시험은 '건너뜀'이다(통과 아님).

### 8.7 GPT-Live 격리 실호출 `gptLiveTest` (U11, 개발 PC) [개발 PC]

U11 첫 작업(위험 R1)의 실호출 확인이다. 기본 `test` · `check` · CI · `verify.sh`에는 들어가지 않는다(루트 `verifyGptLiveIsolation`).
합성 음성만 보내고(R-F15), 키는 그 PC의 env로만 넣는다(R-F3 · R-F13). 키를 넣기 전에 단계 4의 R-F15 확인 기록(확인 대상: Luna 판정 · GPT-Live 음성 중계)을
남긴다. 운영 송수신 부품(팩토리 · 세션 · 해석기) 하나로만 시도하고 한 실행이 연결을 한 번 연다(옛 키트 형식 비교 모드는 없앴다). 흐름은 세션 시작 →
합성 말소리 여러 발화 → 고정 지시문 추가 두 번 → 응답 생성 → (선택) `session.update` → 종료이고, 결과는 설계 W7 확인 ① ~ ⑨를 항목마다 판정 · 관측으로 남긴다.

```bash
cd backend
read -r -s -p 'OPENAI_API_KEY: ' OPENAI_API_KEY && export OPENAI_API_KEY && echo
./gradlew :realtime-activity:gptLiveTest
unset OPENAI_API_KEY
cat realtime-activity/build/gpt-live/report.md
```

- 선택 env(비밀 아님): `GPT_LIVE_RATE`(세션 시작에 싣는 입력 표본율, 기본 24000 — 24kHz 수락 확인, 거절되면 16000으로도 시도) ·
  `GPT_LIVE_INPUT_PCM`(합성 말소리 파일 경로, WAV 또는 PCM16LE 모노 원시, 없으면 저장소 합성음 자리 파일) ·
  `GPT_LIVE_INPUT_RATE`(원시 PCM의 표본율, 기본 24000, 키트 TTS 캐시는 16000) ·
  `GPT_LIVE_UTTERANCES`(자동 응답을 관찰하려고 보내는 발화 수, 기본 3, 1 ~ 10 — 비용이 발화 수에 비례한다) ·
  `GPT_LIVE_UPDATE_JSON`(자동 응답을 끄는 `session.update` 메시지 JSON, type이 `session.update`인 객체. 없으면 그 시험은 건너뛴다).
- 보고서 첫 줄은 `outcome=…`(① 입력 표본율 수락과 ⑦ append + create 뒤 응답이 모두 PASS일 때만 PASS), 다음 줄은 `rate=… utterances=…`이다.
  표는 항목 · 판정 · 관측이고 ① ~ ⑨ 뒤에 종료 형식 · 오류 사건(가린 문장) · 지원 type 목록 · 수신 사건 모양 · 사용량(`session.usage.updated` 마지막 값 ·
  세션 길이) 행이 있다. ②(출력 전사 사건)가 `NOT_OBSERVED`면 구현을 멈추고 사람과 다시 정한다(BR9.2). 전사 원문 · 음성 · 키는 담지 않는다(R-F14).
- 금액은 계산하지 않는다. 단가는 OpenAI 결제 화면에서 확인한다(보고서는 사용량만 남긴다).
- 결과는 '실제 API · 합성 자료' 증거로만 따로 적는다(R-M11). 키가 없으면 `SKIPPED_NO_KEY`이고 시험은 '건너뜀'이다(통과 아님).

## 9. 복구 시나리오 (infra-spec 4.5.3)

| 상황 | 감지 | 조치 |
|---|---|---|
| 컨테이너 · JVM 종료(OOM 포함) | 프로브 실패, `restarts` 증가 | 재시작 정책이 자동 재기동. 원인은 STARTUP_RECOVERY · APP_ERROR 줄 |
| 응답 불능(unhealthy · hang) | readiness 2분 연속 실패(A-5) | `mj restart <서비스>`(realtime을 재시작하면 진행 중 세션이 FAILED(SERVER_RESTART)로 끝난다). env 파일을 바꿨다면 `mj up -d --force-recreate <서비스>` |
| DB 정지 · 무응답 | 앱 503, health 실패 | `mj restart postgres`. 앱은 풀을 다시 열고 readiness가 돌아온다 |
| Nginx 설정 오류 | 배포 D7 `nginx -t` 실패 | 활성 설정은 그대로다. 템플릿을 고쳐 재배포 |
| 인증서 만료 임박 · 갱신 실패 | `certDays` · `certServedDays` 20일 미만 | `mj run --rm --no-deps --entrypoint certbot certbot renew` → 성공하면 표지 파일로 nginx가 60초 안에 다시 읽는다. 챌린지 경로(80 · 보안 그룹) 확인. 두 값이 다르면 `mj exec -T nginx nginx -s reload -c /etc/nginx/mujoong/nginx.conf` |
| migrate 실패 | 배포 D4 | 7절 |
| 디스크 80% 이상 | `diskRoot` · `diskData` · `diskBackup` | `docker image prune`(현재 · 직전 SHA는 남는다), 로그 · 백업 사용처 확인 |
| 시계 어긋남 | `clockSync` · `clockOffsetMs` | `chronyc makestep` 뒤 `systemctl restart mujoong-compose`. 시계가 틀린 채 시연하지 않는다 |
| 데이터 손상 · 잘못된 삭제 | 사람 | 11절 복원(합성 데이터 한정, RTO 60분) |
| 인스턴스 상실 | 상태 검사 · 프로브 | 단순 자동 복구를 기다리거나 새 인스턴스를 4절대로 만들고 데이터 · 백업 볼륨을 붙인다(mkfs 금지) |
| 백업 볼륨만 손상 | `backupAgeH` · `diskBackup` | 새 백업 EBS를 붙여 4절 단계 3대로 마운트하고 즉시 수동 백업 |
| 로그 위생 위반(A-23) | `log-hygiene-check` | 어느 점검 · 서비스인지 확인하고, 비밀 값 일치면 그 비밀을 즉시 회전한다(12절) |

## 10. 수동 명령 (infra-spec 4.5.4)

```bash
mj ps                                   # 상태
mj logs --since 30m core-api            # 로그(붙여 넣을 때는 사건 코드 · traceId만)
mj restart realtime                     # hang 복구(컨테이너 유지, 로그 유지)
mj --profile migrate run --rm migrate   # 마이그레이션만(평시에는 배포가 한다)
mj exec -T backup /opt/mujoong/backup/backup.sh --trigger manual   # 백업 즉시(또는 backup-trigger --trigger manual)
mj exec -T nginx nginx -t -c /etc/nginx/mujoong/nginx.conf && mj exec -T nginx nginx -s reload -c /etc/nginx/mujoong/nginx.conf
mj config --quiet                       # 유효성만(값을 펼치는 config는 mj가 거부한다)
```

`mj`는 `/opt/mujoong/bin/mj`다. 11/2 측정 창의 스택 내리기만 `mj --measurement-window down`(`-v` 금지)을 쓴다(13절).

## 11. 백업 · 복원 (infra-spec 4.4 · reliability-design 2.12)

백업 트리거 4종은 같은 스크립트(`/opt/mujoong/backup/backup.sh`)를 `flock`으로 겹침 없이 부른다: 정기 03:00 KST(backup 컨테이너의
supercronic) · 배포 직전(D2) · 정지 직전(최근 1시간 성공이 없으면) · 기동 직후 보충(24시간 초과 시). 읽기 전용 역할 `mujoong_backup`으로 접속하고
데이터 등급 표지가 `synthetic`이 아니면 거부한다(SD-24). 결과는 `backup.log` 한 줄이다.

복원 리허설(합계 RTO 60분, 운영 스택에 영향 없는 별도 Compose 프로젝트 · 다른 볼륨 · 다른 포트):

| 단계 | 작업 | 목표 |
|---|---|---|
| R1 | 최신 덤프 선택, `pg_restore --list`로 무결성 확인, 파일 이름 · 크기 기록 | 5분 |
| R2 | 리허설 전용 프로젝트에서 빈 PostgreSQL 16(같은 이미지 태그) 기동 | 5분 |
| R3 | 역할 정의 덤프 적용, 역할 비밀번호는 환경 변수로 설정(덤프에는 없다) | 5분 |
| R4 | `pg_restore --no-owner`로 복원, 소유자는 마이그레이션 역할 | 15분 |
| R5 | `migrate validate`(같은 SHA의 migrate 이미지 `validate` 명령)로 Flyway 이력 일치 확인 | 5분 |
| R6 | 두 앱 기동, readiness 통과, STARTUP_RECOVERY 줄 확인 | 10분 |
| R7 | 행 수 점검(core_device · outbox/inbox)과 한 바퀴(시작 → ACTIVE → 끝내기) | 10분 |
| R8 | 합계를 RTO 60분과 비교해 기록(RPO 24시간은 호스트가 살아 있다는 전제) | 5분 |

복원은 삭제된 계정을 되살릴 수 있으므로 합성 데이터에만 한다(NFR12.4). 오프호스트 복사 · EBS 스냅샷은 하지 않는다(사람 결정 O-3).

## 12. 비밀 회전 (infra-spec 3.7 ④)

회전은 수동이다. 값은 4절 단계 4와 같은 방식(`put-parameter --overwrite`, 파일에서 읽기)으로 넣고 화면에 남기지 않는다.

| 비밀 | 절차 |
|---|---|
| `INTERNAL_SERVICE_TOKEN` | 새 값 입력 → 같은 SHA로 `DEPLOY`(두 앱이 함께 다시 만들어진다. 잠시 어긋나도 Outbox가 401을 재시도한다) |
| `DB_CORE_PASSWORD` · `DB_REALTIME_PASSWORD` · `DB_BACKUP_PASSWORD` · `DB_OWNER_PASSWORD` | 슈퍼유저로 `ALTER ROLE`(아래) → 파라미터 갱신 → 같은 SHA로 `DEPLOY` |
| `OPENAI_API_KEY` | 공급자 콘솔에서 새 키 → 파라미터 갱신 → 같은 SHA로 `DEPLOY`(core-api · realtime이 다시 만들어진다. GPT-Live 음성 중계와 Luna 판정이 같은 키를 쓴다) |
| `JEV_API_KEY` | 공급자 콘솔에서 새 키 → 파라미터 갱신 → 같은 SHA로 `DEPLOY`(realtime만 다시 만들어진다) |
| `MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET` | 파라미터 갱신 → 같은 SHA로 `DEPLOY`(이후 ownerHash 값이 바뀐다) |
| `GHCR_PULL_TOKEN` | 새 읽기 전용 토큰(만료 90일 이하) → 파라미터 갱신(배포 때만 쓰인다) |

DB 역할 비밀번호 바꾸기(유닉스 소켓은 막혀 있어 backend 주소로 접속한다. 새 비밀번호는 `\password`가 묻는다) [호스트]:

```bash
mj exec postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$(hostname -i | cut -d" " -f1)" -U postgres -d mujoong'
# psql 안에서: \password core_app   (realtime_app · mujoong_backup · mujoong_owner도 같은 방식)
```

## 13. 11/2 측정 창 (infra-spec 1.3)

| 단계 | 작업 | 확인 |
|---|---|---|
| 0 | [GitHub] `AUTO_DEPLOY=false` | 변수 값 |
| 1 | 상시 컨테이너 로그를 archive로 보존(`docker logs --timestamps <id> \| gzip > /var/log/mujoong/archive/<UTC>-<서비스>-measure.log.gz`, 7개) → `systemctl stop mujoong-compose`(정지 직전 백업 · `POWER_STOP` 자동) → `mj --measurement-window down`(**`-v` 금지**, 망 제거로 'Pool overlaps' 방지) | 컨테이너 0, demo 망 없음 |
| 2 | **계정 관리자가** 보안 그룹 80 · 443 원본을 시험 PC /32로 좁힌다 | 규칙 대조 |
| 3 | `compose.loadtest.yml`로 `mujoong-lt` 기동(같은 SHA, 프로필 dev + load-test, 데이터 `/var/lib/mujoong/loadtest/`) | readiness 200 둘 |
| 4 | 시드 도구로 30쌍 입력, 토큰 원문은 `/var/lib/mujoong/loadtest/tokens/`(root 600)에만 | 행 수 |
| 5 | L1 ~ L7과 `host-sample` 5초 표본, 기록 파일은 측정 직후 내려받는다 | 파일 |
| 6 | 측정 스택 정지 → `docker compose -p mujoong-lt down` → 토큰 · 시험 데이터 삭제 → **계정 관리자가** 보안 그룹 복원 → `systemctl start mujoong-compose`(`POWER_READY` 자동) → `AUTO_DEPLOY` 복원 | `mujoong-lt` 망 없음, demo 망 재생성, demo 프로필에 dev · load-test 없음 |

`compose.loadtest.yml` · 시드 도구 · `host-sample`은 11/2 측정 준비(3.6 이후)의 산출물이다.

## 14. 호스트 기준선 변경 적용

systemd 단위 · cron · logrotate · sysctl · `daemon.json`은 배포가 자동 설치하지 않는다. 배포 끝(D9)에 `WARN 호스트 기준선 차이`가 나오면
현재 릴리스의 `host/`를 보고 바뀐 파일만 4절 단계 5-a의 `install` 줄로 설치한 뒤 `systemctl daemon-reload`(단위) · `sysctl --system`(sysctl) ·
`systemctl restart docker`(daemon.json, **스택 중단이 있으니 정지 창에서만**)를 한다. 고정 진입점(`/opt/mujoong/bin/`)은 D9가 자동으로 바꾼다.

## 15. 설정 변경이 배포로 적용되지 않는 것

| 변경 | 적용 |
|---|---|
| `postgresql.conf` 렌더 결과(`deploy-<id>.log`에 WARN) | 정지 창에서 `mj restart postgres`(두 앱은 풀을 다시 연다) |
| postgres 이미지 태그 · 정의(`WARN postgres 정의가 바뀌었다`) | 정지 창에서 백업 → `mj up -d --no-deps postgres` → 점검 |
| `postgres/init/` | 빈 데이터 디렉터리의 첫 기동에서만 돈다(J84(d)). 기존 볼륨에는 수동 SQL + 이 문서 기록 |

## 16. GitHub 설정 [GitHub]

- **저장소 변수**(Settings → Secrets and variables → Actions → Variables, 비밀 아님): `AWS_REGION`(`ap-northeast-2`) ·
  `AWS_DEPLOY_ROLE_ARN`(`mujoong-gha-deploy`의 ARN) · `DEMO_INSTANCE_ID` · `SERVICE_HOST` · `AUTO_DEPLOY`(`false`로 동결, 없거나 `true`면 켜짐).
  `SERVICE_HOST`가 없으면 배포 워크플로는 건너뛴다(J98).
- **저장소 비밀**: 0개가 목표다(CI 시험 값은 실행 때 만들고, 이미지 푸시는 작업 범위 `GITHUB_TOKEN`, AWS는 OIDC).
- **환경 `demo`**: 배포 브랜치 `main`만, 필수 승인자 없음.
- **main 보호 규칙**(ADR-001 Proposed, 팀장 확정): 직접 · 강제 푸시 금지, 선형 이력, 필수 검사 `ci-gate` 하나, 리뷰 1명, 병합 전 최신 main.
- **패키지(GHCR)**: 비공개면 4절 단계 4의 `GHCR_PULL_TOKEN` · `GHCR_PULL_USER`, 공개로 정하면 둘 다 만들지 않는다(J99, 10/13).

## 17. 검증 스택(개발 PC · CI 전용, demo 아님)

> U1 3.5 Step 52 보강. 검증 명령 C안(`bash scripts/verify.sh`, J103 · J133 · J134)이 띄우는 **일회성** 스택이다. 시험 로그인이 켜진
> dev 프로필이고 가짜 공급자를 쓰므로 demo 호스트에서는 돌리지 않는다. 번들 이미지에는 `compose.ci.yml` · `ci/`가 들어가지 않고(이 문서는 들어간다),
> 호스트 래퍼 `mj` · 배포 스크립트는 `compose.demo.yml`만 받는다.

### 17.1 구성

| 항목 | compose 모드(기본) | host 모드(`--mode host`, Docker 없을 때 J133) |
|---|---|---|
| 기동 | Compose 프로젝트 `mujoong-verify` = `compose.demo.yml` + `compose.ci.yml` | 로컬 PostgreSQL 16 클러스터(`/tmp/mujoong-verify-pg.*`) + `java -jar` 두 개 + `node .next/standalone/server.js` |
| 이미지 · 산출물 | `infra/ci/build-images.sh`가 만든 로컬 이미지 `mujoong.local/mujoong-*:verify`(당기지 않음), 소스 지문 라벨 | A가 만든 bootJar 두 개 · `.next/standalone`, 지문은 `build/verify/fingerprint` |
| 입구 | nginx 443 → 호스트 `127.0.0.1:8443` 하나(80은 게시하지 않음) | Nginx 대역 `tools/dev-edge/edge.mjs`(127.0.0.1:8443) |
| 앱 프로필 | `SPRING_PROFILES_ACTIVE=dev` + `MUJOONG_DEVLOGIN_ENABLED=true`(SD-14 조합 2, J108) | 같음(core 관리 9090 · realtime 관리 9091) |
| 공급자 | `fake-provider` 서비스(`tools/fake-provider`, 시험 도구 이미지) | `node tools/fake-provider/server.mjs`(8090) |
| 도메인 · origin | `SERVICE_HOST=localhost`, 허용 origin `https://localhost:8443` | 같음 |
| 비밀 | `infra/ci/gen-env.sh` → `build/verify/env/<서비스>.env`(umask 077, 실행마다 새 값, 끝에 지움) | 같음 + 0600 `pgpass` |
| 인증서 | `infra/ci/gen-selfsigned.sh` → `build/verify/certs/`(버림 CA · `localhost`) | 같음 |
| 꺼진 것 | certbot · backup · neo4j | — |

host 모드가 확인하지 못하는 항목은 `report.txt`에 `NOT_VERIFIED(host)` 줄로 남고 CI `skeleton` · `images` 작업 몫이다: 이미지 빌드 · 라벨 ·
비루트 · 시험 클래스 0, Compose 병합 설정 단언 B1, Nginx 렌더 설정 · `nginx -t` · 접근 로그 줄, 망 격리(edge · backend · 고정 IP · pg_hba),
프로필 가드의 이미지 기동 거부(host 모드는 같은 bootJar를 `java -jar`로 띄워 확인).

### 17.2 돌리기 · 결과 읽기 [개발 PC · CI]

```bash
bash scripts/verify.sh                # A → F → B → C → P1 · P2 · P3 → T (실측 약 6분)
bash scripts/verify.sh --engine       # 엔진 checkpoint(사람 결정 10): E0 지문 대조 → B → C → 사후 단언, 내부 기한 110초
KEEP=1 bash scripts/verify.sh         # 끝에 스택만 남긴다(생성 비밀 · 비밀 목록은 그래도 지운다)
```

- 표준 출력은 단계마다 한 줄이고, `build/verify/report.txt` 첫 줄이 `mode=`, 마지막 줄이 `RESULT`다. 도구 출력은 `build/verify/logs/<단계>.log`,
  compose 모드의 서비스 로그는 `build/verify/logs/compose.log`다. 단계 PASS 줄은 그 단계가 종료 코드 0으로 끝난 뒤에만 쓰인다.
- 종료 코드 0 통과 · 1 단언 실패(기한 초과 포함) · 2 전제 누락 · 64 인자 오류. 2는 통과가 아니다.
- B1 ④(스켈레톤 확인)는 자동 단언이 아니다. 종료 코드 0과 `report.txt`를 근거로 사람이 승인한다.

### 17.3 문제 해결

| 증상 | 원인 · 조치 |
|---|---|
| `E0 FAIL`(`--engine`) | 미리 만든 이미지 · 산출물의 소스 지문이 지금과 다르다(backend · frontend · infra · tools · contracts · scripts 중 무엇이든 바뀜). 전체 실행을 한 번 돌린 뒤 다시 한다. 바뀐 파일은 `bash infra/ci/source-fingerprint.sh --manifest`의 줄을 이전 결과와 비교해 찾는다 |
| `F FAIL` 또는 `T FAIL`만 | 실행 중 다른 작업(편집기 · 다른 에이전트)이 저장소를 고쳤다. 저장소가 조용할 때 다시 돌린다. 감사 기록(`aidlc/**/audit/`)은 비교에서 빠진다 |
| A1의 `MujoongPostgresTest`(local)만 실패 | root로 돌 때 저장소 경로의 상위 디렉터리를 `postgres` 사용자가 지나가지 못한다(예: 0700 디렉터리 아래 복사본). 저장소를 그런 경로 밖에 두거나 `-Pmujoong.test.db=docker` 환경에서 돈다 |
| 이미지 받기 429(Docker Hub) | 고정 태그가 로컬에 있으면 auto 모드는 pull 성공과 같이 본다. 한 번 받아 두면 된다. 다른 태그로 바꾸지 않는다 |
| 사내 프록시 · 사설 CA 환경의 이미지 빌드 | 앱 이미지는 호스트에서 만든 jar · standalone을 COPY하고, 받을 파일(OS 패치 .deb · Flyway jar)은 BuildKit ADD가 sha256으로 고정해 받는다(RUN 단계 네트워크 없음). backup의 supercronic 빌드 단계만 Go 모듈을 받으므로 CA가 필요한 환경에서는 `build-images.sh --extra-ca <파일>`이 BuildKit 비밀로만 넘긴다(빌드 인자 · ENV 금지, R-03 · J352) |
| 이전 실행의 잔여물 | 시작할 때 `docker compose -p mujoong-verify down -v --remove-orphans`와 남은 host 프로세스 · `/tmp/mujoong-verify-pg.*` 정리를 먼저 한다. SIGKILL로 끊긴 뒤 손으로 지울 때도 같은 명령을 쓴다 |
| `B4.host`가 000(host 모드) | dev-edge는 모르는 Host · SNI를 연결 끊기로 거절한다(Nginx는 404 · TLS 거절). 둘 다 통과로 센다 |

- `docker compose ... config`는 `--quiet`로만 쓴다. 병합 결과가 필요하면 `--format json`을 umask 077 임시 파일로만 받아 처리한 뒤 지운다.
- 이 스택의 로그 · `report.txt`를 이슈에 옮길 때도 0절 규칙을 따른다(사건 코드 · traceId · 건수만).
