# 신뢰성 요구사항 — U1 u1-walking-skeleton

> 단계: Construction · NFR Requirements(3.2) · U1 u1-walking-skeleton · 깊이 Standard · 문서 reliability-requirements.md
> 입력: `functional-design/functional-spec.md` · `rules.md` · `entities.md`(규칙 BR1.1 ~ BR9.7), `requirements.md`(NFR4 · NFR6), `contract-summary.md`(5 · 6 · 7절), ADR-006 · ADR-010, 질문 파일 `nfr-requirements-questions.md`(Q1 · Q2 · Q5 · Q9)
> 날짜: 2026-10-09
> 대리 판단: 질문 파일의 답 Q1 ~ Q12는 팀장(준하)의 대리 판단(가정) J61 ~ J72다. 이 문서는 J61 · J62 · J65 · J67 · J69를 쓴다. 3.2 재점검에서 정한 결정은 질문 번호를 잇지 않고 5절에 '3.2 재점검 결정 RD-n'으로 적었다(이 문서의 몫은 RD-1 · RD-2 · RD-7 · RD-8 · RD-14). 수치는 시연 규모의 시작값이며 11/2 측정(OQ10) 뒤 바뀌면 해당 NFRx.y 행만 고친다.

## 1. 요구사항

| ID | 우선순위 | 요구 | 측정 · 완료 기준 | 근거 |
|---|---|---|---|---|
| NFR4.1 | Must | 기기 연결 장애: 소켓이 닫기 프레임 없이 끊기거나 열린 채 무음이면 마지막 기기 메시지 뒤 40초에 연결 상태가 DISCONNECTED가 되고, 진행 중 ACTIVE 세션은 ABORTED(DISCONNECT)로 정리되며 보호자 04는 끊김을 보인다. | 연결 단절 주입 시험(40초 시계 주입, 직전 · 직후 경계) | NFR4 · BR4.8 · BR6.12 ④ |
| NFR4.2 | Must | 음성 모델 장애: 공급자 연결 실패 · session.started 미수신은 FAILED(PROVIDER_UNAVAILABLE)로 끝나 계정이 잠기지 않고, 기기에 CLOSE_TRANSPORT가 나간다. ACTIVE 뒤 공급자 끊김 감지는 U1 범위 밖이다(소유 U3 · U4). | 공급자 장애 주입 시험(연결 거부 · 무응답): FAILED 1회, 다음 시작이 SESSION_IN_PROGRESS 없이 가능, CLOSE_TRANSPORT(FAILED) 송신 | NFR4 · BR6.8 · BR7.2 · RD-7 |
| NFR4.3 | Must | 보호자 서버 상태 미수신: 기기 상태 보고가 30초 넘게 없으면 04 · 05는 '확인 불가', 12는 관측 시각이 30초를 넘으면 stale로 표시된다. | 보고 중단 주입 시험(30초 경계, 조회 한 번 실패만으로는 stale 아님) | NFR4 · BR4.3 · BR6.15 |
| NFR4.4 | Must | 종료 확인 미수신: 끝내기 뒤 30초 안에 기기 확인이 없으면 ENDED_UNCONFIRMED로 정리되고, 기기 확인 없는 '종료 완료' 표시는 없다. | VOICE_SESSION_CLOSED 지연 주입 시험(30초 경계, 늦은 확인이 전이를 되돌리지 못함) | NFR4 · BR6.12 ③ |
| NFR4.5 | Must | 중복 요청: 시작 요청은 idempotencyKey로, 이벤트는 event_id · 해시로 멱등이다. 같은 요청 · 이벤트를 반복해도 새 세션 · 중복 반영이 없다. | 같은 키 · 같은 event_id 반복 시험(새 세션 0, 처리기 재호출 0, 다른 본문은 409) | NFR4 · BR6.2 · BR5.5 |
| NFR4.6 | Must | 복구 이벤트 역전: 세션 이벤트는 statusVersion, 기기 이벤트는 시각으로 최신만 반영하고 뒤바뀌어 와도 최종 값이 같다. 과거 응답이 재생되거나 종료 요청 중 · 확인된 활동이 자동 재개되거나 다른 세션이 종료되는 일이 없다. | 이벤트 순서 뒤바꿈 시험, 과거 응답 재생 0 | NFR4 · BR5.10 · BR6.8 |
| NFR4.7 | Must | 앱 재시작 복구: Realtime 재시작 시 비종료 세션은 FAILED(SERVER_RESTART)로 닫히고 CONNECTED 연결 행은 DISCONNECTED가 된다. 양 앱은 재시작 뒤 끝나지 않은 Outbox/Inbox를 이어 처리한다. 재시도를 다 써서 FAILED가 된 제어 이벤트(DeviceReleased · DeletionRequested · DeletionCompleted · SessionRecordCaptured)는 앱 시작 때 PENDING으로 되돌려 반드시 전달한다(FD 보강 후보 A1). 일반 이벤트의 20회 상한(BR5.3)은 유지한다. | 재시작 시험(프로세스 강제 종료 뒤 상태 점검). Realtime을 10분 정지했다가 기동하면 DeviceReleased가 전달되어 옛 소켓이 4401로 닫힌다(절차는 4절, 한계는 5절 사람이 정할 것 4) | NFR4 · BR5.12 · BR6.13 · BR5.3 · A1 · RD-1 · RD-7 |
| NFR4.8 | Should | 가용성: 호스트 밖 외부 프로브(팀 PC의 스케줄 작업 또는 무료 외부 pinger, 선택은 3.4)가 1분마다 Core health · /device 정적 페이지 · WSS 핸드셰이크 3종을 호출하고 하나라도 실패하면 그 분을 실패로 센다. 가용성 99.0% 이상(허용 중단 하루 14.4분, 7일 약 100.8분)을 목표로 하고, 계획된 배포는 배포 스크립트가 시작 · 끝 시각을 파일에 남긴 구간만 제외한다. 11/2 시점에는 확보한 일수(최소 4일)의 누적 가용성을 보고하고 7일 롤링은 동결 뒤로 이월한다. | 프로브 결과 파일과 배포 시각 파일로 일일 누적 가용성 집계(성공한 분 ÷ 배포 제외 뒤 전체 분) | NFR4 · J61 · RD-8 |
| NFR4.9 | Should | 자동 복구: 컨테이너가 죽으면 Docker Compose 재시작 정책(restart: unless-stopped)으로 5분 이내(목표 1분) 다시 올라온다. Compose depends_on service_healthy는 기동 순서와 기동 직후 트래픽 차단만 보장하고, 실행 중 readiness 실패는 앱이 503을 직접 답한다(NFR4.13). | 프로세스 강제 종료 후 복구 시간 측정 | NFR4 · J61 · RD-8 |
| NFR4.10 | Should | 배포 중단: 계획된 배포의 서비스 중단은 60초 이내이며 WSS 기기 웹은 BR9.7 재접속으로 스스로 복구한다. 진행 중 세션은 재시작 규칙(NFR4.7)대로 FAILED로 끝나도 된다. | 배포 리허설 중단 시간 측정 | NFR4 · J61 |
| NFR4.11 | Should | 데이터 내구성: PostgreSQL 전체를 매일 한 번(03:00 KST) 논리 백업하고 7일 보관한다. RPO 24시간(호스트 상실 제외: 백업이 같은 호스트에 있어 디스크 · 인스턴스 상실은 이 목표 밖이며 오프호스트 복사는 5절 사람 결정), RTO 60분, 복원 절차를 문서화하고 한 번 리허설한다. 시연 데이터는 합성 데이터에 한한다(NFR12.4). | 백업 파일 생성 로그, 복원 리허설 기록 | NFR4 · J62 · RD-8 |
| NFR4.12 | Must | 격리: 한 앱의 장애가 다른 앱의 기본 기능을 막지 않는다. Core는 Realtime 호출 실패 시 새 세션 시작과 끝내기 요청만 503으로 거절하고(정지 30초 뒤의 시작은 BR4.3 ③으로 423 DEVICE_NOT_STABLE), 나머지 보호자 화면은 동작한다(04 · 05는 30초 뒤 '확인 불가', 12 조회는 사본으로 응답). 내부 HTTP는 제한 시간 · 재시도 규칙(J26)을 지킨다. | Realtime 정지 시점별 시험: 정지 직후 30초 이내 시작 요청은 503, 30초 초과 뒤 시작 요청은 423 DEVICE_NOT_STABLE, 끝내기는 어느 시점이든 503, 04 · 05는 30초 뒤 '확인 불가', 12 조회는 사본으로 답하며 items 비움 | NFR4 · BR4.3 · BR6.7 · BR6.9 · BR6.15 · J26 · RD-8 |
| NFR4.13 | Should | 헬스체크: 앱마다 liveness와 readiness(DB 연결 · 마이그레이션 완료)를 가진다. 준비 전 응답은 502 또는 503(3.4 확정)이다: 앱이 접속을 받지 못하면 Nginx가 502, 앱이 떠 있고 readiness가 실패하면 앱이 직접 503을 답한다(Nginx 502는 업스트림 접속 불가일 때만). Compose는 depends_on service_healthy로 DB → 앱 → Nginx 순서를 지킨다(기동 순서와 기동 직후 트래픽 차단만 보장). | 헬스 엔드포인트 시험, 기동 순서 시험, 실행 중 DB 정지 시 앱의 503 응답 확인 | NFR4 · J61 · RD-8 · RD-14 |
| NFR4.16 | Must | 앱만 오프라인: 보호자 폰의 끝내기 요청이 네트워크 오류 · 시간 초과로 전달되지 못해도 U1은 종료 요청됨으로 표시하지 않고 세션은 ACTIVE로 유지되며 기기 대화는 계속된다(BR6.9). 12 재조회 이어붙임은 U4 몫이다. | 폰 네트워크 차단 주입 시험: 끝내기 오류 문구, 종료 요청됨 표시 0, 서버 세션 ACTIVE 유지, 기기 대화 계속 | NFR4 · BR6.9 · RD-7 |
| NFR6.1 | Must | 모든 시한 · 주기는 설정 키에서 읽고 코드에 숫자를 박지 않는다. 계약 7절에 키가 있는 값은 그 값이 단일 원천이고, 없는 값은 3.2절 mujoong.* 키 레지스트리가 이름의 단일 원천이며 NFR Design(3.3)이 확정해 timing.md에 더한다. | 코드 검색: 시간 숫자 상수 0건(허용 목록 제외), 코드가 읽는 설정 키 목록이 레지스트리 · timing.md와 일치 | NFR6 · BR8.11 · RD-2 |
| NFR6.2 | Must | U1의 네 안전 시한(준비 10초 · 기기 응답 20초 · 종료 확인 30초 · 끊김 40초)과 기기 보고 30초 stale은 시계를 줄여 주입하는 시험으로 경계값(직전 · 직후)을 확인한다. | 시한별 경계 주입 시험 통과(실제 시간을 기다리지 않는다) | NFR6 · BR6.12 |
| NFR6.3 | Must | 시한이 발동하면 세션은 정해진 종료 상태로 한 번만 전이하고(조건부 UPDATE 승자 하나), 늦게 온 반대 신호는 전이를 되돌리지 못한다. | 시한 vs ACK 경합 시험 | NFR6 · BR6.8 |
| NFR6.4 | Should | 시한 시계는 서버 단조 시계(System.nanoTime류)가 아니라 주입 가능한 Clock 추상화로 읽어 시험이 시간을 제어한다. DB 비교 시각은 DB 시계를 쓰지 않고 앱 Clock으로 통일한다. | Clock 주입 시험, Clock 빈 사용 검사 | NFR6 · J69 · BR4.8 · RD-14 |

