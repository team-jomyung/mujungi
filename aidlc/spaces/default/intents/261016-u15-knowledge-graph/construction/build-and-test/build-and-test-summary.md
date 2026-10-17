# U15 빌드·시험 요약

## 인벤토리와 준비 상태

이번 기록의 per-unit 산출물은 u15-knowledge-graph 하나뿐이다. NFR Requirements/NFR Design 단계는 실행 계획에서 건너뛰었으므로 요구사항 NFR1~6, 기능 설계와 코드 생성 Testing Contract를 품질 목표 정본으로 삼았다. Standard 전략의 빌드·통합 지침과 실제 시간/보안 경계 지침을 작성했다.

실행 소스 3cee1bbfe4a4, 전체 종료 코드 0, 519초. U15 100건, 전체 backend 2,987건(실패 0, 지원 환경 분기 건너뜀 3), frontend 986건, tools 125건. 전체 실행 자료의 U15 줄 95.43%, 프런트 줄 98.55%, 모듈 전체 80% 관문 통과다. 빌드·시험 준비 완료이며 배포·실 AI·실제 기기 검증 완료 주장은 하지 않는다.

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| NFR1 | requirements.md NFR1; 기능 설계 FR6 | 전체 조회 예산 2초, 늦은 읽기 쓰기 없음 | 제한·취소 회귀 통과 | U15ServiceIT, U15ConfigurationTest | Build and Test | Met |
| NFR2 | requirements.md NFR2 | 아동 격리·파라미터 질의·비밀 원문 미노출 | 접근/Neo4j 시험 및 A4/P1/P2/P3 통과 | U15AccessPolicyTest, U15Neo4jIT, 전체 report.txt | Build and Test | Met |
| NFR3 | requirements.md NFR3 | 반복·경합·부분 실패 복구 | DB/Neo4j 실제 통합과 삭제/예약 회귀 통과 | U15LedgerIT, U15ServiceIT, U15Neo4jIT | Build and Test | Met |
| NFR4.unit | requirements.md NFR4; Testing Contract | U15 전부 통과, 줄 80% 이상 | 100건 실패/오류/건너뜀 0, 918/962줄 95.43% | 전체 시험 XML, scope JaCoCo report/verification | Build and Test | Met |
| NFR4.module | Testing Contract / team Testing Posture | 기존 회귀·ArchUnit·계약과 모듈 줄 80% | 백엔드 2,987건 실패 0, 3건 환경 분기 건너뜀; core 95.90%, realtime 93.27%, contract 98.52% | logs/A1.log, 모듈 JaCoCo | Build and Test | Met |
| NFR4.frontend | Testing Contract / team Testing Posture | eslint·전체 시험·줄 80%·빌드 | 986건, 줄 98.55%, 모두 통과 | logs/A2.log | Build and Test | Met |
| NFR4.tools | Testing Contract / team Testing Posture | 기존 도구 시험 통과 | 125건 통과 | logs/A3.log | Build and Test | Met |
| NFR4.e2e | requirements.md NFR4 | 전체 verify.sh 종료 코드 0 | A/B/C/P/T 모두 통과, 519초 | build/verify/report.txt, revision 3cee1bbfe4a4 | Build and Test | Met |
| NFR5 | requirements.md NFR5 | 대역/실호출 증거 구분, 자동 시험 실 AI 호출 0 | 가짜 AI·메모리·실제 Testcontainers·합성 기기 증거 구분 | docs/units/u15/report.md | Build and Test | Met |
| NFR6 | requirements.md NFR6 | 네 구현 묶음 순서 커밋, 설계/보고/추적 | 49e7a4a → a09490f → 7fcaee1 → 5945323; 설계·구현·보고·추적 파일 존재 | git log, docs/units/u15/, code-generation/traceability.json | Build and Test | Met |

## 증거의 범위와 인계

원본 로컬 결과는 Linux 체크아웃 build/verify/report.txt·logs와 backend 모듈의 JUnit/JaCoCo다. 최종 수치와 이전 실패 이력은 docs/units/u15/report.md에 보존했다. 원격 CI와 전체 이력 gitleaks는 push 뒤 별도로 확인한다. 기존 수동 스택은 변경하지 않았다. 실제 모델의 개인정보 탐지 품질은 이 작업의 자동 대역 시험으로 입증하지 않는다.
