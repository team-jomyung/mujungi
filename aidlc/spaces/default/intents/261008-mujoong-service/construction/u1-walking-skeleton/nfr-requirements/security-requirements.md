# 보안 요구사항 (security-requirements) — U1 u1-walking-skeleton

> NFR Requirements(3.2) 산출물 · U1 워킹 스켈레톤 · 깊이 Standard · 2026-10-09 (클라우드 세션, 팀장 대리 판단)
> 입력: `functional-design/{functional-spec,rules,entities}.md`(규칙 BR1.1 ~ BR9.7 89개), `requirements.md`(NFR1 · 2 · 3 · 9 · 12), `contract-summary.md`(4.2 · 5 · 6 · 7절), ADR_2S(ADR-006 · 008 · 010 · 019), `nfr-requirements-questions.md`(Q1 ~ Q12)
> 대리 판단: J61 ~ J72는 팀장(준하) 대리의 가정이며 사람이 확정할 때 쓰는 이름이다. 이 문서는 J62 · J66 · J67 · J68 · J72와 앞 단계 J29 · J44 · J50 · J58에 기댄다. 3.2 재점검의 결정은 질문 파일에 J73 이후로 잇지 않고 §5.5에 RD-n(재점검 지시의 결정 번호)으로 기록한다. 수치는 시연 규모의 시작값이다. NFR9.15(로그 필드: 최상위 여섯 필드 + attrs 허용 키 표)는 observability, NFR4.11(백업 수치)은 reliability 문서가 맡고, 설정 키 이름의 단일 원천은 reliability-requirements.md 3.2 레지스트리다.

## 1. 요구사항