## 2. 배경과 해석

### 2.1 요구별 U1 해석

| ID | U1에서의 뜻 | 걸리는 BR · 화면 · 컴포넌트 | U1 밖 몫 |
|---|---|---|---|
| NFR4.1 | 끊김 판정은 따로 도는 두 시계다: 기기 연결 상태는 영속 last_seen_at 주기 점검, ACTIVE 세션은 Realtime 메모리의 세션별 시계. 값은 같은 40초다. 「끄기」(1000)는 연결만 OFFLINE이고 세션은 같은 40초 경로를 탄다 | BR4.7 · BR4.8 · BR6.12 ④ · SM2 · 04 · DeviceGateway | UNSTABLE 판정 · 점선 10초 · P2 팝업 · HELLO resume은 U3 · U4 |
| NFR4.2 | U1이 닫는 것은 시작 구간(PREPARING · DEVICE_ACK)의 공급자 실패뿐이다. ACTIVE 뒤 공급자 끊김 감지는 BR6.8에 없고 기기가 살아 있으면 세션은 보호자 끝내기 전까지 ACTIVE로 남을 수 있다(5절 간극) | BR6.8 · BR6.12 ② · BR7.2 · VoiceRelay | ACTIVE 중 공급자 끊김 감지는 U3, 그 뒤 세션 전이와 공백 6분 SILENCE는 U4(소유 U3 · U4) |
| NFR4.3 | 판정은 읽을 때 계산하므로 별도 복구 동작이 없다. 세션 전이는 일으키지 않는다 | BR4.3 ③ · BR4.7 · BR6.15 · 04 · 05 · 12 | 05 화면 완성은 U2, 푸시 후보는 U7 |
| NFR4.4 | 30초 안에 마무리 인사(3초 이내 재생)와 기기 정리와 확인이 모두 들어간다. 이 시한이 끊김보다 우선한다 | BR6.10 · BR6.12 ③ · 17 · SM3 | 17의 '정리 중 · 정리 끝'은 U4 · U6 |
| NFR4.5 | U1은 시작 멱등과 6종 이벤트 중 SessionStateChanged · DeviceStatusChanged · DeviceReleased 경로의 틀을 가진다 | BR6.2 · BR6.3 · BR5.5 · BR5.6 · 08 | 나머지 이벤트 처리기는 소유 유닛 |
| NFR4.6 | 사본 갱신은 한 문장의 조건부 UPDATE이고 0행이면 버린다(로컬 FAILED 사본에 종료 아닌 이벤트가 오면 취소 재전송) | BR4.1 · BR5.10 · BR6.11 · BR6.14 | SessionRecordCaptured 순서는 U4 · U6 |
| NFR4.7 | Realtime은 세션을 이어 붙이지 않는다. Core는 REQUESTED로 남은 세션을 10초 시한이 닫는다. BR5.3은 약 8분(재시도 20회)이 지나면 행을 FAILED로 두고 되살리지 않으므로, 상대 앱이 그보다 오래 멈추면 DeviceReleased · DeletionRequested 같은 제어 이벤트가 영구히 사라진다. A1은 이 네 종류만 앱 시작 때 PENDING으로 되돌린다 | BR5.12 · BR6.13 · BR6.12 ① · BR3.8 · BR5.3 · BR5.14 | Core 쪽 ResultJob 재선점은 U6 |
| NFR4.8 | 단일 인스턴스 재시작에 기대는 시연 목표다. 프로브가 부르는 세 경로 중 /device와 WSS는 원래 공개이고 health는 한 경로만 외부에 열며 나머지 /actuator는 403이다. Realtime만 멈춰도 WSS 프로브가 잡는다 | NFR4.15 · BR8.9 · Nginx | 인프라 구성은 3.4, 운영 점검은 Operation |
| NFR4.9 | Compose 재시작은 프로세스를 올릴 뿐이고 정합은 앱이 재시작 규칙으로 맞춘다(ADR-006). service_healthy는 처음 기동할 때의 순서를 정하고, 실행 중 앱 하나만 죽으면 그동안 Nginx는 502를 돌려준다 | BR5.12 · BR6.13 · Docker Compose | 구성 세부는 3.4 |
| NFR4.10 | 배포 중 두 앱의 내부 토큰이 잠시 어긋나도 Outbox가 401을 재시도한다. 서버가 보내는 1001은 OFFLINE이 아니고 기기 웹이 5초 간격으로 다시 접속한다 | BR5.3 · BR9.7 · BR4.8 ② | 배포 파이프라인은 Operation |
| NFR4.11 | 백업은 같은 호스트 별도 볼륨, 오프호스트 복사는 이 단계에 없다(J62). 그래서 RPO 24시간은 호스트가 살아 있을 때만 지켜진다. 삭제 완료 뒤 복구 수단 없음(NFR12)과의 충돌은 사람이 정한다 | BR1.4 · BR5.11 · BR2.10 · NFR12.4 | 백업 스크립트 · 복원 리허설은 3.4 · Build and Test |
| NFR4.12 | Core는 Realtime이 없어도 04 · 05 · 12 조회(사본으로 응답, items 비움)가 된다. 새 시작은 정지 30초 이내만 503이고, Realtime이 연결 상태 보고자이므로 30초가 지나면 BR4.3 ③으로 UNKNOWN · canStart false가 되어 423 DEVICE_NOT_STABLE이다. 끝내기는 어느 시점이든 503이다. 반대로 Core가 멈추면 Realtime은 새 연결을 1013으로 닫고 진행 중 세션은 계속되며 Outbox는 쌓이고 재시도한다 | BR4.3 · BR6.1 · BR6.7 · BR6.9 · BR6.15 · BR3.4 · BR3.6 · BR4.8 · BR5.3 | 외부 공급자 장애는 NFR4.2 |
| NFR4.13 | readiness는 DB 연결과 Flyway validate 통과다. 준비 전 응답 코드는 502 또는 503(3.4 확정): 앱 프로세스가 접속을 못 받으면 Nginx 502, 앱이 떠 있으면 앱이 503을 직접 답한다. healthcheck 주기는 3.4가 정한다 | BR8.1 · BR8.2 · BR8.9 | Compose · Nginx 세부는 3.4 |
| NFR4.16 | BR6.9가 이미 U1 범위로 정했다: 보호자 폰의 네트워크 오류 · 시간 초과에서는 '끝내기 요청을 보내지 못했어요. 인터넷을 확인해 주세요.'(mockups 9-18)를 보이고 종료 요청됨으로 표시하지 않으며 서버 세션은 ACTIVE 그대로다. 요청이 서버에 닿은 뒤 응답만 잃은 경우는 세션이 CLOSING이 되어 12의 다음 폴링이 반영하고 다시 눌러도 멱등이다 | BR6.9 · BR6.15 · 12 · P1 | 12 재조회 이어붙임(오프라인에서 돌아온 뒤 화면을 서버 상태에 맞춤)은 U4 |
| NFR6.1 | 시한 키 단일 원천은 계약 7절이고 코드는 mujoong.timing.* 로 읽는다. 7절에 없는 값은 3.2절 레지스트리가 이름의 단일 원천이다 | BR8.11 · timing.md | 키 이름 확정은 3.3, 파일 반영은 3.5 |
| NFR6.2 | 다섯 시계(10 · 20 · 30 · 40초와 30초 stale)를 키 값 줄이기와 Clock 앞당기기 두 방식으로 시험한다 | BR6.12 · BR4.3 ③ · BR6.15 | 19 / 21초 경계와 4 · 6분 시한은 U4가 같은 코드를 확장 |
| NFR6.3 | 출발 상태가 둘 이상인 FAILED 전이는 FOR UPDATE로 읽고 읽은 상태를 조건으로 쓴다 | BR6.8 · BR6.12 ② | U4의 11개 상태 전이 |
| NFR6.4 | BR4.8이 DeviceStatusChanged.occurred_at을 UPDATE 직전에 앱 Clock으로 읽은 시각(트랜잭션 시작 시각이 아님)으로 이미 정했다. 시험은 Clock 빈 사용 검사다 | BR4.1 · BR4.8 · BR5.3 · BR5.7 | — |

