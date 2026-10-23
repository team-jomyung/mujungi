# U17 프런트 컴포넌트와 상태 경계

## Sources

- functional-spec.md F1–F5: UI 상호작용/전이 정본.
- rules.md: BR17.1–BR17.10 표시/소비 규칙.
- ../../../inception/domain-design/components.md 및 ../../../inception/contract-design/contract-summary.md: 단방향 사실 소비.
- ../../../inception/refined-mockups/interaction-spec.md: 기존 레이아웃·접근성·RP-1–RP-4.

## 컴포넌트 계층과 책임

| 논리 구성 | 입력/출력 | 소유하는 임시 상태 |
|---|---|---|
| 진행 응답 파서 | 기존 응답 → 기존 LiveView + 정규화된 guardrail | 없음. 오염 객체만 null, 미래 enum은 UNKNOWN |
| 현재 세션 조회/병합 | 세션/요청 귀속·조회 → 최신 단일 상태/사실 + 같은 세션 말풍선 | 기존 캐시·취소/요청 순서. facts 별도 영속화 없음 |
| LiveScreen / 진행 표시 | 현재 LiveView·이름·오프라인 → 상태·전사·P-01/P-03·기존 탭 | 기존 확인 팝업과 끝내기 요청 진행 여부 |
| DoneScreen / 종료 표시 | 현재 LiveView·같은 세션 items·연결 신뢰 → 사유·선택 인용·선택 인사 줄 | 결과 작업 폴링 등 기존 상태. 인사 타이머/자체 재생 상태 없음 |
| NoticeDialog / StatusLine / TranscriptList | 전달받은 문구·텍스트·확정 행 → 기존 안전한 표시 | 실제 초점과 기존 접근성 상태 |
| 기기 세션/재생 경계 | 현재 C3 명령·실제 오디오 사건 → 기존 재생 표식/종료 확인 | 현재 세션/연결/재생 및 중단 상태 |
| StatusView | 기존 display/label/subLabel/켜기·끄기 | 기존 표시 상태. 동의 권한 없음 |

## Props와 표시 모델

guardrail은 선택 nullable 값이며 지원 판의 세 enum과 nullable 순번을 담는다. 외부 타입의 정확한 이름/인코딩은 C1/C2를 따른다. 인사 줄·종료 인용은 각각 nullable 표시 값으로 분리하여 일반 마무리 제목이나 원인 제목이 숨김 조건에 끌려 사라지지 않게 한다.

LiveOutcome는 DECLINED의 REFUSED/NO_RESPONSE/UNKNOWN을 구분할 수 있어야 한다. 팝업의 열림과 사유 제목은 분리하고, 최신 사유가 바뀌어도 같은 팝업을 재생성하거나 반복 초점 이동하지 않는다. 연습의 기존 표시/파서·정리 상태는 유지한다.

인용은 기존 공개 행 text를 그대로 안전한 텍스트로 넘긴다. HTML/마크업 해석·임의 등록 문구 대입·다른 세션 캐시 검색·추가 전사 조회를 하지 않는다. 이름은 기존 조사/대체 이름 처리를 유지하며 비어 있는 text는 인용 생략이다.

## API와 오류 경계

기존 진행 GET·보호자 끝내기 POST·세션 쿠키/CSRF·no-store·진행 폴링 간격과 기기 WSS를 유지한다. 새 guardrail 파싱 실패는 기존 진행 응답 전체의 인증/소유 검증을 느슨하게 만들지 않는다. 401/403/삭제 차단·503/네트워크·시간 초과·취소 처리도 기존 정책을 따른다. 별도 retry·장기 캐시·로컬 저장·일반 대화 시작 요청을 추가하지 않는다.

stale/오프라인/조회 오류는 기존 접근 가능한 경고와 새 인사 줄 숨김에 함께 반영한다. 같은 상태판의 최신 facts는 갱신하고, null을 지난 성공 값으로 보충하지 않는다. 직접 화면 접근·새로고침도 같은 파서/변환을 사용한다.

## 접근성과 반응형 검증

P-01 취소 기본 초점, P-03 확인 초점·Tab 경계·접근 가능한 이름, 기존 탭 잠금/이동을 유지한다. 같은 상태의 재렌더는 반복 낭독을 만들지 않는다. 실제 button과 보이는 초점을 유지한다. 긴 인용/한글·200% 확대·320px 좁은 화면에서 가로 넘침/버튼 가림이 없어야 한다. 기기 화면의 기존 조작 크기·회전·reduced-motion·텍스트 상태를 보존한다.

## 변경 예상 표면과 시험 분해

- 보호자 공용 api types/live 및 조회 훅·livePresentation: 파싱·순서·null/구버전·세션 격리.
- live/DoneScreen·presentation·shared copy: 사유 3종·실제 인용·인사 조건·로딩/오류.
- 기기 deviceSession/player/runtime: 현재 세션/재생·중단/늦은 콜백·종료 확인. 이미 지키는 구현은 회귀로 증명.
- 해당 시험 및 guardrail-presentation 기능 범위 파일: 실제 수정 클래스/컴포넌트 전체를 측정. 기존 단언/80% 하한/계약·import 경계를 낮추지 않음.

위 경로는 변경 가능 표면이며 실제 파일 목록과 컴포넌트당5–8개 및 핵심 통합 시험은 Code Generation 계획에서 확정한다. U16 서버·contracts·운영 음성 자원은 U17 소유가 아니다.
