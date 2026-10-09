# 성능 요구사항 — U1 u1-walking-skeleton

> 단계: NFR Requirements (3.2) · 문서: performance-requirements · 깊이 Standard · 날짜 2026-10-09 (클라우드 세션)
> 입력: `nfr-requirements-questions.md`(Q3 · Q4 · Q7 · Q9), `functional-design/{functional-spec,rules,entities}.md`, `requirements.md`(NFR5), `contract-summary.md`(5 · 6 · 7절), ADR-006 · ADR-010, `first-bolt.md` 6.1.
> 대리 판단: J61 ~ J72는 사람 지시와 `docs/cloud_handoff.md` 2절에 따라 팀장(준하)의 대리가 정한 가정이다. 3.2 재점검에서 정한 결정은 5절에 RD-n으로 적었다(RD 번호는 재점검 지시의 결정 번호와 같다). 수치는 시연 규모의 시작값이며 11/2 측정(OQ10) 뒤 이 문서의 해당 NFRx.y 행만 고친다.

## 1. 요구사항

| ID | 우선순위 | 요구 | 측정 · 완료 기준 | 근거 |
|---|---|---|---|---|
| NFR5.1 | Should | 서버 중계가 더하는 지연은 relayIn(기기 음성 프레임 수신 → 공급자 전송)과 relayOut(출력 구간 확정 시각 → 기기 첫 프레임 송신)의 p95를 더한 값이 0.5초 이내다. 공급자 응답 길이(provider_response_duration)와 구간 확정 조용 시간(quiet_wait, 800ms)은 중계 증가분에 넣지 않고 NFR5.2의 별도 구간으로 기록한다. | 한 턴 로그(NFR5.4)의 relayInMs · relayOutMs만으로 계산한 p95 합 ≤ 0.5초(판정 표본은 4.2). 11/2 측정 뒤 목표를 조정해도 로그는 남긴다 | NFR5 · TS-N01 · J63 · RD-3 |
| NFR5.2 | Should | 아이 말 끝(t0) → 기기 첫 음성 재생(t3) p95 2.5초 이내를 목표로 하고, U1은 이 값과 구간별 분해(공급자 응답 길이 · 구간 확정 조용 시간 · 게이트 · 기기 재생 시작)를 잴 수 있게 시각을 모두 남긴다. U1 구조에서 첫 소리 지연의 하한은 '응답 생성 시간 + 0.8초 + 중계'이므로 2.5초는 짧은 응답 기준이며, U3의 정식 구간 분할 전에는 미달이 예상된다(목표는 11/2 측정 뒤 조정). | 한 턴 로그(NFR5.4)에 t0(tSpeechEndSource와 함께) · t1 · tSegmentConfirmed · t2 · t3이 세션 단위로 남아 t1−t0 · provider_response_duration · quiet_wait · 게이트 처리 · t3−t2가 계산된다. 목표 미달은 실패가 아니라 조정 대상 | NFR5 · TS-N01 · OQ10 · RD-3 |
| NFR5.3 | Should | 서버 몫만 요구한다: 전사 확정 시각 → 그 행이 12 응답 items에 포함될 수 있게 된 시각의 p95가 0.5초 이내다. 화면 도달(말 끝 → 12 말풍선)은 폴링 때문에 서버 몫에 최대 2초가 더해져 p95 약 2.5초가 예상되며, 1.5초를 지키려면 12 한정 폴링 단축(계약 7절 변경)이 필요해 5절 열림 2로 둔다(완성은 U4). | 한 턴 로그의 tTranscriptFinal → tViewItemFirstServed(사건 SESSION_ITEMS_SERVED) 차이의 p95. 폴링 대기를 뺀 서버 몫으로 재는 방법은 4.2. 목표는 11/2 측정 뒤 조정 | NFR5 · TS-N02 · RD-4 |
| NFR5.5 | Should | 보호자 · 기기 REST의 서버 처리 시간은 p95 300ms 이내, 12 진행 조회(Realtime 중계 포함)는 p95 500ms 이내다(시연 규모: 동시 세션 2 · 등록 기기 30). | 요청별 서버 처리 시간 메트릭의 p95. 내부 HTTP 호출 제한(J26)을 넘는 요청은 실패로 센다. 30쌍 부하는 부하 시험 전용 시드로 만든다(4.1) | NFR5 · J63 · RD-12 |
| NFR5.6 | Should | 첫 화면 속도: /device D0는 코드 표시까지 p95 3초 이내, /parent 04는 Android Chrome 중급기 4G에서 LCP 2.5초 이내, T19 「켜기」 → 대기(HELLO_OK)는 p95 2초 이내다. | Lighthouse/실측 로그. 판정은 표본이 100 미만이면 10회 중 9회 이상이 목표 이내(그 이상 표본이 있으면 p95). 11/2 측정 항목이며 미달 시 목표 조정 | NFR5 · NFR10 · J64 · RD-13 |
| NFR5.7 | Must | 요청 상관 식별자(traceId)가 보호자 REST → Core → 내부 HTTP → Realtime → 이벤트 봉투까지 이어져 지연 구간을 한 요청으로 묶어 볼 수 있다. | 내부 HTTP 헤더와 이벤트 봉투에 traceId가 있고 로그에서 한 시작 요청을 Core · Realtime 양쪽에서 같은 id로 찾는다 | NFR5 · ADR-010 · J67 |
| NFR5.8 | Should | 끼어들기 재생 중단 0.5초(TS-N04)는 U3가 완성한다. U1은 PLAYBACK_MARK(STOPPED, 원인 LOCAL_VOICE_ACTIVITY)의 observedAt을 로그로 남긴다. | 가짜 기기가 보낸 PLAYBACK_MARK(STOPPED, LOCAL_VOICE_ACTIVITY)의 observedAt이 세션 단위 로그에 남는다. 0.5초 판정은 U3 | NFR5 · TS-N04 · RD-7 |

