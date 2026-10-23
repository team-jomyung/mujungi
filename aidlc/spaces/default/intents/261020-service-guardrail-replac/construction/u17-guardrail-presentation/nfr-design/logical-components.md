# U17 논리 구성과 실패 범위

## Sources

- ../nfr-requirements/performance-requirements.md 및 ../nfr-requirements/security-requirements.md: 상세 NFR 15개.
- ../nfr-requirements/tech-stack-decisions.md: 기존 의존성·음성/조회 경계 유지.
- ../functional-design/functional-spec.md F1–F5: 순서·표시·기기 확인 정본.
- ../../../inception/contract-design/contract-summary.md: C1/C2의 현재 사실·구버전/null과 C3 기존 재생 wire.
- ../../../inception/domain-design/components.md: 서버 세션 권위와 UI 단방향 소비.

## 구성과 소유

| 구성 | 책임/실패 범위 | 공유 자원과 격리 |
|---|---|---|
| 응답 정규화 | 선택 facts 구조 실패를 null로 제한. 기본 응답/세션 오류는 기존 실패 | 기존 보호자 API 경계. 별도 parser dependency 없음 |
| 현재 세션 적용 경계 | 로그인 세대·세션·발행 순서·상태판으로 오래된 응답 차단 | 기존 세션별 query cache/취소. facts와 상태는 한 snapshot |
| 순수 표시 매핑 | 사유/실제 인용/인사 줄·로딩/미확인 결정 | 같은 세션 공개 items와 정규화 facts. 외부 호출 없음 |
| 화면/팝업/상태 줄 | 기존 배치/이동·키보드/초점/낭독 | 기존 디자인 토큰·copy와 연습 경계 |
| 기기 명령/재생 | 현재 세션/연결/재생만 표식/종료 확인 | 기존 WebSocket/Web Audio·큐/시한/중복 제거 |
| 시험/증거 | 변경 전체 범위의80%·회귀·대역/실제 구분 | 기존 runner·coverage·검증 명령. paid provider0 |

## NFR 적용과 실패 전파

performance-design.md P1은 현재성/정규화, P2는 호출/기기 수명, P3은 시험·접근성·증거를 담당한다. security-design.md S1은 인증/세션 격리, S2는 원문/오류, S3은 접근성/증거를 담당한다. UI 오류는 서버의 권한·DB·모델 요청 상태를 바꾸지 않는다.

구조가 잘못된 guardrail만 잃은 경우 나머지 유효한 진행 정보는 유지한다. 인증 실패/다른 세션/취소/오래된 응답은 현재 표시 전체에 적용하지 않는다. 연결 신뢰가 사라지면 현재 인사 줄은 숨기되 기존 오류·마무리/기기 미확인 구분은 유지한다. 연습 상태는 자유대화 facts와 분리한다.

## 인프라와 구현 인계

새 서비스/DB/큐/CDN/권한/환경 변수/비밀/배포 토폴로지/네트워크를 추가하지 않는다. 기존 frontend 산출물과 기기 웹 소비만 바뀌므로 Infrastructure Design에서 새 자원을 설계할 필요가 없다. 실제 변경 파일/코드/시험 계획은 Code Generation에서 확정한다.

현 시점은 설계 합격이지 앱 코드/실제 기기 성공이 아니다. 운영 음성 및 서버 전사 지적의 미해결 조건은 performance-design.md의 인계와 최종 Build and Test에 남긴다.
