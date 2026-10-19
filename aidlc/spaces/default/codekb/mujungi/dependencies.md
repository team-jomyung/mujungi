# Dependencies

> 기준: 커밋 570a625. 라이브러리 버전은 `technology-stack.md`가 정본이다.

## 내부 모듈 의존

| 모듈 | 의존 | 금지 |
|---|---|---|
| `core-api` | `contract-model` | `realtime-activity` 참조 · 상대 DB 표 읽기 (R-F5, ArchUnit) |
| `realtime-activity` | `contract-model` | `core-api` 참조 · 상대 DB 표 읽기 (R-F5) |
| 두 앱의 시험 | `test-support` | 운영 코드가 `test-support` 참조 금지 (R-F8) |
| `frontend` | 없음(백엔드와는 `contracts/`로만 만남) | `/parent` ↔ `/device` import, `shared`가 둘 중 어느 쪽 import (R-F4, ESLint) |

앱 사이 통신은 내부 HTTP(`contracts/internal-api.yaml`)와 이벤트 발신함 · 수신함(`contracts/events.asyncapi.yaml`)뿐이다.

## realtime-activity 내부 방향

- 실행부(`session`) → 연습 확장점(`practiceflow`) 한 방향만 허용한다(`PracticeFlowArchTest`, `EXECUTOR_KNOWS_NO_FLOW`). 레거시 `practice` ↔ 새 `practiceflow` 사이 규칙도 같은 시험이 잡는다.
- `RealtimeArchTest`가 `voice` · `gate` · `gateway` · `messaging` · `common.db` 사이 방향을 강제한다(`code-structure.md`의 문자열 참조 표).

## 외부 의존

- 외부 AI: GPT-Live, Luna, 제브 가드레일, 지식 그래프(`contracts/ai-io.md`). 시험 · CI · 검증은 가짜 공급자만 쓴다(R-F1).
- 인프라 외부: PostgreSQL, Nginx, GHCR, GitHub Actions OIDC, AWS SSM(배포).
- 보안: 의존성 취약점 예외는 `ci/security-exceptions.yml`에만 둔다(R-M7).

## 빌드 의존

Gradle 멀티 프로젝트(`backend/settings.gradle.kts`) + 포함 빌드 `build-logic`. 프런트와 tools는 별도 npm 패키지다.