NFR5.4(지연 측정 로그, Must)는 `observability-requirements.md` 소관이고 로그의 필드 · 이름은 그쪽이 정한다. 위 요구의 측정은 그 로그가 다음을 갖는 것을 전제로 한다: LATENCY_TURN의 tSegmentConfirmed · tSpeechEndSource · clockOffsetMs · rttMs, 사건 코드 SESSION_ITEMS_SERVED의 tViewItemFirstServed(RD-5). NFR5.4의 완료 기준은 '한 턴 로그만으로 NFR5.1(relayIn · relayOut) · NFR5.2(t0 ~ t3) · NFR5.3(서버 몫)이 모두 계산되는 스크립트가 돈다'이다. 이 문서는 무엇을 어떻게 재는지(4절)만 정한다.

## 2. 배경과 해석

U1은 지연 목표를 달성하는 단계가 아니라 잴 수 있게 만드는 단계다. 목표 미달은 실패가 아니라 조정 대상이다.

| ID | U1에서의 뜻 | 걸리는 곳 | 후속 유닛 몫 |
|---|---|---|---|
| NFR5.1 | 중계 증가분은 서버가 더하는 지연만이다. relayIn은 기기 프레임 수신 → 공급자 전송, relayOut은 구간 확정 시각(tSegmentConfirmed) → 게이트 처리 → 기기 첫 프레임 송신이다. BR7.3의 출력 구간은 한 응답 전체이고 확정 전 음성은 서버 버퍼에만 있어 첫 소리는 공급자 응답 전체 도착 + 800ms 조용 시간 뒤에야 기기로 나간다. 이 두 시간은 서버가 더하는 지연이 아니라 구조상 대기이므로 provider_response_duration(첫 출력 → 구간 확정 직전 마지막 출력)과 quiet_wait(마지막 출력 → 구간 확정)로 따로 기록해 NFR5.2에서 본다. | BR7.1 · BR7.3 · BR7.4 | U3: 정식 구간 분할로 대기를 줄임 |
| NFR5.2 | 세션 단위로 t0 말 끝 · t1 공급자 첫 출력 · tSegmentConfirmed 구간 확정 · t2 게이트 통과 · t3 기기 재생 시작을 남긴다. t0의 출처는 tSpeechEndSource(공급자 이벤트 · 마지막 입력 프레임 · 입력 전사 확정)로 항상 기록한다. t3은 PLAYBACK_MARK(STARTED)의 기기 관측 시각을 clockOffsetMs · rttMs로 서버 시계에 환산한다. 말 끝부터 응답 마지막 출력까지가 1.2초를 넘으면(1.2 + 0.8 + 중계 0.5) 2.5초를 넘으므로 U1 구조에서 이 목표는 짧은 응답에서만 지켜진다. 출력이 없는 턴 등 시각이 생기지 않는 경우는 '미관측'과 사유를 남기고 시각을 만들지 않는다. | BR7.1 ~ BR7.4 · BR9.6, 계약 4.2절 | U3: 재생 보고 정식 처리 · 구간 분할 |
| NFR5.3 | 서버 몫은 전사 확정 → 그 전사 행이 12 응답에 실릴 수 있게 된 시각(전사 행 커밋, AI 말은 approved_for_display가 true인 시각)이다. 공급자가 전사를 확정하기까지(말 끝 → 전사 확정)는 서버 몫이 아니라 로그에서 따로 계산한다. 폴링 몫은 12 요청 간격(parent.poll-interval 2초)이라 화면 도달에 평균 1초 · 최대 2초가 더해진다. tViewItemFirstServed는 첫 12 응답 시각이어서 폴링 대기가 섞이므로 서버 몫은 측정 전용 빠른 폴러로 잰다(4.2). | BR6.15 · BR7.5 | U4: 확정 전 조각 재전송 · 말풍선 완성 · 화면 도달 계측 |
| NFR5.5 | 서버 처리 시간(요청 수신 → 응답 완료, 네트워크 제외). 12 조회는 Core의 Realtime 진행 조회(제한 1초) 시간을 포함한다. 내부 호출 제한(시작 3초 · 끝내기 2초 · 조회 1초)을 넘은 요청은 표본에서 빼지 않고 실패로 세며, 12가 BR6.15대로 사본으로 답한 횟수는 '사본 폴백'으로 따로 센다. 정적 파일 · 헬스 · 메트릭 · WSS는 제외한다. 30쌍 부하는 계정당 기기 1대 규칙(BR2.4)과 IP당 코드 발급 한도(BR1.6) 때문에 U1의 일반 등록 경로로 만들 수 없어 부하 시험 전용 시드(FD 보강 A6)가 필요하다. | BR6.1 ~ BR6.7 · BR6.9 · BR6.15 · BR8.6 | U2 ~ U6: 자기 엔드포인트가 같은 메트릭에 오름 |
| NFR5.6 | D0: /device 열기 → BR9.1이 끝나 코드가 보일 때까지(와이파이). 04: /parent 04(기기 구역)의 LCP. 켜기: 마이크 허용이 끝난 시각 → HELLO_OK(사람이 허용창에서 고르는 시간은 뺀다). 켜기에는 WSS 업그레이드 · Origin 확인 · verify-token(제한 1초) · HELLO가 든다. 실기기가 1대뿐이라 표본이 100 미만이므로 10회 중 9회 규칙으로 판정한다. | BR9.1 · BR9.2 · BR9.5 · BR3.4 · BR3.6 | 각 유닛: 자기 화면 LCP |
| NFR5.7 | traceId(BR8.6)를 REST 수신 때 정하고, 내부 HTTP 헤더로 넘기고, 이벤트 봉투(선택 필드, 더하기만 안전 — 계약 10절)에 싣고, 수신 처리기 로그에 쓴다. 봉투는 전달 때 행에서 다시 만들므로 Outbox/Inbox 표에 trace_id 열이 필요한데 entities.md에는 없다(FD 보강 후보 A3, 5절). 바깥에서 온 값은 믿지 않고 검사하거나 새로 만든다. | BR8.6 · BR5.4 · BR5.13 · BR6.5 ~ BR6.7 | WSS 음성 턴의 묶음 키: observability 문서 |
| NFR5.8 | 끼어들기는 기기가 로컬 음성 활동 200ms를 감지해 바로 재생을 멈추고 PLAYBACK_MARK(STOPPED, stopCause LOCAL_VOICE_ACTIVITY)를 보내는 U3 흐름이다. U1은 그 흐름을 완성하지 않고, 도착한 메시지의 observedAt을 NFR5.4 로그에 남기는 자리만 둔다. | 계약 4.2절(PLAYBACK_MARK · 끼어들기) · 계약 7절 | U3: 끼어들기 0.5초 완성과 판정(TS-N04) |