### 2.2 장애 시나리오표 (NFR4.1 ~ 4.7 · 4.16)

| ID | 장애 | 감지 | 시스템 반응 | 사용자에게 보이는 것 | 복구 |
|---|---|---|---|---|---|
| NFR4.1 | 아이 기기 소켓이 닫기 프레임 없이 끊기거나(1006 등) 열린 채 무음 | 마지막 기기 메시지(HEARTBEAT 포함) 뒤 40초(device.disconnect). 연결은 영속 last_seen_at 주기 점검, 세션은 메모리 시계 | 연결 DISCONNECTED와 DeviceStatusChanged. ACTIVE 세션은 조건부 UPDATE 1회로 ABORTED(DISCONNECT)와 출력 · 공급자 정리. CLOSING 중에는 끊김 전이가 없고 30초 시한이 앞선다 | 보호자 04 '끊김'(전원 · 와이파이 확인), 12는 17 '연결이 끊겨 끝났어요'. 아이 기기는 마지막 화면 유지, 재촉 문구 없음 | 기기 웹이 켜짐 상태에서 5초 간격으로 재접속해 HELLO_OK로 CONNECTED. 소켓이 살아 있으면 다음 HEARTBEAT. 끊긴 세션은 이어 붙이지 않고 보호자가 새 키로 다시 시작 |
| NFR4.2 | 공급자 연결 거부 · 무응답 · session.started 없음 | 연결 실패는 즉시, 미수신은 session.device-ack-timeout 20초(ACK · DEVICE_READY는 도착한 경우) | PREPARING · DEVICE_ACK → FAILED(PROVIDER_UNAVAILABLE) 1회, 공급자 세션 정리, START_SESSION이 나갔고 연결이 살아 있으면 CLOSE_TRANSPORT(FAILED)와 STANDBY. 기기 연결 판정은 UNRESPONSIVE로 쓰지 않는다 | 12가 폴링에서 FAILED를 받아 08로 돌아가 사유 카드 한 종류(사유별 구분은 U4). 아이 기기는 대기로 돌아가고 기술 문구 없음 | FAILED는 종료 상태라 계정이 잠기지 않는다. 보호자가 새 키로 다시 시작. 자동 재시도 없음. ACTIVE 뒤 끊김은 U1이 보지 못한다(U3 · U4) |
| NFR4.3 | Realtime 보고가 끊김(앱 정지 · 네트워크) | now − reported_at > core.device-status-stale 30초(읽을 때 계산). 12는 now − observedAt > 30초 | DeviceView UNKNOWN(DEVICE_STATUS_UNKNOWN), canStart false라 새 시작은 423 DEVICE_NOT_STABLE. 진행 중 세션은 전이하지 않는다 | 04 · 05 '기기 상태 확인 불가'와 마지막 확인 시각, 12 '기기 상태를 확인할 수 없어요 · 마지막 확인 hh:mm' | 다음 보고(10초 주기) 또는 조회 성공이 오면 자동으로 풀린다 |
| NFR4.4 | 끝내기 뒤 기기가 멈춤을 알리지 않음 | CLOSING 진입 뒤 session.closing-confirm 30초 | CLOSING → ENDED_UNCONFIRMED(confirmed false) 1회, 기기 연결 판정 UNRESPONSIVE, 연결이 살아 있으면 STANDBY. 늦은 VOICE_SESSION_CLOSED는 SessionEvent 기록만 | 17 '기기 응답 없이 끝냈어요'와 본문. 기기 확인 없는 '종료 완료' 표시 없음 | 같은 연결의 다음 HEARTBEAT 또는 새 HELLO_OK가 UNRESPONSIVE를 CONNECTED로 푼다. 보호자는 다음 시작 전에 04 · 05에서 기기 상태 확인 |
| NFR4.5 | 같은 시작 요청 · 같은 이벤트가 반복 도착(재시도 · 두 번 탭 · 두 탭) | (guardianId, idempotencyKey) 유일과 request_hash, eventId와 payload_hash | 같은 키 · 본문은 첫 응답 재생, 다른 본문은 409 IDEMPOTENCY_CONFLICT. 같은 eventId · 해시는 200(처리기 재호출 없음), 다른 해시는 409 EVENT_CONFLICT와 경고 로그, 미반영 | 12로 가는 세션은 하나. 다른 키의 두 번째 요청은 사유 문구 하나 | 호출자 재시도가 같은 결과를 받는다. 별도 복구 없음 |
| NFR4.6 | 사본 갱신 이벤트가 뒤바뀌어 도착하거나 늦은 신호가 옴 | statusVersion ≤ 사본 값, 기기 이벤트 시각 ≤ reported_at, 조건부 UPDATE 0행 | 최신만 반영하고 낡은 것은 PROCESSED로 버린다. 로컬 FAILED 사본에 종료 아닌 이벤트가 오면 CORE_ABANDONED 취소 재전송. 종료 상태는 되돌아가지 않고 늦은 기기 메시지는 버린다 | 04 · 12는 최신 상태만 본다. 과거 응답 재생 · 자동 재개 · 다른 세션 종료 없음 | 처리 순서와 무관하게 최종 값이 같다. 다음 이벤트가 덮는다 |
| NFR4.7 | Realtime 또는 Core 프로세스 재시작, 또는 상대 앱이 재시도 상한(약 8분)보다 오래 멈춤 | 앱 시작 시점(HTTP 수신을 열기 전) | Realtime: 비종료 세션 FAILED(SERVER_RESTART)와 이벤트, CONNECTED 연결 행 DISCONNECTED(connection_id 비움). 양 앱: Inbox RECEIVED 행의 next_attempt_at을 now로 당겨 선점 경로로 처리, Outbox PENDING은 폴러가 집음, FAILED 제어 이벤트 4종은 PENDING으로 되돌림(A1). 10분 묘비는 사라지고 늦게 만들어진 세션은 재취소로 닫힘. Core: REQUESTED는 10초 시한이 닫음 | 12는 FAILED를 받아 08로 돌아간다. 기기는 소켓이 닫혀도 마지막 화면 유지 | 기기 웹이 5초 간격으로 재접속해 HELLO_OK로 CONNECTED. 세션은 이어 붙이지 않고 보호자가 새로 시작. 끝나지 않은 메시지와 되돌린 제어 이벤트는 이어서 전달 |
| NFR4.16 | 보호자 폰만 네트워크가 끊기거나 요청이 시간 초과(앱만 오프라인): 끝내기 요청이 서버에 닿지 못함 | 폰의 요청 오류 · 시간 초과(서버는 요청을 받지 못했다) | 서버 변화 없음. 세션은 ACTIVE 유지, 기기 대화 계속, 끝내기 명령 0회 | 12에 '끝내기 요청을 보내지 못했어요. 인터넷을 확인해 주세요.'(mockups 9-18). 종료 요청됨 표시 없음 | 네트워크가 돌아오면 보호자가 다시 끝내기를 누른다(멱등, BR6.9). 12 재조회 이어붙임은 U4 |

