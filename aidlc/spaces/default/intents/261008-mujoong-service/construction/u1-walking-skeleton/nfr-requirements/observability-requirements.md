# 관측성 요구사항 — U1 u1-walking-skeleton

> 단계: Construction · NFR Requirements(3.2) · U1 u1-walking-skeleton · 깊이 Standard · 문서 observability-requirements.md
> 입력: `functional-design/functional-spec.md` · `rules.md` · `entities.md`(규칙 BR1.1 ~ BR9.7), `requirements.md`(NFR4 · NFR5 · NFR6 · NFR9), `contract-summary.md`(5 · 6 · 7절), 질문 파일 `nfr-requirements-questions.md`(Q1 · Q3 · Q5 · Q7 · Q8), Operation 단계 가드레일(SLO는 수치로, 경보는 SLO 위반보다 먼저)
> 날짜: 2026-10-09
> 대리 판단: 질문 파일의 답 Q1 ~ Q12는 팀장(준하)의 대리 판단(가정) J61 ~ J72다. 이 문서는 J61 · J63 · J65 · J67 · J68을 쓴다. 3.2 재점검에서 정한 결정은 질문 번호를 잇지 않고 5절에 '3.2 재점검 결정 RD-n'으로 적었다(이 문서의 몫은 RD-3 · RD-4 · RD-5 · RD-6 · RD-12 · RD-13 · RD-14). 질문 파일에 없는 수치는 '제안(11/2 확정)'으로 표시했고, 11/2 측정(OQ10) 뒤 해당 행만 고친다. 설정 키 이름은 reliability-requirements.md 3.2의 레지스트리를 따른다.

## 1. 요구사항

| ID | 우선순위 | 요구 | 측정 · 완료 기준 | 근거 |
|---|---|---|---|---|
| NFR4.14 | Should | 세션 결과 가시성: 모든 세션의 종료 상태 · 종료 사유 · failure_code가 지표(상태별 카운터)와 로그로 남아 FAILED · ABORTED 비율을 볼 수 있다. | 종료 사유별 카운터 존재 | NFR4 · J67 |
| NFR4.15 | Should | 헬스와 연결 가시성: 앱 헬스, DB 연결, 열린 기기 연결 수, 기기별 연결 상태 분포, 내부 HTTP 실패율을 내부 메트릭 엔드포인트로 제공한다(외부 비공개). | /actuator 메트릭 접근 시험(외부 403) | NFR4 · J67 |
| NFR5.4 | Must | 지연 측정 로그: 세션 단위로 말 끝 감지(출처 포함) · 공급자 첫 출력 · 구간 확정 · 게이트 통과 · 기기 재생 시작, 전사 확정, 12 응답에 처음 실린 시각, 중계 구간(relayIn · relayOut), 기기 시계 환산 입력(clockOffsetMs · rttMs)을 구조화 로그와 메트릭으로 남긴다. 목표를 조정해도 측정 로그는 유지한다. | 한 턴 로그만으로 NFR5.1(relayIn · relayOut) · NFR5.2(t0 ~ t3) · NFR5.3(서버 몫)이 모두 계산되는 스크립트가 돈다(미관측이 있으면 사유 표시). 11/2 측정에서 p95 계산에 쓸 수 있는 로그가 있다 | NFR5 · TS-N01 ~ N04 · RD-5 |
| NFR6.5 | Should | 시한 발동 지표: 네 안전 시한과 stale 판정이 발동할 때마다 timeout_fired{type} 카운터와 개인정보 없는 로그가 남는다. | 시한 주입 시험에서 카운터 증가 | NFR6 · J67 |
| NFR9.15 | Must | 로그 위생 점검: 로그 포맷은 JSON이고 최상위 여섯 필드(ts · level · traceId · component · event · ownerHash) + attrs(사건 코드별 허용 키 표 — 숫자 · 열거값 · UTC 시각만)만 쓴다. 전사 · 원음 · 토큰은 attrs에도 쓰지 않는다. | 로그 포맷 시험(최상위 여섯 필드 + attrs, attrs 키는 허용 표 안) + NFR9.8 grep 시험 | NFR9 · J67 · RD-6 |

## 2. 배경과 해석

### 2.1 요구별 U1 해석

