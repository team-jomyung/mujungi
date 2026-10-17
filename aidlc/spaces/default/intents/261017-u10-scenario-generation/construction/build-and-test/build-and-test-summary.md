# U10 빌드·검증 요약

## Sources

승인된 요구사항 NFR1~6·FR1~7, Code Generation의 Testing Contract·시험 지침·코드 요약, team Testing Posture를 목록화했다. 이 기록은 nfr-requirements와 nfr-design을 실행하지 않아 해당 산출물이 없다. Standard와 기존 모듈 줄80%·U10 범위 줄80%·기존 전체 회귀·실제 끝-끝 기준은 모두 유지한다.

## Readiness

실행 준비 중이다. 제품 기준은 `1e4db60d449153dd9848f527e0d31d5f3590912c`, 새 사본 `/home/pdypd/mujungi-u10-final-verify`다. 배포·U10 main 병합·U12 실제 소비·실기기는 범위 밖이다. 실행이 끝나기 전 성공으로 보고하지 않는다.

## Test inventory

빌드, 단위/통합/계약/아키텍처, 모듈·U10 범위 커버리지, frontend·tools, Compose 끝-끝 및 역할/로그/비밀/작업 트리 단언, 인프라 검사, PR CI를 다룬다. 성능·보안 지침도 기존 요구가 있으므로 작성했다. 실제 공급자 측정은 자동 대역과 분리한다.

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-BUILD | Testing Contract / 빌드, NFR2 | JAR·frontend 빌드 및 전체 명령 성공 | Pending | Pending | Build and Test | Pending |
| T-REGRESSION | NFR2 / 기존 전체 회귀 | 기존 단위·통합·계약·ArchUnit 성공, 원 단언 유지 | Pending | Pending | Build and Test | Pending |
| T-U10 | Testing Contract / Standard | u10 시험 0건 아님, 핵심 경계와 실패 대조 성공 | Pending | Pending | Build and Test | Pending |
| T-MODULE-COV | team Testing Posture | Core·RT·계약·frontend 모듈 줄80% 이상 | Pending | Pending | Build and Test | Pending |
| T-U10-COV | NFR2 | 실제 운영27파일 범위 줄80%, 누락/빈 범위 실패 | Pending | Pending | Build and Test | Pending |
| T-DEADLINE | NFR1, FR2.3 | 최초15초 마감을 첫 전달까지 유지, 늦은 성공0 | Pending | Pending | Build and Test | Pending |
| T-GRAPH-BUDGET | FR5.2, NFR1 | 그래프 합계2초·장애 기본 경로·진실한 graphContextUsed | Pending | Pending | Build and Test | Pending |
| T-RETRY | NFR1, FR2.2 | 기술1·내용1·최대6호출을 최초 잔여 안에서만 수행 | Pending | Pending | Build and Test | Pending |
| T-COMPAT | FR3·FR4, NFR4 | V10 더하기만·과거 자료/쓰기·기존 C8/C10 필수 값 유지 | Pending | Pending | Build and Test | Pending |
| T-PRIVACY | FR7, NFR3·NFR6 | 공급자 최소 입력·권한/동의/삭제 재확인·민감 로그0 | Pending | Pending | Build and Test | Pending |
| T-E2E | team Walking Skeleton | 전체 verify.sh의 A/B/C/P1/P2/P3/T 성공 | Pending | Pending | Build and Test | Pending |
| T-INFRA | R-M2~5, team Testing Posture | Compose·마이그레이션·프로필·이미지/관문 검사 성공 | Pending | Pending | Build and Test | Pending |
| T-CI | 지시서 / 완료 조건, R-M1 | 최종 PR 커밋의 필수 ci-gate 성공 | Pending | Pending | Build and Test | Pending |
| T-EVIDENCE | NFR5 | 자동 대역/실호출/실기기 증거를 분리, 미측정 공개 | Pending | Pending | Build and Test | Pending |
| T-LIVE-LATENCY | 원 FR2.5, 지시서 실호출 관측 | 준비 확인 후 합성 실호출15초 관측 | Pending | Pending | 별도 준비 확인 필요 / 예정된 후속 검증 단계 없음 | Pending |

## Known limitations

사용자는 코드 생성 완료 때 R-05(RT 간헐 실패 위험)와 R-06(별도 준비 READY의 마감 검사 누락)을 위험으로 수용했다. 수용 사실과 목표 충족 여부는 별개로 남긴다. R-06이나 실호출 미측정을 합격으로 바꾸지 않으며 최종 목표 표에서 결과와 잔여 위험을 공개한다.
