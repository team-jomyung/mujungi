# U8 계약 · 버전 리소스 — 단위 시험 실행 방법

> 대상: U8(`u8-contracts-catalog`)이 만들거나 고친 시험만. 전체 회귀(`bash scripts/verify.sh`)는 Build and Test에서 한 번 돌린다.
> 시험은 가짜 공급자만 쓴다(R-F1 · ADR-002). 실제 OpenAI · 제브 키는 쓰지 않는다.

## 준비

- JDK 21(백엔드 Gradle). DB 통합 시험(`@Tag("db")`)은 Docker가 있어야 한다(`-Pmujoong.test.db=docker`).
- 명령은 `backend/` 폴더에서 돌린다.

## 태그 · 범위

- 새 시험 클래스에는 `@Tag("u8")`을 붙인다. DB가 필요한 시험은 `@Tag("db")`도 붙이고 이름을 `*IT`로 한다.
- 범위 파일 `backend/coverage-scope/u8.txt`: U8이 만들거나 고친 운영 Java 파일. 빈 목록 · 없는 파일이면 측정이 실패한다(J137).

## 명령

1. 계약 · 스키마 시험(contract-model)

   ```bash
   ./gradlew --no-daemon :contract-model:test -PincludeTags=u8
   ./gradlew --no-daemon :contract-model:test --tests 'kr.mujoong.contract.ContractFilesTest'
   ```

2. core 조립 · 설정 시험

   ```bash
   ./gradlew --no-daemon :core-api:test -PincludeTags=u8
   ./gradlew --no-daemon :core-api:test --tests 'kr.mujoong.core.config.ConfigKeyContractTest' --tests 'kr.mujoong.core.arch.TimeLiteralScanTest'
   ```

3. realtime 레지스트리 · 기동 확인 · ArchUnit 시험

   ```bash
   ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u8
   ./gradlew --no-daemon :realtime-activity:test --tests 'kr.mujoong.realtime.config.ConfigKeyContractTest' --tests 'kr.mujoong.realtime.arch.*' --tests 'kr.mujoong.realtime.startup.*'
   ```

4. U8 범위 줄 80%

   ```bash
   ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :contract-model:jacocoTestCoverageVerification :core-api:jacocoTestCoverageVerification :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u8 -PcoverageScope=u8
   ```

   범위 목록에 어느 모듈의 줄도 없으면 그 모듈 측정이 실패하므로, 운영 파일을 고치지 않은 모듈은 이 명령에서 뺀다.

5. DB 시험(시드 멱등 · dev 시드) — Docker가 있을 때

   ```bash
   ./gradlew --no-daemon :core-api:test -PincludeTags=u8 -Pmujoong.test.db=docker
   ```

6. 인프라 · 비밀 이름 시험(저장소 루트에서)

   ```bash
   bash infra/ci/test-infra-validate.sh
   bash infra/ci/test-host-tools.sh
   shellcheck infra/bin/infra-validate infra/bin/render-env infra/bin/log-hygiene-check infra/ci/gen-env.sh infra/ci/image-checks.sh scripts/verify/lib.sh scripts/verify/b-stack.sh scripts/verify/b-host.sh
   ```

## 합격 기준

- 위 명령이 모두 통과한다. 새 시험은 정상 경로와 오류 · 경계 경우를 2개 이상 담는다.
- 기존 `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · ArchUnit 시험은 고치거나 건너뛰지 않는다(R-F2).
- Docker가 없어 5번을 돌리지 못하면 그 사실을 `code-summary.md`에 적고 Build and Test에서 확인한다.
- 결과 보고는 테스트 대역 성공만 적는다. 실제 API · 실제 기기 성공으로 표시하지 않는다(R-M11).
