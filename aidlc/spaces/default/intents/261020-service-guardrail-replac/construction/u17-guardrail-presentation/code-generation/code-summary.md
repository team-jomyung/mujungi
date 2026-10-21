# U17 사실 기반 화면·기기 웹 구현

현재 결과는 마지막 「Loop-back 2」 절이다. 최초 구현과 이전 동결/검증의 아래 이력은 덮어쓰지 않고 보존한다.

## 구현 결과

서버 사실을 단방향으로 소비한다. 선택 guardrail v1 파서는 구조/필수 타입/양의 안전 정수 실패를 null로 제한하고 미래 문자열은 UNKNOWN으로 읽는다. 기본 응답·요청 sessionId·인증/CSRF/no-store 의미는 유지한다. 불확실 여부가 명시적으로 false인 공개 CHILD final 행만 정확한 참조 seq로 인용한다. 기본 등록 종료 표현/종료 시각 대입·추가 전사 조회를 제거했다.

폴링과 P-01 충돌 재조회는 readCurrentLiveView를 공유한다. 현재 API 인증 세대·세션·요청 발행 순서·AbortSignal 및 상태판을 확인하고 상태+facts를 한 응답으로 교체하며 같은 세션 items만 seq 병합한다. 같은 판 PLAYING→STOPPED→null도 적용한다. 로그아웃/로그인/삭제 요청·401은 이전 facts와 귀속을 무효화한다.

DECLINED는 REFUSED/NO_RESPONSE/미확인을 서로 다른 P-03 제목으로 표시하고 확인 후 홈으로 간다. 제목 갱신은 팝업 열림/초점 효과와 분리했다. 기술 FAILED는 기존 실패 경로다. Done은 최초 로딩/오류/일반 CLOSING 제목·실제 인사 줄·실제 종료 인용·기기 미확인·결과 정리를 구분한다. FREE_TALK+CLOSING+PLAYING이며 조회 오류/오프라인/stale/기술 장애가 없을 때만 인사 줄을 표시한다.

기기에서는 다른 세션 STOP/END/CLOSE/상태와 끊긴 세션의 재생 표식을 차단한다. 현재 확인은 정리 뒤 전송하며 기존 명령 중복 제거를 보존한다. 플레이어는 stop/reset/fail 전에 수명을 무효화해 동기 재진입·늦은 종료 콜백이 거짓 COMPLETED를 만들지 못하게 한다. 큐/시한/barge-in은 기존 회귀로 유지한다.

## 변경 표면

운영 TypeScript/TSX16파일, 시험13파일(신규8·기존 강화5), 기능 범위2파일, 합계31파일이다. 삭제된 최종 앱 파일은 없다. 정확한 전체 목록은 source-manifest.json, 바이트별 SHA-256은 verification-results.md에 있다.

- frontend/src/device/audio/player.ts
- frontend/src/device/ws/deviceSession.ts
- frontend/src/parent/common/activity/livePresentation.ts
- frontend/src/parent/common/api/apiClient.ts
- frontend/src/parent/common/api/guardrailPresentation.ts
- frontend/src/parent/common/api/live.ts
- frontend/src/parent/common/api/liveUpdates.ts
- frontend/src/parent/common/api/types.ts
- frontend/src/parent/common/hooks/useLivePolling.ts
- frontend/src/parent/features/done/DoneScreen.tsx
- frontend/src/parent/features/done/presentation.ts
- frontend/src/parent/features/done/useDoneScreen.ts
- frontend/src/parent/features/live/LiveScreen.tsx
- frontend/src/parent/features/live/NoticeDialog.tsx
- frontend/src/parent/features/live/useLiveScreen.ts
- frontend/src/shared/copy/activity.ts

apiClient는 인증 세대 무효화 연결만 수정했다. 해당 공통 파일 전체를 기능 커버리지에 넣고 기존 api-client/account-api/dev-login/login-01 네 시험 파일을 기능 목록·unit-test-instructions.md 명령에 추가했다. runner/config/package/lockfile·backend/contracts/infra/U16은 수정하지 않았다. Next dev가 일시 생성한 frontend/AGENTS.md는 제거했고 next-env.d.ts는 production build로 원상복구됐다.

## 시험과 편차

Testing Contract sha256:231770ff8fc8e342a5163711626da88820010bbc0b413c96ec9fc94bb2241ce4의 test-after를 지켰다. 층별51시험(6/8/6/8/6/6/6/5) PASS, 기능27파일312시험 PASS·LINE763/770=99.09%, 전체83파일1049시험 PASS·LINE3515/3566=98.56%, lint/typecheck/production build PASS다. 정확한 명령·초기 실패와 수정은 verification-results.md에 있다.