## 3. 수치와 설정 키

계약 7절에 키가 있는 값은 7절이 단일 원천이고 코드는 `mujoong.timing.<키>`로 읽는다(BR8.11). 3.2의 표는 7절에 없는 값의 `mujoong.*` 키 레지스트리이며 이름의 단일 원천이다. 최종 이름과 값은 3.3 NFR Design이 확정해 `contracts/timing.md`에 더하기만 한다.

### 3.1 계약 7절 키 중 U1이 쓰는 것

| 항목 | 값 | 출처 | 설정 키 |
|---|---|---|---|
| Core가 REQUESTED를 만든 때부터 PREPARING 이벤트를 못 받으면 로컬 FAILED | 10초 | 7절 · BR6.12 ① | session.preparing-timeout |
| START_SESSION 송신 시도부터 ACK · DEVICE_READY(OK) · session.started 중 하나라도 확인되지 않아 ACTIVE가 되지 않으면 FAILED(빠진 것이 ACK 또는 DEVICE_READY면 DEVICE_NO_ACK, 둘은 왔는데 session.started가 없으면 PROVIDER_UNAVAILABLE) | 20초 | 7절 · BR6.12 ② · J59(h) | session.device-ack-timeout |
| CLOSING 진입부터 VOICE_SESSION_CLOSED가 없으면 ENDED_UNCONFIRMED | 30초 | 7절 · BR6.12 ③ | session.closing-confirm |
| 마지막 기기 메시지부터 DISCONNECTED(진행 중이면 ABORTED) | 40초 | 7절 · BR4.8 · BR6.12 ④ | device.disconnect |
| 기기 HEARTBEAT 송신 주기 | 5초 | 7절 · J27 | device.heartbeat-interval |
| WSS 업그레이드부터 HELLO가 없으면 4408로 닫기 | 5초 | 7절 · BR3.6 | device.hello-timeout |
| 연결 끊김 뒤 기기 재접속(켜짐 상태에서만) | 1 · 2 · 4초 뒤 5초 간격. U1은 5초 고정이고 백오프는 U3 | 7절 · BR9.7 | device.reconnect-backoff |
| Realtime이 Core에 연결 행 전부를 주기 보고 | 10초 | 7절 · BR4.8 · J26 | device.status-report-interval |
| Core가 마지막 보고 뒤 '기기 상태 확인 불가'로 보는 시간 | 30초 | 7절 · BR4.3 ③ · BR6.15 | core.device-status-stale |
| 12 진행 조회 폴링 | 2초 | 7절 · A2 · BR6.15 | parent.poll-interval |
| 등록 코드 유효 시간(bootstrap 쿠키 Max-Age도 24시간) | 24시간 | 7절 · BR1.4 · BR1.5 | bootstrap.code-ttl |
| D0 등록 상태 확인 주기 | 2초 | 7절 · BR1.7 | bootstrap.status-poll |
| 코드 발급 속도 제한 | IP당 1시간 20회(같은 쿠키의 반복 요청은 세지 않음) | 7절 · BR1.6 · J59(k) | bootstrap.rate-limit |
| 등록 실패 제한 | 보호자당 10분 10회 → 429 | 7절 · BR2.6 | device.register-failure-limit |
| 기기 쿠키 수명(MUJOONG_DEVICE, 갱신형) | 400일 | 7절 · BR3.1 · BR3.5 | device.cookie-max-age |
| 보호자 세션 유휴 만료(갱신형) | 30일 | 7절 · NFR9.2 | login.session-idle |

