# U6 통합·회귀 시험 지시

## 명령

backend에서 순서대로 실행한다.

```sh
./gradlew --no-daemon :core-api:test --tests 'kr.mujoong.core.activity.U4aActivityApiIT' -Pmujoong.test.db=docker
./gradlew --no-daemon :contract-model:test :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=u6 -PcoverageScope=u6
./gradlew --no-daemon check
```

코드 생성 unit-test-instructions.md의 서로 다른 모든 명령은 한 번씩 실행한다. frontend 전체 회귀는 `npx vitest run --coverage --maxWorkers=2`, U6범위는 `MUJOONG_COVERAGE_SCOPE=u6 npx vitest run tests/u6 --coverage --maxWorkers=2`다.

## 기준과 증거

기존 test-support LOCAL3개 건너뜀의 상세 재실행 절차는 [skipped-tests.md](skipped-tests.md)를 따른다. 환경이 준비되면 해당 클래스에 --rerun-tasks를 붙여 캐시를 무시하고 검사하며 LOCAL3개가 실제 통과했는지 확인한다.

기존 풀2·획득시한2초·단언을 유지한다. 실제 기록 커밋→결과 접수, 멱등, 동의·삭제, U5a후보, 스키마, 17·26·27·28·30을 검사한다. 실제 AI는 FakeResultTextProvider로 대체한다. U5b 실제 흐름은 인계 사항이다. 범위·모듈 줄80% 이상, 기존 시험 실패0이 기준이다. 첫 명령에서 제품 결함이 재현되면 나머지를 중단하며 미실행은 Unverified다. XML위치는 backend/core-api/build/test-results/test/다.

## Sources

construction/code-generation/code-generation-plan.md · unit-test-instructions.md · code-summary.md; inception/requirements-analysis/requirements.md; construction/functional-design/functional-spec.md。