기존8개 실패 기대는 승인된 동작에 맞춰 강화했다. 구버전 DECLINED를 무응답으로 추정하던 예는 명시적 NO_RESPONSE fixture로 바꾸고, 기본 종료 표현은 정확한 공개 행의 다른 원문/행 시각으로 검증한다. CLOSING 인사는 명시적 PLAYING일 때만 검증하며 미조회는 로딩으로 구분한다. 이전 연결/다른 세션의 표식·STOP은 차단하고 현재 세션에서의 같은 opId 실행/중복 억제를 함께 단언한다. 기존 권한/숫자/요청/실패 단언·시험 수·하한/계약/import/skip은 낮추지 않았다.

## 미완료와 인계

NFR5.2는 부분 미완료다. 가상 DOM 키보드·초점·안전한 텍스트·동일 상태 DOM 유지 시험은 PASS지만 실제320px/200% 레이아웃·브라우저 키보드·실기기 조작/회전 확인은 완료하지 못했다. 격리3187 Next와 기존 Chromium CDP 기동을 시도했으나 페이지 이동이 완료되지 않았고 사용자의 키링 창 문의 뒤 중단했다. 본인이 시작한 실행만 종료했고3187 접속 불가를 확인했다. 키링 입력/생성/설정 변경은 하지 않았다. 브라우저를 재실행하지 않는다.

U16 R-01(안내 전 전사 항목의 늦은 조각 재수락)은 미해결 서버 경계다. U17이 해결했다고 주장하지 않는다. 운영 승인 말소리는 준비되지 않았으며 기존 톤/합성 음원 대역은 운영 승인 자산이 아니다. 전체 verify.sh·실제 대역 서버 통합·서버 회귀는 Build and Test로 인계한다. 실제 유료 공급자/아동 데이터·실기기·배포·migration·manual 스택·main 병합은 수행하지 않았다.

traceability.json은 상위 AC25행(23 UI부분 책임·2 N/A), BR10행, 상세 NFR15행과 NFR머리6행을 추적한다. 서버 후속 지시 AC1.6.1/JEV AC2.2.2는 N/A, NFR5.2와 전체 통합을 포함한 NFR3.3은 Deferred다. 앱 동결 뒤 부모가 외부 검토·단위 완료/체크포인트를 진행한다.

## Loop-back 1 — F-R01/F-R02/R-03 수정과 현재 동결

이 절이 현재 결과이며 위 최초 구현·검증 이력은 보존한다. 새 승인 시도는 기존 계획을 다시 쓰지 않고 완료 증거로 체크 표시만 복원했다.

- F-R01/R-01: liveUpdates의 현재 응답 적용 경계에 연결 사건 세대를 묶었다. offline/online 사건은 기존 facts와 사건 이전 대기 응답의 신뢰를 무효화할 뿐 새 성공이 아니다. 해당 세션·현 세대·취소되지 않은 최신 요청의 유효한 상태판 응답이 적용된 뒤에만 현재 사실을 신뢰한다. 낮은 판/실패/늦은 응답은 복구 근거가 아니다. 같은 판·같은 값의 응답도 TanStack 구조 공유 이후 실제 캐시 객체를 귀속해 올바르게 복구한다. 추가 refetch/타이머/AI 호출 없이 기존 폴링을 유지한다.
- F-R02/R-02: 공통 readCurrentLiveView에서 세션 GET의 최종403을 처리한다. 그 세션의 data/items/facts·인용과 요청 귀속을 제거하고 자동 폴링을 멈춘다. 늦은 이전 응답은 복구하지 못한다. 계정 전체 로그아웃이나 다른 세션 cache 제거는 하지 않으며 기존401/로그아웃/계정 전환·POST CSRF403의 토큰갱신1회/동일 requestId 재시도는 보존한다.
- R-03: 기존56행을 누락하지 않고 모든 OK target을 실제 workspace-relative 구현/시험 파일 정확히1개로 정리했다. 보조 경로/실행 설명은 justification으로 옮겼다. 직접 FR31행을 추가해87행이며 U17의 UI부분 책임과 U16 서버 N/A를 구분했다. NFR3/NFR3.3의 전체 verify·NFR5.2의 실제 화면은 Deferred다. 실제 센서 PASS·invalid_targets0.

