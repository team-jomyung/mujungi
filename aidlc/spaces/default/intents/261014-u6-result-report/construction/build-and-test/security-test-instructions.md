# U6 보안 시험 지시

## 범위와 기준

NFR2–5·NFR10–11: 금지필드 노출0, 금지표현0, AI 이름/id/원음0, 로그원문0, 타인404, 삭제중403, 삭제후 U6행0, 응답·이벤트 계약검사통과를 요구한다. 공급자 호출은 DB 트랜잭션 밖에서 실행한다. 공유 풀 고갈은 삭제·가용성에 영향을 주는 제품 결함이다.

## 명령과 증거

동일 클래스가 U6 전체 실행에 포함되면 그 단언 결과를 보안 검증에도 사용한다. 중복 시험 수를 합산하지 않는다. 이번 NFR2–5·10–11은 evidence/backend-u6.json과 backend-all.json으로 확인했다.

backend에서 `./gradlew --no-daemon :core-api:test -PincludeTags=u6 --tests 'kr.mujoong.core.result.ResultApiIT' --tests 'kr.mujoong.core.report.ReportApiIT' --tests 'kr.mujoong.core.result.ResultDeletionIT' --tests 'kr.mujoong.core.result.ReportHandlerIT' --tests 'kr.mujoong.core.result.ResultTextGatewayTest' --tests 'kr.mujoong.core.result.check.ForbiddenDictionaryTest'`를 실행한다. 전체check의 계약·시간 상수·구조 검사도 적용한다. XML과 요청/로그 단언이 증거다. 삭제 회귀로 중단하면 미실행은 Unverified다. 기존 검토의 수정 확인은 이번 시험 성공을 대신하지 않는다.

## Sources

construction/code-generation/code-generation-plan.md · unit-test-instructions.md · code-summary.md; inception/requirements-analysis/requirements.md; construction/functional-design/functional-spec.md。