| ID | U1에서의 뜻 | 걸리는 BR · 컴포넌트 | U1 밖 몫 |
|---|---|---|---|
| NFR4.14 | 종료 상태 4개(ENDED · ENDED_UNCONFIRMED · ABORTED · FAILED)와 Core 로컬 FAILED를 전이 승자가 한 번씩 센다. 이중 집계를 막으려면 Realtime이 CORE_ABANDONED로 닫은 FAILED는 따로 구분한다 | BR6.7 · BR6.8 · BR6.12 · BR6.14 · BR6.17 | DECLINED · CHILD_STOP · SILENCE · CONSENT_WITHDRAWN 사유는 U4가 라벨 값으로 더한다 |
| NFR4.15 | 헬스 두 종류(liveness · readiness)와 DB, 열린 WSS 연결 수, 연결 상태 분포(OFFLINE · CONNECTED · DISCONNECTED · UNRESPONSIVE), 내부 HTTP 실패율. 12 사본 폴백 · 풀 대기 초과 · 보안 사건 카운터도 같은 내부 엔드포인트에 둔다. 메트릭은 내부망과 호스트 loopback에서만 읽는다 | BR3.9 · BR4.8 · BR8.9 · NFR4.12 | UNSTABLE 분포는 U3 |
| NFR5.4 | 턴 하나마다 LATENCY_TURN 한 줄, 12 응답에 처음 실린 행마다 SESSION_ITEMS_SERVED 한 줄을 남긴다. NFR5.1(relayIn + relayOut) · NFR5.2(t0 ~ t3) · NFR5.3(서버 몫)이 이 줄들만으로 계산된다. t0는 공급자가 입력 종료 이벤트를 주지 않아도 대체 정의로 반드시 기록하고 출처를 tSpeechEndSource에 남긴다. 공급자 응답 길이(providerResponseMs)와 구간 확정 조용 시간(quietWaitMs 800ms)은 NFR5.1에 넣지 않고 NFR5.2의 별도 구간으로 기록한다. t3는 기기 관측 시각을 clockOffsetMs · rttMs로 서버 시계에 환산한다 | BR7.1 · BR7.3 · BR7.4 · BR7.5 · BR6.15 · VoiceRelay · OutputGate | 정식 구간 분할 · 끼어들기 재생 중단 0.5초(NFR5.8)는 U3, 12 말풍선 완성 · 화면 도달 계측은 U4 |
| NFR6.5 | type 다섯 개: preparing(10초) · device_ack(20초) · closing_confirm(30초) · disconnect(40초) · device_status_stale(30초). stale은 읽을 때 계산하므로 '신선 → 확인 불가'로 바뀌는 첫 판정만 센다 | BR6.12 · BR4.3 ③ · BR6.15 | 참여 대기 · 공백 4 · 6분 type은 U4가 더한다 |
| NFR9.15 | 로그 최상위는 여섯 필드(ts · level · traceId · component · event · ownerHash)이고 그 밖의 값은 모두 attrs에 담는다. attrs 키는 사건 코드별 허용 키 표(3.4)에 있는 것만이고 값은 숫자 · 열거값 · UTC 시각만이다(자유 문자열 금지, 예외는 BR5.13이 남기라고 한 EVENT_DELIVERY의 eventId 하나) | BR8.7 · BR5.13 · BR6.1 | 보안 사건 로그는 같은 형식을 쓴다(NFR9.14, security-requirements.md) |

### 2.2 관측 방식 (J67)

| 층 | 방식 | 제약 |
|---|---|---|
| 로그 | 구조화 JSON, 두 앱 모두. SLI 계산의 정본이다. 재시작하면 0이 되는 지표 값은 분 단위 METRIC_SNAPSHOT 사건으로 로그에 옮겨 SLI에 쓴다 | 전사 · 원음 · 토큰 · 쿠키 · 등록 코드 · 보호자 입력 원문 금지(attrs에도 불가) |
| 지표 | Micrometer + Actuator. 현재 상태 확인용이고 프로세스 메모리 값이라 재시작하면 0에서 다시 시작한다 | 내부 엔드포인트만, 라벨은 열거값만 |
| 추적 | traceId를 보호자 REST → Core → 내부 HTTP → Realtime → 이벤트 봉투까지 전파한다. 수집기(OpenTelemetry) · 중앙 로그 수집(ELK · Loki)은 두지 않는다 | 한 요청을 두 앱 로그에서 같은 id로 찾는다(NFR5.7) |
| 가용성 | 호스트 밖 외부 프로브가 1분마다 Core health · /device 정적 페이지 · WSS 핸드셰이크 3종을 부르고 결과를 파일에 쌓는다. 계획된 배포는 배포 스크립트가 남긴 시작 · 끝 시각 파일로 제외한다 | 프로브용으로 여는 health는 Core 한 경로뿐이고 나머지 /actuator는 외부 403 |
| 점검 | 자동 외부 경보 없음. 스모크 스크립트와 지표 · 로그를 사람이 본다. 수동 점검 4기준은 마지막 점검 이후의 최댓값으로 읽는다 | 시연 당일 하루 2회(Q8 · J68) |
| 가드레일 | 앱마다 헬스 지표 1개 이상과 오류율 지표 1개 이상을 둔다 | app 헬스 + http 5xx 비율 + internal_http 실패율 |

## 3. 수치와 설정 키

### 3.1 SLI/SLO 표

