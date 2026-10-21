# U16 보안 설계

## Sources

- ../nfr-requirements/performance-requirements.md, security-requirements.md, scalability-requirements.md, reliability-requirements.md, observability-requirements.md 및 tech-stack-decisions.md: 상세 NFR20개.
- ../functional-design/functional-spec.md 및 rules.md: 동작/실패 전이·사실 수명과 단일 소유.
- ../../../inception/contract-design/contract-summary.md: C1–C6와 기존 인증/마감/호환 계약.
- ../../../inception/domain-design/components.md: 정책·판단·actor·검수/표시의 책임.
- nfr-design-questions.md Q1-A: 질문 전달 실패 후 복구 가능하면 듣기로 복귀, 지속 장애는 기존 기술 정리.

## Trust Boundaries

| 요구 | 설계 해법 | 검증 |
|---|---|---|
| NFR1.2 | 보호자 소유 확인→기존 내부 X-Internal-Token→세션 전이의 기존 권한 경계를 유지한다. 기기 쿠키/Origin/연결 인증 뒤 현재 session/playback/connection 귀속을 다시 검사 | 남의 계정/기기·옛 연결/다른 재생/중복 부정 시험 |
| NFR1.3, NFR5.3 | 명시적 최소 문맥 값만 검증·이스케이프해 지시의 데이터 구역으로 보낸다. 정책/원문문서·내부 ID/비밀을 그 데이터로 추가하지 않음 | 실제 요청 whitelist·제어문자/인용/길이 입력 |
| NFR1.1 | 로그/직렬화 경계는 판·해시·기존 허용 상관ID·이유/시간만 허용한다. 민감 record의 문자열 표현에서 본문을 제외 | 민감 표식·로그/공개 DTO 검사 |
| NFR1.4 | 상태/사실 이벤트와 전사 참조는 기존 세션 소유·삭제 수명에 귀속한다. 원문 복제/참여전 원음 기록 없음 | DB 경계·삭제/권한·참여전 기록 시험 |
| NFR4.2 | 음성은 파일 존재/APPROVED 행만으로 실제 안내로 믿지 않는다. 검수 판/본문·파일해시/길이와 현재 콘텐츠 버전 일치를 확인 | placeholder/해시불일치·낡은 버전·실제 승인 입력 대역 |

## Secrets, Transport and Assets

기존 TLS/WSS·보호자 쿠키/CSRF·내부 인증·비밀 환경변수 경로를 유지한다. 새 인증 수단·원격 키 저장소/외부 수집기를 만들지 않는다. .env 키는 출력/아티팩트/브라우저 번들에 넣지 않는다. 현재 볼륨/DB 백업의 암호화 상태를 이번 문서로 새로 인증하지 않는다.

승인 음성 자원은 새 파일명/콘텐츠 버전과 불변 해시로 묶고 기존 버전을 덮어쓰지 않는다. 배포 산출물의 파일과 manifest 일치는 빌드/계약 대조에서 검사한다. 현재 WS는 실제 바이트 해시를 돌려주지 않으므로 표식 자체가 파일 내용을 암호학적으로 증명하는 것처럼 설명하지 않는다. 승인 자원과 그 제공 경로의 일치, 기기의 실제 완료 보고를 별도 검증한다. 키/실제 아동 데이터 없이 대역을 사용한다.

## Assumptions & Open Questions

실제 말소리 생성/청취·승인은 아직 미완료다. 새 법률 적합성·보존/권한 정책을 추가하지 않는다.

