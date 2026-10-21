# U16 Functional Design Plan and Questions

## Sources

- [desc] Initial description: 현재의 gpt-live 가드레일에서 무중_서비스용_가드레일로 교체하고 싶어
- [scope] Workflow-selected scope: classic / Standard, 단위별 순차·자동 일반 완료 확인. 코드 계획과 새 결정은 별도 사람 확인.
- [U1] ../../../inception/units-generation/unit-of-work.md 및 unit-of-work-story-map.md: U16의 서버 정책/안내/참여/종료/검수·계약/사실 수명 책임. U17의 표시 소비와 구별.
- [R1] ../../../inception/requirements-analysis/requirements.md: FR1–FR7, NFR1–NFR6. 기존 시계/저장·공급자/연습 보존.
- [D1] ../../../inception/domain-design/components.md: 정책 묶음·순수 판단·actor·검수와 엔티티 소유.
- [C1] ../../../inception/contract-design/contract-summary.md: C1–C6의 고정 형식과 미확인/실패·실제 재생 증거/20초 상한.
- [P1] ../../../inception/delivery-planning/bolt-plan.md: 통합 B1, U16 다음 U17, 실제 안내 자원은 첫 상세 설계에서 확인.
- [CODE1] backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/JoinFlow.java: 안내 누락/송신 실패도 재생 완료로 처리하고 조기 응답을 즉시 분류하는 현재 변경 지점.
- [CODE2] backend/core-api/src/main/java/kr/mujoong/core/activity/ApprovedContentCatalog.java: 승인 콘텐츠는 참조 시드로 갱신하며 시작에 버전을 고정한다. 기존 /media/approved/ 파일 경로.
- [AUDIO1] tools/seed/gen-session-wav.mjs, gen-approved-wav.mjs, infra/seed/003-u4a-reference.sql: 현재 세션 안내 WAV는 말소리가 아닌 임시 톤/무음이며 실제 녹음·사람 승인은 별도다.
- [INPUT1] aidlc/spaces/default/knowledge/documents/mujung-service-guardrails/: 제공 정책 문서, 실제 음성 파일 없음.

## Functional Design Plan

- 앱 포함 정책 묶음/원본·적용본 해시·자유대화/연습 선택을 고정한다.
- 실제 안내→현재 참여→일반 대화, 도움1회/거절 화제/정정, 명확·모호 종료와 늦은 출력 차단의 순서/결정표를 설계한다.
- actor의 단일 권위와 후보 귀속·현재 세대/모드/목적 재확인을 유지한다. 로컬/J6의 실제 요청 정책 시험을 명시한다.
- 최소 확정 사실의 수명·종료 후 조회·unknown 복구를 기존 보존/삭제 경계 안에서 설계한다. 새 전사 원문 복제나 장기 기억을 만들지 않는다.
- entities.md/rules.md의 YAML 정본, functional-spec.md의 행동/상태 전이·파생 관계도/규칙 요약, Unit에 배정된 AC 추적을 작성한다. 이 단계는 설계이며 앱 구현 코드를 작성하지 않는다.
- corner checklist에 따라 누락/빈 정책·한글/이스케이프·권한/타세션·중복/시간 경계/순서 역전·저장 실패/재시작/연습 모드를 점검한다. 이미 정한 사항은 재질문하지 않는다.

## Q1 — 실제 안내 음성 자원 준비

현재 확인한 WAV는 시험 톤/무음이며 새 문서를 말로 전달하지 않는다. 이것을 실제 안내 완료 증거로 사용할 수 없다. 유료 호출이나 새 외부 음성 공급자를 자동 도입하지 않는다.

- A. 자원 등록·실패 차단과 대역 검증을 먼저 구현 (추천): 승인된 실제 음성/텍스트·버전/길이를 연결하는 경로와 자원 부재 시 대화 차단을 설계·구현한다. 실제 음성은 별도 준비가 필요하며, 그 전에는 전체 안내 기능/서비스 교체 완료로 보고하지 않는다. 제공 가능한 음성이 생기면 기존 경로로 연결한다.
- B. 기존 실제 안내 음성 경로를 제공: 사용할 수 있는 실제 말소리 파일 경로와 대응하는 문구/버전을 확인해 그 자원을 기준으로 설계한다. 기존20초한도·필수 사실 일치를 확인해야 한다.
- X. Other (please specify)

[Answer]: 현재는 기존 음성 파일 사용, 이후 추가 구현 필요 사항으로 정리

## 후속 질문 — GPT-Live 낭독 음성의 파일화 가능 여부

사용자 질문: 다시, 이미 gpt-live의 키는 .env에 저장되어 있음. 해당 키를 통해 gpt-live에게 안내 문구들을 낭독시킨 뒤, 음성 파일을 추출하여 활용 가능한가?

- 기술적으로 가능하다. 기존 ProviderRequestFactory는 session.start와 session.instructions.append를 지원하고 ProviderClient는 session.output_audio.delta의 base64 PCM을 수신한다. 낭독 전용의 독립 세션에서 문구 하나씩 생성하고 수신 순서의 PCM을 WAV로 저장할 수 있다.
- OpenAI 공식 문서 https://developers.openai.com/api/docs/guides/voice-websockets 및 https://developers.openai.com/api/docs/guides/live-conversations 를 확인했다. 기본 WebSocket 음성 조각에는 output-audio-done 사건이 없으므로 수집 도구가 무음 대기/명시적 시한·전사 대조로 파일 경계를 확인해야 한다.
- 원문을 정확히 낭독했는지 전사 대조와 실제 청취, 잘림/추가 발화/길이·PCM형식 확인 후 기존 승인 카탈로그의 새 버전과 연결한다. 기존20초한도를 넘기면 빠르게 읽혀 통과시키지 않고 안내 구성을 다시 검토한다.
- 문구 제작 때 한 번 생성해 고정 파일을 재사용하는 방식이다. 서비스 세션마다 새 AI 호출을 추가하지 않으며 키를 파일/프런트/로그에 넣지 않는다. .env의 키 값은 읽어 출력하지 않았다.
- 현재 질문은 가능 여부 확인이다. 실제 유료 API 호출은 아직 실행하지 않았고, 앞서 승인한 자동 대역/유료 호출 제외 범위를 실제 생성 실행까지 확장한 것으로 기록하지 않는다. 키의 접근 권한·실제 낭독 품질/생성 성공은 미검증이다.

## Assumptions & Open Questions

- 실제 음성 제작/제공 담당과 시점은 아직 확인되지 않았다. 작업 자율 진행 선택이 음성의 승인/유료 생성 권한을 뜻하지 않는다.
- Q1은 원래 실제 안내 요구를 면제하는 선택이 아니다. 자원 미준비 동안 진행할 구현 범위와 완료 보고의 경계를 정한다.