| SLI | SLO 목표 | 측정 창 | 계산 출처(사건 코드 또는 지표) | 근거 |
|---|---|---|---|---|
| 가용성 프로브 성공률 = 성공한 분 / 전체 분(3종 중 하나라도 실패하면 실패한 분, 계획된 배포 구간 제외) | 99.0% 이상(허용 중단 하루 14.4분, 7일 약 100.8분) | 프로브 1분 간격(제안(11/2 확정)). 11/2 시점은 확보한 일수(최소 4일) 누적이고 7일 롤링은 동결 뒤 | 외부 프로브 결과 파일 + 배포 시각 파일(앱 로그가 아님) | NFR4.8 · Q1 · J61 · RD-8 |
| 컨테이너 복구 시간 = 프로세스 종료 → 헬스체크 통과 | 5분 이내(목표 1분) | 사건마다 | 복구 시험 기록(NFR4.9) + 프로브 결과 파일의 첫 성공 분 + STARTUP_RECOVERY | NFR4.9 · Q1 |
| 계획된 배포의 서비스 중단 시간 | 60초 이내 | 배포마다 | 배포 시각 파일 + 배포 리허설의 1초 간격 health 측정(프로브 1분 간격은 60초 판정에 거칠다) | NFR4.10 · Q1 |
| 시작 성공률 = ACTIVE 도달 / (검사를 통과해 REQUESTED가 된 시작) | 95% 이상, 표본 20건 미만이면 건수로 판정. 제안(11/2 확정) | 7일 롤링 | SESSION_TRANSITION(to ACTIVE 건수 ÷ to REQUESTED 건수) | NFR4.2 · BR6.12 · NFR4.14 |
| 시작 → ACTIVE 지연 = Realtime 접수(PREPARING) → ACTIVE | p95 10초 이하(시한 20초의 절반). 제안(11/2 확정) | 7일 롤링 | SESSION_TRANSITION(같은 세션의 to PREPARING와 to ACTIVE의 ts 차) | session.device-ack-timeout · BR6.12 ② |
| FAILED + ABORTED 비율 = (FAILED + ABORTED) / 종료 상태 세션(CORE_ABANDONED 중복 제외) | 10% 이하. 제안(11/2 확정) | 7일 롤링 | SESSION_TRANSITION(to 종료 상태, origin이 CORE_ABANDONED인 FAILED 제외) | NFR4.14 · BR6.8 |
| 종료 확인률 = ENDED / (ENDED + ENDED_UNCONFIRMED) | 95% 이상. 제안(11/2 확정) | 7일 롤링 | SESSION_TRANSITION(to ENDED, to ENDED_UNCONFIRMED) | NFR4.4 · BR6.12 ③ |
| 중계 증가분 = relayIn(기기 프레임 수신 → 공급자 전송)의 p95 + relayOut(출력 구간 확정 시각 → 기기 첫 프레임 송신)의 p95 | 합 0.5초 이하. 공급자 응답 길이와 구간 확정 조용 시간(800ms)은 넣지 않는다 | 11/2 측정 창(자체 WSS 부하 + 시연 리허설) | LATENCY_TURN(relayInMs · relayOutMs) | NFR5.1 · Q3 · J63 · RD-3 |
| 말 끝 → 기기 첫 음성 재생(t0 ~ t3) | p95 2.5초 이하(목표). U1 구조의 하한 = 응답 생성 시간 + 0.8초 + 중계라 2.5초는 짧은 응답 기준이고 U3 정식 구간 분할 전에는 미달이 예상된다. 11/2 측정 뒤 조정 | 11/2 측정 창 | LATENCY_TURN(tSpeechEnd ~ tDevicePlayStart를 clockOffsetMs로 환산, providerResponseMs · quietWaitMs로 구간 분해) | NFR5.2 · Q3 · RD-3 |
| 전사 확정 → 그 행이 12 응답 items에 포함 가능해진 시각(서버 몫) | p95 0.5초 이하(목표). 화면 도달은 폴링(2초) 때문에 말 끝 뒤 p95 약 2.5초가 예상되며 1.5초를 지키려면 12 한정 폴링 단축(계약 7절 변경)이 필요하다 — 사람 결정(5절) | 11/2 측정 창 | SESSION_ITEMS_SERVED(tTranscriptFinal ~ tViewItemReady, speaker가 CHILD인 행). 폴링 몫은 tViewItemFirstServed로 따로 | NFR5.3 · RD-4 |
| 보호자 · 기기 REST 서버 처리 시간 | p95 300ms 이하 | 7일 롤링과 부하 시험 | METRIC_SNAPSHOT(restLe300Ms ÷ restTotal이 95% 이상 ⇔ p95 300ms 이하) | NFR5.5 · Q3 · J63 |
| 12 진행 조회 처리 시간(Realtime 중계 포함) | p95 500ms 이하 | 7일 롤링과 부하 시험 | METRIC_SNAPSHOT(viewLe500Ms ÷ viewTotal) + session_view_fallback_total(viewFallback) | NFR5.5 · Q3 |
| 이벤트 전달 지연 = 생성(occurredAt) → 상대 앱 PROCESSED | p95 2초 이하 | 7일 롤링 | EVENT_DELIVERY(status가 PROCESSED인 줄의 durationMs) | NFR7.5 · BR5.2 · J49 |
| 이벤트 최종 실패 건수 = Outbox FAILED + Inbox DEAD | 0건. 예외: 상대 앱 중단이 재시도 상한(약 8분)을 넘어 생긴 FAILED는 프로브 실패 구간 · 배포 시각 파일과 겹치면 '장애 중 발생'으로 따로 집계하고 위반으로 세지 않는다. 제어 이벤트 FAILED는 재기동 때 PENDING으로 돌아가 전달된다(A1) | 7일 롤링 | EVENT_DELIVERY(status FAILED · DEAD) + METRIC_SNAPSHOT(outboxFailed · inboxDead) + STARTUP_RECOVERY(rearmedControlEvents) | Q8 · BR5.3 · BR5.8 · RD-1 |
| 호스트 CPU · 메모리 사용률 | CPU는 시나리오 평균, 메모리는 최댓값으로 판정한다: CPU 평균 60% 이하 · 메모리 최댓값 70% 이하 | 부하 시나리오 중과 시연 중 | METRIC_SNAPSHOT(sysCpuAvgPct · sysMemMaxPct). 11/2 시험은 호스트 수집 스크립트(5초 간격)로 교차 확인 | NFR7.2 · Q5 · J65 · RD-12 |
| DB 연결 풀 대기 시간 | 100ms 이하(넘으면 경고 지표) | 5분 | db_pool_wait_exceeded_total + METRIC_SNAPSHOT(poolWaitMaxMs · poolWaitExceeded) | NFR7.6 · Q5 |

판정 표본 규칙(RD-13): p95 목표를 표본 100 미만으로 재는 항목은 '10회 중 9회 이상이 목표 이내'(표본이 10 ~ 99면 목표 이내 비율 90% 이상)로 판정하고, 표본이 10 미만이면 판정하지 않고 값과 건수를 적는다. 표본이 100 이상이면 p95를 쓴다.

### 3.2 경보 · 수동 점검 기준

자동 외부 경보는 두지 않는다(Q8 · J68). 사람이 시연 당일 하루 2회 스모크 스크립트(헬스 + 시작 → ACTIVE → 끝내기 한 바퀴)와 지표 · 로그를 본다. 수동 점검 4기준(아래 표의 앞 네 행)은 점검 시각의 순간값이 아니라 마지막 점검 이후의 최댓값으로 읽는다: 지표 게이지는 재시작하면 0이 되어 지난 사건을 못 보기 때문에 METRIC_SNAPSHOT(분 단위)과 로그를 마지막 점검 시각부터 훑어 최댓값을 구한다(RD-14). Operation 가드레일에 따라 모든 경보선은 해당 SLO 위반보다 앞에 둔다. 제안 경보선은 지연 · 자원 SLO 수치의 80%, 가용성은 소진 속도의 절반이다.

