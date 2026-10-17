# U14 전체 검증 요약

## 입력과 검증 대상

코드 d5d4d4f, code-generation-plan.md의 Testing Contract, unit-test-instructions.md, code-summary.md와 이 기록 requirements.md의 모든 FR/NFR를 사용한다. 별도 nfr-requirements/nfr-design 산출물은 없다. 시험 전략 Standard, 기존 모듈 줄80%와 U14 범위 줄80%를 유지한다. 구현 후 층별 시험 순서는 코드 생성 증거에 보존돼 있다.

## Target Verification Matrix

Source의 requirements는 inception/requirements-analysis/requirements.md, Testing Contract는 construction/code-generation/code-generation-plan.md다. 모든 확인의 Owning Stage는 Build and Test이며 CI는 같은 단계의 외부 증거다. 아래 Actual/Evidence/Verdict는 실제 로컬 실행과 PR27의 CI 결과다.

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| NFR1-u14 | requirements NFR1 | U14 시험 실패0, 범위 줄≥80% | 161개/19클래스 실패·오류·skip0, 837/926줄=90.39% | verification/u14-final-results.json | Build and Test | Met |
| NFR1-backend | Testing Contract | core/RT/contract 모듈 줄≥80%, 전체 회귀 실패0 | 3149개 실패0/LOCAL3skip, core96.11%·RT93.34%·contract98.45% | verification/backend-full-results.json | Build and Test | Met |
| NFR1-frontend | Testing Contract | frontend src 줄≥80%, lint/build/시험 성공 | 전체994개/74파일,98.55%, eslint/Next build 성공 | verification/compose-results.json A2 | Build and Test | Met |
| NFR1-e2e | requirements NFR1 | verify.sh compose 전체 PASS | rc0/747초, A/B/C/P1/P2/P3/T 모두 PASS | verification/compose-results.json | Build and Test | Met |
| NFR1-ci | requirements NFR1 | PR ci-gate 성공 | PR27,1dbcd7c,CI38988761751 attempt2 ci-gate SUCCESS; 실행 작업 모두 SUCCESS | verification/github-ci-results.json | Build and Test | Met |
| NFR2 | requirements NFR2 | 가짜 AI, 비밀/민감자료 유출0 | 가짜 공급자 확인, A4/P2/P3 PASS, 저장 UUID/uncertain capture 시험 PASS | compose-results.json 및 U14 태그 | Build and Test | Met |
| NFR3 | requirements NFR3 | 소유권·철회·삭제/늦은 응답 회귀 성공 | PG 선택·삭제·빈 재생·철회/late callback 시험 포함161개 PASS | u14-final-results.json 및 traceability | Build and Test | Met |
| NFR4 | requirements NFR4 | V1~8 불변, V9 호환9시험·승인8해시·검사 성공 | V9 시험9개 포함, 기존 migration 변경0, 검사 rc0 | u14-final-results.json, migration-review.md, 승인 목록 | Build and Test | Met |
| NFR5 | requirements NFR5 | 기존 시간 소유권/대조·RT/27 경계 유지 | U14TimingContractTest 및 전체 계약 대조 PASS, 비소유 설정 복제 없음 | 태그·전체 backend, implementation-notes.md | Build and Test | Met |
| FR3.4-retry | requirements FR3.4 | 총4회, 지연5/15/30초, 내부 재시도0 | U14ResultRetryIT/TimingContractTest PASS | 태그161개와 전체3149개 | Build and Test | Met |
| TC-volume | Testing Contract | 컴포넌트당5~8 기본 및 핵심 통합, 오류/경계 포함 | 19클래스161개, 책임별 단위·핵심 PG 경계/오류 사례 및 독립 검토 확인 | code-summary.md, u14-final-results.json | Build and Test | Met |

## 준비와 제한

전용 ext4 복사본과 Docker 환경의 로컬 빌드/시험 및 PR CI는 PASS, 적용 목표11행 모두 Met다. 최초 CI U5b 작업은 시험 전 Kotlin 의존성 다운로드 실패였고 동일 커밋의 해당 작업 재실행으로 통과했다. secrets의 이력 검사도 SUCCESS다. PR 규칙상 skeleton/deploy는 SKIPPED이며 skeleton은 로컬 전체 compose로 보완했다. 배포/병합은 요청 범위가 아니다. U15는 PR26 미병합이며 선행 병합이 필요하다. U14-a/b는 생산부와 파서·스키마 동시 배포가 필요하다. 실AI/실기기 품질 및 U12 생산부 연동 성공을 주장하지 않는다.
