# U10 빌드·검증 요약

## Sources

승인된 요구사항 NFR1~6·FR1~7, Code Generation의 Testing Contract·시험 지침·코드 요약, team Testing Posture를 목록화했다. 이 기록은 nfr-requirements와 nfr-design을 실행하지 않아 해당 산출물이 없다. Standard와 기존 모듈 줄80%·U10 범위 줄80%·기존 전체 회귀·실제 끝-끝 기준은 모두 유지한다.

## Readiness

검증 소스는 `7125ee009525c314226ec181f61c4d87efe2f187`이며, 검증된 main6a785를 통합한700ms 계약판이다. 전체 명령은767초 RESULT PASS rc0이고 지정된 네 한정 명령도 각각1회 통과했다. 기록 커밋41bc128을 push하고 main 대상 PR31을 만들었으며 CI39040062848의 필수 ci-gate와 실제 실행 작업 전부가 성공했다. 이 결과를 적는 문서 커밋의 CI도 PR에서 추가 확인한다. 이전 bd666d0 C9 실패와 중단·절전·Loop-back1·2는 보존한다. 명령·CI 통과와 별개로 R-06은 Not Met, 실호출은 Unverified여서 모든 품질 목표 충족이나 배포 준비 완료를 주장하지 않는다. main 병합·배포·실기기는 범위 밖이다.

## Test inventory

빌드, 단위/통합/계약/아키텍처, 모듈·U10 범위 커버리지, frontend·tools, Compose 끝-끝 및 역할/로그/비밀/작업 트리 단언, 인프라 검사, PR CI를 다룬다. 성능·보안 지침도 기존 요구가 있으므로 작성했다. 실제 공급자 측정은 자동 대역과 분리한다.

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-BUILD | Testing Contract / 빌드, NFR2 | JAR·frontend 빌드 및 전체 명령 성공 | JAR2개 시험 클래스0·frontend build·전체 rc0 | test-results.md / 7125ee0 전체 실행 | Build and Test | Met |
| T-REGRESSION | NFR2 / 기존 전체 회귀 | 기존 단위·통합·계약·ArchUnit 성공, 원 단언 유지 | backend3546건 실패0/기존 skip3·frontend994건·tools133건 | test-results.md / 전체 XML·A1~A3 로그 | Build and Test | Met |
| T-U10 | Testing Contract / Standard | u10 시험 0건 아님, 핵심 경계와 실패 대조 성공 | 22클래스160건·실패/오류/skip0 | test-results.md / 전체 XML 및 한정 실행 | Build and Test | Met |
| T-MODULE-COV | team Testing Posture | Core·RT·계약·frontend 모듈 줄80% 이상 | Core96.1877%·RT93.3393%·계약98.4526%·frontend98.55% | test-results.md / 보존된 전체 JaCoCo·A2 로그 | Build and Test | Met |
| T-U10-COV | NFR2 | 실제 운영27파일 범위 줄80%, 누락/빈 범위 실패 | 27파일1372/1418줄=96.7560%, 누락0 | test-results.md / 전체 JaCoCo·u10.txt 대조 | Build and Test | Met |
| T-DEADLINE | NFR1, FR2.3 | 최초15초 마감을 첫 전달까지 유지, 늦은 성공0 | 별도 준비 READY 경계 R-06 미수리, 기존 위험 수용 | review-02.md / test-results.md | Build and Test | Not Met |
| T-GRAPH-BUDGET | FR5.2, NFR1 | 그래프 합계2초·장애 기본 경로·진실한 graphContextUsed | 실제 BASE 입력·예산/장애/BASE-only 대조 통과 | test-results.md / U10 전체 및 한정 XML | Build and Test | Met |
| T-RETRY | NFR1, FR2.2 | 기술1·내용1·최대6호출을 최초 잔여 안에서만 수행 | 생성/검토 공유 예산·재시도/취소 대조 통과, READY 경계는 T-DEADLINE 별도 | test-results.md / U10 전체 및 한정 XML | Build and Test | Met |
| T-COMPAT | FR3·FR4, NFR4 | V10 더하기만·과거 자료/쓰기·기존 C8/C10 필수 값 유지 | V10·구버전 쓰기·대본 DB/첫 전달 대조 및 FlywayConfIT5건 통과 | test-results.md / 전체·한정 XML | Build and Test | Met |
| T-PRIVACY | FR7, NFR3·NFR6 | 공급자 최소 입력·권한/동의/삭제 재확인·민감 로그0 | U10 대조·A4·P1/P2/P3 통과 | test-results.md / 전체 XML·보안 로그 | Build and Test | Met |
| T-E2E | team Walking Skeleton | 전체 verify.sh의 A/B/C/P1/P2/P3/T 성공 | A1~A4/F/B/C1~C12/P1/P2/P3/T 통과767초 | test-results.md / 전체 report.txt | Build and Test | Met |
| T-INFRA | R-M2~5, team Testing Posture | Compose·마이그레이션·프로필·이미지/관문 검사 성공 | 전체 B/P1~P3와 정적8건·실패 대조46건 통과 | test-results.md / 전체 로그 및 인프라 출력 | Build and Test | Met |
| T-CI | 지시서 / 완료 조건, R-M1 | 최종 PR 커밋의 필수 ci-gate 성공 | 소스 동일한41bc128의 ci-gate 및 실제 작업 모두 성공, 결과 기록 커밋도 추가 확인 | test-results.md / PR31·CI39040062848 및 PR Checks | Build and Test | Met |
| T-EVIDENCE | NFR5 | 자동 대역/실호출/실기기 증거를 분리, 미측정 공개 | 대역/실호출/실기기 구분과 중단/실패 이력 보존 | test-results.md / performance-test-instructions.md | Build and Test | Met |
| T-LIVE-LATENCY | 원 FR2.5, 지시서 실호출 관측 | 준비 확인 후 합성 실호출15초 관측 | 실제 공급자 호출 미실행 | performance-test-instructions.md | 별도 준비 확인 필요 / 예정된 후속 검증 단계 없음 | Unverified |

## Known limitations

사용자는 코드 생성 완료 때 R-05(RT 간헐 실패 위험)와 R-06(별도 준비 READY의 마감 검사 누락)을 위험으로 수용했다. 수용 사실과 목표 충족 여부는 별개로 남긴다. R-06이나 실호출 미측정을 합격으로 바꾸지 않으며 최종 목표 표에서 결과와 잔여 위험을 공개한다.

추적 표는 승인된 입력의 분류를 유지한다. FR/NFR31행 중 상세22행은 OK이고 FR 묶음 머리7행·스토리 밖 NFR2/5는 N/A다. 모든75개 upstream ID는 매핑돼 있고57개 OK 대상 파일이 실재하지만 문자 그대로 모든31행이 OK인 관문은 미충족이다. 이 차이는 cross-unit-traceability.md에 공개하며 추적 입력을 합격용으로 고치지 않았다.