| 경보 항목 | 경보 기준 | 출처 | 걸리는 SLO와 여유 |
|---|---|---|---|
| WARN 이상 로그 | 마지막 점검 이후의 5분 창 중 가장 많은 창이 10건 초과 | Q8 · J68 | 시작 성공률 · FAILED+ABORTED 비율의 선행 지표(세션 하나가 실패할 때 WARN 1 ~ 3건). 보안 사건 5종도 WARN이라 여기에 센다 |
| 이벤트 PENDING 최장 나이 | 마지막 점검 이후 스냅샷(outboxOldestPendingSec)의 최댓값이 60초 초과 | Q8 · J68 | 이벤트 최종 실패 0건보다 약 7분 앞: 재시도 간격 합이 60초에 이르는 때는 6 ~ 7번째 시도, FAILED(20회 초과)는 약 481초 |
| FAILED 이벤트 | 마지막 점검 이후 EVENT_DELIVERY(FAILED · DEAD) 1건 이상 또는 스냅샷(outboxFailed · inboxDead) 최댓값 1 이상 | Q8 · J68 | SLO 0건과 같은 선이며 위 PENDING 경보가 먼저 걸린다. 제어 이벤트 FAILED는 재기동 때 PENDING으로 돌아온다(A1) |
| DB 풀 대기 | 100ms 초과가 5분 이상: 연속 5개 스냅샷에서 poolWaitMaxMs가 100 초과 | Q8 · J68 | NFR7.6 경고 지표를 지속 조건으로 읽음. 사용률 경보(아래)가 먼저 |
| 가용성 | 하루 누적 프로브 실패 7분 초과 | 제안(11/2 확정) | SLO 100.8분/7일(하루 몫 14.4분)의 절반 |
| 시작 성공률 | 하루 98% 미만(표본 10건 이상) 또는 연속 2건 실패 | 제안(11/2 확정) | SLO 95%보다 앞 |
| 지연 p95 전반 | 시작 → ACTIVE 8초 · 중계 증가분(relayIn + relayOut) 0.4초 · 말 끝 → 첫 음성 2.0초 · 전사 확정 → 12 포함 가능(서버 몫) 0.4초 · REST 240ms · 12 조회 400ms · 이벤트 전달 1.6초 초과 | 제안(11/2 확정) | 각 SLO 수치의 80% |
| 호스트 자원 · 풀 사용률 | CPU 평균 50% · 메모리 최댓값 60% · 풀 사용 8 / 10 이상이 5분 지속 | 제안(11/2 확정) | CPU 60% · 메모리 70% · 풀 대기 100ms보다 앞 |

### 3.3 지표

| 지표(제안 이름) | 종류 | 라벨 | 요구 | 앱 |
|---|---|---|---|---|
| session_ended | 카운터 | status · end_reason · failure_code · phase(START 또는 RUNNING) · origin(REALTIME · CORE_LOCAL · CORE_ABANDONED) | NFR4.14 | Realtime, Core 로컬 FAILED는 Core |
| session_activated | 카운터 | 없음 | 시작 성공률 | Realtime |
| session_start_to_active_seconds | 히스토그램 | 없음 | 시작 → ACTIVE 지연 | Realtime |
| timeout_fired | 카운터 | type(preparing · device_ack · closing_confirm · disconnect · device_status_stale) | NFR6.5 | Core(preparing · device_status_stale), Realtime(나머지) |
| internal_http_requests · internal_http_seconds | 카운터 · 타이머 | target(session_start · session_end · session_view · verify_token · device_status · event_publish) · outcome(ok · timeout · conn_error · http_4xx · http_5xx) | NFR4.15 | 호출하는 쪽 양 앱 |
| device_connections_open · device_connection_state | 게이지 | state(OFFLINE · CONNECTED · DISCONNECTED · UNRESPONSIVE) | NFR4.15 · NFR7.8 | Realtime |
| outbox_pending · outbox_oldest_pending_age_seconds · outbox_failed · inbox_dead | 게이지 | direction(core_to_realtime · realtime_to_core) | NFR7.8 · Q8 | 양 앱 |
| event_delivery_seconds | 히스토그램 | event_type | NFR7.5 | 받는 앱(PROCESSED 시점의 now − occurredAt) |
| relay_in_seconds · relay_out_seconds · turn_first_audio_seconds | 히스토그램 | 없음 | NFR5.1 · NFR5.2 · NFR5.4 | Realtime(턴 로그와 같은 값) |
| view_item_ready_seconds | 히스토그램 | speaker(child · ai) | NFR5.3 · NFR5.4 | Realtime(전사 확정 → 12 응답에 포함 가능, SESSION_ITEMS_SERVED와 같은 값) |
| session_view_fallback_total | 카운터 | 없음 | NFR5.5 · NFR4.12 | Core(12 조회가 Realtime 실패로 사본으로 답한 횟수, BR6.15) |
| db_pool_wait_exceeded_total | 카운터 | 없음 | NFR7.6 | 양 앱(풀 대기가 mujoong.metrics.db-pool-wait-warn을 넘은 횟수) |
| security_event_total | 카운터 | type(auth_failed · rate_limited · origin_denied · dev_login_blocked · internal_token_failed) | NFR9.14 | 양 앱(3.4의 보안 사건 코드와 1:1) |
| http_server_requests(프레임워크 기본) | 히스토그램 | method · uri 템플릿 · status(원시 경로 · id 금지) | NFR5.5 · 오류율 | 양 앱 |
| health · jvm · hikaricp(프레임워크 기본) | 게이지 | liveness · readiness · 풀 사용 · 풀 대기 · 힙 · GC · 스레드 | NFR4.15 · NFR7.8 | 양 앱 |

