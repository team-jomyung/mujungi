# U9 Luna 호출부 — CI/CD

> 근거: `infrastructure-design-questions.md`(가정 1 ~ 5 수락), `../nfr-design/security-design.md`(SD6 · SD8 · SD9 · SD10 · SD12), `../nfr-design/logical-components.md`, `.github/workflows/ci.yml`, `infra/bin/render-env` · `log-hygiene-check`, `scripts/verify/b-stack.sh`.
> U9는 `library` 단위다. 새 인프라 자원 · 배포 방식 변경이 없으므로 인프라 명세 · 모니터링 설계 문서는 두지 않고, 빌드 · 시험 · 점검 · 운영 절차가 어떻게 바뀌는지만 적는다.

## 1. 빌드

| 순서 | 바뀌는 것 | 어디서 | 근거 |
|---|---|---|---|
| 1 | `processResources`가 `contracts/schemas/ai-*.schema.json`을 실행 자원 `luna/schemas/`로 복사 | core-api · realtime-activity `build.gradle.kts` | Q3-A, SD12 |
| 2 | `json-schema-validator`를 `testImplementation` → `implementation` | 같은 두 파일(`libs.versions.toml`은 그대로) | tech-stack-decisions |
| 3 | 기본 `test` 작업에만 `luna-live` 고정 제외, 새 `lunaLiveTest` 작업(`luna-live`만 포함, `check` · `build` 미연결) | `backend/build-logic` `mujoong.java-conventions` | SD8 |
| 4 | bootJar에 시험 클래스가 없는지(`verifyBootJarHasNoTestClasses`) 그대로 확인. `LunaResponsesStub`은 `test-support`라 운영 jar에 들어가지 않는다 | 기존 작업 | R-F8 |
| 5 | **로그 계약 묶음(한 커밋):** 새 사건 `LUNA_CALL` · `LUNA_STARTUP`과 속성 키 · 값 형식 LABEL · NAME_LIST를 다음 파일에 함께 넣는다 — contract-model `EventCode.java` · `AttrKey.java` · 포매터 검증과 그 시험, `infra/bin/log-hygiene-check`의 `ALLOWLIST_JSON`(31 ~ 52행, 사건 코드별 attrs 고정 표), `infra/ci/test-host-tools.sh`(458행 이후 자바 정본 대조, 새 사건 사례), `infra/RUNBOOK.md` 로그 사건 절, `contracts/ai-io.md` 10절 감사 로그 속성 표. 이 묶음을 빠뜨리면 PR `ci-gate`(`infra-validate` · host tools 시험)가 대조 불일치로 막거나, 맞춰 놓지 않은 표 때문에 main 병합 뒤 `skeleton`의 로그 위생 단언(P2)이 실패한다 | 위 파일들 | SD6, R-M10 |
| 6 | **빌드 구성 시험:** 위치는 `backend/build-logic`의 Gradle TestKit 시험(또는 각 앱 `src/test`의 `@Tag("u9")` 시험이 `gradle tasks --all` 결과를 읽는 방식 중 Code Generation에서 정함). 단언 세 가지: `check` 작업 그래프에 `lunaLiveTest` 없음, 기본 `test`의 제외 목록에 `luna-live` 있음, `lunaLiveTest`의 포함 목록 = `luna-live`. 이 시험은 `check`에 들어가 CI 127행에서 돈다 | build-logic 또는 두 앱 시험 | SD8, NFR5.1 |

## 2. 시험 · 관문 (변경 없음 / 영향만)

| CI 단계(`ci.yml`) | 지금 명령 | U9 뒤 동작 | 확인 |
|---|---|---|---|
| 백엔드 전체(127행) | `clean check verifyBootJarHasNoTestClasses` | U9 단위 · 통합 시험(`@Tag("u9")`)이 루프백 대역으로 함께 돈다. `lunaLiveTest`는 돌지 않는다 | 빌드 구성 시험(SD8) |
| 범위 커버리지(157 · 161 · 177행) | `:…:test -PincludeTags=…` | 기본 `test`의 `luna-live` 제외와 함께 돈다. U9 범위 파일 `coverage-scope/u9.txt`는 로컬 · 검토 근거(CI가 막는 범위는 U5b · U7뿐) | team.md Testing Posture |
| 의존성 취약점(206 ~ 216행) | bootJar trivy `rootfs` HIGH · CRITICAL | 새 실행 의존성(`json-schema-validator`와 전이 의존성)이 대상이 된다 | Code Generation 단계에서 로컬로 한 번 돌려 결과 기록(SD9). 걸리면 ① 같은 메이저 안에서 고친 판으로 올린다(`libs.versions.toml` 판 변경은 이 경우만, 까닭을 주석으로) ② 고친 판이 없으면 `ci/security-exceptions.yml`에 GHSA/CVE · 사유 · 만료일로 올리고 사람 승인을 받는다(R-M7) ③ 승인 전에는 병합하지 않는다 |
| 이미지 점검(410행 이후) | 이미지 빌드 · 라벨 · 비루트 · 시험 클래스 0 | 영향 없음(자원 파일 몇 개가 늘어남) | 기존 점검 |
| 끝-끝 `skeleton`(main 푸시) | `bash scripts/verify.sh --mode compose` | 키가 빈 값이라 어댑터 꺼짐. 기동 사건 `LUNA_STARTUP`(꺼짐) 한 줄이 로그에 더해진다 | 1절 5행 로그 계약 묶음. Build and Test에서 `verify.sh` P2로 병합 전에 확인 |

