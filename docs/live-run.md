# 실키 전체 기동 가이드 (팀원용)

실제 OpenAI(GPT-Live · Luna)와 제브를 연결해 전체 스택을 내 PC에서 띄우는 방법이다. 이 스택은 **개발자 PC 로컬 전용**이다. 시험 · CI · `verify.sh`는 계속 가짜 공급자만 쓴다(R-F1).

## 한눈에

```bash
bash scripts/live/keys.sh              # 1) 키 입력(한 번). 저장소 밖 ~/.config/mujoong/live.env 에 저장
bash scripts/live/up.sh --tag <SHA>    # 2) CI가 올린 이미지로 기동 (또는 --local: 내 작업 트리에서 빌드)
# 브라우저: https://localhost:9443/parent/dev-login  (보호자), https://localhost:9443/device (기기)
bash scripts/live/down.sh              # 끄기 (--purge: DB·생성 비밀까지 삭제)
```

Docker 권한이 필요한 PC에서는 `sg docker -c 'bash scripts/live/up.sh --tag <SHA>'`처럼 감싼다.

## 1. 키 넣기 (push되지 않는 방식)

- `keys.sh`가 키를 **화면에 보이지 않게** 묻고 `~/.config/mujoong/live.env`(권한 0600, 폴더 0700)에 쓴다. 저장소 밖이라 `git add`로 잡히지 않는다. 저장 위치가 저장소 안이면 스크립트가 거절한다.
- 필요한 값: `OPENAI_API_KEY`(필수), 제브를 실제로 쓰려면 `JEV_API_KEY` · `JEV_BASE_URL` · `JEV_MODEL`(고정 버전, `-latest` 금지). 제브 키를 비우면 제브는 가짜 대역으로 돈다.
- 이미 환경 변수로 갖고 있으면 묻지 않고 그 값을 쓴다: `OPENAI_API_KEY=… bash scripts/live/keys.sh`(셸 기록에 남지 않게 비밀번호 관리자 · `read -s`를 쓰세요).
- 값 확인 없이 상태만 보기: `bash scripts/live/keys.sh --show`.
- 겹겹의 안전장치: ① 저장소 밖 저장 ② `.gitignore`의 `live.env` · `*.live.env` · `.env*` ③ 생성 비밀(DB 비밀번호 등)은 `build/live/`(무시 폴더)에만 ④ CI의 gitleaks · `a-secrets.sh` 스캔.
- **키를 채팅 · 이슈 · PR · 커밋에 붙여 넣지 않는다**(R-F13). 아동 데이터를 외부 모델에 보내기 전 공급자 약관 확인(R-F15)이 끝났는지 팀에서 확인한 뒤, 실제 아이 음성 대신 합성 · 팀원 음성으로 시험한다.

### 제브(U13) 실제 연결과 단독 확인

- U13에서 `jevLiveTest`에 쓴 값(`JEV_API_KEY` · `JEV_BASE_URL` · `JEV_MODEL`)을 `keys.sh`에 그대로 넣으면 같은 파일을 두 곳이 쓴다. 셸에 이미 `export`해 둔 값이 있으면 묻지 않고 가져온다.
- 제브 키를 넣으면 `JEV_MODE=REAL`이 함께 기록되어 realtime의 연습 발화가 실제 제브 검사를 거친다(허용 호스트 · 고정 버전 모델 규칙은 기동 점검이 확인한다). 키를 비우면 가짜 대역(STUB)이고 기동 로그에 경고 한 줄이 남는다.
- 스택 없이 제브만 확인: `bash scripts/live/jev-probe.sh` — 키 파일 값을 환경 변수 한 줄로만 넘겨 `./gradlew :realtime-activity:jevLiveTest`를 돌리고 보고서(`build/jev-live/report.txt`, 키 · 주소 · 문장 원문 없음)를 보여 준다. 결과는 `[실호출-합성]`으로 적는다.
- **먼저**: 제브 R-F15 확인 기록(`infra/RUNBOOK.md` 단계 4)이 비어 있으면 키를 넣기 전에 남긴다. 연습 발화에는 합성 · 팀원 음성만 쓴다.

## 2. 이미지 받기 / 만들기

| 방식 | 명령 | 쓸 때 |
|---|---|---|
| CI 이미지 | `bash scripts/live/up.sh --tag <40자 커밋 SHA>` | `main` 병합 뒤. CI가 SHA 태그로 GHCR(`ghcr.io/team-jomyung/mujoong-{core-api,realtime,web,migrate}`)에 올린 이미지를 당긴다. `latest`는 없고 이미 있는 태그는 덮어쓰지 않는다(R-F10) |
| 로컬 빌드 | `bash scripts/live/up.sh --local` | 병합 전 브랜치 코드를 시험할 때. JDK 21 · Node 22 필요, 첫 빌드는 몇 분 걸린다 |

- 팀원은 코드를 빌드할 필요가 없다. 병합된 SHA(`git log origin/main`의 40자)를 `--tag`에 주면 된다.
- 패키지가 비공개이면 한 번 로그인한다: `docker login ghcr.io -u <GitHub 사용자>` (읽기 권한 토큰 `read:packages`, 입력창에만 입력). 이미지가 없다는 오류가 나면 그 SHA가 CI 푸시를 거친 커밋인지 확인한다.
- 이미지 안에는 키가 없다. 키는 기동할 때 환경 변수로만 들어간다.

## 3. 접속과 문제 해결

