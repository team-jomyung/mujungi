# U17 표시·소비 규칙

## Sources

- functional-spec.md F1–F5 및 상위 C1–C3/RP-1–RP-4를 추적한다.
- UI 단위의 필수 산출물은 상호작용 명세/컴포넌트/추적이다. 이 보조 파일은 traceability의 규칙 ID 대조를 위해 표시·소비 규칙만 정본으로 열거한다. 서버 업무 규칙이나 새 데이터 엔티티를 정의하지 않는다.

## Rules

```yaml
rules:
  - id: BR17.1
    statement: "선택 사실의 정규화와 현재 응답 귀속"
    category: validation
    applies_to: "진행 응답/세션 캐시"
    trigger: "진행 응답 수신"
    logic: "IF 같은 세션의 현재 요청 응답이고 상태판이 역행하지 않으면 THEN 같은 판도 최신 facts로 교체한다. 구조/판 실패는 null, 미래 문자열 enum은 UNKNOWN이며 이전 facts로 채우지 않는다."
    violation_behaviour: "잘못된 세션/지난 응답은 적용하지 않는다. 선택 객체 실패는 해당 객체만 null."
    source: "FR4.3, NFR2, NFR6"
  - id: BR17.2
    statement: "브라우저는 안내나 참여를 승인하지 않는다"
    category: authorization
    applies_to: "자유대화 대기/진행 표시"
    trigger: "준비·참여 응답"
    logic: "IF 서버 ACTIVE가 아니면 THEN 화면·말풍선·보호자 동의·조기 발화만으로 ACTIVE/일반 대화/원음 시작을 만들지 않는다."
    violation_behaviour: "기존 준비/대기를 유지하고 새 시작 요청을 보내지 않는다."
    source: "FR2.2–FR2.5, FR6.3"
  - id: BR17.3
    statement: "안내 완료는 현재 실제 완료만 소비한다"
    category: constraint
    applies_to: "안내 표시/기기 재생"
    trigger: "승인 콘텐츠/재생 사건"
    logic: "IF 현재 자원의 실제 재생이 끝났으면 THEN 현재 세션/재생 식별로 완료를 보고한다. 전송·ACK·프리패치·표시는 완료가 아니다."
    violation_behaviour: "실패/중단/지난 확인을 완료로 대체하지 않고 기존 실패/중단 경로를 따른다."
    source: "FR2.1–FR2.5"
  - id: BR17.4
    statement: "미참여 사유는 확인된 값만 표시한다"
    category: policy
    applies_to: "P-03와 이동"
    trigger: "서버 DECLINED/FAILED"
    logic: "IF DECLINED면 THEN 같은 응답의 REFUSED/NO_RESPONSE를 서로 다른 제목으로 표시하고 그 외에는 일반 미참여 문구를 사용한다. 기술 실패는 기존 실패 경로를 따른다."
    violation_behaviour: "무응답/거절/정상 활동을 추정하지 않는다. 확인 뒤 기존 홈으로 이동한다."
    source: "FR2.4, FR5.1"
  - id: BR17.5
    statement: "확정된 공개 종료 행만 인용한다"
    category: validation
    applies_to: "CHILD_STOP 설명"
    trigger: "종료 사유 표시"
    logic: "IF 같은 세션의 참조 seq에 가시적 CHILD 최종·확실·비어 있지 않은 행이 있으면 THEN 그 기존 text만 안전한 텍스트로 인용한다."
    violation_behaviour: "기본 등록 표현/마지막 행/불확실/숨김 행 대체 및 추가 조회 없이 인용을 생략한다."
    source: "FR4.1, FR5.1, NFR1"
  - id: BR17.6
    statement: "현재 실제 인사 재생만 재생 중으로 표시한다"
    category: constraint
    applies_to: "S-17 인사 줄"
    trigger: "진행 응답/신뢰 변화"
    logic: "IF FREE_TALK의 최신 유효 CLOSING이고 goodbye PLAYING이며 stale/오프라인/확인된 장애가 없으면 THEN 인사 줄을 보인다. 일반 마무리 제목과 별개다."
    violation_behaviour: "null/다른 인사 상태/terminal/조회 오류/단절/세션 변경은 인사 줄을 숨긴다."
    source: "FR4.1, FR4.3, NFR2"
  - id: BR17.7
    statement: "요청·종료 의도·기기 정지는 구별한다"
    category: policy
    applies_to: "보호자 끝내기/종료 화면"
    trigger: "종료 요청/종료 조회"
    logic: "IF 보호자 끝내기면 THEN 기존 P-01/같은 requestId 재시도/실패 처리를 유지한다. 아이 서버 확정 종료에는 추가 확인 UI를 만들지 않는다. 기기 confirmed 전에 정지 완료를 표시하지 않는다."
    violation_behaviour: "로딩/오류/무응답을 종료 동의나 기기 완료로 바꾸지 않는다."
    source: "FR4.1–FR4.3, FR6.1–FR6.2"
  - id: BR17.8
    statement: "중단된 재생/연결의 늦은 사건은 현재 완료가 아니다"
    category: constraint
    applies_to: "기기 세션/음성 경로"
    trigger: "중단·실패·끊김·종료 콜백"
    logic: "IF 현재 재생/연결이 중단됐으면 THEN 늦은 완료 콜백은 COMPLETED나 현재 세션 재활성화를 만들지 않는다. 현재 종료 정리 뒤에만 기존 VOICE_SESSION_CLOSED를 보낸다."
    violation_behaviour: "기존 STOPPED/FAILED·중복 제거·현재 식별 경계를 유지한다."
    source: "FR4.3, FR7.2, NFR2"
  - id: BR17.9
    statement: "서비스 설명과 화면 원문은 기존 사실/권한을 지킨다"
    category: policy
    applies_to: "copy/전사/서비스 상태"
    trigger: "서비스 사실 표시"
    logic: "IF UI에 서비스 결과/텍스트를 표시하면 THEN 확인된 기존 기능/권한만 말하고 삭제 접수와 완료·기기 정지와 결과 정리를 구분하며 원문을 안전한 텍스트로 표시한다."
    violation_behaviour: "새 삭제/기억/진단/공유 보장·민감 로그·원문 복제·HTML 실행을 추가하지 않는다."
    source: "FR5.1–FR5.3, FR6.3, NFR1, NFR5"
  - id: BR17.10
    statement: "연습·접근성·기존 시간/호출 경계를 보존한다"
    category: constraint
    applies_to: "기존 화면/기기 소비 전반"
    trigger: "모드 전환/화면 갱신"
    logic: "IF 연습/미확인 모드면 THEN 자유대화 사실을 표시하지 않고 기존 연습 표시를 유지한다. 모든 변경 표면의 기존 키보드/초점/텍스트 상태/확대/조작 크기와 시간값·호출 수를 보존한다."
    violation_behaviour: "새 모델/호출/재시도/클라이언트 권한을 추가하거나 기존 품질 하한을 낮추지 않는다."
    source: "FR6, FR7.1–FR7.3, NFR3, NFR6"
```

## 규칙 요약

| ID | 표시/소비 결과 |
|---|---|
| BR17.1 | 선택 사실의 정규화와 현재 응답 귀속 |
| BR17.2 | 브라우저는 안내나 참여를 승인하지 않는다 |
| BR17.3 | 안내 완료는 현재 실제 완료만 소비한다 |
| BR17.4 | 미참여 사유는 확인된 값만 표시한다 |
| BR17.5 | 확정된 공개 종료 행만 인용한다 |
| BR17.6 | 현재 실제 인사 재생만 재생 중으로 표시한다 |
| BR17.7 | 요청·종료 의도·기기 정지는 구별한다 |
| BR17.8 | 중단된 재생/연결의 늦은 사건은 현재 완료가 아니다 |
| BR17.9 | 서비스 설명과 화면 원문은 기존 사실/권한을 지킨다 |
| BR17.10 | 연습·접근성·기존 시간/호출 경계를 보존한다 |

기능 흐름과 순서는 functional-spec.md가 정한다. 이 규칙을 통과한 UI 대역만으로 서버 참여 권한/모델 정책/실제 기기 성공을 인증하지 않는다.
