# U11 인계 — 다른 환경에서 이어가기

> 작성: 2026-10-17. 브랜치 `unit/u11-judgment-turn`(PR2). PR1(선 형식 · 키 통일)은 PR #28로 `main`에 병합됐다(`683c669`).

## 1. 지금 상태

| 항목 | 상태 |
|---|---|
| 작업 기록 | `aidlc/spaces/default/intents/261016-u11-judgment-turn/` (범위 refactor, brownfield, Standard) |
| 끝난 단계 | Requirements Analysis · Functional Design · Code Generation(승인, 심사 READY, 경미 지적 R-01~R-06 반영) |
| 남은 단계 | **Build and Test** (진행 중: 아래 3절 결과를 `construction/build-and-test/test-results.md`에 옮겨 적고 마무리) |
| 구현 | PR2 29단계 전부 구현 · 시험(대역만) 통과. 코드 요약 `construction/code-generation/code-summary.md` 4절 |

## 2. 이어가는 법

1. 브랜치를 받는다: `git fetch && git switch unit/u11-judgment-turn`.
2. 팀 규칙(`team.md`)에 따라 작업 기록은 담당자 PC에서 이어 간다. 활성 작업 기록 가리킴(`aidlc/spaces/default/intents/active-intent`)은 gitignore이므로 `261016-u11-judgment-turn`으로 직접 맞추고 `/aidlc`를 실행한다(다음 단계는 build-and-test).
3. 시험 도구: JDK 21, Docker, Node 22. 이 PC(WSL)의 `date`는 uutils라서 `%3N`을 무시한다. GNU date를 PATH 앞에 둔다(project.md 교정).
4. 병합 순서: 마이그레이션 버전 순서를 따른다. realtime V5(이 PR) → U13 V6 → U12 V7. core 줄(U15 → U14 → U10)은 독립이다.

## 3. Build and Test 지금까지 결과 (2026-10-17, 커밋 `aa1340b`, `bash scripts/verify.sh --mode compose`)

**테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공은 따로 적는다(R-M11). 아래는 모두 테스트 대역(가짜 공급자) 증거다.**

| 단계 | 결과 |
|---|---|
| A1 backend `clean check verifyBootJarHasNoTestClasses` | PASS — 시험 3385건 · 실패 0 · 건너뜀 3, 모듈 JaCoCo 80% 통과, bootJar 시험 클래스 0 |
| A2 frontend (eslint · vitest 80% · next build) | PASS — 줄 98.55% |
| A3 tools (vitest) | PASS |
| A4 비밀 스캔(키 형식 grep) | PASS — gitleaks 이력 스캔은 CI `secrets` 작업 몫 |
| B0~B2, B5 (이미지 5종, 병합 설정, migrate, 시드, 프로필 가드) | PASS |
| **B3 검증 스택 기동** | **FAIL(환경 문제)** — nginx가 `127.0.0.1:8443` 바인딩 실패. 이 PC에서 그 포트를 컨테이너가 아닌 프로세스가 이미 쓰고 있었다(`ss -ltnp`에 소유 프로세스 미표시). 코드 결함이 아니다. core-api · realtime · web · postgres · fake-provider는 모두 healthy였다 |
| C 기기 시뮬레이터 한 턴 · 사후 단언 P1 · P2 · P3 · T | **미실행**(B3 실패로 중단) |

**다시 돌릴 것**: 8443을 쓰는 프로세스를 정리한 환경(또는 `mujoong-manual` 스택이 없는 PC)에서 `bash scripts/verify.sh --mode compose`를 전체 실행한다. 사용자의 `mujoong-manual` 스택은 건드리지 않는다(R-F12).

## 4. 아직 하지 않은 것 (Build and Test 몫)

- 전체 `verify.sh` B · C · 사후 단언 통과 확인(위 3절).
- 이미지 점검 `infra/ci/image-checks.sh`, `infra/bin/infra-validate`, `bash infra/ci/test-infra-validate.sh`, `bash infra/ci/test-host-tools.sh`.
- `construction/build-and-test/` 산출물 완성: 위 디렉터리에 초안 파일이 일부 있다. 필수는 `build-instructions.md` · `integration-test-instructions.md` · `build-and-test-summary.md`(Target Verification Matrix) · `test-results.md` · `cross-unit-traceability.md`(FR/NFR 전체 OK 확인, `code-generation/traceability.json` 87행 기준). 남은 `Pending` 판정은 없어야 한다.
- **실호출 확인 `[실호출-합성]`(사람이 키를 환경에 둔 뒤에만, R-F13 · R-F15)**: `./gradlew :realtime-activity:gptLiveTest`. 키는 `~/.config/mujoong-live.env`(600) 같은 환경 파일로만 두고 채팅에 붙이지 않는다. 관측할 항목:
  - 새 말 허용 여유 `mujoong.timing.provider.new-speech-margin`(잠정 600ms)
  - 아이 말 묶음 조용함 `mujoong.timing.provider.input-transcript-quiet`(잠정 1500ms)
  - 끼어들기 지시가 한 번만 지켜지는지(표본 1건뿐), 연습 발화(verbatim) 첫 600ms 잘림
  - Luna 판정 프롬프트(`judgment/prompt-v1.txt`) 품질과 4초 시한
  값을 바꾸면 R-M5의 세 곳(두 앱 `application.yml` · `contracts/timing.md` · `frontend/src/shared/timing/timing-defaults.json`)을 같은 커밋에서 고친다.

## 5. 알려진 사항

- `ReportPdfTest.twentyActivityReportCreatesNoTemporaryFiles`는 병렬 실행에서 `/tmp/tomcat.*`를 건드려 가끔 실패한다(core-api만 따로 다시 돌리면 통과).
- `HOLD_REASK` 지시는 아직 운영 호출처가 없다. U12 확장점이 부를 때를 위해 남겨 두었다(심사 R-01).
- 커밋하지 않은 로컬 파일: `.aidlc-unit7-diagnostic/`, `...handoff_v2_2026-10-15.md:Zone.Identifier`(둘 다 PR에 넣지 않았다).
- GPT-Live 선 형식 사실은 `construction/code-generation/design-addendum-wire-format.md`(A1~A10), 실호출 요약은 같은 폴더 `real-call-evidence.md`.