## 3. 수치와 설정 키

`mujoong.*` 키 이름의 단일 원천은 `reliability-requirements.md` 3.2 레지스트리다. 이 문서는 키를 새로 만들지 않고 그 이름을 쓴다(RD-2).

| 항목 | 값 | 출처 | 설정 키 |
|---|---|---|---|
| 중계 증가분(relayIn p95 + relayOut p95) | 0.5초 | NFR5 · J63 · RD-3 | 코드가 읽지 않음(측정 판정 기준) |
| 말 끝 → 기기 첫 음성 p95 | 2.5초(짧은 응답 기준) | NFR5 · TS-N01 | 코드가 읽지 않음 |
| 서버 몫: 전사 확정 → 12 응답 items 포함 p95 | 0.5초 | RD-4 | 코드가 읽지 않음 |
| 화면 도달 예상(판정 아님) | 폴링 2초면 약 2.5초, 1초로 줄이면 약 1.5초 | RD-4 | 코드가 읽지 않음 |
| REST 서버 처리 p95 | 300ms | Q3 · J63 | `management.metrics.distribution.slo.http.server.requests`에 경계 100 · 300 · 500 · 1000ms(Spring 표준 키, 경계는 제안, 3.3 확정) |
| 12 진행 조회 p95 | 500ms | Q3 · J63 | 위와 같음 |
| 내부 HTTP 제한 6종 | 시작 3초 · 끝내기 2초 · 조회 1초 · 토큰 확인 1초 · 상태 보고 1초 · 이벤트 전달 3초 | J26 · 계약 12절 | 레지스트리 참조: `mujoong.internal-http.session-start-timeout` · `session-end-timeout` · `session-view-timeout` · `verify-token-timeout` · `device-status-timeout` · `event-publish-timeout` |
| 12 진행 폴링 주기 | 2초 | 계약 7절 · A2 | `mujoong.timing.parent.poll-interval` |
| D0 등록 상태 확인 주기 | 2초 | 계약 7절 · J22 | `mujoong.timing.bootstrap.status-poll` |
| 출력 구간 확정 조용 시간(quiet_wait) | 800ms | BR7.3 · J52 | `mujoong.gate.segment-quiet`(레지스트리 참조) |
| HEARTBEAT 주기(시계 환산 입력) | 5초 | 계약 7절 · J27 | `mujoong.timing.device.heartbeat-interval` |
| D0 코드 표시 p95 | 3초(와이파이) | Q4 · J64 | 코드가 읽지 않음 |
| 04 LCP | 2.5초(Android Chrome 중급기 4G) | Q4 · J64 | 코드가 읽지 않음 |
| 켜기 → HELLO_OK p95 | 2초 | Q4 · J64 | 코드가 읽지 않음 |
| 부하 시험 전용 시드 | 보호자 · 아이 · 기기 30세트, 기기 토큰 해시 선계산 | RD-12 · A6 | 코드가 읽지 않음(시드 러너 인자) |
| 코드 발급 속도 제한 | prod는 IP당 1시간 20회 그대로, 부하 시험 환경에서만 설정으로 올림 | 계약 7절 · BR1.6 · RD-12 | `mujoong.timing.bootstrap.rate-limit`(7절 키 `bootstrap.rate-limit`) |
| 판정 표본 규칙 | 표본 100 이상이면 p95, 100 미만이면 10회 중 9회 이상이 목표 이내 | RD-13 | 코드가 읽지 않음 |
| traceId 전파 헤더 | `X-Trace-Id`(제안) | NFR5.7 · J67 | 코드 상수. 3.5가 계약 파일에 더함 |

