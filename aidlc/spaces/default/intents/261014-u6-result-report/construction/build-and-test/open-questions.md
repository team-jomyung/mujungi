# 빌드·시험 후속 확인

## Open questions

- 사람이 선택한 분류: `Open question`(2026-10-14). 건너뛴 시험을 상세 기록하고 추후 재시험할 수 있도록 한다. 호스트 PostgreSQL16 환경이 준비되면 MujoongPostgresTest의 LOCAL3개를 강제 재실행하여 실제 통과 여부를 확인한다. 이는 완료되지 않은 후속 확인이며 영구 작업 규칙으로 설치하지 않는다.

## 재확인 절차와 완료 기준

2026-10-14 사용자 설치 후 LOCAL18.6 재검증 완료: 기존LOCAL3개 모두통과, 클래스전체6개통과·skip0. 후속전체check2405개통과·skip0. 증거는 evidence/skipped-retest-postgresql18.json·backend-check-postgresql18.json이다. 프로젝트 기준LOCAL16 재검증만 후속확인으로 남는다. 이전 질문·건너뜀 이력은 유지한다.

[skipped-tests.md](skipped-tests.md)에 메서드·건너뜀 사유·준비 조건·재실행 명령·증거와 완료 기준을 기록했다. LOCAL3개가 실제로 실행되어 실패0·건너뜀0인지 확인하고 날짜·환경·결과를 갱신한다. Docker와 LOCAL을 함께 준비한 전체 클래스 실행은6개 통과가 기준이다. 이전 건너뜀 이력은 유지한다.
