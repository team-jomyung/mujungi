# 시간·현재성 검증

## 적용 기준

새 부하/동접/SLA 수치를 추가하지 않는다. U16 기존 참여20초·재안내20초·공백4/6분·재접속40초·확인30초·모호종료20초·승인재생20초, HTTP500ms/1초·판정4초·JEV800ms와 기존 제외/경합 의미를 보존한다. U17 기존2초 폴링·retry:false·공유 요청·취소/세대와 추가호출0을 검증한다.

## 실행과 증거

build-instructions.md의 U16 ServiceGuardrail 및 기능 coverage, U17 live-updates/기기/화면 및 관련 회귀 명령을 사용한다. 같은 명령은 중복 실행하지 않는다. 주입 시계 직전/정확한 경계/직후와 지연 대역 응답을 관측한다. integration-test-instructions.md의 전체 verify.sh를 별도 실행한다. 시험 성공을 운영 처리량/실기기 지연으로 보고하지 않는다.