## 4. 시험 · 검증 방법

### 4.1 요구별 시험

| ID | 시험 | 단계 |
|---|---|---|
| NFR5.1 | 가짜 Clock으로 relayIn(수신 → 공급자 전송)과 relayOut(구간 확정 → 기기 첫 프레임 송신)이 시각 차를 맞게 계산되고, 공급자 응답 길이와 구간 확정 조용 시간이 relayOut에 섞이지 않고 별도 구간으로 남는지 단위 시험 | 3.5 구현 |
| NFR5.1 | 실공급자 세션의 한 턴 로그로 relayInMs p95 + relayOutMs p95를 계산한다. 짧은 · 보통 · 긴 응답으로 나눠 provider_response_duration과 quiet_wait 분포도 따로 적는다 | 11/2 측정 |
| NFR5.2 | 한 턴에 t0 · t1 · tSegmentConfirmed · t2 · t3이 세션 단위로 남는다. 공급자가 입력 종료 이벤트를 주지 않는 경우(주입)에도 t0가 tSpeechEndSource(마지막 입력 프레임 또는 입력 전사 확정)와 함께 기록되고, 출력이 없는 턴은 해당 시각이 '미관측'과 사유로 남는다 | 3.5 구현 |
| NFR5.2 | 같은 턴의 t0 ~ t3와 구간별 지연(t1−t0 · provider_response_duration · quiet_wait · 게이트 처리 · t3−t2)의 p50 · p95. 응답 길이별로 나눠 하한(응답 생성 시간 + 0.8초 + 중계)과 비교한다 | 11/2 측정 |
| NFR5.3 | 가짜 공급자 통합 시험: 전사 확정 시각과 그 행이 처음 12 응답에 실린 시각(SESSION_ITEMS_SERVED)이 로그에 남고, 12 응답 items에 같은 행이 있다 | Build and Test |
| NFR5.3 | 측정 전용 빠른 폴러로 서버 몫(tTranscriptFinal → tViewItemFirstServed)의 p95. 실제 주기(2초)의 요청 간격 분포를 폴링 몫으로 따로 적고, 화면 도달 예상치(서버 몫 + 폴링 몫)를 U4 보호자 화면 계측과 비교한다 | 11/2 측정 |
| NFR5.5 | 요청 타이머가 경로 템플릿 라벨만 쓰는지(실제 id 라벨 없음, NFR9.8) 점검. Realtime 응답을 1.2초 지연시키면 12가 사본으로 답하고 폴백 · 실패 카운터가 오른다 | Build and Test |
| NFR5.5 | 부하 스크립트(부하 시험 전용 시드 30세트 · 세션 2 · 폴링 합계 40 RPS 이하)에서 엔드포인트별 p95. 시드는 dev 또는 load-test 프로필에서만 켜지고 `bootstrap.rate-limit`은 그 환경 설정으로만 올린다. prod 프로필 설정에서 값이 20회/시간 그대로이고 시드 러너가 등록되지 않는 것도 설정 시험으로 확인한다. 토큰 원문은 저장소에 두지 않고 실행 때 만든다 | 11/2 측정 |
| NFR5.6 | Lighthouse 모바일 프로필로 04 LCP, 실기기 1대 실측. D0 코드 표시와 켜기 → HELLO_OK 시각은 `?debug=1` 상태 로그(BR9.6)로. 항목마다 10회 이상 재고 10회 중 9회 이상이 목표 이내인지 판정 | 11/2 측정 |
| NFR5.7 | 통합 시험: 시작 요청 1건의 traceId가 Core 로그 · 내부 HTTP 헤더 · Realtime 로그 · 이벤트 봉투 · 수신 처리기 로그에서 같고, 바깥에서 온 이상한 값은 새로 만든다 | Build and Test |
| NFR5.7 | Outbox 행에 저장한 trace_id가 전달 때 봉투로 복원되고 payload를 비운 뒤에도 남는다(FD 보강 A3 전제) | 3.5 구현 |
| NFR5.8 | 가짜 기기가 PLAYBACK_MARK(STOPPED, stopCause LOCAL_VOICE_ACTIVITY)를 보내면 observedAt과 원인 값이 세션 단위 로그에 남는다. 0.5초 판정은 U3 | 3.5 구현 |

