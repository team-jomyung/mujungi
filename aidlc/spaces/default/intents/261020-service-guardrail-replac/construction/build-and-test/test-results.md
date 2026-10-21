# Build and Test 실행 결과

## 현재 재진입 결과 — 2026-10-21T06:53:34Z

FAIL. U16 수정·Neo4j 초기화 예산 분리 이후 백엔드 전체 4,118개와 프런트 1,055개, lint·production build가 통과했다. 두 체크포인트가 같은 명령을 각 실행한 중복은 증거상 구분하며 합산하지 않는다. U17의 새 늦은403 경계 시험은 11개 중 기존8 PASS·신규3 FAIL이다. 이후 전체 소스가 녹색이라고 표현하지 않는다.

이번 재진입은 기존 산출물을 Modify로 보존한다. 전체 빌드는 직전 체크포인트 결과를 참고했다. 상위 운영 코드 결함을 추가 회귀에서 확인해 즉시 수정 경로로 돌아간다. 이번 재진입의 단위 지시서25개 명령과 다른 통합 검사는 아직 재실행하지 않았으며 이전 실행을 이번 실행으로 계상하지 않는다. 성공하지 않은 목표는 아래 최종 행렬에서 Not Met 또는 Unverified다.

### F-R04 — 이전 요청의 늦은403

실행: `npx vitest run tests/parent/common/guardrail-live-updates.test.tsx` (frontend). 최종 재현 실행 2026-10-21 15:53:26 KST, 종료1, 11개 중8 PASS/3 FAIL, 1.25초. 최초 실행에서 goodbye 시험 값을 NOT_STARTED로 주었고 파서가 UNKNOWN으로 변환했다. 명시적으로 유효한 STOPPED로 바로잡아 다시 실행해 동일한3개 실패를 확인했다. 시험 하한·운영 코드 변경은 없다.

identity, sequence, leave 세 순서 모두 이전 조회를 대기시키고 현재 조회 ACTIVE 성공/hasCurrentLiveFacts=true를 확인한 뒤 이전 FORBIDDEN403을 반환한다. 31행 expected=current accepted object, actual=undefined로 실패한다. 실제 QueryClient/ApiClient/공통 경계를 실행하고 HTTP 응답만 대역이다. `liveUpdates.ts` catch가 generation/session/sequence/abort 검증 전에 invalidateDeniedSession을 실행하는 것이 원인이다. 리뷰 R-04의 단순 읽기 재현을 실제 회귀로 확인했다.

최신 U16 기능 시험 RT1112/Core237/contract8, 전체 모듈 contract330/Core1759/RT2029 PASS. 전체 LINE은 98.54%/96.27%/93.73%, 기능92.80%/96.80%/100%다. 프런트 기능318 LINE99.12%, 전체1055 LINE98.58%는 신규3개 실패 시험 작성 전의 증거다. 프런트 변경 없이 같은 빌드가 반복된 이유는 유닛별 검증 명령에 양쪽 전체 검사가 함께 등록되어 있기 때문이다.

