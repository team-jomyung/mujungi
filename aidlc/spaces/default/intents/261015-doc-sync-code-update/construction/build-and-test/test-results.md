# 시험 결과 — U8 · U9 범위

> 증거 종류(R-M11): 아래 결과는 모두 **테스트 대역(가짜 GPT-Live · 루프백 Luna 대역) · 합성 자료**다. 실제 API · 실제 기기 결과는 없다. 인프라 시험은 이 PC의 도구 차이 때문에 GNU `date` · shellcheck 컨테이너를 PATH 앞에 두고 돌렸다(3 · 4절).

## 1. 빌드

| 실행 | 커밋 | 결과 | 근거 |
|---|---|---|---|
| `clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker` | `80496f2`(U9 단위 확인 안 A1) | 성공 | `build/verify/report.txt` A1 PASS 390s |
| 같은 명령(U9 코드 검토 반영 뒤) | `54cfc89` | 성공 | U9 `code-summary.md` 7절 |

## 2. 단위 · 통합 시험 (단위별 명령, 중복 제거 뒤 한 번씩)

| 명령 | 결과 |
|---|---|
| `./gradlew :contract-model:test :core-api:test :realtime-activity:test -PincludeTags=u8,u9 -Pmujoong.test.db=docker` (U8 · U9 단위 명령을 합친 한 번) | 성공 — contract-model 22 · core-api 89 · realtime-activity 67, 실패 0 · 건너뜀 0 |
| U9 범위 커버리지(`-PincludeTags=u9 -PcoverageScope=u9`) | 성공 — core 93.86%(688/733) · realtime 91.87%(565/615), 하한 80% |
| U8 범위 커버리지 | 성공 — U8 `code-summary.md`(U8 단위 확인 때) |
| 모듈 전체(`check` 안 JaCoCo) | 성공 — contract-model 98.45% · core-api 95.94% · realtime-activity 93.28% |

## 3. 끝-끝 · 인프라 · 보안

| 확인 | 결과 | 근거 |
|---|---|---|
| `bash scripts/verify.sh`(compose) — A1 ~ A4 · F · B · C · P1 ~ P3 · T | **PASS**(604초, 커밋 `80496f2`) | `build/verify/report.txt`, U9 단위 확인 증명 |
| `bash infra/ci/test-host-tools.sh` | 시스템 `date`(uutils 0.8.0): **실패 2건 / 37건**(`turn-metrics-one-turn` · `turn-metrics-p95-judged`). GNU `date`를 PATH 앞에 둠: **37건 어긋남 0** | U8 · U9 사례(`log-hygiene-allowlist-matches-java` · `log-hygiene-luna-events`)는 둘 다 통과. 원인은 4절 |
| `bash infra/ci/test-infra-validate.sh` | **46건 어긋남 0** | shellcheck `koalaman/shellcheck:v0.10.0` 컨테이너 래퍼를 PATH 앞에 둠 |
| `infra/bin/infra-validate` | 8건 모두 PASS | 같은 shellcheck 컨테이너 |
| gitleaks(이력, CI 이미지 · 설정) | 0건(`586238b..HEAD`) | U9 code-summary 7절 |
| trivy bootJar HIGH · CRITICAL | 0건(core · realtime) | U9 code-summary 4절 |
| `./gradlew lunaLiveTest`(키 없음) | SKIPPED_NO_KEY(통과로 보지 않음) | U9 code-summary 4절 |

## 4. 실패 원인 (turn-metrics 2건)

- 증상: `turn-metrics-one-turn`은 기대 줄 "p95 1038ms"가 없고 1371ms가 나왔다. `turn-metrics-p95-judged`는 종료 코드 1, `alerts=NFR5.3`이다.
- 원인: 이 PC의 `/usr/bin/date`는 uutils coreutils 0.8.0이다. `date +%3N`이 자릿수를 무시하고 9자리를 낸다(`26.121000000`). 시험 도우미 `iso_ms`(`infra/ci/test-host-tools.sh:503`)가 만든 합성 로그 시각이 그래서 어긋났다.
- 확인: `/usr/bin/gnudate`를 `date`로 PATH 앞에 두고 다시 돌리면 37건 모두 통과한다. `infra/bin/turn-metrics`와 U8 · U9 코드는 원인이 아니고, CI(GNU coreutils)는 영향받지 않는다.
- 남은 일(범위 밖): 시험 도우미와 `infra/bin/probe-three`의 `now_ms`(`date +%s%3N`, 이 PC에서 19자리)를 `%3N`에 기대지 않게 고친다.
