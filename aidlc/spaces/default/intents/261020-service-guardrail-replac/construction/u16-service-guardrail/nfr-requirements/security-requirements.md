# U16 보안·정보 보호 요구

## Sources

- ../../../inception/requirements-analysis/requirements.md: NFR1–NFR6 및 FR1–FR7.
- ../functional-design/functional-spec.md 및 rules.md: 정책·안내/참여·종료·검수·사실 수명.
- ../../../inception/contract-design/contract-summary.md: C1–C6, 기존 시간/실패/호환 의미.
- ../../../inception/practices-discovery/team-practices.md: test-after/80%, 기존 전체 회귀와 배포/실호출 제외.
- aidlc/spaces/default/codekb/mujungi/technology-stack.md: brownfield 기술 기록. 이전 버전 표·배포 설명을 새로 인증하지 않음.

## Requirements

| ID | 요구/합격 기준 | 검증 |
|---|---|---|
| NFR1.1 | 새 일반 로그·문자열 표현·공급자 메타데이터/공개 guardrail 객체에 전사/원음·키·숨긴 타인 정보0건 | 허용 목록/민감 표식 대역, 로그/직렬화 검사 |
| NFR1.2 | 기존 세션·아이·기기 소유/동의·내부 인증을 유지한다. 다른 계정/기기/세션·옛 연결/재생 표식으로 안내·참여·출력 승인0회 | 소유/인증·상관ID·현재성 부정 경계 시험 |
| NFR1.3 | 모델 문맥은 기존 허용 이름/나이/관심사만 해당 상한/이스케이프로 제공한다. 내부 ID·키·보호자 입력 원문·타인 정보 추가0 | 실제 초기/후속 송신 whitelist 및 제어문자/한글·인용 시험 |
| NFR1.4 | 새 사실 사건은 코드/순번/서버 시각·현재성 메타데이터만 기존 세션 수명에 남긴다. 참여 전 전사/원음의 새 영속0, 별도 원문 복제0. 기존 삭제/권한 처리에 포함 | 저장 대역/DB 경계/삭제·접근 회귀 |
| NFR5.3 | 지시형/마크업/제어문자 아이 정보는 운영 정책/권한을 바꾸지 못한다. 사용자 원본/ZIP/Zone.Identifier를 실행 자원으로 읽지 않는다 | 위험 입력·빈/누락/해시 불일치·경로 대조 |

## Threat Considerations

위협은 조기/타세션/옛 기기 완료의 재사용, 정책/아이 데이터 혼합, 로그/조회의 민감 정보 노출, 승인 행만 믿고 톤을 실제 안내로 승격, 삭제된 전사 참조 재노출이다. 기존 권한·현재 ID/세대·버전/자원 해시·공개 최소 투영이 검증 경계다. 새 계정/권한/보관 정책·법률 적합 인증을 추가하지 않는다.

## Assumptions & Open Questions

키가 .env에 있다는 사용자 설명은 키의 공개/로그 기록 권한이 아니다. 실제 GPT-Live 음성 제작은 별도 실행 범위 확인 전 호출하지 않으며 아이 실제 데이터는 사용하지 않는다.