### 현재 Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| u16-service-guardrail/NFR1.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md`<br>`aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 로그는 기존 허용 식별자·판/해시·이유 코드·시각/지연만 사용하고 원문/원음/키·모델 자유서술 추가0건 / 새 일반 로그·문자열 표현·공급자 메타데이터/공개 guardrail 객체에 전사/원음·키·숨긴 타인 정보0건 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR3.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 변경 층 구현 뒤 해당 층 단위/핵심 통합 시험을 작성·실행한다. 기존 적용 모듈 및 기능 범위 줄80% 하한·ArchUnit/계약 검사 유지 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR3.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 승인된 단위 명령으로 각 Unit 결과를 확인하고 최종 Build and Test에서 기존 bash scripts/verify.sh와 변경 경계 통합을 수행한다. 실패/전제 미준비는 PASS 아님 | verify.sh 실행 전 훅 차단; 전체 흐름 미검증 | test-results.md §미검증 목록 | build-and-test | Unverified |
| u16-service-guardrail/NFR3.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | actual local/J6 요청 정책, 안내/참여·시계·현재성·사실 수명과 RP 표시 소비를 분리해 시험한다. 결과 주입만으로 요청 전달 합격을 주장하지 않는다 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR4.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 자동 검증은 가짜 공급자/합성 자료다. 실모델·실기기·실아동 성공0건을 성공으로 표시하지 않고 증거 유형별 결과를 분리한다 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR4.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 실제 GPT-Live 낭독/파일화는 생성 호출과 원문·청취·길이/형식·승인 등록의 각각 실제 결과가 있어야 완료다. 미호출/미검수는 미완료 | 실제 승인 음성 생성·청취·등록 미실행 | test-results.md §미검증 목록 | build-and-test | Unverified |
| u16-service-guardrail/NFR5.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 정본 SHA256→적용본 판/해시·편차→앱 산출물→요청 대역/시험의 연결을 유지한다 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR2.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/performance-requirements.md` | 기존 참여20초/재안내20초·공백4분/6분·재접속40초·종료확인30초·모호종료20초·승인재생20초와 재생/출력대기/기술 일시정지 제외를 유지한다 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR6.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/performance-requirements.md` | 내부 조회1초/연결500ms·무재시도, 판정4초·JEV800ms의 기존 마감과 큐 설정을 유지한다. 새 폴링/자동 AI 호출/재시도 루프0 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR5.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/performance-requirements.md`<br>`aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/scalability-requirements.md` | 정책 자원은 앱 시작 적재·세션 불변 참조로 사용한다. 일반 턴마다 원본 documents/파일 재읽기0 / 정책 본문은 불변 앱 자원/세션 참조로 공유하고 요청마다 파일/정본을 반복 읽지 않는다 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR2.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 안내 미확인·현재 참여 미수락·검사 실패·지난 세대/모드/목적·중복/종료 경합에서 미확인 일반 음성0회, 중복ACTIVE/첫 응답0회, 종료 뒤 일반 재생 재개0회 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR2.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 참여/중요 확정 사실의 기록은 관련 상태 전이와 원자적이다. 저장 실패/경합 패자의 출력 효과0회. 재시작/actor 제거/legacy/fallback은 근거 없는 사실을 복구하지 않는다 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR2.4 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 확인 질문이 미전달·실패·만료·중단됐을 때 ASKING이 무기한 남지 않는다. 실패 사건 또는 기존 재생 시한에서 현재 질문/보류 귀속을 해제할 처리 경로가 있어야 한다. 미전달 질문 답 추정/반복 질문0 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR6.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 기존 저장/삭제·동의·권한·연습·JEV 판/범주·공급자4type을 보존하고 새 자동AI 호출0 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR4.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 기존 톤/무음이나 synthetic 완료 표식은 실제 안내 전달 합격이 아니다. 실제 자원 미준비는 명시적 기능 미완료/보류로 보고한다 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR6.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/scalability-requirements.md` | 기존 actor/메일함/입출력 큐·재생 상한과 단일 소유를 유지하고 새 프로세스·큐 제품·자동 확장 구성0 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR1.4 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/scalability-requirements.md`<br>`aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 확정 사실은 세션의 기존 이벤트 수명 안에만 남긴다. 화제/도움 상태의 새 장기 원문 목록0 / 새 사실 사건은 코드/순번/서버 시각·현재성 메타데이터만 기존 세션 수명에 남긴다. 참여 전 전사/원음의 새 영속0, 별도 원문 복제0. 기존 삭제/권한 처리에 포함 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR1.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 기존 세션·아이·기기 소유/동의·내부 인증을 유지한다. 다른 계정/기기/세션·옛 연결/재생 표식으로 안내·참여·출력 승인0회 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR1.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 모델 문맥은 기존 허용 이름/나이/관심사만 해당 상한/이스케이프로 제공한다. 내부 ID·키·보호자 입력 원문·타인 정보 추가0 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR5.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 지시형/마크업/제어문자 아이 정보는 운영 정책/권한을 바꾸지 못한다. 사용자 원본/ZIP/Zone.Identifier를 실행 자원으로 읽지 않는다 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR2.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 현재 세션/요청 순서를 지켜 다른 세션·취소/늦은 응답·낮은 상태판의 현재 화면 적용0회. 같은 판의 최신 facts는 매번 반영하고 null을 이전 성공으로 보충0회 | 새 성공 이후 늦은403이 캐시 제거: 인증 세대·요청 순번·화면 이탈/재진입 3회귀 실패 | frontend/tests/parent/common/guardrail-live-updates.test.tsx:31 | build-and-test | Not Met |
| u17-guardrail-presentation/NFR2.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 구조 오류/구버전/미래 enum/자료 부재에서 거짓 REFUSED·NO_RESPONSE·실제 인용·인사 재생·기기 정지 표시0회. 유효한 나머지 진행 정보는 유지 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR2.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 현재 안내 완료는 실제 재생 종료 뒤에만 전송. 중단/실패/옛 재생 콜백의 완료 보고0회, 같은 명령 중복 실행0회. 기기 현재 정리 전 종료 확인0회 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR3.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | test-after로 각 변경 층 구현 뒤 해당 시험 작성·실행. Standard의 컴포넌트당5–8개와 핵심 경계 통합 시험. 기존 단언/계약/import 경계/skip 정책 유지 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR3.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 프런트 모듈 전체와 실제 수정 표면을 포함하는 guardrail-presentation 기능 범위의 LINE 각각80% 이상. 빈 범위/없는 파일은 실패 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR3.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 변경 뒤 lint·전체 프런트 coverage 시험·production build 통과. Build and Test에서 기존 전체 verify.sh와 실제 대역 서버 통합 검증 | verify.sh 실행 전 훅 차단; 전체 흐름 미검증 | test-results.md §미검증 목록 | build-and-test | Unverified |
| u17-guardrail-presentation/NFR4.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 파서/컴포넌트/기기 웹 대역, 가짜 공급자 통합, 실제 모델/기기/아동 증거를 각각 구분. 자동 시험에서 실제 유료 공급자 호출0회 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR4.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 실제 승인 음성 미준비와 U16 참여 전사 지적을 UI 시험 PASS로 해소했다고 보고0회. 최종 통합 전에 미해결 상태/후속 검증을 명시 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR5.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 한글·따옴표·역슬래시·줄바꿈·마크업처럼 보이는 text가 파싱/인용에서 손상·실행0회. 기본 등록 표현으로 대체0회 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR5.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 키보드 조작·팝업 접근 가능한 이름/초점·동일 상태 반복 낭독 방지·색 외 설명을 유지. 320px 및200% 확대에서 변경한 긴 문구/인용이 조작을 가리거나 가로 넘침0건 | 실브라우저 320px/200%·기기 점검 미실행 | test-results.md §미검증 목록 | build-and-test | Unverified |
| u17-guardrail-presentation/NFR6.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 기존 parent poll-interval(2초)·진행 요청 공유·retry:false·no-store·요청 취소와 시간 상한을 유지. 인용 조회/AI 호출/재시도 루프/장기 facts 캐시 추가0개 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR6.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 기존 C1/C2/C3 wire·오류·연습·종료 확인 의미 유지. FREE_TALK facts를 연습/미확인 모드에 적용0회. 새 runtime dependency0개 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR1.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/security-requirements.md` | 현재 인증/세션 소유·쿠키/CSRF·no-store를 유지. 로그아웃/권한 실패/다른 세션 전환 뒤 이전 아이 facts/인용/말풍선의 새 세션 재사용0회. 서버 소유 확인을 클라이언트 검증으로 대체하지 않음 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR1.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/security-requirements.md` | 새 브라우저 로그/URL/query key/웹 저장소에 전사 원문·원음·정책 본문/해시·토큰/비밀 값 추가0개. 인용은 이미 허용된 현재 세션 items만 안전한 텍스트로 렌더링하고 HTML 실행0회 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR1.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/security-requirements.md` | UI는 참여·원음 시작·기기 정지·삭제/공유 완료 권한을 만들지 않음. 승인 정보 부재 때 완료를 표시0회. 기존 수동 스택/배포/실제 아동 입력·유료 공급자 호출은 수행하지 않음 | 현재 유닛 수정 후 단위·통합·전체 검사 증거로 경계 확인 | ../u17-guardrail-presentation/code-generation/verification-results.md | build-and-test | Met |
| RG1 | ../u16-service-guardrail/code-generation/code-generation-plan.md §RG1 Acceptance Matrix | 완료 녹음/현재 참여 인계, 명확한 재인사 출력0, 정상 질문/인용/연습 보존, 추가 AI0 | U16 기능1357 및 전체 backend4118 시험 통과; 실제 음성 등록은 별도 미완료 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| INIT-BUDGET | ../u16-service-guardrail/code-generation/code-generation-plan.md §Startup Initialization Budget | 일반 요청2초·시작 전체10초·실패 정리 합산2초, 성공 후에만 ready | 실제 2.3초 초기화 성공/일반2초 만료, 실제 cold Graph22 및 설정58 시험 통과 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| TC-U16 | ../u16-service-guardrail/code-generation/code-generation-plan.md §Testing Contract | 기존 시험 유지, 전체/기능 LINE80% | 기능1357 PASS; 전체 backend4118 PASS; 모듈/기능 LINE80% 이상 | ../u16-service-guardrail/code-generation/verification-results.md | build-and-test | Met |
| TC-U17 | ../u17-guardrail-presentation/code-generation/code-generation-plan.md §Testing Contract | 기존 시험 유지, 전체/기능 LINE80% | 앞선 전체1055 PASS/LINE98.58%; 이후 추가 회귀3 FAIL로 현재 녹색 아님 | frontend/tests/parent/common/guardrail-live-updates.test.tsx | build-and-test | Not Met |
| XTRACE | 요구/스토리→유닛 traceability.json | 모든 FR/NFR/AC OK 및 실제 단일 파일 | 77개 중76개 OK/존재, NFR3 Deferred 유지 | cross-unit-traceability.md | build-and-test | Not Met |

아래는 첫 실패 실행 당시의 원본 기록이다. 현재 판정에는 위 행렬을 사용하며 과거 실패·차단과 Loop-Back Log는 삭제하지 않는다.


## 결과

FAIL. 기존 기능 범위1299개(백엔드)·312개(프런트)가 통과했지만, 새로 추가한 실제 실행 회귀4개가 실패했다. 단위 지시의 서로 다른21개 명령을 각1회 실행했다.20개 종료0, 1개는 잘못된 package 필터로 시험을 찾지 못해 실패했고 실제 패키지로 바로잡은 추가 명령은 종료0이다. 명령 사이에 겹치는 시험이 있으므로 전체 실행 수를 더해 유일 시험 수로 보고하지 않는다. build-instructions.md의 전체 빌드 명령은 직전 동일 운영 소스의 체크포인트 종료0을 참고하며 이 단계에서 별도 재실행하지 않았다. 이후 추가 회귀가 실패했으므로 전체 소스가 현재 녹색이라고 하지 않는다.

## 명령별 결과

정확한 원래 명령/출력과 실행 상태는 command-results.json에 보존한다.

| 유닛 | 지시서 명령 순서(1부터) | 결과 |
|---|---|---|
| u17-guardrail-presentation | 1 | exit 0 |
| u17-guardrail-presentation | 2 | exit 0 |
| u17-guardrail-presentation | 3 | exit 0 |
| u17-guardrail-presentation | 4 | exit 0 |
| u17-guardrail-presentation | 5 | exit 0 |
| u16-service-guardrail | 1 | exit 0 |
| u17-guardrail-presentation | 6 | exit 0 |
| u17-guardrail-presentation | 7 | exit 0 |
| u17-guardrail-presentation | 8 | exit 0 |
| u17-guardrail-presentation | 9 | exit 0 |
| u17-guardrail-presentation | 10 | exit 0 |
| u17-guardrail-presentation | 11 | exit 0 |
| u16-service-guardrail | 2 | exit 0 |
| u16-service-guardrail | 3 | exit 0 |
| u16-service-guardrail | 4 | exit 0 |
| u16-service-guardrail | 5 | exit 1 |
| u16-service-guardrail | 6 | exit 0 |
| u16-service-guardrail | 7 | exit 0 |
| u16-service-guardrail | 8 | exit 0 |
| u16-service-guardrail | 9 | exit 0 |
| u16-service-guardrail | 10 | exit 0 |

### 지시서 수정1 — 실행 대상 package

StopConfirmServiceGuardrailTest는 session이 아닌 dialog package에 있다. 단위 지시서와 통합 빌드 지시의 필터만 실제 파일로 고쳤다. 수정 후 정확한 명령을 재실행해21초/종료0이었다. 시험 단언·하한·runner·시간 값 변경은 없다.

## 실패 재현과 원인

### B-R01 — 안내 이전 입력의 늦은 꼬리

`./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.session.ParticipationLateItemServiceGuardrailTest -Pmujoong.test.db=docker`(backend), 종료1, 2개 중2개 실패.

- preNoticeItemCannotJoinWhenItsTailArrivesAfterDrainQuiet: 안내 전 delta→실제 안내 COMPLETED→quiet 만료→같은 item delta→quiet 만료.
- preNoticeItemThatAlreadyWentQuietCannotBecomeNewAcceptance: 안내 전 delta→quiet 만료→실제 안내 COMPLETED→같은 item delta→quiet 만료.
- 두 경우 모두 `ParticipationLateItemServiceGuardrailTest.java:59`에서 expected WAITING_CHILD, actual ACTIVE다. 최초 실패는 각각23/34행 호출이다. 가짜 공급자/합성 자료의 실제 SessionExecutor·메일함·시계 경로이며 실공급자/실기기가 아니다.
- 원인: 참여 창에서 묶음 ID를 지우고 draining을 quiet 후 해제하여 이전 item ID가 새 입력처럼 현재 시작 시각을 얻는다. 이미 quiet 된 이전 ID도 보존되지 않는다. 기존 앞꼬리 시험의 직후 경계만으로는 이 경우를 잡지 못했다.

### F-R01/F-R02 — 연결 복구 신뢰와 GET403 캐시

`npx vitest run tests/parent/features/done/guardrail-review-regression.test.tsx`(frontend), 종료1, 2개 중2개 실패.

-38행: offline→online 뒤 새 조회를 지연시켰지만 이전 done-goodbye 요소가 남는다(expected null).
-64행: 유효한 CHILD 종료 인용 수신→현재 세션 GET403 뒤 done-stop-quote가 남는다(expected null).
- 실제 ParentProviders/QueryClient/ApiClient/DoneScreen과 DOM을 실행하고 fetch만 대역이다. 실브라우저/실기기 확인이 아니다. navigator 속성은 finally/afterEach로 복원한다.
- 원인: trusted가 online&&error=null만으로 복구되고, 최종 세션403이 해당 세션 cache/request 귀속을 제거하지 않는다. 기존 CSRF403 갱신 의미는 이번 실패와 구분한다.

### XTRACE — 최종 직접 추적

cross-unit-traceability.md: FR31개와 상위 NFR6개에 직접 OK/존재 파일 연결이 없다. AC40개는 U16 파일로 연결된다. U17 다중 경로/설명 target46개는 센서 기준 단일 파일이 아니며 문서 매핑을 고쳐야 한다. 연결 누락을 그 수만큼의 구현 부재로 표현하지 않는다.

## 커버리지와 증거 범위

| 구분 | 시험 | LINE | 결과 |
|---|---:|---|---|
| U16 realtime 기능 |1095|4122/4438=92.88%|PASS|
| U16 core 기능 |196|351/369=95.12%|PASS|
| U16 contract 기능 |8|19/19=100%|PASS|
| U17 frontend 기능 |312|763/770=99.09%|PASS|
| 추가 backend 회귀 |2|위 기능 측정 이후 작성|2 FAIL|
| 추가 frontend 회귀 |2|위 기능 측정 이후 작성|2 FAIL|

기존35개 backend/16개 frontend 운영 파일의 기능 범위를 유지했다. 새 회귀는 별도 표기하며 기존 scope/측정 하한을 줄이지 않았다. 직전 전체 checkpoint의 backend4087·frontend1049 PASS/lint/build·frontend LINE98.56%는 이전 실행 참고 증거다. 현재4개 실패가 해소됐다는 근거로 사용하지 않는다. runner가 생성하는 원본 XML/coverage는 다음 필터 실행이 덮어쓰므로 기능 수치는 해당 실행 직후 읽어 위 표/command-results.json에 보존했다.

## 미검증 목록

- 전체 `bash scripts/verify.sh`: PATH에 GNU date를 둔 호출과 plain 호출 모두 PreToolUse에서 감사 기록 직접 쓰기로 차단됐다. 실제 script는 실행하지 못했다. 차단을 우회하거나 훅/검사를 끄지 않았다. 따라서 A/B/C/P/T 성공이나 실제 대역 서버 끝-끝 성공으로 보고하지 않는다. 이 명령이 왜 감사 쓰기로 판정됐는지는 미확인이다.
- 실제 승인 말소리: 운영 manifest는 빈 상태로 실패 보류다. 생성·청취/본문·형식/길이 검수·등록 미실행. 톤/합성 대역 성공은 실제 말소리 합격이 아니다.
- 실제320px/200%·브라우저 키보드·기기 조작/회전: 미확인. 사용자가 키링 창을 문의한 뒤 이번 실행에서 Chromium/GUI/Next 서버를 다시 시작하지 않았다. jsdom 접근성 성공으로 대체하지 않는다.
- 실제 유료 공급자·실기기·실아동·배포·운영 migration·수동 mujoong-manual 변경: 수행하지 않았다.

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| u16-service-guardrail/NFR1.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md`<br>`aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 로그는 기존 허용 식별자·판/해시·이유 코드·시각/지연만 사용하고 원문/원음/키·모델 자유서술 추가0건 / 새 일반 로그·문자열 표현·공급자 메타데이터/공개 guardrail 객체에 전사/원음·키·숨긴 타인 정보0건 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR3.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 변경 층 구현 뒤 해당 층 단위/핵심 통합 시험을 작성·실행한다. 기존 적용 모듈 및 기능 범위 줄80% 하한·ArchUnit/계약 검사 유지 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR3.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 승인된 단위 명령으로 각 Unit 결과를 확인하고 최종 Build and Test에서 기존 bash scripts/verify.sh와 변경 경계 통합을 수행한다. 실패/전제 미준비는 PASS 아님 | 전체 verify.sh가 프로젝트 훅에서 실행 차단 | test-results.md 미검증 목록 | build-and-test | Unverified |
| u16-service-guardrail/NFR3.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | actual local/J6 요청 정책, 안내/참여·시계·현재성·사실 수명과 RP 표시 소비를 분리해 시험한다. 결과 주입만으로 요청 전달 합격을 주장하지 않는다 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR4.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 자동 검증은 가짜 공급자/합성 자료다. 실모델·실기기·실아동 성공0건을 성공으로 표시하지 않고 증거 유형별 결과를 분리한다 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR4.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 실제 GPT-Live 낭독/파일화는 생성 호출과 원문·청취·길이/형식·승인 등록의 각각 실제 결과가 있어야 완료다. 미호출/미검수는 미완료 | 실제 음성 생성·청취·등록 미실행 | test-results.md 미검증 목록 | build-and-test | Unverified |
| u16-service-guardrail/NFR5.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/observability-requirements.md` | 정본 SHA256→적용본 판/해시·편차→앱 산출물→요청 대역/시험의 연결을 유지한다 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR2.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/performance-requirements.md` | 기존 참여20초/재안내20초·공백4분/6분·재접속40초·종료확인30초·모호종료20초·승인재생20초와 재생/출력대기/기술 일시정지 제외를 유지한다 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR6.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/performance-requirements.md` | 내부 조회1초/연결500ms·무재시도, 판정4초·JEV800ms의 기존 마감과 큐 설정을 유지한다. 새 폴링/자동 AI 호출/재시도 루프0 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR5.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/performance-requirements.md`<br>`aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/scalability-requirements.md` | 정책 자원은 앱 시작 적재·세션 불변 참조로 사용한다. 일반 턴마다 원본 documents/파일 재읽기0 / 정책 본문은 불변 앱 자원/세션 참조로 공유하고 요청마다 파일/정본을 반복 읽지 않는다 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR2.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 안내 미확인·현재 참여 미수락·검사 실패·지난 세대/모드/목적·중복/종료 경합에서 미확인 일반 음성0회, 중복ACTIVE/첫 응답0회, 종료 뒤 일반 재생 재개0회 | 늦은 안내 이전 입력2경계 모두 ACTIVE 오전환 | ParticipationLateItemServiceGuardrailTest.java:59; test-results.md B-R01 | build-and-test | Not Met |
| u16-service-guardrail/NFR2.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 참여/중요 확정 사실의 기록은 관련 상태 전이와 원자적이다. 저장 실패/경합 패자의 출력 효과0회. 재시작/actor 제거/legacy/fallback은 근거 없는 사실을 복구하지 않는다 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR2.4 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 확인 질문이 미전달·실패·만료·중단됐을 때 ASKING이 무기한 남지 않는다. 실패 사건 또는 기존 재생 시한에서 현재 질문/보류 귀속을 해제할 처리 경로가 있어야 한다. 미전달 질문 답 추정/반복 질문0 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR6.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 기존 저장/삭제·동의·권한·연습·JEV 판/범주·공급자4type을 보존하고 새 자동AI 호출0 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR4.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/reliability-requirements.md` | 기존 톤/무음이나 synthetic 완료 표식은 실제 안내 전달 합격이 아니다. 실제 자원 미준비는 명시적 기능 미완료/보류로 보고한다 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR6.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/scalability-requirements.md` | 기존 actor/메일함/입출력 큐·재생 상한과 단일 소유를 유지하고 새 프로세스·큐 제품·자동 확장 구성0 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR1.4 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/scalability-requirements.md`<br>`aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 확정 사실은 세션의 기존 이벤트 수명 안에만 남긴다. 화제/도움 상태의 새 장기 원문 목록0 / 새 사실 사건은 코드/순번/서버 시각·현재성 메타데이터만 기존 세션 수명에 남긴다. 참여 전 전사/원음의 새 영속0, 별도 원문 복제0. 기존 삭제/권한 처리에 포함 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR1.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 기존 세션·아이·기기 소유/동의·내부 인증을 유지한다. 다른 계정/기기/세션·옛 연결/재생 표식으로 안내·참여·출력 승인0회 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR1.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 모델 문맥은 기존 허용 이름/나이/관심사만 해당 상한/이스케이프로 제공한다. 내부 ID·키·보호자 입력 원문·타인 정보 추가0 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u16-service-guardrail/NFR5.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/nfr-requirements/security-requirements.md` | 지시형/마크업/제어문자 아이 정보는 운영 정책/권한을 바꾸지 못한다. 사용자 원본/ZIP/Zone.Identifier를 실행 자원으로 읽지 않는다 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR2.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 현재 세션/요청 순서를 지켜 다른 세션·취소/늦은 응답·낮은 상태판의 현재 화면 적용0회. 같은 판의 최신 facts는 매번 반영하고 null을 이전 성공으로 보충0회 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR2.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 구조 오류/구버전/미래 enum/자료 부재에서 거짓 REFUSED·NO_RESPONSE·실제 인용·인사 재생·기기 정지 표시0회. 유효한 나머지 진행 정보는 유지 | online 복구만으로 캐시 PLAYING 재표시 | guardrail-review-regression.test.tsx; test-results.md F-R01/F-R02 | build-and-test | Not Met |
| u17-guardrail-presentation/NFR2.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 현재 안내 완료는 실제 재생 종료 뒤에만 전송. 중단/실패/옛 재생 콜백의 완료 보고0회, 같은 명령 중복 실행0회. 기기 현재 정리 전 종료 확인0회 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR3.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | test-after로 각 변경 층 구현 뒤 해당 시험 작성·실행. Standard의 컴포넌트당5–8개와 핵심 경계 통합 시험. 기존 단언/계약/import 경계/skip 정책 유지 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR3.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 프런트 모듈 전체와 실제 수정 표면을 포함하는 guardrail-presentation 기능 범위의 LINE 각각80% 이상. 빈 범위/없는 파일은 실패 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR3.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 변경 뒤 lint·전체 프런트 coverage 시험·production build 통과. Build and Test에서 기존 전체 verify.sh와 실제 대역 서버 통합 검증 | 전체 verify.sh가 프로젝트 훅에서 실행 차단 | test-results.md 미검증 목록 | build-and-test | Unverified |
| u17-guardrail-presentation/NFR4.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 파서/컴포넌트/기기 웹 대역, 가짜 공급자 통합, 실제 모델/기기/아동 증거를 각각 구분. 자동 시험에서 실제 유료 공급자 호출0회 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR4.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 실제 승인 음성 미준비와 U16 참여 전사 지적을 UI 시험 PASS로 해소했다고 보고0회. 최종 통합 전에 미해결 상태/후속 검증을 명시 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR5.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 한글·따옴표·역슬래시·줄바꿈·마크업처럼 보이는 text가 파싱/인용에서 손상·실행0회. 기본 등록 표현으로 대체0회 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR5.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 키보드 조작·팝업 접근 가능한 이름/초점·동일 상태 반복 낭독 방지·색 외 설명을 유지. 320px 및200% 확대에서 변경한 긴 문구/인용이 조작을 가리거나 가로 넘침0건 | jsdom 접근성만 확인; 실제320px/200%·기기 미점검 | test-results.md 미검증 목록 | build-and-test | Unverified |
| u17-guardrail-presentation/NFR6.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 기존 parent poll-interval(2초)·진행 요청 공유·retry:false·no-store·요청 취소와 시간 상한을 유지. 인용 조회/AI 호출/재시도 루프/장기 facts 캐시 추가0개 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR6.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/performance-requirements.md` | 기존 C1/C2/C3 wire·오류·연습·종료 확인 의미 유지. FREE_TALK facts를 연습/미확인 모드에 적용0회. 새 runtime dependency0개 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR1.1 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/security-requirements.md` | 현재 인증/세션 소유·쿠키/CSRF·no-store를 유지. 로그아웃/권한 실패/다른 세션 전환 뒤 이전 아이 facts/인용/말풍선의 새 세션 재사용0회. 서버 소유 확인을 클라이언트 검증으로 대체하지 않음 | GET403 뒤 이전 인용 잔존 | guardrail-review-regression.test.tsx; test-results.md F-R01/F-R02 | build-and-test | Not Met |
| u17-guardrail-presentation/NFR1.2 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/security-requirements.md` | 새 브라우저 로그/URL/query key/웹 저장소에 전사 원문·원음·정책 본문/해시·토큰/비밀 값 추가0개. 인용은 이미 허용된 현재 세션 items만 안전한 텍스트로 렌더링하고 HTML 실행0회 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| u17-guardrail-presentation/NFR1.3 | `aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/nfr-requirements/security-requirements.md` | UI는 참여·원음 시작·기기 정지·삭제/공유 완료 권한을 만들지 않음. 승인 정보 부재 때 완료를 표시0회. 기존 수동 스택/배포/실제 아동 입력·유료 공급자 호출은 수행하지 않음 | 현재 단위 대역 시험 및 기존 코드/증거 대조로 해당 경계 확인 | test-results.md; command-results.json; 해당 유닛 code-generation/verification-results.md | build-and-test | Met |
| TC-U16 | ../u16-service-guardrail/code-generation/code-generation-plan.md §Testing Contract | test-after·Standard·전체/기능 LINE80%·기존 회귀 유지 | 기존 범위 LINE80% 통과; 추가 회귀 실패 | test-results.md; command-results.json | build-and-test | Not Met |
| TC-U17 | ../u17-guardrail-presentation/code-generation/code-generation-plan.md §Testing Contract | test-after·Standard·전체/기능 LINE80%·기존 회귀 유지 | 기존 범위 LINE80% 통과; 추가 회귀 실패 | test-results.md; command-results.json | build-and-test | Not Met |
| XTRACE | inception 요구/스토리→두 code-generation/traceability.json | 모든 FR/NFR/AC의 OK와 실제 단일 target 파일 | FR31·NFR6 직접 연결 누락; AC40 파일 존재 | cross-unit-traceability.md | build-and-test | Not Met |


## Loop-Back Log

### Loop-back 1 — 2026-10-21T04:49:40Z

- Diagnosis: B-R01 실제 실행2개, F-R01/F-R02 실제 DOM2개 실패. XTRACE37개 직접 연결 및 U17 target46개 형식 누락. 기존 기능/전체 시험 성공이 새 경계 실패를 해소하지 않는다.
- Root-cause stage: code-generation, u16-service-guardrail 및 u17-guardrail-presentation. 지시서 package 오기는 이 단계 수정1회로 해결했다. 다른 설계 단계는 유지한다.
- Planned fix: U16은 참여 이전에 시작된 provider item의 귀속을 quiet 이후에도 현재 창에서 재사용하지 못하게 하고 현재 새 입력만 수락한다. 원문 영속/새 시계·AI 호출·재시도는 추가하지 않는다. U17은 단절 때 인사 신뢰를 무효화하고 현재 세션의 새 적용 응답 뒤에만 복구한다. 최종 세션403은 해당 세션 cache·요청 귀속·민감 표시를 제거하고 늦은 응답 복구를 막는다. 계정 전체 로그아웃·기존 CSRF403 재시도는 변경하지 않는다. 두 유닛의 실제 담당 FR/NFR/AC와 단일 파일 target을 정리하고 미검증 사실은 유지한다.
- Estimated impact: U16 소스/회귀 보강 약1–2시간, 직렬 actor/공급자 세대와 입력 경계에 중간 위험. U17 소스/회귀 보강 약1–2시간, 공통 query/인증 소비자에 중간 위험. 추적 정리 약15–30분, 문서 위험 낮음. 기존 전체/기능 검사·신규 경계/반례 재실행 필요. 금전 비용은 실제 유료 호출 없이 로컬 시험만 사용하여 추가 공급자 비용0. 작업 시간은 추정이며 검증 실행 시간은 별도다.
- Remaining evidence: 전체 verify 훅 차단은 원인 조사/안전한 실행 복구가 아직 필요(조사 약15–30분 추정, 해결 방식 미확정). 실제 음성 제작/청취와 실브라우저 자료도 미완료로 남는다. 이번 코드 수정을 이 항목들의 성공으로 간주하지 않는다.
- Replay: 해당 두 유닛만 수정하고 그 전 단계 산출물은 유지한다. 수정 계획은 새 시도에서 검토할 수 있도록 작성한 뒤 구현한다. 이 실패 실행의 일반 합격 게이트는 열지 않는다. 재진입 시 본 파일은 Modify로 보존하고 이 절을 삭제/초기화하지 않는다.


### Loop-back 2 — 2026-10-21T06:53:34Z

- Diagnosis: F-R04. 새 현재 응답이 성공한 뒤 인증 이전 세대/앞선 요청 순번/이탈 이전 요청의403이 도착하면 현재 세션 캐시와 사실 신뢰가 지워진다. 실제 회귀3개 실패(기존8개 PASS). 앞선 전체1055 PASS가 이 경합을 덮지 않는다.
- Root-cause stage: code-generation, u17-guardrail-presentation의 liveUpdates.ts 오류 경로. 테스트/환경 설정 문제가 아니다. U16과 그 전 단계는 유지한다.
- Planned fix: 오류 결과에도 성공과 동일한 current generation/session/sequence/AbortSignal 검증을 부작용보다 먼저 적용한다. 지난 결과는 aborted로 거절하고 현재403만 해당 세션을 제거한다. 취소·세대교체·순번·동일 세션 재진입, 현재403 purge와 CSRF/401 경계를 검증한다. U17 계획·지시서·기능 범위·Step5 증거를 Modify로 갱신한다.
- Estimated impact: 구현/회귀 약20–40분과 프런트 검증 시간. 공통 조회·인증 캐시 경합이므로 중간 위험, 공개 wire/백엔드/의존성 변경 없음. 실제 공급자 호출 없이 추가 공급자 비용0. 다른 유닛 재구현·재검토 없음.
- Remaining evidence: verify.sh 훅 차단/실제 승인 음성/실브라우저 점검은 여전히 Unverified다. 이 프런트 수정으로 해소된다고 하지 않는다. NFR3 Deferred 직접 추적도 최종 합격에 남긴다.
- Replay: U17만 재개하며 현재 일반 합격 게이트는 열지 않는다. 원래 Loop-back1과 실패 기록은 보존한다.