7절의 나머지 키(session.join-wait · session.silence-* · device.gap-display · device.unstable-* · device.playback-mark-interval · device.output-queue-max · gate.* · practice.* · result.job-attempts · report.ready-target · goal.restore-window)는 U1이 읽지 않는다. 후속 유닛(U3 ~ U6)이 같은 파일 · 같은 시험 틀에 더한다.

### 3.2 7절에 없는 값 — mujoong.* 키 레지스트리 (이름의 단일 원천, 이름 · 값은 3.3이 확정)

다른 NFR 문서(performance · scalability · security · observability · tech-stack)의 3절은 `mujoong.*` 키를 새로 만들지 않고 이 표의 이름을 쓰거나 '레지스트리 참조'로 적는다. 새 키가 필요하면 이 표에 먼저 올린다. 프레임워크 표준 키(spring.* · management.*)는 각 문서 3절에 그대로 둔다.

| 항목 | 값 | 출처 | 설정 키 |
|---|---|---|---|
| 내부 HTTP 제한: 시작 명령(시간 초과 때만 같은 sessionId로 1회 재시도) | 3초, 재시도 1회 | J26 · BR6.5 · BR6.7 | mujoong.internal-http.session-start-timeout · mujoong.internal-http.session-start-retries |
| 내부 HTTP 제한: 끝내기 · Core 취소(CORE_ABANDONED) | 2초 | J26 · BR6.7 · BR6.9 | mujoong.internal-http.session-end-timeout |
| 내부 HTTP 제한: 진행 조회(시작 검사 대조 포함) | 1초 | J26 · BR6.1 ② · BR6.15 | mujoong.internal-http.session-view-timeout |
| 내부 HTTP 제한: 토큰 확인(초과하면 WSS 1013) | 1초 | J26 · BR3.4 · BR3.6 | mujoong.internal-http.verify-token-timeout |
| 내부 HTTP 제한: 연결 상태 보고(유실 허용) | 1초 | J26 · BR4.8 | mujoong.internal-http.device-status-timeout |
| 내부 HTTP 제한: 이벤트 전달 POST /internal/events | 3초 | J26 · BR5.2 | mujoong.internal-http.event-publish-timeout |
| Outbox 폴러 주기와 배치(직렬, 재시도 대기 행이 뒤 행을 막지 않음) | 500ms, 20개 | J49 · BR5.2 | mujoong.outbox.poll-interval · mujoong.outbox.batch-size |
| Outbox 재시도 간격과 상한(일반 이벤트 기준, 409 · 400은 즉시 FAILED. 제어 이벤트 4종의 FAILED는 A1이 앱 시작 때 되돌림) | 1 · 2 · 4 · 8 · 16 · 30초 뒤 30초 고정, 20회 | J26 · J49 · BR5.3 | mujoong.outbox.retry-backoff · mujoong.outbox.max-retries |
| Inbox 처리기 재호출 간격과 상한(6번째 실패는 DEAD) | 1 · 2 · 4 · 8 · 16초, 5회 | J49 · BR5.8 | mujoong.inbox.retry-backoff · mujoong.inbox.max-retries |
| Inbox 처리기 선점 시간 | 30초 | J49 · BR5.7 | mujoong.inbox.claim-lease |
| Inbox 재호출 폴러 주기 | 제안: Outbox 폴러와 같은 500ms (원문이 값을 정하지 않음) | BR5.7 | mujoong.inbox.poll-interval |
| 실패 행(FAILED · DEAD) 페이로드 보관 | 7일 | J50 · BR5.11 | mujoong.retention.failed-payload |
| 정리 작업 주기: Outbox/Inbox 페이로드 정리 | 하루 한 번 | BR5.11 | mujoong.cleanup.messaging-interval |
| 정리 작업 주기: 만료 코드 점검 | 10분 | BR1.4 | mujoong.cleanup.device-expiry-interval |
| EXPIRED 기기 행 보관 후 삭제 | 7일 | BR1.4 | mujoong.cleanup.expired-device-retention |
| Core 취소 sessionId의 묘비(Realtime 메모리) | 10분 | J54 · BR6.6 · BR6.7 | mujoong.session.tombstone-ttl |
| 등록 코드 추첨 횟수(첫 추첨 포함, 모두 겹치면 503) | 5회 | J45 · BR1.2 | mujoong.bootstrap.code-draw-attempts |
| 속도 제한 카운터 키 수 상한(위조 키로 메모리가 차지 않게) | 10,000 | BR1.6 | mujoong.limits.rate-counter-max-keys |
| REST 요청 본문 상한 | 64KB | Q6 · J66 | mujoong.limits.rest-body-max |
| WSS 텍스트 프레임 상한 | 8KB | Q6 · J66 | mujoong.limits.ws-text-frame-max |
| 내부 이벤트 본문 상한(밖에 닿지 않음) | 2MB | BR5.4 | mujoong.limits.internal-event-body-max |
| 기기 웹 재시도 간격: 코드 못 받은 동안 bootstrap · status 폴링 실패 · T19 HTTP 실패 · 닫기 코드 1001 · 1011 · 1013 · 4400 · 4408 뒤 재접속 | 5초 (재접속은 U3의 백오프로 대체) | BR9.4 · BR9.7 · BR1.2 | mujoong.device-web.retry-interval |
| 「등록되었어요」 표시 | 3초 | BR9.2 | mujoong.device-web.registered-display |
| 출력 구간 확정 조용 시간 | 800ms | J52 · BR7.3 | mujoong.gate.segment-quiet |
| 기기 연결 DISCONNECTED 점검 주기(영속 last_seen_at 기준) | 제안 5초(heartbeat와 같음, 발동 지연 ≤ 40초 + 주기) | BR4.8 | mujoong.device.disconnect-check-interval |
| 세션 안전 시한 점검 주기(점검 방식일 때만. 세션별 일회성 타이머면 불필요) | 제안 1초 | BR6.12 · BR6.13 | mujoong.session.timer-tick |
| 시험 로그인 켜기(dev 프로필에서만 의미가 있음) | 기본 false | BR8.3 | mujoong.dev-login.enabled |
| HSTS max-age | 180일 | Q6 · J66 | mujoong.security.hsts-max-age |
| 보호자 세션 쿠키 Max-Age(서버 유휴 만료가 정본) | 30일 | NFR9.2 | mujoong.security.session-cookie-max-age |
| DB 연결 풀 대기 경고 기준 | 100ms | NFR7.6 · J68 | mujoong.metrics.db-pool-wait-warn |
| ownerHash 비밀(값은 문서에 두지 않음) | 서버 환경 변수 | NFR9.10 | mujoong.observability.owner-hash-secret |
| 지연 구간 로그 기록 비율 | 100%(동시 세션 2) | NFR5.4 | mujoong.observability.latency-log-sample-rate |
| 지표 스냅샷 로그 주기(METRIC_SNAPSHOT) | 60초 | observability 3.4 · RD-14 | mujoong.observability.metric-snapshot-interval |