라벨 위생: 라벨 값은 열거값만 쓴다. id · 전사 · 토큰 · 코드는 라벨이 될 수 없다(NFR9.8). 보안 사건 카운터(NFR9.14)의 type 값은 3.4의 보안 사건 코드에서 SECURITY_ 접두를 떼고 소문자로 쓴 것이다. 위 표의 게이지 · 카운터 중 SLI에 쓰는 값은 3.4의 METRIC_SNAPSHOT이 분 단위로 로그에 옮긴다.

### 3.4 로그 필드

| 필드 | 형식 | 내용 |
|---|---|---|
| ts | ISO 8601 UTC(BR8.10) | 시각 |
| level | ERROR · WARN · INFO · DEBUG | 수준. WARN 이상이 3.2의 첫 경보 입력이다 |
| traceId | 문자열 | 요청 상관 id(NFR5.7). WSS에서 시작된 사건은 Realtime이 세션 단위로 만든다 |
| component | 열거 | core-api 또는 realtime과 컴포넌트 이름(DeviceRegistry · CoreMessaging · SessionLifecycle · DeviceGateway · VoiceRelay · OutputGate 등) |
| event | 열거 | 사건 코드(아래 표) |
| ownerHash | 16진 12자 | 소유 id(세션 · 기기 · 보호자)의 HMAC-SHA-256 앞 12자. 소유 id가 없는 사건(METRIC_SNAPSHOT 등)은 null. 제안(3.3 확정) |
| attrs | 객체 | 사건 코드별 허용 키 표(아래)에 있는 키만. 값은 숫자 · 열거값 · UTC 시각만이고 자유 문자열 금지. 전사 · 원음 · 토큰은 attrs에도 쓰지 않는다 |

| 사건 코드 | 수준 | 허용 attrs | 요구 |
|---|---|---|---|
| SESSION_TRANSITION | INFO(FAILED · ABORTED · ENDED_UNCONFIRMED는 WARN) | from(Core의 REQUESTED 생성은 NONE) · to · statusVersion · endReason · failureCode · confirmed · phase · origin | NFR4.14 |
| TIMEOUT_FIRED | WARN | type · thresholdMs · elapsedMs | NFR6.5 |
| LATENCY_TURN | INFO | turnSeq · tSpeechEnd · tSpeechEndSource · tProviderFirstOut · tSegmentConfirmed · tGatePass · tFirstFrameSent · tDevicePlayStart(기기 관측) · tDevicePlayStartRecv(서버 수신) · clockOffsetMs · rttMs · tTranscriptFinal · relayInMs · relayOutMs · providerResponseMs · quietWaitMs | NFR5.4 |
| SESSION_ITEMS_SERVED | INFO | seq · speaker · tTranscriptFinal · tViewItemReady · tViewItemFirstServed | NFR5.4 · NFR5.3 |
| PLAYBACK_MARK_STOPPED | INFO | stopCause · tDeviceObserved · tServerRecv · clockOffsetMs · rttMs | NFR5.8 |
| DEVICE_CONNECTION | INFO(DISCONNECTED · UNRESPONSIVE는 WARN) | from · to · closeCode | NFR4.15 |
| INTERNAL_HTTP | INFO(실패는 WARN) | target · outcome · statusCode · durationMs · attempt | NFR4.15 |
| EVENT_DELIVERY | INFO(FAILED · DEAD · EVENT_CONFLICT는 WARN) | eventId(UUID 형식 값, BR5.13) · eventType · status · retryCount · durationMs(PROCESSED는 occurredAt → PROCESSED) | NFR7.8 · BR5.13 |
| STARTUP_RECOVERY | INFO | recoveredSessions · reopenedInboxRows · resetConnections · rearmedControlEvents | NFR4.7 |
| METRIC_SNAPSHOT | INFO | outboxPending · outboxOldestPendingSec · outboxFailed · inboxDead · poolActive · poolWaitMaxMs · poolWaitExceeded · openConnections · restTotal · restLe300Ms · viewTotal · viewLe500Ms · viewFallback · sysCpuAvgPct · sysMemMaxPct · heapUsedPct | NFR7.8 · SLO 3.1 · RD-14 |
| SECURITY_AUTH_FAILED | WARN | kind(GUARDIAN_SESSION · DEVICE_TOKEN · REGISTER_CODE) · statusCode | NFR9.14 |
| SECURITY_RATE_LIMITED | WARN | limiter(BOOTSTRAP_IP · REGISTER_FAILURE) · statusCode | NFR9.14 |
| SECURITY_ORIGIN_DENIED | WARN | channel(WSS · REST) · statusCode · closeCode | NFR9.14 |
| SECURITY_DEV_LOGIN_BLOCKED | WARN | profile(DEV · DEMO · PROD) · statusCode | NFR9.14 |
| SECURITY_INTERNAL_TOKEN_FAILED | WARN | direction(CORE_TO_REALTIME · REALTIME_TO_CORE) · statusCode | NFR9.14 |

LATENCY_TURN과 SESSION_ITEMS_SERVED 키의 뜻(NFR5 판정에 쓰는 정의):

