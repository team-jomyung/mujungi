# U6 빌드·시험 결과

## 최종 상태

**최신 재검증(2026-10-14): PostgreSQL18.6 설치 경로를 명시한 전체check 성공(34초). 집계2,405개 통과·실패0·건너뜀0이다.** test-support27개는 새로 실행했고 나머지 모듈은 Gradle 입력 검증의 UP-TO-DATE 결과를 재사용했다. 앞서 건너뛴 LOCAL3개는18.6에서 통과했다. LOCAL16 검증으로 해석하지 않는다. 증거는 evidence/backend-check-postgresql18.json·skipped-retest-postgresql18.json이다. 아래 기존 집계는 재검증 전 이력이다.

2026-10-14. 이번 검증 PASS. 백엔드 check·bootJar·시험 항목 검사 BUILD SUCCESSFUL(4m19s), frontend build/lint/typecheck 모두 종료0이다. 초기 환경 실패는 Docker 설정으로 재검증했다.

## 실행과 집계 — PostgreSQL 설치 전 이력

| 실행 | 통과 | 실패 | 건너뜀 | 시간/증거 |
|---|---:|---:|---:|---|
| U4a Docker 경계 | 11 | 0 | 0 | 43s; evidence/u4a-docker-01.json |
| backend U6 | 232 | 0 | 0 | 1m51s; evidence/backend-u6.json |
| backend 전체 | 2402 | 0 | 3 | 4m19s; evidence/backend-all.json |
| frontend U6 | 61 | 0 | 0 | 13.81s; evidence/frontend-u6.json |
| frontend 전체 | 864 | 0 | 0 | 117.03s; evidence/frontend-all.json |
| 기존 17/U4a 화면 | 28 | 0 | 0 | 5.96s |
| record27/U5a 표적 | 65 | 0 | 0 | 8.89s |
| 제한 컨테이너 PDF | 1 | 0 | 0 | 2539ms; evidence/pdf-container.json |

각 범위 실행과 전체 실행에 같은 시험이 포함된다. 표를 모두 더해 고유 시험 수라고 주장하지 않는다. backend 전체는 core1060·contract298·realtime1020·test-support27(24통과·기존3skip)이다. realtime/test-support의 이번check는 UP-TO-DATE 결과 재사용이다. 운영 jar 시험항목0.

## 실패와 제한

기존 건너뜀3개는 모두 MujoongPostgresTest의 LOCAL 사례이며 호스트 PostgreSQL16 부재가 원인이다. LOCAL 경로를 통과로 세지 않는다. 항목별 메서드·미검증 동작·준비 조건·강제 재실행 명령·완료 기준은 [skipped-tests.md](skipped-tests.md)에 기록했다. evidence/skipped-tests.json에 skipped 사유와스택 식별을 보존했다. 이3개는 이번 전체check에서도 이전 결과를 재사용했으므로 새 실행된 건너뜀이 아니다.

처음 `-Pmujoong.test.db=local`은 PostgreSQL16 부재로 initializationError1건, BUILD FAILED15s였다. Docker 백엔드로 환경 수정 후 같은 U4a11개가 통과했다.

앞선 코드 단계의 삭제 시험 실패와 동기 AFTER_COMMIT의 공유 연결 고갈은 알려진 간헐 위험이다. 코드를 수정하지 않았다. 이번 성공이 이전 실패 이력을 없애지는 않는다. 코드 단계에서 사람이 수용한 위험으로 남겼다. 이전 report.md/code-summary.md의212개 수치는 수정 전 실행이며 이번 결과232개와 구분한다.