| ID | 우선순위 | 요구 | 측정 · 완료 기준 | 근거 |
|---|---|---|---|---|
| NFR1.1 | Must | 기기로 나가는 모든 소리는 출력 게이트를 통과한 승인 구간 또는 승인된 안내 문구뿐이다. 게이트 폐기 구간은 재생 · 전사 노출이 없다. | 게이트 우회 시험: 미승인 구간 주입 시 기기 재생 로그 0, approved_for_display false | NFR1 · BR7.3 ~ BR7.5 |
| NFR1.2 | Must | U1 게이트 슬라이스의 시드 사전은 계약 9절 category 열거 7개(DIAGNOSIS · TREATMENT · SCORE · CAUSE · GENERALIZATION · EMOTION_CLAIM · PRESSURE)마다 최소 1개 항목을 갖는다. 항목에 걸린 구간은 폐기되고 승인 대체 문구가 나가는 자동 시험이 통과한다. 시드 예시는 진단 · 치료 · 점수 · 정상 · 비정상 류에 더해 등급 · 향상률 · 핵심 원인 · 학교 일반화 단정 · 의도 · 감정 단정의 대표 패턴을 적는다(FD 보강 A5). 전체 금지 표현 사전 · 27 · PDF 시험은 후속 유닛이 같은 시험 틀에 더한다. | 단위 시험 둘: ① 사전의 category 7개 모두에 항목이 1개 이상 있다(범주가 비면 시험 실패) ② 항목별로 폐기와 대체 문구 재생을 확인한다. 후속 유닛이 목록을 확장해도 같은 시험이 돈다 | NFR1 · BR7.4 · 계약 9절 · RD-10 |
| NFR1.3 | Must | U1이 그리는 화면(D0 · T19 · 04 · 05a · 08 · 12 · P1 · 17)과 오류 문구에 진단 · 점수 · 의도 · 감정 단정 표현이 없다. 문구는 문구표(C.S-xx) 또는 mockups 9절 제안만 쓴다. | U1 화면 문구 전수 검토 체크리스트 통과 | NFR1 · mockups 9절 |
| NFR2.1 | Must | U1 화면 · 기기 · 오류 문구에 연속 이용 보상, 완료율 압박, 중단 만류, 재촉 문구가 없다. 재접속 5초 재시도 · 시한 실패는 조용히 처리하고 아이에게 재촉하는 말을 하지 않는다. | 문구표 전수 검토에서 해당 UI · 문구 0건 | NFR2 |
| NFR3.1 | Must | 공급자(음성 모델)로 보내는 정보는 지시문 + childContext(displayName · age · interests)뿐이다. 보호자 입력 네 칸 원문, 이메일, 판정 기준, 이전 판정은 보내지 않는다. | 공급자 요청 로그 샘플에 처리 목적 밖 필드가 없다(필드 화이트리스트 시험) | NFR3 · BR6.5 |
| NFR3.2 | Must | 대화 역할의 요청에는 판정 기준 · 보호자 입력 원문 · 이전 판정 결과 필드가 구조적으로 없다. U1은 필드 화이트리스트 시험을 두고 후속 유닛이 같은 시험을 확장한다. | 역할 지식 누출 합성 시험(U1 범위: 화이트리스트 위반 시 실패) 통과 | NFR3 · FR-F15 |
| NFR9.1 | Must | 모든 접점은 HTTPS(wss 포함)다. TLS 1.2 이상, HTTP 요청은 HTTPS로 301, HSTS를 켠다(max-age 180일). 인증서는 자동 갱신한다. | 외부 포트 스캔: 80은 301만, 443 TLS 1.2+. 인증서 만료 30일 전 갱신 로그 | NFR9 · ADR-006 · J66 |
| NFR9.2 | Must | 보호자 인증은 서버 세션 쿠키(MUJOONG_SESSION: HttpOnly · Secure · SameSite=Lax)다. 유휴 30일 갱신형 만료(login.session-idle), 로그아웃 · 만료 시 서버 세션이 무효화된다. U1은 dev 시험 로그인으로 같은 세션 계층을 쓰고 구글 로그인은 U2다. | 쿠키 속성 확인 시험, 만료 세션 요청 401 | NFR9 · BR8.4 · BR8.5 |
| NFR9.3 | Must | 보호자 변경 요청(POST · PUT · PATCH · DELETE)은 X-CSRF-TOKEN 검증을 거치며 면제는 정확한 경로 목록(BR8.8)뿐이다. /api/device/** 전체를 면제하지 않는다. | 면제 목록 밖 경로의 토큰 없는 요청 403 CSRF_TOKEN_INVALID 시험 | NFR9 · BR8.8 |
| NFR9.4 | Must | 기기 인증은 HttpOnly · Secure · SameSite=Strict 쿠키(MUJOONG_DEVICE 400일 갱신형, MUJOONG_DEVICE_BOOTSTRAP 24시간)와 서버 해시 비교(상수 시간)다. 비밀 크기는 bootstrap 비밀 128비트 이상, 기기 토큰 256비트이고 각각 SHA-256 해시만 저장한다. WSS는 Origin 허용 목록을 확인하고 미등록 · 해제 토큰은 4401로 거부한다. | 미등록 · 만료 · 해제 토큰 WSS 거부 시험, 허용 밖 Origin 403, 비밀 길이(128비트 이상 · 256비트)와 DB에 해시만 있음 확인 | NFR9 · BR1.5 · BR3.1 ~ BR3.6 · J29 · RD-14 |
| NFR9.5 | Must | 보호자는 자기 계정의 기기 · 세션 · 기록에만 접근하고, 남의 id는 존재 여부를 숨기는 404로 답한다. | 다른 계정 id로 조회 · 끝내기 · 상태 요청 시 404 시험 | NFR9 · BR6.9 · BR6.15 |
| NFR9.6 | Must | 앱 사이 내부 호출은 X-Internal-Token(공유 비밀, 상수 시간 비교)을 요구하고, Nginx는 /internal/** 외부 접근을 403으로 막는다. 내부망이라는 이유로 인증을 생략하지 않는다. | 외부 경로의 /internal 403, 토큰 없는 내부 호출 401 시험 | NFR9 · ADR-008 · BR3.9 · BR8.9 |
| NFR9.7 | Must | DB 역할을 앱별로 분리한다: core_app은 core 스키마에만, realtime_app은 realtime 스키마에만 쓰기 권한을 갖고 서로의 스키마에는 권한이 없다. | 권한 시험: 교차 스키마 쓰기 시도가 DB에서 거부된다 | NFR9 · BR8.1 · ADR-019 |
| NFR9.8 | Must | 로그(attrs 포함) · 오류 응답 · Outbox 페이로드(전달 후) · 메트릭 라벨에 전사 원문, 원음, 토큰, 쿠키 값, 등록 코드 전체, 비밀을 남기지 않는다(등록 코드는 앞 4자만). | 로그 샘플 grep 시험(시드 전사 · 토큰 문자열 0건), 오류 봉투 message 점검 | NFR9 · BR8.7 · BR5.11 |
| NFR9.9 | Must | 남용을 막는다: bootstrap IP당 1시간 20회, 등록 실패 보호자당 10분 10회, REST 본문 64KB 상한, WSS 텍스트 프레임 8KB · 오디오 프레임은 규정 크기만, WSS 업그레이드 뒤 5초 안에 HELLO가 없으면 4408. | 한도 초과 시 429 · 4400/4408 시험(HELLO 5초의 기산점은 WSS 업그레이드 시각) | NFR9 · BR1.6 · BR2.6 · J29 · RD-14 |
| NFR9.10 | Must | 비밀(공급자 키 · 내부 토큰 · DB 비밀번호 · OAuth 비밀)은 서버 환경 변수 또는 서버 비밀 파일(권한 600)에만 있고 저장소 · 이미지 · 프런트 번들 · 로그 · 문서에 없다. CI는 CI 비밀 저장소를 쓴다. | 저장소 비밀 스캔(gitleaks류) 통과, 프런트 번들에 키 문자열 없음 | NFR9 · construction.md 보안 · J66 |
| NFR9.11 | Must | 활성 프로필에 demo 또는 prod가 있으면 dev 프로필 동시 활성 또는 mujoong.dev-login.enabled=true인 모든 조합에서 앱 기동을 거부한다. dev 단독(enabled=false), demo 단독, prod 단독은 /api/dev/login이 404다. | 프로필 조합 기동 시험: prod+dev · prod+enabled=true · demo+dev · demo+enabled=true는 기동 거부, dev(enabled=false) · demo 단독 · prod 단독은 404 | NFR9 · BR8.3 · FD 보강 A2 · RD-9 |
| NFR9.12 | Must | 보안 헤더와 입력 검증: CSP(default-src 'self', 필요한 connect 대상만), X-Content-Type-Options nosniff, Referrer-Policy strict-origin-when-cross-origin, frame-ancestors 'none'. 모든 경계 입력은 Bean Validation, DB 접근은 매개변수화 쿼리만 쓴다. | 응답 헤더 시험, 잘못된 입력 400 VALIDATION_FAILED 시험 | NFR9 · J66 |
| NFR9.13 | Should | 의존성 취약점 점검(Gradle 의존성 검사 · npm audit)에서 high 이상이 없거나 예외 사유가 기록되어 있다. | CI 점검 결과 | NFR9 · J66 |
| NFR9.14 | Should | 보안 사건(인증 실패, 속도 제한 발동, 허용 밖 Origin, dev 로그인 차단, 내부 토큰 실패) 다섯 종은 개인정보 없이 구조화 로그(최상위 여섯 필드 + attrs의 사건 코드별 허용 키 표, 숫자 · 열거값 · UTC 시각만)와 카운터 security_event_total{type}으로 남는다. | 사건별 카운터 존재, 로그에 토큰 · 코드 전체 없음, attrs에 허용 키 표 밖 키 없음 | NFR9 · J67 · RD-6 |
| NFR12.1 | Must | U1이 소유한 데이터(Device · Core/Realtime Outbox/Inbox)는 계정 삭제 참가자 셋(BR2.10 · BR5.14 ×2)으로 지워지고, 삭제 완료 뒤 U1 표에서 그 보호자의 행이 0건이다(전달 중인 제어 이벤트는 전달 뒤 정리). 이를 판정할 수 있도록 U1이 저장하는 모든 Outbox/Inbox 행은 guardian_id를 채운다(FD 보강 A4). | 삭제 지휘 시험(U2와 통합): 완료 뒤 U1 표에서 guardian_id = :g 또는 aggregate_id가 삭제된 deviceId인 행 0건, 이벤트 종류별로 새 행의 guardian_id가 비어 있지 않음, 참가자 재호출 멱등 | NFR12 · J58 · RD-11 |
| NFR12.2 | Must | 원음은 U1에서 메모리에만 있고 디스크 · DB · Outbox · 로그에 쓰이지 않는다. 원음 저장 허용 세션의 저장은 U3(RAW_AUDIO). | 원음 비저장 시험: 세션 종료 뒤 디스크 · DB에서 오디오 바이트 0 | NFR12 · NFR9 · BR7.1 |
| NFR12.3 | Must | Outbox/Inbox 페이로드는 전달 · 처리가 끝나면 즉시 비우고(실패 행은 7일 뒤), 대화 원문이 든 이벤트는 전달 후 즉시 비운다. | 정리 작업 시험: PUBLISHED · PROCESSED 행 payload 비어 있음 | NFR12 · BR5.11 · J50 |
| NFR12.4 | Must | 백업과 삭제의 충돌을 닫고 정식 사전 전의 위험을 막는다: 시연 서버의 백업은 합성 데이터에만 허용한다. 실제 보호자 · 아이 데이터를 다루기 전에 ① '삭제 완료 뒤 복구 수단 없음'(NFR12)과 백업 보관의 충돌을 사람이 해소하고(열린 질문) ② 정식 금지 표현 사전(U3) 전에는 실제 아동 데이터로 시연하지 않는다. | 실제 데이터 운영 전 승인 항목(D-14)에 백업 충돌 해소와 '정식 사전(U3) 전 실제 아동 데이터 시연 금지'가 모두 포함되어 있다 | NFR12 · J62 · RD-10 |

## 2. 배경과 해석

소제목 일곱 개는 NFR을 주제별로 묶은 것이다. 「후속 몫 · 메모」는 소유 유닛이 완성하는 부분과 3.3 · 3.4가 확정할 설계 입력이다. 이 문서에서 새로 생긴 해석 · 질문은 S-n으로 부르고 §5에 모았다(J 번호가 아니다).

### 2.1 인증 · 세션 (NFR9.1 · 9.2 · 9.3 · 9.5 · 9.11)

| NFR | U1에서 뜻하는 것 | 걸리는 BR · 화면 · 컴포넌트 | 후속 몫 · 메모 |
|---|---|---|---|
| NFR9.1 | 브라우저가 닿는 모든 길(/parent · /device · /api/** · wss /ws/device)이 Nginx TLS를 지난다. Nginx와 앱 사이는 내부망 평문이라 앱 포트를 호스트에 열지 않는다 | BR8.9 · Nginx | 도메인 · 인증서 방식은 3.4(OQ5) |
| NFR9.2 | 쿠키에는 세션 ID만 있고 상태는 서버(Spring Session JDBC, core 스키마)에 있다. 로그아웃 · 만료 때 서버 행을 지워 옛 쿠키는 401이다. U1은 POST /api/dev/login이 같은 계층에 시드 보호자 세션을 연다 | BR8.4 · BR8.5 | 구글 로그인 · 로그아웃은 U2. 로그인 때 세션 ID 교체는 3.3. 로그아웃해도 음성 세션은 계속된다(BR6.16) |
| NFR9.3 | 면제는 정확한 경로 5개: POST /api/dev/login(dev) · 구글 교환(U2) · POST /api/device/bootstrap · POST /api/device/bootstrap/claim · /internal/**. register · activities/free · sessions/{id}/end는 필수 | BR8.8 · BR8.6 | 토큰 취득 경로는 3.3(S-8). 부트스트랩 두 경로는 SameSite=Strict 기기 쿠키에 기댄다 |
| NFR9.5 | 조회는 항상 「자원 id AND 세션 보호자 id」다. 없거나 남의 것은 같은 404, 세션 없음은 401이다. 등록은 본문에 보호자 id를 받지 않는다 | BR8.5 · BR6.9 · BR6.15 · BR2.2 | U4 ~ U6가 같은 규칙을 쓴다 |
| NFR9.11 | dev 빈(DevLoginController · DevSeedRunner)은 dev 프로필 AND mujoong.dev-login.enabled=true(기본 false)일 때만 생긴다. 활성 프로필에 demo 또는 prod가 있으면 dev 프로필 동시 활성 또는 enabled=true인 모든 조합에서 앱 기동을 거부한다. BR8.3은 prod만 거부하므로 demo 거부는 FD 보강 A2다. dev 단독(enabled=false) · demo 단독 · prod 단독은 빈이 없어 404이고 dev 단독에 enabled=true일 때만 시험 로그인이 열린다 | BR8.3 · BR8.4 | S-5는 해결됨(§5.3, RD-9). 3.5는 A2를 반영한 규칙으로 구현한다. 구글 로그인은 U2 |

### 2.2 기기 인증 (NFR9.4)

| NFR | U1에서 뜻하는 것 | 걸리는 BR · 화면 · 컴포넌트 | 후속 몫 · 메모 |
|---|---|---|---|
| NFR9.4 부트스트랩 | 비밀 128비트 이상을 MUJOONG_DEVICE_BOOTSTRAP 쿠키(HttpOnly · Secure · SameSite=Strict · Path=/api/device · 24시간)로만 건네고 서버는 SHA-256 해시만 둔다 | BR1.5 · BR1.3 · D0 | 요구 문구가 비밀 크기를 둘로 나눠 적었다: bootstrap 비밀 128비트 이상, 기기 토큰 256비트(아래 행, RD-14) |
| NFR9.4 기기 토큰 | claim이 256비트 토큰을 MUJOONG_DEVICE 쿠키(HttpOnly · Secure · SameSite=Strict · Path=/ · 400일 갱신형)로 내리고 해시만 둔다(상수 시간 비교). 서버 만료는 없고 해제 · 삭제 · 첫 연결 전 claim 재호출(회전)로만 무효가 된다. 첫 WSS 인증 뒤 claim은 410이다 | BR3.1 ~ BR3.3 · BR3.5 | 고엔트로피 비밀이라 SHA-256으로 충분하다(해석). 분실 · 회전 UI는 범위 밖(S-13) |
| NFR9.4 WSS | Origin 허용 목록(서비스 origin 하나) 밖이면 403으로 업그레이드하지 않는다. 토큰은 쿠키로만 가고 연결마다 Core에 확인한다(캐시 없음, 1초). 실패 4401, Core 무응답 1013, 형식 오류 4400, HELLO 없음 4408. 새 연결이 HELLO_OK를 받으면 옛 연결을 4001로, 해제되면 열린 연결을 4401로 닫는다 | BR3.4 · BR3.6 ~ BR3.8 · BR9.7 | HELLO resume · 재접속은 U3. Origin은 브라우저 위협만 막으므로 토큰과 함께 쓴다 |

### 2.3 내부 호출과 DB 역할 (NFR9.6 · 9.7)

| NFR | U1에서 뜻하는 것 | 걸리는 BR · 화면 · 컴포넌트 | 후속 몫 · 메모 |
|---|---|---|---|
| NFR9.6 | 모든 /internal/**(시작 · 끝내기 · 조회 · 토큰 확인 · 연결 보고 · 이벤트)가 X-Internal-Token을 상수 시간으로 비교하고 없거나 다르면 401이다. 이 헤더로 사용자 권한은 생기지 않는다(본문 id를 다시 대조). Nginx는 외부 /internal/**을 403으로 막는다 | BR3.9 · BR5.4 · BR8.9 | 발송 쪽은 401을 배포 중 일시 불일치로 보고 재시도한다(BR5.3). 회전은 Operation. 방향별 분리는 S-6 |
| NFR9.7 | 역할은 core_app · realtime_app에 마이그레이션 역할(별도)을 더해 셋이다. 앱 역할은 상대 스키마에 읽기 · 쓰기가 모두 없고, 앱은 Flyway를 validate만 하므로 DDL도 없다(설계 입력). 스키마를 가로지르는 열은 FK 없는 논리 참조다 | BR8.1 · BR8.2 · BR6.17 · ADR-019 | 새 표에도 같은 권한이 붙게 기본 권한을 둔다. DB 포트는 외부 비공개(3.4) |

### 2.4 입력 · 남용 방지 (NFR9.9 · 9.12)

| NFR | U1에서 뜻하는 것 | 걸리는 BR · 화면 · 컴포넌트 | 후속 몫 · 메모 |
|---|---|---|---|
| NFR9.9 bootstrap | IP당 1시간 20회 초과는 429 RATE_LIMITED. IP는 Nginx가 X-Forwarded-For를 `$remote_addr`로 덮어쓴 값만 쓰고 IPv6는 앞 64비트로 묶는다. 같은 쿠키의 반복은 세지 않고 카운터 키 수에 상한을 둔다(mujoong.limits.rate-counter-max-keys). 30쌍 부하 시험은 이 한도를 부하 시험 환경(A6)에서만 설정으로 올리고 prod 값은 20회 그대로다 | BR1.6 · BR8.9 · D0 | 메모리 카운터라 단일 인스턴스 전제(NFR7.4). 로드밸런서가 생기면 3.4에서 신뢰 프록시를 바꾼다. 같은 NAT 뒤 기기 21대 이상 동시 등록은 한도에 걸린다(§5.6) |
| NFR9.9 등록 실패 | 보호자당 10분 10회 초과는 429 TOO_MANY_ATTEMPTS(슬라이딩 창, 성공하면 지움). 응답 유실 뒤 같은 보호자의 재시도 201은 세지 않는다 | BR2.6 · BR2.1 · 05a | 위협 계산은 2.8 |
| NFR9.9 크기 · 시한 | REST 본문 64KB, WSS 텍스트 8KB, 입력 오디오 프레임은 965바이트(머리 5 + 본문 960)만 받는다. WSS 업그레이드 뒤 5초 안에 HELLO가 없으면 4408(5초는 업그레이드부터 센다), 형식 오류 4400. /internal/events의 2MB는 밖에 닿지 않는 별도 한도다 | BR5.4 · BR3.6 · 계약 4.2 | 초과 응답 코드는 S-2, 닫기 코드는 S-3 |
| NFR9.12 헤더 | CSP(default-src 'self' + 서비스 origin의 wss connect), nosniff, Referrer-Policy strict-origin-when-cross-origin, frame-ancestors 'none'을 정적 · API · Next.js 모든 응답에 붙인다 | BR8.9 · 모든 화면 | nonce 방식 · connect 값은 3.3 · 3.4(S-7) |
| NFR9.12 입력 · 쿼리 | 경계 입력은 Bean Validation(등록 코드 형식 400, 08 서술 칸 200자 초과 400, 코드 VALIDATION_FAILED). DB는 매개변수화 쿼리만 쓰고 조건부 UPDATE도 이름 있는 매개변수다. 문자열을 이어 붙인 SQL은 금지한다 | BR2.1 · BR6.4 · BR8.6 | 후속 유닛의 새 입력도 같은 틀을 쓴다 |

### 2.5 비밀 · 로그 위생 (NFR9.8 · 9.10 · 9.13 · 9.14, CI 점검 포함)

| NFR | U1에서 뜻하는 것 | 걸리는 BR · 화면 · 컴포넌트 | 후속 몫 · 메모 |
|---|---|---|---|
| NFR9.8 | 금지 목록은 BR8.7(키 · 내부 토큰 · 세션 쿠키 · 기기 토큰 · bootstrap 비밀 · 전사 · 원음 · 보호자 입력 · 등록 코드 전체). 적용 면: 앱 로그 · 오류 message · 메트릭 라벨 · Nginx 접근 로그(쿼리 · 쿠키 제외) · 전달 뒤 Outbox/Inbox. 메시징 로그는 eventId · eventType · 상태 · 재시도 · 소요 시간만, last_error는 상태 코드 · 예외 종류만이다 | BR8.7 · BR5.13 · BR5.11 · BR8.6 | 로그 필드는 NFR9.15(observability)가 정한다: 최상위 여섯 필드(ts · level · traceId · component · event · ownerHash) + attrs(사건 코드별 허용 키 표, 숫자 · 열거값 · UTC 시각만). 전사 · 원음 · 토큰은 attrs에도 쓰지 않는다(RD-6). 요청 본문을 찍는 디버그 로그는 끈다(S-12) |
| NFR9.10 | 비밀 네 종은 환경 변수 또는 권한 600 파일에만 둔다. .env는 저장소 밖, 이미지에 굽지 않고(빌드 인자 포함) 프런트 번들에 키가 없다(기기에 키를 주지 않는다). 이 NFR 문서들에도 비밀 · 모델 식별자를 쓰지 않는다 | BR8.7 · BR7.2 · BR7.6 | OAuth 비밀은 U2, 회전은 Operation |
| NFR9.13 | Gradle 의존성 검사와 npm audit(high 이상)를 CI에서 돌리고 예외는 사유 · 만료일을 적은 목록에만 둔다 | construction.md 보안 | 도구는 3.5에서 확정 |
| NFR9.14 | 인증 실패 · 속도 제한 · 허용 밖 Origin · dev 로그인 차단 · 내부 토큰 실패 다섯 종을 security_event_total{type} 카운터와 한 줄 로그로 남긴다. 한 줄 로그는 최상위 여섯 필드(traceId · ownerHash · 사건 코드 event 등)에 더해 attrs에는 사건 코드별 허용 키 표 안의 숫자 · 열거값 · UTC 시각만 담는다(NFR9.15). 외부 경보는 없고 수동 점검한다(J68) | BR8.5 · BR1.6 · BR2.6 · BR3.6 · BR3.9 · BR8.3 | 지표 · 사건 코드 5종의 이름은 observability 3.3 · 3.4가 정한다. CSRF 거부 · 4401은 인증 실패의 하위 type 후보 |

### 2.6 AI 안전 (게이트 · 문구 · 최소 정보) (NFR1.1 · 1.2 · 1.3 · 2.1 · 3.1 · 3.2)

| NFR | U1에서 뜻하는 것 | 걸리는 BR · 화면 · 컴포넌트 | 후속 몫 · 메모 |
|---|---|---|---|
| NFR1.1 | 공급자 출력은 서버 버퍼에만 쌓이다 구간(전사 800ms 조용 또는 아이 입력 재개)이 확정되면 게이트가 본다. 전사 없음 · 대응 불가 · 시드 패턴이면 폐기하고 WAITING_FILLER를 재생하며, 통과한 구간만 OUTPUT_BEGIN → 0x02 → OUTPUT_END로 나간다(송신 직전 ACTIVE 재확인). AI 전사의 approved_for_display는 통과 때만 true이고 폐기 구간은 12에 없다. 기기는 승인 음성과 OUTPUT_BEGIN 묶음 밖을 재생하지 않고 CLOSING에서는 일반 출력을 막는다 | BR7.3 ~ BR7.6 · BR6.10 · BR6.11 · BR6.15 | 정식 구간 분할 · 검사는 U3. 정적 승인 음성은 개인정보 없는 읽기 전용 파일만 둔다(BR8.9) |
| NFR1.2 | 시드 사전을 파일로 읽고, 시험이 먼저 계약 9절 category 7개 모두에 항목이 있는지 확인한 뒤(범주가 비면 실패) 모든 항목을 돌며 폐기와 대체 문구 재생을 확인한다. 항목이 늘면 시험도 자동으로 는다. 범주별 대표 패턴은 아래 표다 | BR7.4 (나) · BR7.3 | BR7.4 (나)의 시드 범위(진단 · 치료 · 점수 · 정상 · 비정상 류) 확대는 FD 보강 A5. 정식 사전 v1(OQ15) · 27 · PDF 시험은 U3 이후. 일상어 오탐은 S-4. 정식 사전 전 실제 아동 데이터 시연 금지는 NFR12.4 |
| NFR1.3 | U1 화면 여덟 곳과 오류 code별 문구를 체크리스트로 전수 본다. 화면 글은 code로 고른 문구표(C.S-xx) 또는 mockups 9절이고 서버 message는 쓰지 않는다. 아이 기기의 오류성 글은 「연결을 확인하는 중」과 마이크 허용 안내뿐이다 | BR8.6 · BR9.4 · BR9.5 · BR2.8 | 05는 04로 되돌리는 스텁(BR4.5). 나머지 화면은 소유 유닛이 같은 체크리스트로 본다 |
| NFR2.1 | 체크리스트에 재촉 · 만류 · 보상 · 완료율 열을 더한다. 위험 지점: (1) D0 · T19 실패는 화면 유지 + 5초 조용한 재시도(카운트다운 · 안내 없음), (2) 시작 실패 · 시한 FAILED는 기기를 CLOSE_TRANSPORT 뒤 STANDBY로 조용히 되돌리고 사유는 보호자 화면에만, (3) 승인 문구 CLOSING · WAITING_FILLER(임시 WAV, J60)에 만류 · 재촉 없음, (4) P1은 확인만 묻고 권하지 않음, (5) 17은 사유 · 시각만 | BR9.4 · BR9.5 · BR9.7 · BR6.8 · BR6.9 · BR6.10 | 정식 문구 · 녹음은 U2, 시작 안내 · 공백 경고는 U4 |
| NFR3.1 | 공급자 요청은 VoiceRelay 한 곳에서만 만들고 텍스트 · 메타 필드는 지시문과 childContext뿐이다. 08 네 칸 원문은 검증만 하고 저장 · 전송하지 않으며 이메일 · 판정 기준 · 이전 판정 · 각종 id도 나가지 않는다. 아이 입력 음성 프레임은 처리 목적 자체라 화이트리스트 밖 필수 입력으로 본다(S-1) | BR6.5 · BR6.4 · BR7.1 · BR7.2 | 공급자 보존 조건 확인은 실제 아동 데이터 전(S-11) |
| NFR3.2 | 대화 역할 요청 타입에 판정 기준 · 보호자 입력 원문 · 이전 판정 필드가 없고 자유 문자열에 섞어 넣는 우회도 시험이 막는다. U1에는 판정 역할이 없고 세션 패키지의 practice는 null이다 | BR6.5 · 계약 5절 | U5가 판정 기준 · 이전 판정 표식을 같은 시험에 더한다 |

NFR1.2 시드 사전의 범주별 대표 패턴(FD 보강 A5). 범주마다 최소 1개 항목을 둔다.

| category (계약 9절) | 시드 대표 패턴 (예) | 막으려는 말 |
|---|---|---|
| DIAGNOSIS | 진단, 장애예요, ~증이에요 | 진단 단정 |
| TREATMENT | 치료, 교정이 필요, 치료받아야 | 치료 · 교정 권유 |
| SCORE | 점수, 정상, 비정상, N등급, N% 향상, 향상률 | 점수 · 등급 · 향상률 |
| CAUSE | 핵심 원인, ~가 원인이에요, ~ 때문에 말이 느린 | 핵심 원인 단정 |
| GENERALIZATION | 학교에서도 그럴, 어디서나 그래, 항상 그런 | 학교 등 다른 장면으로의 일반화 단정 |
| EMOTION_CLAIM | 화가 났군요, 속상했던 거예요, 일부러, ~하려던 거죠 | 아이 의도 · 감정 단정 |
| PRESSURE | 꼭 해야 해요, 그만두면 안 돼요, 얼른 말해 | 재촉 · 중단 만류(NFR2.1과 같은 방향) |

범주 배정과 표기는 시드용 가정이고 정식 사전(U3)이 7개 범주의 항목을 확정한다. 조사 결합 · 띄어쓰기 변형은 시험이 만든다(§4).

### 2.7 데이터 보존 · 삭제 (NFR12.1 ~ 12.4)

| NFR | U1에서 뜻하는 것 | 걸리는 BR · 화면 · 컴포넌트 | 후속 몫 · 메모 |
|---|---|---|---|
| NFR12.1 | 삭제 참가자 셋. Device(REGISTERED를 RELEASED(ACCOUNT_DELETED)로 바꾸고 DeviceReleased를 쓴 뒤 그 보호자의 core_device 행을 모두 삭제), Core · Realtime 메시징(전달이 끝난 Outbox/Inbox 행 삭제, PENDING · RECEIVED 제어 이벤트는 유지, 원문 이벤트는 페이로드 즉시 비움). 멱등이고 자기 스키마에만 쓴다. 「0건」은 지휘가 마지막에 다시 부른 뒤의 상태다. 이를 guardian_id로 판정하도록 U1이 저장하는 모든 Outbox/Inbox 행에 guardian_id를 채운다(A4): DeviceStatusChanged는 Realtime이 연결 행의 guardian_id로, Core 수신 쪽은 aggregate_id로 core_device를 조회해 채운다. 확인 표: core_device · core_outbox_event · core_inbox_event · realtime_outbox_event · realtime_inbox_event, 조건은 guardian_id = :g 또는 aggregate_id가 삭제된 deviceId | BR2.10 · BR5.14 · BR8.1 | 지휘는 U2, Realtime 쪽 호출 순서는 U4. 수신 때 core_device가 이미 없어 guardian_id를 못 채운 행을 aggregate_id 조건으로 지울지는 3.5가 확인한다. realtime_device_connection 삭제는 U1 규칙에 없다(S-9) |
| NFR12.2 | 입력 음성은 ACTIVE 뒤 프레임만 중계하고 쌓지 않으며 ACTIVE 전 · 종료 · FAILED 뒤 프레임은 버린다. 디스크 · DB · Outbox · 로그에 쓰지 않는다. 힙 덤프 · 코어 덤프에 원음이 들어갈 수 있어 자동 덤프를 끄거나 접근을 막는다(S-12) | BR7.1 | 원음 파일 저장은 U3(RAW_AUDIO). 공급자 쪽 보존은 통제 밖(S-11) |
| NFR12.3 | PUBLISHED · PROCESSED · IGNORED가 되면 payload_json을 {}로 바꾸고 purged_at을 채워 식별 · 해시 · 상태만 남긴다. FAILED · DEAD는 7일 뒤 같은 방식으로 비운다(하루 한 번 정리). U1 이벤트(SessionStateChanged · DeviceStatusChanged · DeviceReleased)에는 대화 원문이 없고, 원문 이벤트(SessionRecordCaptured)는 U4가 이 장치를 쓴다 | BR5.11 · BR5.14 · BR5.13 | 원문 이벤트의 FAILED 7일 보관은 U4에서 다시 본다(S-10) |
| NFR12.4 | 시연 서버 백업은 합성 데이터에만 허용한다(J62). 백업 보관(NFR4.11)과 「삭제 완료 뒤 복구 수단 없음」의 충돌은 해소된 것이 아니라 사람 결정으로 남았다. 시드 사전이 7범주 대표 패턴뿐이므로 정식 사전(U3) 전에는 실제 아동 데이터로 시연하지 않는 것도 같은 승인 항목(D-14)에 넣는다(RD-10) | NFR4.11 · BR5.14 · BR7.4 | 5.1에서 사람이 정한다. 3.4 백업 설계는 그때까지 합성 데이터를 전제로 한다 |

### 2.8 위협 고려 요약 (STRIDE)

| 위협 (공격 경로) | 분류 | 대응 (NFRx.y · BR) | 남는 위험 |
|---|---|---|---|
| 기기 코드 맞히기 (05a에서 무작위 대입) | S · E | 31^8 ≈ 8.5×10^11 · 24시간 · 등록하면 소비 · 실패 10분 10회 429 · 연결 중 교체 409(NFR9.9, BR1.1, BR1.4, BR2.3, BR2.4, BR2.6) | 낮음. 하루 1,440회 · 유효 코드 30개 가정 시 적중 약 5×10^-8(추정). 화면을 본 사람의 선등록은 못 막음 |
| 쿠키 탈취 (XSS · 도청) | S · I | HttpOnly · Secure · TLS 1.2+ · HSTS · CSP · 서버 세션 즉시 무효화 · 기기 토큰 해제 · 회전 + 해시 저장(NFR9.1, 9.2, 9.4, 9.12, BR3.3) | 중간. 기기 토큰은 서버 만료 없음(S-13) |
| CSRF (다른 사이트가 등록 · 시작 · 끝내기를 요청) | T | X-CSRF-TOKEN · 면제 5경로 · SameSite(NFR9.3, BR8.8) | 낮음. 토큰 취득 경로 3.3(S-8) |
| 내부 API 노출 (밖에서 /internal/** 호출) | S · E · I | Nginx 403 · 토큰 상수 시간 · 사용자 권한 없음 · 포트 미게시(NFR9.6, BR3.9, BR8.9) | 낮음. 호스트 침해 시 토큰 노출 |
| 로그 유출 (전사 · 토큰 · 코드가 로그 · 오류 · 메트릭에 남음) | I | NFR9.8, NFR9.15(attrs 허용 키 표), NFR12.3, BR5.13, BR8.7 | 중간. 라이브러리 기본 로그(grep 시험으로 점검) |
| dev 로그인 노출 (공개 서버에서 누구나 시드 세션) | S · E | demo · prod에 dev 동시 활성 또는 enabled=true면 기동 거부 · dev 단독(enabled=false) · demo 단독 · prod 단독 404 · 01에 단추 없음 · 차단 카운터(NFR9.11, NFR9.14, BR8.3) | 낮음. demo 거부는 FD 보강 A2가 규칙에 반영되는 3.5를 전제로 한다 |
| 위조 Origin (다른 사이트 또는 비브라우저가 /ws/device 접속) | S | Origin 403 · SameSite=Strict · 토큰 4401(NFR9.4, BR3.6) | 낮음. 비브라우저는 Origin을 위조할 수 있어 토큰이 실제 인증 |
| 공급자 키 유출 (키가 기기 · 번들 · 응답 · 로그로 나감) | I · E | 서버 키 전용 · 기기에 키 없음 · 권한 600 · 비밀 스캔 · 번들 grep(NFR9.10, BR7.2, BR7.6) | 중간. 호스트 침해 시 노출, 회전은 후속 |
| 게이트 우회 (미승인 소리 · 금지 표현 · 늦은 출력 재생) | T · E | NFR1.1, NFR1.2(시드 사전 7범주), NFR12.4, BR7.3 ~ BR7.6, BR6.10, BR6.11 | 중간. 시드 사전은 7범주를 덮지만 대표 패턴뿐이라 정식 사전(U3) 전에는 실제 아동 데이터로 시연하지 않는다(NFR12.4, D-14) |
| 목적 밖 정보의 공급자 전송 (보호자 입력 · 판정 기준) | I | NFR3.1, NFR3.2, BR6.5, BR6.4 | 낮음(U1). 판정 기준은 U5에서 시험 확장 |
| 남의 자원 접근 (다른 보호자 id 추측) | I · E | 소유 확인 후 같은 404(NFR9.5, BR8.5) | 낮음 |
| 서비스 거부 (bootstrap 남용 · 큰 본문 · 느린 WSS) | D | NFR9.9 한도 · WSS 업그레이드 뒤 HELLO 5초 | 중간. 단일 인스턴스 · WAF 없음(J66 범위 밖). 같은 NAT 뒤 기기 21대 이상 동시 등록은 한도에 걸린다(§5.6) |
| 행위 부인 (끝내기 · 기기 해제 주체 다툼) | R | NFR9.14, NFR9.15, 끝내기 requestId(BR6.9), Outbox eventId | 낮음. 별도 감사 로그 보관은 범위 밖 |

## 3. 수치와 설정 키

설정 키 이름의 단일 원천은 reliability-requirements.md 3.2의 mujoong.* 레지스트리다. 이 표는 키를 새로 만들지 않고 레지스트리 이름 또는 계약 7절 키를 쓴다(RD-2). 계약 7절에 키가 있는 값은 그 키가 단일 원천이고(코드는 `mujoong.timing.*`로 읽음), 두 곳에 모두 없는 값은 키 칸에 '레지스트리 미등재'로 적는다. 그 이름은 3.3이 레지스트리에 더해 확정한다(S-14).

| 항목 | 값 | 출처 | 설정 키 |
|---|---|---|---|
| HTTP → HTTPS · TLS 최소 | 301 · TLS 1.2 | NFR9.1 · J66 | Nginx 설정(키 없음) |
| HSTS max-age | 180일 (15,552,000초) | NFR9.1 · J66 | Nginx 응답 헤더 설정(레지스트리 키 없음, 3.3 확인) |
| 인증서 갱신 | 자동, 만료 30일 전 갱신 로그 | NFR9.1 | certbot 계열 설정(3.4 확정) |
| 보호자 세션 유휴 만료 | 30일, 갱신형 (2,592,000초) | 계약 7절 · A1 | `login.session-idle` |
| MUJOONG_SESSION 쿠키 Max-Age | 서버 유휴 만료가 정본, 쿠키는 같은 30일 | NFR9.2 | `login.session-idle` (쿠키도 이 값을 읽음, 별도 키 없음) |
| MUJOONG_DEVICE 쿠키 Max-Age | 400일, 갱신형 (34,560,000초) | 계약 7절 · B4 | `device.cookie-max-age` |
| MUJOONG_DEVICE_BOOTSTRAP 쿠키 Max-Age | 24시간 (86,400초), 코드 유효 기간과 같은 값 | BR1.5 · 계약 7절 | `bootstrap.code-ttl` (쿠키도 이 값을 읽음) |
| bootstrap 비밀 · 기기 토큰 크기 | bootstrap 비밀 128비트 이상 · 기기 토큰 256비트 (각각 SHA-256 해시 저장) | BR1.5 · BR3.1 · RD-14 | 코드 상수(시간 값 아님) |
| bootstrap 발급 제한 | IP당 1시간 20회 → 429 RATE_LIMITED. 부하 시험 환경(A6)에서만 설정으로 상향하고 prod 값은 20회 그대로 | 계약 7절 · BR1.6 | `bootstrap.rate-limit` |
| 등록 실패 제한 | 보호자당 10분 10회 → 429 TOO_MANY_ATTEMPTS | 계약 7절 · BR2.6 | `device.register-failure-limit` |
| 속도 제한 카운터 키 수 상한 | 10,000 (대리 제안 시작값, 시연 30쌍 대비 넉넉함) | BR1.6 · RD-2 | `mujoong.limits.rate-counter-max-keys` (reliability 3.2 레지스트리 이름) |
| REST 본문 상한 | 64KB (65,536바이트) | NFR9.9 · J66 | 레지스트리 미등재 (S-14) |
| WSS 텍스트 프레임 상한 | 8KB (8,192바이트) | NFR9.9 · J66 | 레지스트리 미등재 (S-14) |
| WSS 입력 오디오 프레임 | 965바이트 (머리 5 + 본문 960) | 계약 4.2 · J27 | 프로토콜 상수 |
| 내부 이벤트 본문 상한 | 2MB (밖에 닿지 않음) | BR5.4 | 레지스트리 미등재 (S-14) |
| HELLO 대기 | WSS 업그레이드 뒤 5초 → 4408 | 계약 7절 · BR3.6 · RD-14 | `device.hello-timeout` |
| Core 토큰 확인 응답 제한 | 1초 (넘으면 WSS를 1013으로 닫음) | BR3.4 · J26 | `mujoong.internal-http.verify-token-timeout` |
| 게이트 구간 확정 조용 시간 | 800ms | BR7.3 · J52 | `mujoong.gate.segment-quiet` |
| 조용한 재시도 간격 (D0 · T19) | 5초 | BR9.4 · BR9.7 | `mujoong.device-web.retry-interval` |
| 안전 시한 네 종 (조용히 처리) | 10 · 20 · 30 · 40초 | 계약 7절 · BR6.12 | `session.preparing-timeout` · `session.device-ack-timeout` · `session.closing-confirm` · `device.disconnect` |
| 실패 행 페이로드 보관 | 7일 | BR5.11 · J50 | `mujoong.retention.failed-payload` |
| 정리 주기 | Outbox/Inbox 페이로드 하루 한 번, 등록 코드 만료 점검 10분 | BR5.11 · BR1.4 · J50 | `mujoong.cleanup.messaging-interval` · `mujoong.cleanup.device-expiry-interval` (reliability 3.2 레지스트리 이름) |
| dev 시험 로그인 활성 | 기본 false, dev 프로필에서만 true 허용(demo · prod와 동시 활성이면 기동 거부) | BR8.3 · NFR9.11 | `mujoong.dev-login.enabled` (BR8.3의 키) |
| 백업 보관 | 7일 (소유: NFR4.11) | NFR4.11 · J62 | reliability-requirements.md 참조 |

## 4. 시험 · 검증 방법

| ID | 시험 | 단계 |
|---|---|---|
| NFR1.1 | 게이트 우회 주입(미승인 구간 · 빈 전사 · CLOSING 뒤 늦은 출력): 기기 재생 0, approved_for_display false, 12 응답에 없음. 재생기 단위 시험: OUTPUT_BEGIN 없는 0x02 프레임 폐기 | 3.5 구현 |
| NFR1.2 | ① 범주 존재 시험: 시드 사전에 계약 9절 category 7개(DIAGNOSIS · TREATMENT · SCORE · CAUSE · GENERALIZATION · EMOTION_CLAIM · PRESSURE) 각각 항목이 1개 이상, 범주가 비면 실패 ② 시드 사전 모든 항목 × (원형 · 조사 결합 · 띄어쓰기 변형) 매개변수화 시험 → 항목별 폐기 + 대체 문구 재생. 금지어 없는 일상 문장은 통과 | 3.5 구현 |
| NFR1.3 | 화면 여덟 곳 · 오류 code별 문구 체크리스트(진단 · 점수 · 의도 · 감정 단정, 출처 C.S-xx 또는 mockups 9절) | Build and Test |
| NFR2.1 | 체크리스트의 재촉 · 만류 · 보상 · 완료율 열 0건. 아이 기기에 실패 주입(429 · 503 · 닫기 코드 · 시한 FAILED)해 화면 유지 확인. 승인 문구 두 건 검토 | Build and Test |
| NFR3.1 | 공급자 요청을 허용 필드 스키마와 비교. 세션 패키지에 08 원문 · 이메일 · id를 넣어도 요청에 0건(공급자는 대역) | 3.5 구현 |
| NFR3.2 | 판정 기준 · 보호자 입력 · 이전 판정 자리에 표식 문자열을 넣어 요청 전체 0건. 요청 타입에 필드가 없음을 구조 시험(ArchUnit)으로 확인 | 3.5 구현 |
| NFR9.1 | 외부 포트 스캔(80은 301만, 443은 TLS 1.2+이고 1.1 이하 거부), HSTS max-age=15552000, 갱신 드라이런 로그 | Build and Test |
| NFR9.2 | 쿠키 속성 시험, 유휴 만료(login.session-idle를 줄여 주입) · 로그아웃 뒤 옛 쿠키 401과 서버 행 삭제, 로그인 뒤 세션 ID 교체 | 3.5 구현 |
| NFR9.3 | 면제 5경로 밖 변경 요청의 토큰 없음 · 틀림 → 403 CSRF_TOKEN_INVALID(register 포함), 면제 경로 통과, /api/device/** 전체 면제 없음을 경로 목록 시험으로 고정 | 3.5 구현 |
| NFR9.4 | 미등록 · 만료 · 해제 · 회전된 토큰 WSS → 4401, 허용 밖 Origin → 403, claim 재호출 410, DB에 원문 없음(해시만), 쿠키 속성, 발급 비밀 길이(bootstrap 비밀 128비트 이상 · 기기 토큰 256비트) 단위 시험 | 3.5 구현 |
| NFR9.5 | 다른 계정 id로 기기 · 세션 · 끝내기 · 12 조회 → 404(없는 id와 구분 불가), 세션 없음 → 401 | 3.5 구현 |
| NFR9.6 | 외부 경로 /internal/** → 403, 토큰 없음 · 틀림 → 401, 올바른 토큰으로도 보호자 API 호출 불가 | 3.5 구현 |
| NFR9.7 | Testcontainers에서 core_app의 realtime 접근, realtime_app의 core 접근, 앱 역할의 DDL이 모두 권한 오류. ArchUnit 앱 간 스키마 의존 금지 | 3.5 구현 |
| NFR9.8 | 시드 전사 · 토큰 · 쿠키 · 등록 코드 전체를 흘린 뒤 앱 로그(attrs 포함) · 오류 봉투 · 메트릭 · Nginx 접근 로그 · 전달 뒤 Outbox에서 grep 0건 | 3.5 구현 |
| NFR9.9 | bootstrap 21번째 429(같은 쿠키 반복은 미집계), 등록 실패 11번째 429와 성공 시 초기화, 64KB · 8KB 초과 · 규정 외 오디오 프레임, WSS 업그레이드 시각부터 시계 주입해 5초 직전 HELLO는 통과하고 직후는 4408, 형식 오류 4400. 부하 시험 환경이 아닌 설정(prod 값)에서 한도가 20회로 남는지 설정 대조 | 3.5 구현 |
| NFR9.10 | 저장소 비밀 스캔(gitleaks류, 이력 포함), 프런트 번들 · 이미지 레이어 grep 0건, 비밀 파일 권한 600 | Build and Test |
| NFR9.11 | 프로필 조합 기동: prod+dev · prod+enabled=true · demo+dev · demo+enabled=true는 기동 거부(프로세스가 뜨지 않음), dev+enabled=false · demo 단독 · prod 단독은 /api/dev/login 404, dev+enabled=true만 시드 보호자 세션 발급. 부하 시험용 프로필(A6)을 두면 같은 거부 조합에 넣는다 | 3.5 구현 |
| NFR9.12 | 정적 · API · Next.js 응답 헤더 시험, 잘못된 입력 400 VALIDATION_FAILED, 이어 붙인 SQL 정적 검사 | 3.5 구현 |
| NFR9.13 | CI의 Gradle 의존성 검사 · npm audit(high 이상) 결과와 예외 목록의 사유 · 만료일 | Build and Test |
| NFR9.14 | 사건 다섯 종을 일으켜 security_event_total{type} 증가와 한 줄 로그를 확인하고, 토큰 · 코드 전체가 없고 attrs 키가 사건 코드별 허용 키 표 안임을 확인 | 3.5 구현 |
| NFR12.1 | 채움 시험(3.5 구현): 이벤트 종류별로 Outbox/Inbox 새 행의 guardian_id가 비어 있지 않음(DeviceStatusChanged 포함). 삭제 지휘 시험(U2 통합): 다섯 표에서 guardian_id = :g 또는 aggregate_id가 삭제된 deviceId인 행 0건, 재호출 멱등, PENDING 제어 이벤트는 전달 뒤 정리, 다른 보호자 행은 그대로 | Build and Test |
| NFR12.2 | 세션 한 번 뒤 디스크 · DB · Outbox · 로그에서 시드 음성 바이트 0건, ACTIVE 전 프레임 폐기, 자동 덤프 설정 점검 | Build and Test |
| NFR12.3 | 정리 작업 시험: 끝난 행 payload {}와 purged_at, FAILED · DEAD는 7일(시계 주입) 뒤 비움, 원문 시험 이벤트(대역)는 전달 직후 비움 | 3.5 구현 |
| NFR12.4 | 승인 항목(D-14)에 ① 백업 충돌 해소 ② 정식 사전(U3) 전 실제 아동 데이터 시연 금지가 들어 있는지, 백업 대상 DB가 합성 데이터인지 문서 · 출처 점검 | Build and Test |

## 5. 열린 질문과 가정

### 5.1 사람이 정할 질문 — 백업과 삭제의 충돌, 정식 사전 전 실제 데이터 (NFR12.4)

- 충돌: NFR12는 「삭제 완료 뒤 복구 수단 없음」을 요구하고 NFR4.11은 매일 논리 백업을 7일 보관한다(J62). 삭제 전에 만든 백업에 그 보호자 · 아이 데이터가 최대 7일 남고, 그 백업을 복원하면 삭제된 계정이 되살아난다.
- 현재(대리 판단 J62): 시연 서버는 합성 데이터에만 쓴다. 충돌은 실제 데이터에서만 생기지만 해소된 것은 아니다.
- 결정자 · 시점: 팀장(준하)과 약관 · 처리방침 문구 담당(A, OQ7). 실제 보호자 · 아이 데이터를 받기 전에 정한다. 결과는 실제 데이터 운영 전 승인 항목 D-14(원 요구사항의 미결정 항목: 실제 아동 데이터 처리는 공급자 · 동의 · 보관 조건 승인 뒤)에 넣는다.
- D-14에 더하는 항목(RD-10): 정식 금지 표현 사전(U3)이 들어오기 전에는 실제 아동 데이터로 시연하지 않는다. 시드 사전은 7범주의 대표 패턴뿐이라 감정 · 의도 단정 같은 발화를 모두 막는다고 보장하지 못한다. 결정자 · 시점은 위와 같다.

| 안 | 내용 | 얻는 것 | 잃는 것 |
|---|---|---|---|
| A (현재) | 합성 데이터 한정을 유지하고 실제 데이터는 받지 않는다 | 충돌 없음, 일정 영향 0, 백업 유지 | 실제 사용자로 시연할 수 없다 |
| B | 실제 데이터를 받되 백업 7일 보관을 처리방침에 적고, 복원 때 삭제 이력(삭제된 보호자 id 목록)을 다시 적용한다 | 백업 유지(RPO 24시간), 구현이 작다 | NFR12 문구를 「삭제 후 최대 7일 뒤 백업에서 소멸」로 완화해야 하고 삭제 이력이 식별자를 남기며 약관(OQ7)에 영향 |
| C | 실제 데이터를 받되 백업을 끄거나 삭제 때 해당 백업을 다시 만든다 | NFR12를 그대로 지킨다 | RPO가 사실상 없어지거나(NFR4.11 불충족) 구현 비용이 7일 일정을 넘길 수 있다 |
| D | 백업을 암호화하고 계정별 키를 삭제 때 폐기한다 | 백업 유지와 복구 불가를 함께 얻는다 | 구현 · 키 관리가 일정 밖이고 키가 새 비밀이 된다 |

권고(결정 아님): 사람이 정할 때까지 A를 유지하고, 실제 데이터가 필요해지면 가장 싼 B를 처리방침 문구와 함께 택한다. 이 문서는 어느 안도 확정하지 않는다.

### 5.2 가정과 나머지 열린 질문

| 번호 | 종류 | 내용 | 정할 곳 |
|---|---|---|---|
| J62 · J66 · J67 · J68 · J72 | 대리 판단 | 백업은 합성 데이터에만(J62). TLS · HSTS · 헤더 · 비밀 스캔 · 의존성 점검 · 크기 상한은 시연 규모 시작값이고 WAF는 없음(J66). 로그는 최상위 여섯 필드 + attrs(사건 코드별 허용 키 표) 화이트리스트(J67, RD-6으로 문구 개정). 외부 경보 없이 수동 점검(J68). 삭제 참가자 셋을 U1이 구현하고 삭제 뒤 조회 0건을 시험(J72) | 사람 확정 |
| J29 · J44 · J50 · J58 | 앞 단계 가정 | 부트스트랩 · 기기 토큰 방식, dev 로그인 한정, 페이로드 비움 정책, 삭제 참가자 셋을 이 문서가 그대로 받는다 | 각 소유 단계 |
| S-1 | 해석 | NFR3.1의 필드 화이트리스트는 텍스트 · 메타 필드에 적용하고, 아이 입력 음성 프레임은 처리 목적 자체라 대상 밖으로 본다 | 사람 확인 |
| S-2 | 열린 질문 | REST 본문 64KB 초과 응답: BR8.6 코드 목록에 413에 해당하는 코드가 없다. 413 + 새 코드를 J59류 계약 추가로 올릴지 400 VALIDATION_FAILED로 묶을지 | 3.3 · 3.5 |
| S-3 | 열린 질문 | WSS 8KB 초과 · 규정 외 오디오 프레임의 처리: 닫기 코드 표에 1009가 없다. 4400(PROTOCOL_ERROR)이 가장 가깝다 | 3.3 |
| S-4 | 열린 질문 | 시드 사전의 「정상」은 일상어(예: 정상적으로)에도 나와 과잉 폐기가 날 수 있다. A5로 범주가 7개로 늘면 같은 위험이 커지므로 시드 패턴은 명시적 표현 위주로 고른다. 폐기 비용은 대기 문구 재생이다. 문맥 사전은 U3가 정교화한다 | U3 |
| S-6 | 열린 질문 | 내부 토큰을 호출 방향별(Core → Realtime, Realtime → Core) 둘로 나눌지. 같은 호스트 환경 변수라 효과는 제한적이다 | 3.3 |
| S-7 | 열린 질문 | 허용 Origin의 실제 값, 인증서 방식, CSP connect 값, Next.js nonce 방식 | 3.3 · 3.4 |
| S-8 | 열린 질문 | CSRF 토큰 취득 경로와 재취득 시점(면제 목록은 BR8.8로 확정) | 3.3 |
| S-9 | 열린 질문 | realtime_device_connection(U3 소유) 행은 device_id로 연결돼 있는데 삭제 참가자가 BR2.10 · BR5.14에 없다. 계정 삭제 뒤 이 행이 남는지 확인 필요 | U3 · U4 |
| S-10 | 열린 질문 | 원문이 든 SessionRecordCaptured가 FAILED로 끝나면 BR5.11대로 7일간 원문이 남는다. FAILED 즉시 비우고 해시만 남기는 대안을 검토하되, A1(FAILED 제어 이벤트 재무장)은 FAILED 행의 페이로드가 남아 있어야 다시 보낼 수 있으므로 함께 정한다 | U4 |
| S-11 | 사람이 정함 | 공급자 쪽 음성 · 전사의 보존 · 학습 이용 조건은 우리 통제 밖이다. 실제 아동 데이터 전에 공급자 조건을 확인한다(음성 모델 공급자 요구사항 명세 v0.1의 GL-11, 승인 항목 D-14) | 사람 · 실제 데이터 전 |
| S-12 | 열린 질문 | 자동 힙 덤프 · 요청 본문을 찍는 디버그 로그의 기본값 | 3.4 |
| S-13 | 열린 질문 | 기기 토큰은 서버 만료가 없고 쿠키만 400일이다(BR3.3). 탈취 대비는 해제뿐이므로 실제 기기 운영 전 운영용 기기 인증(ADR-008 TBD)을 다시 본다 | 사람 · 실제 기기 전 |
| S-14 | 열린 질문 | 보안 전용 값 셋(REST 본문 64KB · WSS 텍스트 프레임 8KB · 내부 이벤트 본문 2MB)과 HSTS max-age가 reliability 3.2 키 레지스트리에 없다. 레지스트리에 이름을 더할지, Nginx · 프레임워크 고정 설정(키 없음)으로 둘지. 이 문서는 새 키를 만들지 않았다 | 3.3 |

### 5.3 해결됨

| 번호 | 종류 | 내용 | 해결 |
|---|---|---|---|
| S-5 | 해결됨(NFR9.11 반영, FD 보강 A2) | demo에 dev 프로필이 함께 켜지고 enabled=true인 조합을 BR8.3이 기동 거부하지 않는 구멍. 권고대로 demo 또는 prod가 켜진 채 dev 프로필 · enabled=true인 조합을 모두 거부한다 | NFR9.11 행 · 2.1 · 4절에 반영. BR8.3의 거부 조건을 prod에서 demo · prod로 넓히는 규칙 보강은 A2로 3.5가 반영 (RD-9) |

### 5.4 FD 보강 후보 A1 ~ A6

승인 완료된 functional-design은 고치지 않는다. 아래는 FD와 어긋나거나 FD를 넓히는 보강이며, 3.5에서 FD 규칙에 반영한다. 여섯 건은 모든 NFR 문서 5절에 같은 번호로 오르고, 이 표의 마지막 칸은 이 문서와 닿는 곳이다.

| 번호 | 보강 내용 | 걸리는 FD 규칙 | 이 문서와 닿는 곳 |
|---|---|---|---|
| A1 | FAILED 제어 이벤트(DeviceReleased · DeletionRequested · DeletionCompleted · SessionRecordCaptured) 재무장: 앱 시작 시 FAILED 제어 이벤트를 PENDING으로 되돌려 반드시 전달한다. 일반 이벤트의 20회 상한은 유지한다 | BR5.3 · BR5.12 | NFR9.4(해제 신호가 가야 옛 소켓이 4401로 닫힘)와 NFR12.1. 주의 둘: BR5.14 (a)는 FAILED Outbox 행을 지우므로 FAILED 제어 이벤트가 재무장 전에 지워지지 않게 맞춘다. 재전송에는 FAILED 행 페이로드가 남아야 하므로 NFR12.3의 7일 비움과 S-10 대안도 함께 본다. 소유 문서는 reliability NFR4.7 |
| A2 | BR8.3 거부 조건을 prod에서 demo · prod로 넓힌다(demo+dev 또는 demo+enabled=true도 거부) | BR8.3 | NFR9.11 직접 반영, S-5 해결 |
| A3 | Outbox/Inbox 네 표에 trace_id 열(선택)을 더한다 | entities의 Outbox/Inbox | 로그 상관(NFR9.15, observability). 페이로드가 아닌 식별자 열이라 NFR12.3 비움 대상이 아니고 삭제 시험(NFR12.1)에서는 같은 행으로 함께 지워진다 |
| A4 | 모든 Outbox/Inbox 행에 guardian_id를 채운다: DeviceStatusChanged는 연결 행의 guardian_id, Core 수신 쪽은 aggregate_id로 core_device를 조회해 채운다 | entities의 guardian_id · BR5.14 | NFR12.1 완료 기준과 시험(RD-11) |
| A5 | BR7.4 (나)의 시드 사전을 계약 9절 category 7개마다 최소 1항목으로 넓힌다 | BR7.4 | NFR1.2 완료 기준과 2.6 대표 패턴 표(RD-10) |
| A6 | 부하 시험 전용 시드: 보호자 · 아이 · 기기 30세트를 DB에 직접 넣고 기기 토큰 해시를 미리 계산하며 bootstrap.rate-limit은 설정으로만 올린다 | BR1.6 · BR8.3 · BR8.4 | NFR9.9의 prod 값(20회) 유지. 시드 빈은 dev(또는 3.5가 두는 load-test) 프로필에서만 등록하고, 선계산 토큰 해시는 시험용 가짜 값이다. load-test 프로필을 두면 NFR9.11 거부 조합에 넣는다. 쓰는 쪽은 scalability NFR7.2와 performance NFR5.5 |

### 5.5 3.2 재점검 결정 RD-n

질문 파일은 J72에서 멈추고 J73 이후로 잇지 않는다. 재점검의 결정은 번호 n이 지시서의 결정 번호와 같은 RD-n으로 각 문서 5절에 기록한다. 아래는 이 문서와 닿는 결정이며 모두 팀장 대리 판단이라 사람 확인 대상이다(나머지 번호는 해당 문서에 있다).

| 번호 | 결정 | 이 문서의 반영 | 확정 |
|---|---|---|---|
| RD-1 | FD 보강은 FD를 고치지 않고 A1 ~ A6으로 올려 3.5가 반영한다 | 5.4 | 사람 확인 |
| RD-2 | 키 이름의 단일 원천은 reliability 3.2 레지스트리다. 속도 제한 카운터 상한은 mujoong.limits.rate-counter-max-keys | 3절, S-14 | 사람 확인 |
| RD-6 | 로그는 최상위 여섯 필드 + attrs(사건 코드별 허용 키 표, 숫자 · 열거값 · UTC 시각만). 전사 · 원음 · 토큰은 attrs에도 불가 | NFR9.8 · NFR9.14 · 2.5 · 5.2의 J67 | 사람 확인 |
| RD-9 | demo 또는 prod에 dev 동시 활성 또는 enabled=true면 기동 거부 | NFR9.11 · 2.1 · 2.8 · 4절 · 5.3 | 사람 확인 |
| RD-10 | 시드 사전은 category 7개마다 최소 1항목, 정식 사전(U3) 전 실제 아동 데이터 시연 금지를 D-14에 넣는다 | NFR1.2 · NFR12.4 · 2.6 · 5.1 | 사람 확인 |
| RD-11 | 모든 Outbox/Inbox 행에 guardian_id를 채우고 삭제 시험은 guardian_id 또는 삭제된 deviceId 기준 0건 | NFR12.1 · 2.7 · 4절 | 사람 확인 |
| RD-12 | 30쌍 부하 시험은 전용 시드로 하고 bootstrap.rate-limit은 그 환경에서만 올린다. NAT 뒤 21대 이상 운영 주의 | NFR9.9 · 3절 · 5.6 | 사람 확인 |
| RD-14 | HELLO 5초는 WSS 업그레이드 뒤부터, 비밀 크기는 bootstrap 비밀 128비트 이상 · 기기 토큰 256비트(각각 SHA-256 해시 저장) | NFR9.9 · NFR9.4 · 2.2 · 2.4 · 3절 | 사람 확인 |

### 5.6 운영 주의 — NAT 뒤 기기 21대 이상

- bootstrap 발급은 IP당 1시간 20회(`bootstrap.rate-limit`)라서 같은 공인 IP(NAT) 뒤에서 기기 21대째 등록부터 429 RATE_LIMITED가 된다. 같은 쿠키의 반복 요청은 세지 않으므로 이미 코드를 받은 기기는 영향이 없다.
- 시연 장소의 기기 수를 20대 이하로 하거나, 시연 환경 설정으로 한도를 올리고 그 값을 NFR9.9의 수치 변경으로 기록한다. prod 값은 20회 그대로다. 30쌍 부하 시험은 이 한도에 걸리지 않도록 전용 시드(A6)로 만든다.
- 429를 받은 아이 기기는 기술 문구 없이 5초 뒤 다시 시도한다(BR9.4).
