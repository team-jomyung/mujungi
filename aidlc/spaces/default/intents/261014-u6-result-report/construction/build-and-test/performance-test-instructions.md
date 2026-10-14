# U6 성능 시험 지시

## 목표

NFR1·functional-spec.md10.3: 20활동·호출당2초·첫 호출 실패 후60초 안 READY, 초과FAILED. NFR12: PDF20활동, 읽기전용 루트·메모리1.6GB·tmp64MB에서 새파일0과 이미지 크기 증가 기록. 이후 검증을 맡는 단계는 없다.

## 명령과 판정

동일 성능/PDF 클래스가 U6 전체 실행에 포함되면 그 실행을 증거로 사용해 재실행과 합산을 생략한다. 이번 컨테이너 명령은 evidence/pdf-container.json에 정확히 기록했다.

backend에서 `./gradlew --no-daemon :core-api:test -PincludeTags=u6 --tests 'kr.mujoong.core.result.ReportHandlerIT' --tests 'kr.mujoong.core.report.ReportPdfTest' --tests 'kr.mujoong.core.report.ReportPdfColdStartTest'`로 A–D·PDF 경계를 검사한다. 제한 컨테이너는 docs/units/u6/design.md의 PDF 측정 명령을 따른다. 기존 PDF1709ms·0파일·이미지증가18.90MiB는 이전 실행이며 이번 성공으로 세지 않는다. 삭제 회귀로 먼저 중단하면 이 목표는 Unverified다. 실제 공급자 성능은 승인된 범위 밖이다.

## Sources

construction/code-generation/code-generation-plan.md · unit-test-instructions.md · code-summary.md; inception/requirements-analysis/requirements.md; construction/functional-design/functional-spec.md。
