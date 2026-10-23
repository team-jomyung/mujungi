# Domain Design Questions

## Sources

- [desc] Initial description: 현재의 gpt-live 가드레일에서 무중_서비스용_가드레일로 교체하고 싶어
- [scope] Workflow-selected scope: classic / Standard.
- [R1] ../requirements-analysis/requirements.md: 자유대화 정책·후속 지시 교체, 실제 안내 확인, 단일 세션의 참여/종료, 기존 JEV/연습 보호.
- [R2] ../user-stories/stories.md 및 ../refined-mockups/{mockups,interaction-spec}.md: 8스토리, RP-1–RP-4와 기존 화면/표시 정합성.
- [memory:M1] ../practices-discovery/team-practices.md: 앱 경계·시간·검사·가짜 공급자 회귀.
- [RE1] aidlc/spaces/default/codekb/mujungi/{architecture,component-inventory}.md: 기존 서버 실행기/프롬프트/검수/프런트 구조.
- [C1] backend/realtime-activity/.../session/SessionExecutor.java: 현재 mailbox·세대·J6/확인·출력 보류·JoinFlow 경계.
- [C2] backend/realtime-activity/.../voice/ProviderRequestFactory.java: 최소 아이 문맥, 기존 4개 wire type, 현재 초기 요청에 활동 모드 입력 없음.
- [C3] backend/realtime-activity/.../dialog/StopIntentJudge.java: 등록 표현 외 패턴·인용/거절 구분의 기존 로컬 규칙. 기존 J6 경로는 SessionExecutor가 조정한다.

## Q1 — 정책과 상태의 소유 경계

이번에 새 논리 단위가 필요한 부분은 버전·해시를 가진 정책 자원과 그 정책을 적용하는 판단 경계다. 기존 SessionExecutor의 세션 직렬 처리·상태 소유는 유지할 수 있다. 두 안 모두 새 원격 정책 서비스·AI 공급자·배포를 추가하지 않는다.

| 안 | 장점 | 비용/되돌리기 |
|---|---|---|
| A. 정책 자원·순수 판단·세션 실행·검수·표시의 책임을 명시적으로 분리 | 자원 버전/모드 선택과 서버 상태를 독립 시험. 안내·참여·세대·종료의 유일 소유는 기존 실행기. 모드별 정책이 연습에 섞이지 않음 | 작고 명시적인 내부 경계와 상태 증거를 정의해야 함. 기존 앱/패키지 안에서 되돌릴 수 있음 |
| B. 기존 DialogInstructions/JoinFlow/SessionExecutor 안에서 변경을 모아 처리 | 새로운 논리 경계를 줄이고 기존 파일 중심 변경 | 정책 적재/해시/순수 판단/상태의 결합이 커지고 모드·후속 지시 회귀 범위를 독립 검증하기 어려움. 나중에 분리 가능 |

추천은 A다. 정책 묶음은 세션 시작 시 고정하고 기존 실행기만 현재 상태·안내 증거·참여·종료를 바꾼다. 판단/검수/화면은 그 결과를 사용하며 별도 상태 권위를 만들지 않는다. 실제 클래스 수·메서드·자료형·DB/API 스키마는 후속 설계에서 정한다.

- A. 기존 실행기의 단일 상태 소유 + 명시적 정책/판단 경계 (추천)
- B. 기존 클래스 중심으로 모아 구현
- X. Other (please specify)

[Answer]: A. 단일 상태 소유 + 정책/판단 분리 (Recommended)

## 기본 설계 방향

- 정책 본문·출처 해시·적용본 해시/버전은 정책 자원의 소유다. 세션에는 그 묶음의 참조와 현재 세션에서 확인한 재생/참여 증거만 둔다.
- 종료/휴식/화제/정정 판단은 상태 변경을 직접 하지 않고 의도를 반환한다. 기존 실행기가 현재 세션/모드/세대/출력 허가를 확인하고 전이·재생을 실행한다.
- 기존 JEV 검사/출력 보류는 유지하고, 검수 결과가 돌아와도 현재 세션 재확인 없이는 출력하지 않는다. 연습 확장점은 실행기를 역호출하지 않는 기존 방향을 유지한다.
- 새 정책은 자유대화에만 선택한다. 연습은 기존 지침을 보존하며 최소 아이 문맥 이외 내부 필드나 계약 JSON을 공급자 본문에 넣지 않는다.
- RP-2/3/4의 표시에는 검증된 사유·표현·재생 사실만 투영하고, 사실이 없으면 인용/인사 줄을 숨긴다. 기존 Core↔Realtime HTTP·기기 WS·보호자 REST 계약을 이용하고 앱 코드/DB를 직접 교차 참조하지 않는다.
- R-01은 기존 로컬 종료 규칙과 J6 판단 요청의 정책 전달을 확인할 시험 경계로 인계한다. 새 공급자/추가 호출 루프를 만들거나 이 단계에서 이미 해결됐다고 표시하지 않는다.

## Assumptions & Open Questions

- 소유권+속성 이름 수준만 정한다. 자료형·검증 제약·허용값·관계 수와 구체적 전이 결정표는 Functional Design 몫이다.
- 새 DB 보관/장기 기억/정책 hot reload·관측 대시보드는 이번 범위 밖이다. 세션은 시작 때 고정한 버전을 사용하고, 정책 변경 중 살아 있는 세션을 자동 전환하지 않는다.
- 물리 배포 묶음·Unit 분해는 다음 단계에서 정한다. 여기의 논리 컴포넌트 개수는 새 프로세스·새 서비스 개수가 아니다.