- 보호자: `https://localhost:9443/parent/dev-login`(시험 로그인), 기기: `https://localhost:9443/device`. 자체 서명 인증서라 브라우저 경고가 나온다. 계속 진행하거나 `build/live/certs/ca.crt`를 신뢰 목록에 넣는다(마이크는 HTTPS `localhost`에서 열린다).
- 포트를 바꾸려면 `--port`(처음 한 번, 이후 바꾸려면 `down.sh --purge` 먼저). 8443은 검증 스택 · 수동 스택이 쓰므로 피한다.
- 로그: `docker compose -p mujoong-live logs -f realtime` (비밀 · 전사는 로그에 없다).
- 이 스택은 프로젝트 이름 `mujoong-live`와 별도 망 대역(172.30.40·50)을 쓴다. `mujoong-manual` · `mujoong-verify`와 같이 떠 있어도 겹치지 않는다. `down.sh`는 `mujoong-live` 라벨의 자원만 지운다.
- 다시 올릴 때는 같은 명령을 다시 실행한다. DB는 볼륨에 남고 시드는 멱등이다.

## 4. 무엇이 실제이고 무엇이 아닌가

- 실제: GPT-Live 음성 중계, Luna 판정, (제브 키를 넣었다면) 제브 검사. realtime 컨테이너는 `api.openai.com` · 제브 호스트로 나가는 길이 열려 있다(기동 확인 때 TLS 연결 확인). 시험 로그인은 로컬 전용 우회이며 Google 로그인은 쓰지 않는다.
- 이 스택으로 한 확인은 `verify.sh` 증거와 다르다. 결과를 적을 때 "실호출 · 합성 대화"와 "가짜 공급자"를 따로 적는다(R-M11).

## 5. 시험 기록

### 2026-10-18 첫 실키 기동 시험 (U12 브랜치 `--local`, 실제 OpenAI · 제브 키, `[실호출-합성]` · `[실기기]`)

사람이 직접 한 확인과 서버 로그로 확인한 사실을 나눠 적는다. 원인 판단은 아직 확정이 아니며 조원들과 상의한 뒤 정한다.

**사람이 관찰한 것**

- 시작 때 시작 음성(안내음)이 들렸다.
- 보호자 화면에서 무중이의 대본(글)은 확인됐지만, 무중이의 **음성 출력은 되지 않았다**.
- 기기 화면에 "듣는 중" 상태가 표시됐다. 다만 실제로 듣고 있는지는 화면만으로 확인할 수 없었다.
- 브라우저 콘솔 에러는 `/api/session/csrf` 401(로그인 전 세션 확인) · `/api/device/me` 401 · `/api/device/bootstrap/status` 401(기기 등록 전 상태 확인) · `favicon.ico` 404뿐이었고, 네트워크 에러는 없었다. 서버 로그로 보면 이들은 모두 로그인 · 등록 **전** 정상 응답이며 증상의 원인이 아니다.
- "안녕" 다음에 확인차 "내 말이 들리니?"라고 말한 뒤 "응"이라고 하자 **그 말이 실제로 입력되는 것을 확인했다.**
- 사람의 판단: 판정 과정에 문제가 있는 것으로 보인다. 조원들과 상의 후 진행한다.

**서버 로그로 확인된 사실** (realtime 로그, 전사 원문은 R-F14로 남기지 않는다)

- 세션 상태 흐름은 `PREPARING → DEVICE_ACK → WAITING_CHILD → CLOSING(GUARDIAN_STOP) → ENDED`였고 `ACTIVE`에 도달하지 않았다.
- 아이 전사가 4번 서버에 도착해 참여 판정(Luna `JUDGMENT_LIVE`)이 4번 호출됐고 모두 호출 자체는 성공(약 1.9 ~ 2.7초)했다. 입력 경로(마이크 → WebSocket → 공급자 전사)는 서버까지 살아 있었다.
- 공급자 턴 지연 기록(`LATENCY_TURN`)은 0건이었다. 무중이의 GPT-Live 음성은 `ACTIVE`가 된 뒤에 나오므로 이 시험에서는 아직 한 번도 시작하지 않았다.
- 참여 확인 전의 안내(시작 안내 · 재안내)는 승인 음성 파일을 재생한다. 이 파일은 `tools/seed/gen-session-wav.mjs`가 만든 약 334Hz의 **합성음 자리표시**이고 사람 말이 아니다(U4a-J20, 실제 녹음은 D 담당이 v3로 더한다). 파일 요청은 모두 200이었다.
- 참여 확인은 짧은 긍정("응", "네" 등)이면 판정 없이 바로 참여로 처리하고(`JoinTracker`), 그 밖의 불명확한 대답만 Luna 판정(J1)으로 간다. 이 시험에서는 판정이 호출됐는데도 참여로 이어지지 않았다.

**추후 확인할 것** (조원 논의 후)

1. 판정(J1) 결과가 `PARTICIPATE`로 오지 않은 이유: 전사 내용 · 판정 프롬프트(`judgment/prompt-v1.txt`) · 선택지 · 모델 응답. 로그에는 판정 결과 값이 없으므로 진단용으로 남길 수 있는 항목(결과 코드만)을 정해야 한다.
2. "응" 한 단어 입력이 참여로 처리돼 `ACTIVE`로 넘어가는지, 넘어간 뒤 무중이의 GPT-Live 음성이 나오는지.
3. 안내음을 사람 말 음성으로 바꾸는 방법(승인 녹음 v3, 또는 시험용 로컬 음성).
4. 기기 화면의 "듣는 중" 표시가 실제 입력 수신과 일치하는지 확인할 수 있는 표시.