| 키 | 뜻 | 쓰는 판정 |
|---|---|---|
| tSpeechEnd | t0 말 끝 감지 시각(서버 시계). 공급자가 입력 종료 이벤트를 주면 그 수신 시각, 아니면 아래 대체 정의 | NFR5.2 |
| tSpeechEndSource | t0의 출처 열거: PROVIDER_EVENT(공급자 입력 종료 이벤트) · LAST_INPUT_FRAME(마지막 입력 프레임 수신 시각) · INPUT_TRANSCRIPT_FINAL(입력 전사 확정 시각). 반드시 기록하며 t0를 '미관측'으로 두지 않는다 | NFR5.2 |
| tProviderFirstOut | t1 공급자 첫 출력 수신 시각 | NFR5.2 |
| tSegmentConfirmed | 출력 구간 확정 시각(출력 전사가 800ms 조용해지거나 아이 입력이 다시 시작된 때) | NFR5.1 relayOut의 시작 |
| tGatePass | t2 게이트 통과 시각 | NFR5.2 |
| tFirstFrameSent | 기기로 첫 오디오 프레임을 보낸 시각 | NFR5.1 relayOut의 끝 |
| tDevicePlayStart | t3 기기가 관측한 재생 시작(PLAYBACK_MARK STARTED의 observedAt, 기기 시계) | NFR5.2 |
| tDevicePlayStartRecv | 서버가 그 PLAYBACK_MARK를 받은 시각(서버 시계, 환산 교차 확인용) | NFR5.2 |
| clockOffsetMs · rttMs | 기기 시계 환산 입력. clockOffsetMs = 기기 시계 − 서버 시계(HELLO_OK · HEARTBEAT_ACK의 serverTime과 왕복 지연으로 가장 최근에 구한 값), rttMs = 왕복 지연. t3의 서버 시계 값 = tDevicePlayStart − clockOffsetMs이고 오차는 ± rttMs ÷ 2 | NFR5.2 |
| relayInMs | 그 턴에 처리한 입력 프레임의 relayIn(기기 프레임 수신 → 공급자 전송) 지연의 p95(턴 단위 값). 프레임 전체 분포는 relay_in_seconds 히스토그램으로 본다 | NFR5.1 |
| relayOutMs | tFirstFrameSent − tSegmentConfirmed(구간 확정 → 게이트 처리 → 기기 첫 프레임 송신) | NFR5.1 |
| providerResponseMs | 공급자 응답 길이: 첫 출력 수신(t1) → 구간 확정 직전 마지막 출력 수신 | NFR5.2 구간 |
| quietWaitMs | 구간 확정 조용 시간: 마지막 출력 수신 → 구간 확정(설정값 800ms, 아이 입력이 다시 시작돼 확정되면 더 짧다) | NFR5.2 구간 |
| tTranscriptFinal | 아이 전사 확정(FINAL) 시각. SESSION_ITEMS_SERVED에서는 그 행의 확정 시각 | NFR5.3 |
| speaker | 행의 화자 열거 CHILD · AI(BR7.5) | NFR5.3 표본 구분 |
| tViewItemReady | 그 행이 12 응답 items에 포함 가능해진 시각(FINAL 전환과 approved_for_display true를 커밋한 시각, BR7.5) | NFR5.3 서버 몫의 끝 |
| tViewItemFirstServed | 그 행이 12 응답 items에 처음 실린 시각(Realtime view 처리기가 행마다 한 번 기록, 폴링 몫 = 이 값 − tViewItemReady) | NFR5.3 폴링 몫 |

금지 필드(최상위 · attrs 모두): 전사 원문, 원음, 토큰, 쿠키 값, 등록 코드(NFR9.8의 '앞 4자만'은 허용 키 표에 codePrefix가 더해질 때만 쓸 수 있고 U1 표에는 없다), 보호자 입력 원문, 비밀, 이메일.

### 3.5 설정 키

설정 키 이름은 reliability-requirements.md 3.2 레지스트리를 따른다. 이 문서에서 새로 만든 키는 없다.

| 항목 | 값 | 출처 | 설정 키 |
|---|---|---|---|
| 로그 형식 | JSON, 최상위 여섯 필드 + attrs(사건 코드별 허용 키 표) | NFR9.15 · J67 · RD-6 | 설정 키 아님(로그 설정 파일에 고정) |
| ownerHash 비밀 | 서버 환경 변수(값은 문서에 두지 않음) | NFR9.10 | mujoong.observability.owner-hash-secret (환경 변수, 레지스트리) |
| 지연 구간 로그 기록 비율 | 100%(동시 세션 2) | NFR5.4 | mujoong.observability.latency-log-sample-rate (레지스트리) |
| 지표 스냅샷 로그 주기 | 60초 | RD-14 | mujoong.observability.metric-snapshot-interval (레지스트리) |
| 풀 대기 경고 기준 | 100ms | NFR7.6 · J68 | mujoong.metrics.db-pool-wait-warn (레지스트리) |
| 메트릭 엔드포인트 노출 | 내부망 · 호스트 loopback만, 외부 403, 프로브용 health 한 경로만 외부 허용 | NFR4.15 | 설정 키 아님(Nginx 설정과 management.* 표준 키, 3.4) |
| 외부 프로브 | 호스트 밖에서 1분마다 Core health · /device 정적 페이지 · WSS 핸드셰이크 3종. 실행 위치는 5절 | 제안(11/2 확정) · RD-8 | 프로브 스크립트 인자(설정 키 아님) |
| 로그 보관 | 7일(백업 보관과 같음). 구현은 Compose json-file의 max-size · max-file로 7일분 추정 용량을 상한으로 둔다(대안: logrotate 7일). 추정: METRIC_SNAPSHOT 2앱 × 1,440줄/일 × 약 0.7KB ≈ 2MB/일에 턴 · 이벤트 · 접속 로그 수 MB/일이라 7일 합 수십 MB, 상한은 그 두 배 안팎(예: 컨테이너당 max-size 20m × max-file 5). 크기 상한은 기간을 보장하지 않으므로 추정을 넘으면 기간이 짧아진다 | 제안(11/2 확정) · RD-8 | Compose logging 옵션(3.4) |
| 수동 점검 주기 | 시연 당일 하루 2회 | Q8 · J68 | 운영 점검표(설정 키 아님) |

## 4. 시험 · 검증 방법