NFR5.4의 '한 턴 로그만으로 NFR5.1 · NFR5.2 · NFR5.3(서버 몫)을 계산하는 스크립트' 시험은 `observability-requirements.md` 4절이 맡고, 이 절의 11/2 측정이 그 스크립트를 쓴다.

### 4.2 11/2 측정 계획

| 대상 | 시작 → 끝 | 기록 방법 |
|---|---|---|
| 입력 중계(NFR5.1) | 기기 프레임 수신 → 공급자 전송 완료 | 타이머 메트릭의 전체 분포와 턴 로그의 relayInMs. 초당 50개 프레임마다 로그를 남기지 않는다 |
| 출력 중계(NFR5.1) | 구간 확정(tSegmentConfirmed) → 게이트 통과 → 기기 첫 프레임 송신 | 턴 로그의 relayOutMs. 구간 확정 전(공급자 응답 길이 + 조용 시간)은 넣지 않고 provider_response_duration · quiet_wait로 따로 적는다 |
| 말 끝 → 첫 음성(NFR5.2) | t0 → t1 → tSegmentConfirmed → t2 → t3 | 서버 시각 넷은 한 호스트 시계. t3은 기기 관측 시각을 clockOffsetMs · rttMs로 서버 시계에 환산하고 오차를 같이 적는다. 환산식은 observability 3.4가 한 곳에서 정하고 이 문서는 입력만 요구한다 |
| 서버 몫(NFR5.3) | 전사 확정 → 12 응답 items 포함 | 측정 전용 빠른 폴러(요청 간격 100ms, 부하 스크립트 인자)로 tViewItemFirstServed를 읽어 폴링 대기를 0.1초 이내로 줄인다. 실제 2초 주기의 폴링 몫은 요청 간격 분포로 따로 적는다. 화면 표시 시각은 보호자 화면 시험 계측으로 따로 |
| REST(NFR5.5) | 요청 수신 → 응답 완료 | Actuator 요청 타이머 분포, 폴백 · 실패 카운터 |
| 첫 화면(NFR5.6) | D0 코드 표시 · 04 LCP · 켜기 → HELLO_OK | Lighthouse 모바일 프로필 + 실기기, T19 `?debug=1` 로그 |

