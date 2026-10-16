# 빌드 방법 — U8 · U9 범위

> 이 기록의 Construction은 U8(계약 · 버전 리소스)과 U9(Luna 호출부)만 구현했다. U10 ~ U15는 Delivery Planning(`bolt-plan.md`)대로 건너뛰고 지시서로 넘긴다(사람 선택 2026-10-16).

## 1. 준비물

| 도구 | 판 | 확인 |
|---|---|---|
| JDK | 21 | `java -version` |
| Node · npm | 22 | `node -v` |
| Docker · Compose | Compose v2.24.4 이상 | `docker compose version` |
| jq · curl · openssl · sha256sum · timeout · setsid | 아무 판 | `verify.sh` 전제 점검이 확인 |
| shellcheck | 아무 판(CI는 컨테이너) | `infra-validate` · `test-infra-validate.sh`에 필요 |
| gitleaks | CI 이미지 다이제스트 | 없으면 이력 스캔은 CI 몫 |

키 · 비밀 값은 필요 없다. 시험 · 검증은 가짜 공급자만 쓴다(R-F1). `OPENAI_API_KEY`가 셸에 있어도 기본 `test` 작업은 시험 JVM에 넘기지 않는다(U9).

## 2. 빌드

```bash
cd backend && ./gradlew --no-daemon clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker
```

- `check`에는 U9의 `verifyLunaLiveIsolation`(실호출 시험 분리 확인)이 들어 있다.
- 실행 자원: 두 앱 jar에 `luna/schemas/`(계약 `ai-*.schema.json` · `common.schema.json` 복사본)가 들어간다.
- 프런트 · tools는 U8 · U9에서 바뀌지 않았다. 전체 확인은 `bash scripts/verify.sh`가 함께 빌드한다.

## 3. 빌드 확인

- `BUILD SUCCESSFUL`, 모듈 줄 커버리지 80% 하한(JaCoCo) 통과.
- `bootJar`에 시험 클래스 0건(`verifyBootJarHasNoTestClasses`).
- 끝-끝: `bash scripts/verify.sh`(compose 모드, Compose 프로젝트 `mujoong-verify`, 입구 `127.0.0.1:8443`).

## 4. 자주 나는 문제

| 증상 | 까닭 | 해결 |
|---|---|---|
| `verify.sh` `PREREQ 전제 누락: JDK 21 · jq` | 도구 없음 | 별도 터미널에서 `sudo apt-get install -y openjdk-21-jdk-headless jq` |
| `B3.up FAIL` nginx `failed to bind host port 127.0.0.1:8443` | 다른 프로그램이 8443을 씀 | 그 프로그램을 끄고 다시(사용자 수동 스택 `mujoong-manual`은 건드리지 않음, R-F12) |
| `A4.patterns FAIL` | 작업 트리 파일에 키 형식 문자열(예: 주소 안에 사용자 이름 · 비밀번호를 넣은 URL) | 문자열을 부품으로 조립하거나 지운다. 허용 목록으로 덮지 않는다(R-F9) |
| `infra-validate` shellcheck MISSING | shellcheck 없음 | 설치하거나 `koalaman/shellcheck` 컨테이너로 검사. CI는 항상 돈다 |