| ID | 시험 | 단계 |
|---|---|---|
| NFR4.14 | 종료 상태 4개와 Core 로컬 FAILED를 각각 만든 뒤 session_ended 라벨(status · end_reason · failure_code · origin)과 SESSION_TRANSITION 로그를 확인한다. CORE_ABANDONED 닫기는 이중 집계되지 않음 | 3.5 구현 |
| NFR4.15 | 내부망에서 헬스 · 연결 수 · 상태 분포 · internal_http 지표와 session_view_fallback_total · db_pool_wait_exceeded_total · security_event_total 존재 확인, Nginx 경유 외부 요청은 /actuator 메트릭 403(health 한 경로만 200), Realtime 정지 시 internal_http 실패율 상승 | Build and Test |
| NFR5.4 | 자체 WSS 부하 스크립트로 한 턴을 돌려 LATENCY_TURN 한 줄과 12 폴링 뒤 SESSION_ITEMS_SERVED 줄에 새 키(tSegmentConfirmed · tSpeechEndSource · tFirstFrameSent · clockOffsetMs · rttMs · providerResponseMs · quietWaitMs · tViewItemReady · tViewItemFirstServed)가 모두 있는지 확인. 한 턴 로그만으로 NFR5.1(relayIn · relayOut) · NFR5.2(t0 ~ t3) · NFR5.3(서버 몫)을 계산하는 스크립트가 도는지 확인하고 미관측 비율이 0%가 아니면 사유를 표시한다. 가짜 공급자가 입력 종료 이벤트를 주지 않을 때 tSpeechEndSource가 LAST_INPUT_FRAME 또는 INPUT_TRANSCRIPT_FINAL로 남는지 확인. 실제 수치는 11/2에 기록 | Build and Test |
| NFR5.8 | 가짜 기기가 PLAYBACK_MARK(STOPPED, stopCause LOCAL_VOICE_ACTIVITY)를 보내면 PLAYBACK_MARK_STOPPED 한 줄에 stopCause · tDeviceObserved(observedAt) · tServerRecv가 남는다(0.5초 판정 자체는 U3) | 3.5 구현 |
| NFR6.5 | 시한 5종을 Clock 주입으로 발동시켜 timeout_fired{type} 증가와 TIMEOUT_FIRED 로그(개인정보 없음)를 확인. stale은 첫 판정만 증가 | 3.5 구현 |
| NFR9.15 | 로그 한 줄마다 JSON 파싱과 필드 검증(최상위는 여섯 필드뿐, attrs 키는 사건 코드별 허용 키 표 안, attrs 값은 숫자 · 열거값 · UTC 시각이고 eventId만 UUID 형식), 시드 전사 · 토큰 · 쿠키 문자열 grep 0건(NFR9.8). 사건 코드 15종 모두 주입해 확인 | 3.5 구현 |
| NFR9.14 보조 | 보안 사건 5종(잘못된 토큰 · 한도 초과 · 허용 밖 Origin · /api/dev/login 요청 · 내부 토큰 불일치)을 주입하면 사건 코드 한 줄과 security_event_total{type} 증가가 있고 attrs 키가 허용 표 안이다 | 3.5 구현 |
| METRIC_SNAPSHOT | 60초마다 줄이 남고 숫자 키만 있으며, 앱을 재시작해도 이전 줄이 로그 파일에 남아 마지막 점검 이후 최댓값을 구하는 스크립트가 도는지 확인 | 3.5 구현 |
| (SLO 3.1 표 전체) | 계산 출처 열의 사건 코드 · 스냅샷 · 프로브 파일로 SLI를 계산하는 스크립트가 모든 행의 값을 채우고 경보선과 비교한다. 표본 규칙(RD-13)을 적용하고 목표 조정이 필요하면 해당 행만 고친다 | 11/2 측정 |

## 5. 열린 질문과 가정