- 환경: 시연과 같은 EC2, Nginx 경유 HTTPS/WSS, 실공급자(합성 · 성인 음성). 가짜 공급자 결과와 섞지 않고 기록에 구분한다(`first-bolt.md` 6.2).
- 표본(RD-13): 보통 발화 · 느린 발화와 긴 쉼 · 중간 멈춤 세 시나리오를 각 20턴 이상 3회 반복하고 화면 항목은 각 10회 이상 잰다. 판정은 표본이 100 이상이면 p95, 100 미만이면 10회 중 9회 이상(턴 표본이면 90% 이상)이 목표 이내다. 세 시나리오를 합친 턴 표본이 100 이상이면 p95로 판정하고 시나리오별 값은 참고로 적는다. 분포 전체와 최댓값은 어느 경우에도 같이 적는다.
- 동시 세션 비교(NFR7.1)는 같은 대본으로 한 세션과 두 세션을 돌려 비교한다. 1차 판정은 NFR5.1과 t3−t2의 증가율이다(scalability 문서).
- 결과 기록: 재현 조건(커밋 · 설정 키 값 · 브라우저 · 네트워크), 표본 수, p50 · p95, 판정. 미달은 조정 대상이며 이 문서의 해당 행 목표만 고친다. 측정 로그는 목표를 바꿔도 유지하고(NFR5.4), 원음 · 전사 원문은 측정 기록에 넣지 않는다(NFR9.8 · NFR12.2).

## 5. 열린 질문과 가정