XML/JaCoCo 집계는 다음 실행 전 evidence JSON에 저장했다. JSON은 전체XML 사본이 아니다. 실제 AI·U5b·모바일 수동 검증 한계는 요약과 같다.

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| NFR1 | requirements.md NFR1; functional-spec.md10.3 | 20활동 READY≤60초; 초과FAILED | A11394ms·D4303ms; B/C통과 | evidence/backend-u6.json; ReportHandlerIT | build-and-test | Met |
| NFR2 | requirements.md NFR2 | 금지필드노출0; 스키마/PDF통과 | U6응답/PDF검사통과 | evidence/backend-u6.json; ResultApiIT·ReportApiIT·ReportPdfTest | build-and-test | Met |
| NFR3 | requirements.md NFR3 | 금지표현0 | 금지사전/고정문구 검사통과 | evidence/backend-u6.json; ForbiddenDictionaryTest | build-and-test | Met |
| NFR4 | requirements.md NFR4 | AI 이름/id/원음0; 로그원문0 | 입력/로그 단언통과 | evidence/backend-u6.json; ResultTextGatewayTest | build-and-test | Met |
| NFR5 | requirements.md NFR5 | 타인404; 삭제중403 | 404/403/CSRF경계통과 | evidence/backend-u6.json; ResultApiIT·ReportApiIT | build-and-test | Met |
| NFR6 | requirements.md NFR6 | 시간상수0; 설정/스캐너통과 | 전체check 설정/시간검사통과 | evidence/backend-all.json | build-and-test | Met |
| NFR7 | requirements.md NFR7 | 동시접수/키당작업1 | 동시/멱등/재시도 단언통과 | evidence/backend-u6.json | build-and-test | Met |
| NFR8-BE-U6 | requirements.md NFR8; unit-test-instructions.md2 | U6줄≥80% | 86.73%(1203/1387) | evidence/backend-u6.json | build-and-test | Met |
| NFR8-FE-U6 | requirements.md NFR8; unit-test-instructions.md2 | U6줄≥80% | 83.21%(228/274) | evidence/frontend-u6.json | build-and-test | Met |
| NFR8-MODULE | requirements.md NFR8; unit-test-instructions.md2 | 각모듈줄≥80% | core96.27%, contract98.47%, realtime92.84%, frontend98.55% | evidence/backend-all.json; evidence/frontend-all.json | build-and-test | Met |
| NFR9 | requirements.md NFR9; Testing Contract scope_floor | 기존시험모두통과; check성공 | 최신check2405통과·skip0·실패0; 이전실패이력있음 | evidence/backend-check-postgresql18.json; evidence/backend-all.json | build-and-test | Met |
| NFR10 | requirements.md NFR10 | 계약검사통과 | 전체 계약/스키마 검사통과 | evidence/backend-all.json; evidence/backend-u6.json | build-and-test | Met |
| NFR11 | requirements.md NFR11 | 삭제후U6행0 | U6삭제5개 등 삭제단언통과; 이번U4a11개통과 | evidence/backend-u6.json; evidence/u4a-docker-01.json | build-and-test | Met |
| NFR12-TMP | requirements.md NFR12 | 1.6GB·읽기전용·tmp64MB 새파일0 | 2539ms·7페이지·새파일0 | evidence/pdf-container.json | build-and-test | Met |
| NFR12-IMAGE | requirements.md NFR12 | 동일기준선 이미지증가 기록 | 545066875→564883067; +19816192바이트 | evidence/pdf-container.json | build-and-test | Met |
| BUILD-JAR | code-generation-plan.md Step28 | 빌드성공; jar시험항목0 | bootJar성공; 시험항목0 | evidence/backend-all.json | build-and-test | Met |
| BUILD-FE | code-generation-plan.md Step29 | build/lint/typecheck성공 | build/lint/typecheck성공 | evidence/frontend-all.json | build-and-test | Met |

## 커버리지와 추적

backend U6줄86.73%(1203/1387), frontend U6줄83.21%(228/274). 전체 core96.27%(6910/7178), contract98.47%(1485/1508), realtime92.84%(8387/9034), frontend98.55%(3145/3191). 80% 기준을 낮추지 않았다. cross-unit-traceability.md의 상세FR/NFR52개가 모두 OK대상 존재를 통과했다.

## Sources

evidence/backend-all.json·backend-u6.json·frontend-all.json·frontend-u6.json·pdf-container.json·u4a-docker-01.json; code-generation/traceability.json; requirements.md.
