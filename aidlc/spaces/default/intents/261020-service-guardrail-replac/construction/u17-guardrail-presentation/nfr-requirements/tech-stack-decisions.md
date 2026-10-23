# U17 기술 스택 유지 결정

## Sources

- ../functional-design/functional-spec.md F1–F5 및 ../functional-design/rules.md BR17.1–BR17.10: 표시/수신/기기 소비 경계.
- ../../../inception/requirements-analysis/requirements.md: NFR1–NFR6, FR2/FR4–FR7.
- ../../../inception/contract-design/contract-summary.md: C1/C2 응답·null/구버전·현재성, C3 실제 재생/종료 확인.
- aidlc/spaces/default/codekb/mujungi/technology-stack.md: 조사 시점의 기존 스택. frontend/package.json과 현재 useLivePolling을 추가 대조했다. 원격 상태/새 버전 정보는 인증하지 않는다.

## 결정과 이유

기존 frontend의 TypeScript·Next.js·React·현재 query/cache 계층과 Vitest/Testing Library/jsdom·ESLint를 유지한다. frontend/package.json의 고정 의존성 및 lockfile을 기준으로 하며 새 라이브러리·SDK·서비스 워커·오디오 엔진을 도입하지 않는다. 기존 기기 WebSocket/Web Audio와 승인 콘텐츠 재생 경계를 사용한다. 패키지 업그레이드가 이번 가드레일 표시 문제 해결에 필요하지 않다.

기존 parser/순수 presentation 변환·화면 hook을 확장해 C1/C2를 소비한다. 서버의 신규 영속 모델/DB/배포 플랫폼은 U17 선택이 아니다. CodeKB의 이전 배포 설명·버전 표를 현재 원격 환경으로 인증하지 않는다.

## 선택지와 영향

| 접근 | 결과 |
|---|---|
| 기존 프런트 내부 타입/정규화/순수 표시 매핑 확장 | 채택. 기존 오류/권한/폴링/연습을 유지하며 선택 객체만 보수적으로 소비 |
| 새 상태 저장소/자동 조회 루프·새 기기 wire | 채택하지 않음. 새 의존·장기 원문 복제·중복 호출과 서버 권한 혼동을 늘림 |
| 서버 사실 대신 기존 상태로 사유/인용/인사를 추정 | 채택하지 않음. RP-2–RP-4의 거짓 표시를 유지함 |

## 검증과 범위

NFR6.1/NFR6.2의 호출·모드·wire와 NFR3.1–NFR3.3의 runner/coverage/빌드 기준을 적용한다. 새 플랫폼 처리량/고가용성/복구 SLA는 만들지 않는다. 개인정보/안전한 실패는 security-requirements.md와 performance-requirements.md를 따른다. 미정의 실제 음성/실공급자 품질을 기술 선택만으로 해결했다고 하지 않는다.

## Assumptions & Open Questions

None.
