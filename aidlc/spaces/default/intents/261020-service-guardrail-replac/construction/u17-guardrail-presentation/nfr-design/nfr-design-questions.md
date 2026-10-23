# U17 품질 설계 확인 기록

## Sources

- ../nfr-requirements/performance-requirements.md 및 ../nfr-requirements/security-requirements.md: 상세 NFR 15개.
- ../nfr-requirements/tech-stack-decisions.md: 기존 의존성·음성/조회 경계 유지.
- ../functional-design/functional-spec.md F1–F5: 순서·표시·기기 확인 정본.
- ../../../inception/contract-design/contract-summary.md: C1/C2의 현재 사실·구버전/null과 C3 기존 재생 wire.
- ../../../inception/domain-design/components.md: 서버 세션 권위와 UI 단방향 소비.

## 기존 결정 적용

기존 조회/캐시/파서/표시·기기 재생 구성에 현재성·보수적 실패·안전한 텍스트·시험 경계를 적용한다. 새 재시도/차단기/캐시 계층·암호화/비밀·서비스/인프라를 고를 빈칸은 없다. 성능/보안/호환성·접근성의15개 상세 요구를 기존 구조 안에서 구현한다.

## 모호성·변경 경계

동일 statusVersion 최신 사실은 갱신하고, 취소된 이전 요청·다른 세션은 버린다. null은 이전 사실로 채우지 않는다. 이 적용 규칙은 폴링과 수동 재조회 등 캐시 갱신 경로 모두에 적용한다. 실제 지연 SLA를 새로 만들거나 UI가 서버 상태 권한을 갖지 않는다. 신규 사용자 질문/답변을 만들지 않고 이전 결정의 구체화로 진행한다.
