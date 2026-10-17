# U10 빌드·검증 요약

## Sources

승인된 요구사항 NFR1~6·FR1~7, Code Generation의 Testing Contract·시험 지침·코드 요약, team Testing Posture를 목록화했다. 이 기록은 nfr-requirements와 nfr-design을 실행하지 않아 해당 산출물이 없다. Standard와 기존 모듈 줄80%·U10 범위 줄80%·기존 전체 회귀·실제 끝-끝 기준은 모두 유지한다.

## Readiness

최종 검증 커밋은 main U11을 합친 `6cd526f`, 사본은 `/home/pdypd/mujungi-u10-final-verify`다. 전체 명령은758초에 FAIL: backend3416건과 frontend994건은 통과했으나 U11 tools 시험1건이 실패했다. 빌드 산출물은 생성됐지만 전체 검증/배포 준비 완료 판정은 하지 않는다. push·PR·CI는 이 새 실패의 처리 판단 뒤 진행한다. U10 main 병합·배포·U12 실제 소비·실기기는 범위 밖이다.

## Test inventory

빌드, 단위/통합/계약/아키텍처, 모듈·U10 범위 커버리지, frontend·tools, Compose 끝-끝 및 역할/로그/비밀/작업 트리 단언, 인프라 검사, PR CI를 다룬다. 성능·보안 지침도 기존 요구가 있으므로 작성했다. 실제 공급자 측정은 자동 대역과 분리한다.

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-BUILD | Testing Contract / 빌드, NFR2 | JAR·frontend 빌드 및 전체 명령 성공 | JAR/Next 빌드 PASS, 전체 A3 실패 | test-results.md / report.txt | Build and Test | Not Met |
| T-REGRESSION | NFR2 / 기존 전체 회귀 | 기존 단위·통합·계약·ArchUnit 성공, 원 단언 유지 | backend3416·frontend994 PASS, tools130/131 | A1/A2/A3.log | Build and Test | Not Met |
| T-U10 | Testing Contract / Standard | u10 시험 0건 아님, 핵심 경계와 실패 대조 성공 | 22클래스160건, 실패/오류/skip0 | JUnit XML 및 test-results.md | Build and Test | Met |
| T-MODULE-COV | team Testing Posture | Core·RT·계약·frontend 모듈 줄80% 이상 | 96.1781/93.4167/98.4526/98.55% | JaCoCo XML / frontend coverage | Build and Test | Met |
| T-U10-COV | NFR2 | 실제 운영27파일 범위 줄80%, 누락/빈 범위 실패 | 1372/1418줄=96.7560%, 누락0 | coverage-scope/u10.txt / JaCoCo | Build and Test | Met |
| T-DEADLINE | NFR1, FR2.3 | 최초15초 마감을 첫 전달까지 유지, 늦은 성공0 | 별도 준비 READY 경계 R-06 미수리, 기존 위험 수용 | review-02.md / test-results.md | Build and Test | Not Met |
| T-GRAPH-BUDGET | FR5.2, NFR1 | 그래프 합계2초·장애 기본 경로·진실한 graphContextUsed | 관련 U10 자동 대조 통과 | U10Deadline/Graph/PlannedGeneration 시험 | Build and Test | Met |
| T-RETRY | NFR1, FR2.2 | 기술1·내용1·최대6호출을 최초 잔여 안에서만 수행 | 관련 생성·복구 대조 통과 | U10 시험 XML | Build and Test | Met |
| T-COMPAT | FR3·FR4, NFR4 | V10 더하기만·과거 자료/쓰기·기존 C8/C10 필수 값 유지 | PG/계약/첫 패키지 대조 통과 | U10Repository/Preparation/PlannedGeneration XML | Build and Test | Met |
| T-PRIVACY | FR7, NFR3·NFR6 | 공급자 최소 입력·권한/동의/삭제 재확인·민감 로그0 | U10 대조 및 A4 PASS, 스택 P2/P3 미실행 | U10 XML / A4 출력 / test-results.md | Build and Test | Unverified |
| T-E2E | team Walking Skeleton | 전체 verify.sh의 A/B/C/P1/P2/P3/T 성공 | A3 실패로 B/C/사후 단언 미실행 | report.txt RESULT FAIL | Build and Test | Unverified |
| T-INFRA | R-M2~5, team Testing Posture | Compose·마이그레이션·프로필·이미지/관문 검사 성공 | 정적8건·음성대조46건 PASS, 이미지/CI 미실행 | infra-validate / test-infra-validate 출력 | Build and Test | Unverified |
| T-CI | 지시서 / 완료 조건, R-M1 | 최종 PR 커밋의 필수 ci-gate 성공 | push/PR 전 수리 판단 대기 | test-results.md | Build and Test | Unverified |
| T-EVIDENCE | NFR5 | 자동 대역/실호출/실기기 증거를 분리, 미측정 공개 | 대역/실호출/실기기 구분과 중단/실패 이력 보존 | test-results.md / performance-test-instructions.md | Build and Test | Met |
| T-LIVE-LATENCY | 원 FR2.5, 지시서 실호출 관측 | 준비 확인 후 합성 실호출15초 관측 | 실제 공급자 호출 미실행 | performance-test-instructions.md | 별도 준비 확인 필요 / 예정된 후속 검증 단계 없음 | Unverified |

## Known limitations

사용자는 코드 생성 완료 때 R-05(RT 간헐 실패 위험)와 R-06(별도 준비 READY의 마감 검사 누락)을 위험으로 수용했다. 수용 사실과 목표 충족 여부는 별개로 남긴다. R-06이나 실호출 미측정을 합격으로 바꾸지 않으며 최종 목표 표에서 결과와 잔여 위험을 공개한다.
