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

## 집중 조사 보강 — 실험 경로의 의존성

- 실제 브라우저 진단은 로컬 HTTPS 앱, 보호자 로그인·동의·기기 등록, STABLE 기기 연결, 합성 한국어 WAV, 실제 공급자 키/접근 가능 환경에 의존한다.
- 실제 출력은 GPT-Live 연결 성공 외에도 참여 수락, 입력 묶음 확정, 출력 세대 귀속, 완전한 전사/음성 묶음, 출력 게이트, 제브가 켜졌을 때 유효한 ALLOW, 레인·기록기·기기 연결 준비에 의존한다.
- compose.live.yml은 저장소 밖 env_file을 사용하며 realtime 공급자를 실제 WSS로 설정하고 Luna를 켠다. 제브 REAL/STUB 여부는 가이드에 따라 키 설정으로 갈린다. 설정 파일만으로 현재 실행 중 컨테이너가 이 값을 쓰는지는 알 수 없다.
- 유료 실험을 npm test·Gradle check·CI·verify.sh의 암묵적 의존성으로 추가하지 않는다. 기존 격리 gptLiveTest는 명시 호출이며 키 부재 SKIPPED_NO_KEY를 PASS와 구별한다.
- 실제 모델 호출 수·TTS 비용·실행 예산은 다음 요구사항/설계에서 정해야 한다. 이 조사에서 가격 확인이나 유료 API 호출은 하지 않았다.

## 집중 조사 보강 — 261020-service-guardrail-replac

기준: 2026-10-20, 커밋 2e3d7b0. 이전 절은 이전 의도의 기록으로 보존했다. 이번 검증 범위는 timestamp의 개별 파일에 한정한다.

- 기본 GPT-Live 지시와 JEV 정책은 독립 의존이다. 새 자유대화 지시문 채택이 JEV 모델·키·임계값·스키마 교체를 자동으로 뜻하지 않는다.
- 후속 서버 지시가 계속 누적되므로 FIRST·REST_ACK_AND_RETURN·고정 교정·연습 발화까지 정책 충돌을 검토해야 한다.
- 현재 ChildContext/ProviderRequestFactory가 받지 않는 보호자 원문·기억·내부 식별자는 ZIP trusted_runtime_context_fields 목록만으로 외부 모델에 전송할 수 없다.
- 패키지가 근거로 지목한 원본 화면설계 HTML과 v03_full.txt는 ZIP에 없다. 저장소에는 v1.5j PDF 및 문구표가 있으나 ZIP source hash와 같은 원본이라고 확인하지 않았다. 라이선스/재배포 허락 증거는 패키지에서 찾지 못했다. 제품 내부 적용과 외부 재배포의 권한 범위는 열린 확인 항목이다.
- 유료 AI 호출·배포·수동 실행 중 스택 변경은 이번 조사에 포함하지 않는다.

