# U15 통합 시험 지침

## 경계와 실행

상위 입력은 code-generation-plan.md, unit-test-instructions.md, code-summary.md다. 전체 실행과 U15 필터 명령은 build-instructions.md의 명령을 공유하므로 중복 집계하지 않는다. 실제 PostgreSQL/Neo4j Testcontainers와 메모리 대역, 가짜 Luna를 사용한다. 격리된 합성 UUID·시계를 쓰며 원본 ZIP은 읽기 전용이다.

핵심 경계는 현재 동의/아동 소유 확인, 초기화와 판 충돌, 원격 명시적 트랜잭션 종료 확인, 그래프 성공/DB 실패 회수, 동시 예약, 동일 후보 재전달, 기록 삭제 실패 후 목록/AI/재구성 숨김, 삭제 중 늦은 생성 차단, 등록 성공 후 초기화 장애 격리, 꺼짐 및 키 없음이다. 기존 U2/U5a/U6/U9를 포함한 전체 회귀는 A1에서 실행한다.

## 합격 기준과 증거

U15 100건, 실패/오류/건너뜀 0. 전체 백엔드 2,987건 실패 0이며 local/Docker 양쪽을 나열하는 지원 시험의 비선택 환경 3건만 건너뜀이다. C1~C12는 등록·안정·시작·가짜 음성 한 턴·금지 출력 차단·종료를 통과했다. 각 증거는 test-results.md와 docs/units/u15/report.md에 연결한다. 실 모델·실제 기기의 성공을 뜻하지 않는다.