### 3.3 가용성 · 복구 · 내구성 (Q1 · Q2)

| 항목 | 값 | 출처 | 설정 키 |
|---|---|---|---|
| 가용성 | 99.0% 이상(허용 중단 하루 14.4분, 7일 7 × 24 × 60 × 1% = 100.8분). 11/2 시점은 확보한 일수(최소 4일, 허용 57.6분) 누적 가용성을 보고하고 7일 롤링은 동결 뒤로 이월 | NFR4.8 · J61 · RD-8 | 해당 없음(관측 목표, observability 3.1) |
| 외부 프로브 | 호스트 밖에서 1분마다 Core health · /device 정적 페이지 · WSS 핸드셰이크 3종을 호출하고 결과를 파일에 기록, 하나라도 실패하면 실패한 분 | NFR4.8 · RD-8 | 프로브 스크립트 인자(설정 키 아님, 실행 위치는 3.4) |
| 계획된 배포 제외 기록 | 배포 스크립트가 시작 · 끝 시각(UTC)을 파일에 남긴 구간만 제외 | NFR4.8 · RD-8 | 배포 스크립트(3.4, 형식 한 줄) |
| 컨테이너 자동 복구 | restart: unless-stopped, 5분 이내(목표 1분). depends_on service_healthy는 기동 순서와 기동 직후 트래픽 차단만 보장 | NFR4.9 · J61 · RD-8 | Compose 파일(3.4) |
| 계획된 배포 서비스 중단 | 60초 이내 | NFR4.10 · J61 | 해당 없음(배포 절차, 3.4) |
| 헬스체크 | liveness와 readiness(DB 연결 · 마이그레이션 완료). 준비 전 응답 502 또는 503(3.4 확정). 실행 중 readiness 실패는 앱이 503, Nginx 502는 업스트림 접속 불가일 때만. 주기 · 재시도는 3.4 | NFR4.13 · J61 · RD-8 | Compose healthcheck(3.4) |
| 논리 백업 | 매일 03:00 KST 한 번, 같은 호스트 별도 볼륨, 7일 보관 | NFR4.11 · J62 | 백업 스크립트 인자(3.4) |
| 복구 목표 | RPO 24시간(호스트 상실 제외), RTO 60분, 복원 리허설 1회 | NFR4.11 · J62 · RD-8 | 해당 없음(운영 절차) |

## 4. 시험 · 검증 방법