| 구분 | 내용 | 확정 단계 |
|---|---|---|
| 가정 J67 | 구조화 JSON 로그 + Micrometer 지표 + traceId 전파. 분산 추적 수집기 · 중앙 로그 수집은 두지 않는다 | 3.3 |
| 가정 J68 | 자동 외부 경보 없음. 수동 점검 기준 4개(WARN 10건 / 5분, PENDING 60초, FAILED 1건, 풀 대기 100ms 5분)를 하루 2회 본다. 각 기준은 마지막 점검 이후 최댓값으로 읽는다(RD-14) | 후속 열린 질문 |
| 가정 J61 · J63 · J65 | 가용성 · 지연 · 용량 수치는 시작값이고 11/2 측정 뒤 조정한다 | 11/2 측정 |
| 제안 수치 | 시작 성공률 95% · 시작 → ACTIVE p95 10초 · FAILED+ABORTED 10% · 종료 확인률 95% · 프로브 1분 · 경보선 80% 규칙 · 로그 보관 7일과 용량 추정 · ownerHash 12자 · 스냅샷 60초는 질문 파일에 없어 임의로 둔 값이다 | 11/2 확정 |
| 3.2 재점검 결정 RD-3 | NFR5.1 재정의: 중계 증가분 = relayIn(기기 프레임 수신 → 공급자 전송) + relayOut(출력 구간 확정 시각 → 기기 첫 프레임 송신)의 p95 합 0.5초 이내. 공급자 응답 길이(providerResponseMs)와 구간 확정 조용 시간(quietWaitMs 800ms)은 NFR5.2의 별도 구간으로 기록한다. NFR5.2는 'U1 구조에서 첫 소리 지연의 하한 = 응답 생성 시간 + 0.8초 + 중계'이고 2.5초는 짧은 응답 기준이라 U3 정식 구간 분할 전에는 미달이 예상된다(목표는 11/2 측정 뒤 조정). relayInMs는 턴 단위 p95라 NFR5.1의 relayIn p95는 그 분포의 p95로 계산하는 근사다 | 11/2 측정 |
| 3.2 재점검 결정 RD-4 | NFR5.3은 서버 몫만 요구한다: 전사 확정 시각 → 그 행이 12 응답 items에 포함 가능해진 시각 p95 0.5초 이내. 화면 도달은 폴링(parent.poll-interval 2초) 때문에 말 끝 뒤 p95 약 2.5초가 예상된다. 1.5초를 지키려면 12 한정 폴링 단축이 필요하고 이는 계약 7절 변경 안건이다 | 사람 결정 |
| 3.2 재점검 결정 RD-5 | NFR5.4 보강: 로그에 tSegmentConfirmed · tViewItemFirstServed(사건 코드 SESSION_ITEMS_SERVED) · tSpeechEndSource · clockOffsetMs · rttMs를 더했다. 완료 기준은 '한 턴 로그만으로 NFR5.1 · NFR5.2 · NFR5.3(서버 몫)이 모두 계산되는 스크립트가 돈다'다. performance 열림 1 · 5는 이 결정으로 닫는다 | 3.5 구현 |
| 3.2 재점검 결정 RD-6 | NFR9.15 개정: 최상위 여섯 필드(ts · level · traceId · component · event · ownerHash) + attrs(사건 코드별 허용 키 표 — 숫자 · 열거값 · UTC 시각만). 전사 · 원음 · 토큰은 attrs에도 불가. 질문 파일 Q7 답과 이 문서 안의 최상위 필드 표현을 모두 같은 말로 맞췄다. 사람이 확인할 점: BR5.13이 eventId를 남기라고 해서 EVENT_DELIVERY의 eventId(UUID 형식)를 이 규칙의 유일한 예외로 두었다 | 사람 확인 |
| 3.2 재점검 결정 RD-12 | 부하 시험: 30쌍은 부하 시험 전용 시드(A6)로 만들고 NFR7.1의 1차 판정은 NFR5.1(relayIn + relayOut)과 (t3−t2)의 증가율이며 종단 값은 참고다. NFR7.2 호스트 판정은 'CPU는 시나리오 평균, 메모리는 최댓값으로 판정한다'로 scalability와 같은 문구를 쓴다(5분 평균 아님) | 11/2 측정 |
| 3.2 재점검 결정 RD-13 | 표본 규칙: p95 목표를 표본 100 미만으로 재는 항목은 '10회 중 9회 이상이 목표 이내'(그 이상 표본이 있으면 p95)로 판정한다(3.1 아래 문단) | 11/2 측정 |
| 3.2 재점검 결정 RD-14 | 보안 사건 코드 5종(3.4)과 지표 session_view_fallback_total · db_pool_wait_exceeded_total · security_event_total{type}(3.3)을 더했다. SLO 표에 '계산 출처' 열을 넣고 분 단위 지표 스냅샷 사건 METRIC_SNAPSHOT을 두었다. 수동 점검 4기준은 마지막 점검 이후 최댓값으로 읽는다. NFR5.8의 U1 몫은 PLAYBACK_MARK_STOPPED 로그(3.4)다 | 3.3 |
| FD 보강 후보 A3 · A4 | 이벤트 봉투 traceId를 위한 Outbox/Inbox trace_id 열(A3)과 모든 행의 guardian_id 채움(A4)은 FD 보강 후보다. 정본은 reliability-requirements.md 5절이다 | 3.5 |
| 해석 | NFR5.3 판정 표본은 speaker가 CHILD인 행이다: 아이 전사는 BR7.5상 확정 즉시 승인되지만 AI 행은 구간 확정 대기(800ms)와 게이트 통과를 거쳐 승인되므로 서버 몫 0.5초에 섞으면 안 된다. AI 행의 지연은 별도 표시한다 | 사람 확인 |
| 해석 | 지표는 프로세스 메모리 값이라 재시작하면 0이 된다. SLI 계산의 정본을 로그로 두고 값은 METRIC_SNAPSHOT으로 옮긴다 | 3.3 |
| 해석 | sysCpuAvgPct · sysMemMaxPct는 앱이 읽는 시스템 값이라 컨테이너 경계에서 호스트 전체와 다를 수 있다. 11/2 시험은 호스트 수집 스크립트 값을 정본으로 하고 스냅샷은 시연 중 추세에 쓴다 | 3.4 |
| 해석 | stale은 읽을 때 계산되므로 timeout_fired{device_status_stale}는 '신선 → 확인 불가' 첫 판정만 센다(재시작하면 한 번 더 셀 수 있음) | 3.3 |
| 사람이 정할 것 | 외부 알림(Slack 등) 연동 여부 — 계정 · 비밀이 이번 범위에 없다 | 후속 단계 |
| 사람이 정할 것 | 외부 프로브 실행 위치(팀 PC의 스케줄 작업 또는 무료 외부 pinger). 호스트 cron은 호스트 · 네트워크 전체 중단을 보지 못한다. 상세는 reliability-requirements.md 5절 | 3.4 |
| 사람이 정할 것 | 12 한정 폴링 단축 여부(RD-4, 계약 7절 변경 안건)와 NFR5.3 1.5초를 어느 몫에 적용할지 | 사람 결정 |
| 계약 편차 | 이벤트 봉투(6절)에는 traceId가 없다. 선택 필드로 더하는 것은 안전한 추가이며 J59 편차 목록의 열네 번째로 올려야 한다. 내부 HTTP 헤더 이름은 3.3이 정한다(후보 X-Trace-Id 또는 W3C traceparent) | 3.3 · 3.5 |
| 열린 질문 | 프로브용 health 한 경로만 외부에 열고 나머지 /actuator는 외부 403으로 하는 해석(NFR4.8과 NFR4.15의 정합)의 Nginx 설정 | 3.4 |
