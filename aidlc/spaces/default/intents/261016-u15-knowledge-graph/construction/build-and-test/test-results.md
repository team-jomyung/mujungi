# U15 실행 결과

## 최종 실행

2026-10-16T13:09:33Z 시작, 소스 3cee1bbfe4a4, 동일 커밋의 WSL ext4 체크아웃에서 build-instructions.md의 전체 명령 실행. 종료 코드 0, 519초. A1 327초, A2 66초, A3 10초, A4 1초, F 1초, B 90초, C 19초, P1 2초, P2 1초, P3/T 각 0초다.

백엔드 2,987건 실패 0/지원 환경 분기 건너뜀 3, 프런트 986건 실패 0, 도구 125건 실패 0. U15 포함분 100건은 실패·오류·건너뜀 0이다. U15 범위 관문 918/962줄(95.43%) 통과. 모듈 전체 80% 관문과 frontend 98.55% 관문 통과. 단위 필터 재실행은 같은 운영 소스를 사용하며 원본 전체 실행 결과와 별도로 기록한다.

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

## 이전 실패와 보완

5945323 전체 실행은 V8 이력 기대값과 realtime 시간 초과 1건으로 실패했다. realtime 해당 시험은 같은 소스의 단독 재실행 통과 후 후속 모든 전체 실행에서도 통과했고 realtime 코드/시한은 바꾸지 않았다. 273e774 실행은 B1의 기존 NEO4J_AUTH 전달 이름 누락, 5908564 실행은 수동 스택과 고정 대역 충돌로 중단됐다. 5908564·3cee1bb에서 검증 설정을 보완했고 기본 Compose config/invariants/secret-env-files 검사와 최종 전체 실행이 통과했다. 이 단계의 코드 재생성 loop-back은 없으며 원래 코드 검토 수정 이력은 code-generation/reviews/에 있다.

## 증거와 한계

로컬 증거: /home/pdypd/mujungi-u15-verify/build/verify/report.txt 및 logs/, backend/*/build/test-results/test/, scope JaCoCo. 전체 실행 뒤 scope 관문·단위 필터를 실행하면 일부 XML/JaCoCo 파일은 최신 자료로 바뀌므로 전체 건수는 A1 로그와 원본 report를 함께 본다. 실제 AI/실제 기기는 실행하지 않았고 gitleaks 전체 이력 검사는 CI에 남는다.
