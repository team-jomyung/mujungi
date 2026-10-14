# U6 빌드·시험 요약

## 상태와 검증 목록

**최신 결과: PostgreSQL18.6 경로를 지정해 건너뛴3개를 다시 실행했고 전체check도 통과했다. 집계2,405개 통과·실패0·건너뜀0.** test-support27개는 새 실행, 나머지는 Gradle UP-TO-DATE 결과 재사용이다. LOCAL18.6 결과이며 프로젝트 기준 LOCAL16 검증으로 바꾸지 않는다. 이전 검증 이력과 계정삭제 연결 고갈 위험은 유지한다.

2026-10-14. PASS: 이번 실행의 빌드·시험·커버리지 목표를 충족했다. Standard·test-after 계약을 따랐다. nfr-requirements/nfr-design 단계는 실행되지 않아 해당 산출물은 없으며 requirements.md의 NFR1–12, functional-spec.md, 현재 code-generation-plan.md의 Testing Contract와 unit-test-instructions.md의 범위·모듈80% 하한을 전부 확인했다.

검증은 빌드·범위 단위/통합·전체 회귀·성능·보안이다. 동일 U6 전체 실행에 포함된 성능/보안 단언은 중복 합산하지 않는다. 뒤에 검증을 맡는 단계는 없다. 구조 추적52개 FR/NFR의 OK 대상 파일은 모두 존재한다.

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

## 실패·수정·재현 이력

초기 local DB 실행은 호스트 PostgreSQL16 부재로 초기화1건 실패했다. 빌드·시험 환경 범위에서 Docker 백엔드로 수정했고 U4a11개는 통과했다. 이후 U6232개와 전체check는 통과했다. 테스트 소스·풀2·획득시한2초·품질하한은 바꾸지 않았다.

앞선 코드 단계에서 계정 삭제 시험이 전체/단독 모두 연결 고갈로 실패했다. 동기 AFTER_COMMIT→REQUIRES_NEW의 추가 연결 의존성은 수정하지 않았으며 사람이 코드 단계의 위험을 수용했다. 이번 통과는 해당 구조의 안전성 입증이나 이전 실패 취소가 아니다. 간헐적 재발 위험을 유지한다.

## 준비도와 한계

기존 건너뜀3개는 MujoongPostgresTest LOCAL 경로였다. 설치된18.6에서 모두 통과했고 Docker 대응 사례도 통과했다. 프로젝트 기준 LOCAL16은 아직 미검증이다. 상세 이력·재검증 명령·완료 기준은 skipped-tests.md에 기록했다.

현재 로컬 빌드/시험 완료. 배포 준비 또는 운영 안전성은 승인하지 않는다. 실제 AI 공급자는 승인된 범위에 따라 미연결이고 성능/내용은 가짜 공급자 기준이다. U5b 실제 연습 흐름, D담당 초안확인, Android Chrome PDF 공유/저장은 기존 인계 사항이다.

PDF 컨테이너 검증은 기존 u6-verify 이미지의 동일한 PDF 코드·글꼴·템플릿을 재실행했다. 최근 동의/장면 검증 변경은 그 이미지에 없으므로 전체 현행 제품 이미지 검증이라고 주장하지 않는다. core/contract는 이번 전체check에서 실행했고 realtime/test-support는 Gradle 입력검증의 UP-TO-DATE 결과를 재사용했다. test-support는 운영 모듈이 아니며80% 운영모듈 대상에 포함하지 않는다.

## Sources

construction/code-generation/code-generation-plan.md·unit-test-instructions.md·code-summary.md; inception/requirements-analysis/requirements.md NFR1–12; construction/functional-design/functional-spec.md; evidence/*.json.