| 번호 | 내용 | 정할 곳 |
|---|---|---|
| J63 | REST p95 300ms · 12 조회 p95 500ms는 시작값이다(p99는 쓰지 않음) | 11/2 측정 뒤 팀 |
| J64 | D0 3초 · 04 LCP 2.5초 · 켜기 2초는 시작값이다(점수형 목표는 쓰지 않음) | 11/2 측정 뒤 팀 |
| J67 | 구조화 로그 + 메트릭 + traceId 전파로 충분하고 분산 추적 백엔드는 두지 않는다 | 3.3 NFR Design |
| J69 | 측정은 서버 구간 로그와 자체 WSS 부하 스크립트로 하고 외부 부하 도구는 두지 않는다. 도구 이름은 3.5 확정 | 3.5 구현 |
| RD-2 | 키 이름 단일 원천은 reliability 3.2 레지스트리다. 이 문서의 3절은 키를 새로 만들지 않고 레지스트리 이름(내부 HTTP 제한 6종 · `mujoong.gate.segment-quiet`)을 쓴다 | 3.3이 최종 확정 |
| RD-3 | NFR5.1을 relayIn + relayOut p95 합으로 다시 정의하고 provider_response_duration · quiet_wait는 NFR5.2의 별도 구간으로 뺐다. NFR5.2 2.5초는 짧은 응답 기준이며 U3 구간 분할 전에는 미달이 예상된다(구 열림 1의 정의 부분을 닫음) | 확정. 목표 값은 11/2 측정 뒤 팀 |
| RD-4 | NFR5.3은 서버 몫만 요구한다. observability의 SLO 표와 경보선도 이 정의를 쓴다(구 열림 2의 앞 절반) | 확정. 목표 값은 11/2 측정 뒤 팀 |
| RD-5 | 한 턴 로그만으로 NFR5.1 · 5.2 · 5.3이 계산되도록 observability 3.4가 tSegmentConfirmed · tViewItemFirstServed(SESSION_ITEMS_SERVED) · tSpeechEndSource · clockOffsetMs · rttMs를 둔다. t0는 tSpeechEndSource와 함께 항상 기록한다 | 확정(observability 3.4에 반영) |
| RD-6 | 로그는 최상위 여섯 필드(ts · level · traceId · component · event · ownerHash) + attrs(사건 코드별 허용 키 표, 숫자 · 열거값 · UTC 시각만)다. 세션 묶음 키(세션 id 해시)와 turnSeq는 attrs에 담고 전사 · 원음 · 토큰은 attrs에도 쓰지 않는다. NFR9.15를 이 표현으로 고쳤다(구 열림 4를 닫음) | 사람 확인(NFR9.15 개정) |
| RD-7 | NFR5.8을 신설했다: 끼어들기 0.5초는 U3, U1은 PLAYBACK_MARK(STOPPED, LOCAL_VOICE_ACTIVITY)의 observedAt 기록만 | 확정 |
| RD-12 | 30쌍 부하는 부하 시험 전용 시드(A6)로 만들고 `bootstrap.rate-limit`은 그 환경에서만 올린다. NFR7.1의 1차 판정은 NFR5.1과 t3−t2의 증가율이다 | 확정. 시드 구현은 3.5 |
| RD-13 | p95 목표를 표본 100 미만으로 재는 항목(NFR5.6)은 10회 중 9회 이상이 목표 이내로 판정한다 | 확정 |
| FD 보강 후보 A3 | Outbox/Inbox 네 표에 trace_id 열(선택)을 더한다(BR5.13 · 이벤트 봉투 traceId 복원). 승인된 functional-design은 고치지 않고 3.5에서 FD 규칙에 반영한다 | 3.5 |
| FD 보강 후보 A6 | 부하 시험 전용 시드: 보호자 · 아이 · 기기 30세트, 토큰 해시 선계산, rate-limit은 설정으로만 상향. BR8.3의 프로필 목록에 load-test를 더할지 dev만 쓸지는 3.5가 정한다. A1 · A2 · A4 · A5는 reliability · security 문서 5절에 있다 | 3.5 |
| 열림 1 | 사람이 정할 것: 800ms 구간 확정 대기(BR7.3)를 U3에서 줄일지, NFR5.2의 2.5초를 짧은 응답 기준으로 둘지 길이별로 나눌지. 구간 확정 사유(조용 시간 또는 입력 재개)를 로그에 남기는지는 observability 3.4가 정한다(입력 재개로 확정된 턴은 quiet_wait가 800ms보다 짧다) | 사람, 11/2 측정 뒤 |
| 열림 2 | 사람이 정할 것: NFR5.3의 1.5초를 화면 도달에도 지키려면 요구 A2가 허용하는 1초 폴링으로 12 진행 화면만 줄이는 계약 7절 변경 안건. 폴링 RPS는 scalability 2.1에서 약 16 → 17로 늘어난다 | 사람, 11/2 측정 뒤 |
| 열림 3 | 내부 HTTP 헤더 이름(X-Trace-Id 또는 W3C traceparent)과 이벤트 봉투의 선택 필드 traceId를 계약 추가 · 편차 목록(J59)에 더한다. observability 문서도 같은 항목을 올렸다 | 3.3 · 3.5 |
| 열림 4 | 닫힘(RD-6): 세션 묶음 키 · turnSeq는 attrs로 담는다 | 사람 확인 |
| 열림 5 | 닫힘(RD-5): '말 끝 감지'의 출처가 공급자 입력 종료 이벤트가 아니어도 tSpeechEndSource 대체 정의로 t0를 기록한다. 공급자가 그 이벤트를 주는지는 3.5 공급자 연결에서 확인만 한다 | 3.5 |
| 열림 6 | Android 중급기 실기 확보 | 팀 |
| 열림 7 | 가정: 서버 몫 측정은 측정 전용 빠른 폴러(100ms)를 쓰고, 그 폴러는 코드가 읽는 설정 키가 아니라 부하 스크립트 인자다 | 3.5 |
