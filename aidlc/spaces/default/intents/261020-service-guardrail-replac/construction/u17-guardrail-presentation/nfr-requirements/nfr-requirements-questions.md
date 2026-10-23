# U17 품질 요구 확인 기록

## Sources

- ../functional-design/functional-spec.md F1–F5 및 ../functional-design/rules.md BR17.1–BR17.10: 표시/수신/기기 소비 경계.
- ../../../inception/requirements-analysis/requirements.md: NFR1–NFR6, FR2/FR4–FR7.
- ../../../inception/contract-design/contract-summary.md: C1/C2 응답·null/구버전·현재성, C3 실제 재생/종료 확인.
- aidlc/spaces/default/codekb/mujungi/technology-stack.md: 조사 시점의 기존 스택. frontend/package.json과 현재 useLivePolling을 추가 대조했다. 원격 상태/새 버전 정보는 인증하지 않는다.

## 기존 결정 적용

새 품질 수치를 선택할 질문은 없다. 기존 모듈/기능 줄80%, Standard test-after, C1/C2/C3, 기존2초 폴링/재시도 없음·시간값, 개인정보·실호출/수동 스택 제한을 그대로 구체화했다. 키보드/초점·320px/200%와 사실 추정0건은 이미 정한 UI 설계의 검증 기준이다.

## 범주 점검과 모호성

성능은 기존 요청/시한·추가 호출0과 변경 표면 반응/표시를 다룬다. 보안은 현재 소유/인증·원문/로그/캐시 격리를 다룬다. 신뢰성은 잘못된 객체/응답 역전/중단/미확인 대역에서 거짓 표시0건으로 구체화한다. 확장·복구·관측성의 새 서버 SLA는 UI 범위 밖이며 기존 오류/상태 경고와 민감 로그 금지는 범위 안이다.

운영 승인 음성 및 U16 전사 귀속 지적은 별도 미해결이며 품질 요구를 약화하거나 UI 성공으로 대체하지 않는다. 본 문서는 새 사용자 답변/승인 기록이 아니다.