실공급자는 어느 CI 단계에서도 부르지 않는다(R-F1). `lunaLiveTest`는 사람이 직접 돌리는 작업이다(편차 U9-c).

## 3. 비밀 · 설정

| 항목 | 상태 | 비고 |
|---|---|---|
| `OPENAI_API_KEY` | U8이 분배함(선택, core-api · realtime) | `render-env` OPTIONAL · `compose.demo.yml` · `b-stack.sh` 기대값 그대로. 새 비밀 이름 없음 → R-M9 목록 변경 없음 |
| `OPENAI_ANALYSIS_MODEL` · `OPENAI_JUDGE_MODEL` | 비밀 아님, 선택 env | 비면 `gpt-6-luna`. Compose · `render-env`에 넣을지는 demo 투입 때 정한다(이번 범위 밖) |
| `mujoong.luna.*` | 두 앱 `application.yml` + `LunaProperties` | `ConfigKeyContractTest` 대조(SD12). 시간 값 아님 |

## 4. 배포 · 롤백

- 배포 방식은 그대로다(main 병합 → `ci-gate` → demo 자동 배포, 지금은 `AUTO_DEPLOY` 없음으로 건너뜀). U9는 이번 작업에서 배포하지 않는다(team.md Deployment).
- demo에 키를 넣는 날의 순서. RUNBOOK의 확인 기록 표와 이 순서는 **U9 Code Generation에서 RUNBOOK에 새로 쓰는 산출물**이다(지금 RUNBOOK 238 ~ 239행에는 문구만 있다):
  0. 모델 이름을 기본값(`gpt-6-luna`)이 아닌 값으로 쓰려면, 먼저 `OPENAI_ANALYSIS_MODEL` · `OPENAI_JUDGE_MODEL`을 `infra/compose.demo.yml` · `render-env`에 더하는 변경이 병합돼 있어야 한다(이번 범위 밖, 없으면 기본값으로만 동작).
  1. R-F15 확인 기록 표에 한 줄(확인자 · 날짜 · 약관 판 · 환경)을 남긴다. 기록이 없으면 넣지 않는다.
  2. Parameter Store에 `OPENAI_API_KEY`를 넣고 같은 SHA로 `DEPLOY`를 다시 실행한다(RUNBOOK 12절 회전 표의 `OPENAI_API_KEY` 행과 같은 길: core-api · realtime이 다시 만들어진다).
  3. 기동 로그에서 `LUNA_STARTUP` `lunaState=ENABLED`를 확인한다.
- **롤백:** Parameter Store의 `OPENAI_API_KEY`를 비우고(빈 값 또는 삭제) 같은 SHA로 `DEPLOY`를 다시 실행한다. 기동 로그 `LUNA_STARTUP` `lunaState=DISABLED_NO_KEY`를 확인한다. 어댑터가 꺼지면 지금과 같은 실패 경로라 데이터 변경 · 마이그레이션 되돌림이 없다. 재기동만으로는 컨테이너 환경이 갱신되지 않으므로 쓰지 않는다.

## 5. 실호출 시험 운영

- 위치: 개발자 PC. 키는 그 PC의 env로만 넣는다(R-F13 · R-F3).
- 명령: `cd backend && ./gradlew lunaLiveTest`(core · realtime 각각 또는 함께).
- 결과: `build/luna-live/report.txt`(목적 · 응답 모델 · 지연 · 사유, 원문 없음). 키가 없으면 `SKIPPED_NO_KEY`.
- 보고: '실제 API · 합성 자료' 증거로만 적고, 기본 시험 · 끝-끝 결과(테스트 대역)와 섞지 않는다(R-M11).

## Sources

- `infrastructure-design-questions.md`(같은 폴더)
- `../nfr-design/security-design.md` · `logical-components.md`, `../nfr-requirements/security-requirements.md`
- `.github/workflows/ci.yml`(127 · 157 · 161 · 177 · 206 ~ 216 · 410행), `infra/bin/render-env`(66 · 193 · 196행), `infra/compose.demo.yml`(170행), `scripts/verify/b-stack.sh`(110행), `infra/bin/log-hygiene-check`

## Assumptions & Open Questions

- 모델 이름 env를 Compose · `render-env`에 넣는 일은 demo에 키를 넣는 작업(이번 범위 밖)에서 함께 한다. 넣지 않으면 기본값 `gpt-6-luna`로 동작한다.
- 공급자 비용 상한 · 사용량 경보는 계정 쪽 설정이라 이 문서에서 다루지 않는다. demo 투입 때 RUNBOOK에 확인 항목으로 적는다.