이번 운영 수정은 liveUpdates.ts·useLivePolling.ts·useDoneScreen.ts 3파일 안이다. 새 운영 파일 없이 기능 범위16파일을 그대로 보존했다. 부모의 guardrail-review-regression.test.tsx 원래2개 단언을 유지·해결하고4개 핵심 반례를 추가했다. 현재 manifest32파일은 기존31파일+부모 회귀1파일이며 scope 시험28파일과 단위 명령에 함께 반영했다. 계획 본문/runner/하한/기존 단언·skip·의존성/서버 계약은 바꾸지 않았다.

최종 lint/production build(TypeScript 포함) PASS. 전체84파일1055시험 PASS·LINE3543/3594=98.58%, 기능28파일318시험 PASS·LINE791/798=99.12%. 부모의 원래2FAIL은 수정 뒤2PASS이며 보강을 포함한 회귀파일은6PASS다. 관련5파일54시험도 PASS다. 상세 실패→수정·정확한 명령/covered-total·새32파일 SHA-256은 verification-results.md 마지막 절에 있다.

앱 동결2026-10-21T05:08:10Z. 재현용 바이트 지문 sha256:f93b3979c2811d3817bc2492be6d90ec250961232d4a9066bde61f0a44501082. 현재 앱/시험/범위 및 Step5 산출물을 부모의 fresh independent review로 인계한다. 초기 단위 리뷰의 Accepted risk 처리나 현재 수정 성공은 별개의 사실이며 이번 실제 결과만 보고한다.

브라우저/Chromium/GUI/Next 서버 실행0·키링 조작0. 실브라우저320px/200%/브라우저 키보드·기기 회전/조작, 실제 운영 말소리, 전체 verify.sh의 hook 차단/실제 대역 서버 통합은 미해결로 유지한다. U16의 늦은 입력 B-R01과 RG1 재인사 방지·backend 회귀는 별도 승인 작업이며 이 UI 수정으로 해결했다고 주장하지 않는다. backend/global checkpoint/전체 verify 실행0이며 Build and Test 기록/라우팅/리뷰/lifecycle은 부모 소유다.

## Loop-back 2 — F-R04/R-04 늦은403 현재성 수정

이번 수정은 liveUpdates.ts의403 오류 적용 직전 한 경계에 한정했다. 성공 응답과 동일한 generation/sessionId/sequence/AbortSignal 검사를 통과한 현재403만 해당 세션을 제거한다. 이전 인증 세대·앞선 순번·동일 세션 이탈/재진입·취소된 요청의 늦은403은 aborted로 끝나며 새 성공의 캐시와 현재 사실을 보존한다. 현재401의 기존 전역 무효화와 CSRF403 재시도 정책은 변경하지 않았다.

부모의 기존3개 실패 단언을 그대로 유지했고 세대/순번을 변경하지 않는 AbortSignal 단독 취소1개를 추가했다. 수정 전11개 중8PASS/3FAIL → 수정 후 공통12PASS, 관련3파일34PASS다. 기능28파일322PASS·LINE794/801=99.12%, 전체84파일1059PASS·LINE3546/3597=98.58%, lint와 production build(TypeScript 포함) 모두 종료0이다. 원래14단계 기준선을 재실행하지 않았고 Repair1–5 완료 증거만 갱신했다.

manifest32경로·운영 범위16파일·시험 목록28파일을 실제 수정 표면과 대조해 누락0으로 확인했다. 새 경로가 없어 source-manifest.json 및 기능 범위 내용은 그대로 유효하다. traceability87행과 단일 실제 OK target·Deferred를 보존하고 BR17.1/NFR1.1/NFR2.1에 늦은403 근거를 추가했다.

앱 동결2026-10-21T07:02:08Z, 재현용 바이트 지문 sha256:1fafac26b9f0de78e237da0d8edbe2685599db9c558b19756b3ea0e95ec1d2bf. 이전 동결 이력은 역사로 남긴다. 이번 작업에서 backend/U16·계약·runner·하한·추가 의존성은 변경하지 않았고 브라우저/GUI/Next 서버·키링·verify.sh·global checkpoint를 실행하지 않았다. 실제 운영 말소리·실브라우저320px/200%/키보드·실기기·전체 대역 서버 통합 미검증을 유지한다. NFR5.2 및 NFR3/NFR3.3을 무조건 OK로 인증하지 않는다. U16의 후속 수정 상태와 별개로 이 UI 작업이 서버 결함을 해결했다고 주장하지 않는다. 부모가 독립 검토·라우팅·lifecycle을 담당한다.

