# U16 기존 기술 유지 결정

## Sources

- ../../../inception/requirements-analysis/requirements.md: NFR1–NFR6 및 FR1–FR7.
- ../functional-design/functional-spec.md 및 rules.md: 정책·안내/참여·종료·검수·사실 수명.
- ../../../inception/contract-design/contract-summary.md: C1–C6, 기존 시간/실패/호환 의미.
- ../../../inception/practices-discovery/team-practices.md: test-after/80%, 기존 전체 회귀와 배포/실호출 제외.
- aidlc/spaces/default/codekb/mujungi/technology-stack.md: brownfield 기술 기록. 이전 버전 표·배포 설명을 새로 인증하지 않음.

## Decisions

| 영역 | 결정/근거 | 관련 요구 |
|---|---|---|
| 서버 | 기존 Java21·현재 백엔드 프레임워크/직렬 처리·JSON코덱·WebSocket 연결을 유지한다. 새 SDK/프레임워크 설치 없음 | NFR6.1–NFR6.3 |
| 저장 | 기존 Realtime 세션 이벤트에 최소 사실 개정, 같은 전이/권한/삭제 수명. 새 DB/큐/캐시 제품 없음 | NFR1.4, NFR2.3 |
| 정책 | 앱 포함 UTF-8 본문+판/해시·편차, 세션 고정 불변 묶음 | NFR5.1–NFR5.3 |
| 음성 | 기존 PCM16LE/24kHz/모노·승인 파일 경로, 새 파일은 별도 검수 후 새 버전. GPT-Live 사전 낭독/WAV추출 가능, 실제 유료 호출 미실행 | NFR4.2–NFR4.3, NFR6.1 |
| 검증 | 기존 JUnit/통합/계약·ArchUnit·JaCoCo 및 frontend/tool 검증, 가짜 공급자 | NFR3.1–NFR3.3, NFR4.1 |
| 배포 | 기존 파이프라인 유지, 이번 실제 배포/수동 스택 변경 없음. 오래된 CodeKB의 GHCR/Compose 운영 설명은 최신 배포 사실로 재인증하지 않음 | NFR6.3 |

## Assumptions & Open Questions

기술 버전 업그레이드는 범위가 아니다. 실제 설치/명령 실행 준비는 검증 전에 확인한다. CodeKB의 과거 버전 숫자 전체를 이번에 다시 검증했다는 주장을 하지 않는다.

