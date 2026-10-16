# U9 Luna 호출부 — Infrastructure Design 질문지

> 근거: `../nfr-design/security-design.md`(SD1 ~ SD12) · `logical-components.md`, `../functional-design/functional-spec.md` 7절, `../../../inception/domain-design/components.md`, `../../../inception/contract-design/contract-summary.md`.
> 코드 · 인프라 사실(이번 단계에서 확인함)
> - CI `ci.yml`: 백엔드 전체는 `./gradlew clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`(127행), 범위 커버리지는 `:…:test -PincludeTags=u5b|u7`(157 · 161 · 177행), 의존성 취약점은 bootJar를 trivy `rootfs`로 HIGH · CRITICAL 스캔(206 ~ 216행), 이미지 점검은 별도 작업(410행 이후).
> - `OPENAI_API_KEY`는 U8이 이미 분배했다: `infra/bin/render-env` 선택 비밀(66 · 193 · 196행), `infra/compose.demo.yml`(170행), `scripts/verify/b-stack.sh` 서비스별 기대값(110행, core-api · realtime). 검증 스택 · CI에서는 빈 값이다.
> U9는 `library` 단위라 인프라 명세 · 모니터링 설계 문서 없이 CI/CD 문서만 쓴다(단계 정의의 종류별 산출물). 새 인프라 자원이 없으므로 아래 가정 묶음으로 확인받는다.

## Assumption Confirmation

1. **새 인프라 없음:** 새 서버 · 컨테이너 · DB · 큐 · 비밀 이름이 없다. 나가는 HTTPS(443, `api.openai.com`)만 쓰고, GPT-Live가 이미 같은 호스트로 나가므로 방화벽 · 보안 그룹을 바꾸지 않는다.
2. **비밀 분배:** U8이 넣은 `OPENAI_API_KEY` 분배(core-api · realtime, 선택)를 그대로 쓴다. 검증 스택 · CI는 빈 값이라 어댑터가 꺼진 채 돈다. demo에 키를 넣는 일은 이번 작업 범위 밖이고(team.md Deployment), 넣을 때는 RUNBOOK R-F15 확인 기록이 먼저다(SD10).
3. **CI 영향:** `check`에 `lunaLiveTest`가 물리지 않으므로 CI 명령은 바꾸지 않는다. 범위 시험 명령의 `-PincludeTags`는 기본 `test` 작업에 걸리므로 `luna-live` 고정 제외와 함께 돈다. 의존성 이동(`json-schema-validator` → 실행 범위)은 기존 trivy bootJar 스캔이 잡는다. 이 스캔을 Code Generation 단계에서 로컬로 한 번 돌려 결과를 적는다(SD9).
4. **verify.sh 영향:** 끝-끝 스택은 키가 없어 지금과 같은 실패 경로를 탄다. 사후 단언(로그 위생 P2)이 `LUNA_STARTUP`(꺼짐) 한 줄을 허용하도록 `log-hygiene-check` 허용 속성 목록을 SD6대로 함께 고친다.
5. **배포 · 롤백:** U9는 배포 방식을 바꾸지 않는다. 키를 넣은 뒤 문제가 생기면 키를 비우고 앱을 재기동하는 것이 롤백이다(어댑터 꺼짐 = 지금 동작). 이 절차를 RUNBOOK 확인 기록 표 옆에 한 줄로 적는다.

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions

## Q1. (검토 뒤) CI/CD 검토 지적을 어떻게 처리할까

아키텍처 검토는 '진행 가능'이었지만 Major 1건 · Minor 5건이 남았다(`reviews/review-01.md`). R-01: `log-hygiene-check`의 허용 표는 `EventCode` 정본과 대조되는 고정 표라, 새 사건(`LUNA_STARTUP` · `LUNA_CALL`)은 contract-model · `contracts/` · `test-host-tools.sh`를 함께 고쳐야 한다. 빠뜨리면 PR 관문은 통과하고 main 병합 뒤 끝-끝 로그 위생 단언에서 처음 실패한다. Minor: R-02 롤백은 RUNBOOK대로 같은 SHA `DEPLOY` 재실행, R-03 R-F15 확인 기록 표는 Code Generation 산출물로 표시, R-04 trivy에 걸릴 때 대응 순서, R-05 빌드 구성 시험의 위치, R-06 demo 모델 env 선행 항목.

A. 문서 고친 뒤 진행 — 새 사건 코드 변경 묶음(EventCode · AttrKey · log-hygiene-check 표 · test-host-tools.sh · RUNBOOK 로그 절 · ai-io.md)을 한 커밋 단계로 적고, Minor 5건도 반영한 뒤 다시 검토를 받는다 (추천)
B. 코드 생성 계획에서 정하기 — 문서는 그대로 두고, Code Generation 계획에 이 결정을 단계로 넣는다
X. Other (please specify)

[Answer]: A