| ID | 시험 | 단계 |
|---|---|---|
| NFR4.1 | Clock 주입: 마지막 메시지 뒤 직전(40초 − 1틱)은 변화 없음, 직후는 DISCONNECTED와 ABORTED(DISCONNECT) 1회. 닫기 프레임 없는 종료와 열린 채 무음 두 경우, CLOSING 중에는 ABORTED가 되지 않음, 04 끊김 표시 | 3.5 구현 |
| NFR4.2 | 가짜 공급자로 연결 거부와 무응답 주입: FAILED(PROVIDER_UNAVAILABLE) 1회, 새 키 시작 성공, CLOSE_TRANSPORT(FAILED) 송신, 기기 연결 판정이 UNRESPONSIVE가 아님. ACTIVE 뒤 공급자 끊김은 이 시험의 범위가 아님(U3 · U4가 추가) | 3.5 구현 |
| NFR4.3 | 보고 중단 주입: 30초 직전은 STABLE, 직후는 UNKNOWN과 canStart false. 12는 observedAt 30초 경계, 조회 한 번 실패는 stale이 아님 | 3.5 구현 |
| NFR4.4 | VOICE_SESSION_CLOSED 지연 주입: 30초 직전 ENDED, 직후 ENDED_UNCONFIRMED와 UNRESPONSIVE. 늦은 확인이 전이를 되돌리지 못함 | 3.5 구현 |
| NFR4.5 | 같은 idempotencyKey 두 번 · 동시 두 번, 같은 eventId 두 번(같은 해시 · 다른 해시): 세션 1개, 처리기 호출 1회, 409 두 종류 | 3.5 구현 |
| NFR4.6 | SessionStateChanged · DeviceStatusChanged 순서 뒤바꿈과 로컬 FAILED 사본에 종료 아닌 이벤트: 최종 값 동일, 취소 재전송, 과거 응답 재생 0 | 3.5 구현 |
| NFR4.7 | Compose에서 Realtime · Core 강제 종료 후 재기동: 비종료 세션 FAILED(SERVER_RESTART), 연결 행 DISCONNECTED, RECEIVED 행 처리, 기기 웹 재접속 | Build and Test |
| NFR4.7 | A1 재무장: Realtime을 정지한 채 기기 교체 등록으로 DeviceReleased를 발생시키고 10분 두면 Core Outbox의 그 행이 20회 재시도 뒤 FAILED가 된다. Realtime을 기동하고 Core를 재기동하면(보내는 쪽 시작 때 되돌림) 그 행이 PENDING으로 돌아가 전달되고 옛 소켓이 4401로 닫힌다. 일반 이벤트의 FAILED 행은 되살아나지 않고(20회 상한 유지), 409 · 400으로 즉시 FAILED가 된 행, ACCOUNT_DELETED로 닫힌 SessionRecordCaptured 행, payload가 이미 비워진 행도 되돌리지 않는다 | Build and Test |
| NFR4.8 | 프로브 일일 집계 스크립트: 프로브 결과 파일과 배포 시각 파일로 3종 중 하나라도 실패한 분을 실패로 세고 배포 시각 파일 구간만 제외해 누적 가용성을 계산한다. Realtime만 정지해도 WSS 프로브가 실패한 분으로 잡히는지 한 번 확인한다. 11/2에는 확보 일수 누적값을 보고하고 7일 롤링은 동결 뒤 | 11/2 측정 |
| NFR4.9 | 컨테이너 강제 종료 뒤 헬스체크 통과까지 시간을 3회 측정해 5분 · 1분 기준과 비교. 앱만 죽은 동안 Nginx가 502를 돌려주고 통과 뒤 정상으로 돌아오는지 확인 | Build and Test |
| NFR4.10 | 배포 리허설에서 서비스 중단 시간(health를 1초 간격으로 부르는 별도 측정)과 기기 웹 자동 재접속 확인 | Build and Test |
| NFR4.11 | 백업 파일 생성 로그 7일치 확인과 빈 DB로의 복원 리허설 1회(소요 시간을 RTO 60분과 비교). RPO 24시간은 호스트가 살아 있다는 전제에서만 확인함을 기록에 적는다 | Build and Test |
| NFR4.12 | Realtime 컨테이너 정지 시점별: 정지 직후 30초 이내 시작 요청은 503 REALTIME_UNAVAILABLE, 30초 초과 뒤 시작 요청은 423 DEVICE_NOT_STABLE(BR4.3 ③), 끝내기는 어느 시점이든 503, 04 · 05는 30초 뒤 '확인 불가', 12 조회는 사본으로 답하고 items 비움. Core 정지: 새 WSS 1013, 진행 중 세션 유지, Outbox 재시도. 내부 HTTP 제한 시간 초과 시험 | 3.5 구현 |
| NFR4.13 | 앱별 liveness · readiness 응답 시험(DB 끊김 · 마이그레이션 미완료), DB → 앱 → Nginx 기동 순서 시험, 실행 중 DB 정지 시 앱이 503을 직접 답하는지(Nginx 502가 아님), 앱 컨테이너 정지 시 Nginx 502 | Build and Test |
| NFR4.16 | 폰 네트워크 차단 주입(화면 시험에서 요청 오류 · 시간 초과 주입, 실기 비행기 모드 1회): '끝내기 요청을 보내지 못했어요' 문구, 종료 요청됨 표시 0, 서버 end 호출 0회, 세션 ACTIVE 유지, 기기 대화 계속. 요청이 닿은 뒤 응답만 잃은 경우는 CLOSING으로 반영되고 재탭이 멱등인지 별도 확인 | 3.5 구현 |
| NFR6.1 | 시간 숫자 상수 검색(ArchUnit 또는 정적 검사, 허용 목록 제외)과 코드가 읽는 키가 레지스트리 · timing.md와 일치하는지 대조 | 3.5 구현 |
| NFR6.2 | 다섯 시계 각각 직전 · 직후 주입(키 값 줄이기와 Clock 앞당기기 두 방식), 실제 시간 대기 없음 | 3.5 구현 |
| NFR6.3 | 시한 vs ACK(DEVICE_READY · session.started · VOICE_SESSION_CLOSED) 동시 경합: 전이 1회, 늦은 신호 무시, SessionEvent 기록 | 3.5 구현 |
| NFR6.4 | 시간 읽기를 모두 Clock 빈으로 하는지 검사(System.currentTimeMillis · nanoTime · SQL now 직접 호출 0건)와 Clock 고정 시험. DeviceStatusChanged.occurred_at이 고정 Clock 값과 같은지 확인 | 3.5 구현 |

## 5. 열린 질문과 가정

| 구분 | 내용 | 확정 단계 |
|---|---|---|
| 가정 J61 | 가용성 99.0%(계획 배포 제외), 복구 5분(목표 1분), 배포 중단 60초. 99.9% · 블루-그린은 단일 인스턴스 전제(소켓 레지스트리 · 메모리 시계)와 맞지 않아 쓰지 않는다 | 11/2 측정 |
| 가정 J62 | 매일 03:00 KST 논리 백업, 같은 호스트 별도 볼륨, 7일, RPO 24시간(호스트 상실 제외), RTO 60분 | 3.4 · 11/2 |
| 가정 J65 · J67 · J69 | 수직 확장만(Core · Realtime 각 1개) · 헬스 · 지표는 observability와 공유 · 시한 시험은 Clock 주입 | 3.3 |
| 이어받은 가정 | J26(내부 HTTP 제한) · J45(코드 추첨) · J49(폴러 · 재호출) · J50(페이로드 보관) · J51(네 시한) · J53(재시작) · J54(묘비) · J55(UNRESPONSIVE) · J59(h) | 3.3이 키 확정 |
| 3.2 재점검 결정 RD-1 | 승인된 functional-design은 고치지 않는다. FD와 어긋나는 보강은 아래 'FD 보강 후보 A1 ~ A6'으로 올려 3.5에서 FD 규칙에 반영한다(다른 문서의 보강 요구도 이 번호를 쓴다) | 3.5 |
| FD 보강 후보 A1 | FAILED 제어 이벤트 재무장(BR5.3 · BR5.12 보강): 앱 시작 때 자기 Outbox에서 FAILED이고 eventType이 DeviceReleased · DeletionRequested · DeletionCompleted · SessionRecordCaptured인 행을 PENDING으로 되돌려(retry_count 0, available_at 시작 시각) 반드시 전달한다. 일반 이벤트의 20회 상한은 그대로다. 되돌리지 않는 행: last_error가 EVENT_CONFLICT · INVALID_ENVELOPE(즉시 FAILED)이거나 ACCOUNT_DELETED(BR5.14 (c))인 행, 보관 7일이 지나 payload가 비워진 행. Inbox DEAD는 대상이 아니다(처리기 결함 신호이므로 경보로 본다). 근거: 상대 앱의 8분 넘는 중단이 NFR4.8 허용 범위 안이라 제어 이벤트가 영구히 사라질 수 있다 | 3.5, 건수는 observability STARTUP_RECOVERY의 rearmedControlEvents로 남김 |
| FD 보강 후보 A2 | BR8.3 거부 조건을 prod에서 demo · prod로 넓힌다: 활성 프로필에 demo 또는 prod가 있으면 dev 프로필 동시 활성 또는 mujoong.dev-login.enabled=true인 모든 조합에서 앱 기동을 거부한다. 근거: NFR9.11(security) | 3.5 |
| FD 보강 후보 A3 | Outbox/Inbox 네 표에 trace_id 열(선택)을 더한다. 이벤트 봉투를 전달 때 행에서 다시 만들기 때문이다. 근거: NFR5.7(performance), 계약 10절 더하기만 | 3.5 |
| FD 보강 후보 A4 | 모든 Outbox/Inbox 행에 guardian_id를 채운다: DeviceStatusChanged는 Realtime이 연결 행의 guardian_id를, Core 수신 쪽은 aggregate_id로 core_device를 조회해 채운다(BR5.14 보강). 근거: NFR12.1(security) | 3.5 |
| FD 보강 후보 A5 | BR7.4 시드 사전을 계약 9절 category 7개마다 최소 1항목으로 넓힌다. 근거: NFR1.2(security) | 3.5 |
| FD 보강 후보 A6 | 부하 시험 전용 시드: 보호자 · 아이 · 기기 30세트를 DB에 직접 넣고 기기 토큰 해시를 미리 계산한다(dev 또는 load-test 프로필 한정). bootstrap.rate-limit은 그 환경에서만 설정으로 올린다(prod 값은 20회/시간 그대로). 근거: NFR7.2 · NFR5.5(scalability · performance) | 3.5 |
| 3.2 재점검 결정 RD-2 | 이 문서 3.2가 mujoong.* 키 레지스트리다. 문서마다 달리 제안했던 이름 8건(게이트 조용 시간 · Outbox/Inbox 접두 · 정리 주기 · 실패 페이로드 보관 · 속도 제한 카운터 상한 · 기기 웹 재시도 · 내부 HTTP 제한 6종)을 하나로 통일했다: mujoong.gate.segment-quiet · mujoong.outbox.* · mujoong.inbox.* · mujoong.cleanup.*(주기 · 기기 보관) · mujoong.retention.failed-payload · mujoong.limits.rate-counter-max-keys(10,000, 비어 있던 값을 채움) · mujoong.device-web.retry-interval · mujoong.internal-http.*-timeout 6개. 값은 그대로다. 다른 문서의 3절은 키를 새로 만들지 않는다 | 3.3 |
| 3.2 재점검 결정 RD-7 | NFR4.16(Must, reliability)을 신설해 앱만 오프라인(BR6.9)을 U1 몫으로 닫았다. NFR4.2에 'ACTIVE 뒤 공급자 끊김 감지는 U1 범위 밖(소유 U3 · U4)'을 행 본문에 적었고 NFR4.7에 A1과 시험을 넣었다. 새 요구 NFR5.8(Should, performance)은 performance 문서 소관이며 그 로그 필드는 observability 3.4가 정한다. 요구 ID는 모두 67개다 | 3.5 |
| 3.2 재점검 결정 RD-8 | NFR4.8 ~ 4.13 현실화: 외부 프로브 3종(호스트 밖, 1분) · 배포 시각 파일 · 확보 일수 누적 · depends_on의 한계 · 503/502 구분 · NFR4.12 시점별 시험 · 로그 보관은 Compose json-file 크기 상한(observability 3.5) · RPO 24시간에 '호스트 상실 제외' 단서 | 3.4 |
| 3.2 재점검 결정 RD-14 | 3.1 device-ack-timeout 행 정정(ACK · DEVICE_READY · session.started 중 하나라도 빠지면 FAILED), NFR6.4 정정(BR4.8이 이미 앱 Clock으로 정함), NFR4.13 준비 전 응답 코드 통일. 보안 사건 · 12 사본 폴백 · 풀 대기 지표, SLO 계산 출처 열, METRIC_SNAPSHOT은 observability 문서가 정의한다 | 3.3 |
| 사람이 정할 것 1 | 백업 7일 보관과 NFR12 '삭제 완료 뒤 복구 수단 없음'의 충돌. 실제 보호자 · 아이 데이터를 다루기 전 승인 항목(NFR12.4, D-14) | 사람 결정 |
| 사람이 정할 것 2 | 백업이 같은 호스트에만 있으면 EBS 손상 · 인스턴스 종료에서 백업도 함께 잃어 RPO 24시간을 못 지킨다(NFR4.11은 호스트 상실 제외로 적었다). ADR-006 11.3은 '가능하면 EC2 외부'를 권한다. 오프호스트 복사를 둘지 정한다(J62는 두지 않았다) | 사람 결정 |
| 사람이 정할 것 3 | 외부 프로브 실행 위치(팀 PC의 스케줄 작업 또는 무료 외부 pinger). 팀 PC는 꺼질 수 있어 프로브 공백이 생기고, WSS 핸드셰이크 프로브가 토큰 없이 업그레이드 응답 코드만 볼지도 정한다 | 사람 결정 · 3.4 |
| 사람이 정할 것 4 | A1 한계: 앱 시작 때만 되돌리므로 받는 쪽만 죽었다 살아난 경우 보내는 쪽의 FAILED 제어 이벤트는 보내는 쪽을 재기동해야 살아난다. '상대 앱 복구 뒤 양 앱 재기동'을 운영 절차로 둘지, 제어 이벤트 한정 주기적 재무장(예: 30초 고정 간격 무한 재시도)으로 FD를 더 넓힐지 정한다 | 사람 결정 · 3.3 |
| 간극 | ACTIVE 뒤 공급자 연결이 끊기면 U1은 감지하지 못한다. 기기가 살아 있으면 세션은 보호자 끝내기 전까지 ACTIVE로 남을 수 있다(NFR4.2는 시작 구간만 닫는다) | U3 · U4 |
| 간극 | 마스터 NFR4.12 문구는 '시작만 503'이나 끝내기도 Realtime이 필요해 503(BR6.9)이다. 위 1절은 '시작과 끝내기 요청만'으로 풀어 썼고 시작은 정지 30초 뒤부터 423이다 | 3.3 확인 |
| 열린 질문 | 점검 주기 두 값(5초 · 1초)과 Inbox 폴러 주기는 제안이다. 발동 시각은 시한 + 점검 주기 이하로 늦을 수 있고 시험은 점검 함수를 직접 호출해 이 지연을 배제한다 | 3.3 |
| 열린 질문 | 헬스체크 주기 · 재시도, 준비 전 응답 코드(502 또는 503), 외부 프로브가 쓸 health 경로(나머지 /actuator는 외부 403) | 3.4 |
| 열린 질문 | 배포 시각 파일의 형식(한 줄에 시작 · 끝 UTC 시각)과 배포 스크립트가 파일을 남기지 못한 경우의 처리(제외하지 않는다) | 3.4 · Operation |
