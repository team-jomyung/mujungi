# Practices Discovery Evidence

## 조사 범위

- 리드: aidlc-pipeline-deploy-agent. Brownfield 재실행, classic · Standard, collaborators off. 지원 검토·contributions 파일은 없다.
- 작업: Q1 답변을 반영한 최종 통합본 작성. 관례 확정 게이트 승인·memory 승격 전이다. 애플리케이션·memory·workflow 상태는 수정하지 않았다. 시험·공급자 호출·배포·수동 스택 조작은 하지 않았다.
- 현재 브랜치: feat/service-guardrail-replacement. HEAD = main = 2e3d7b0e0796844166fb54b21220512ff20ce9e1.
- git status에는 기존 aidlc 변경·다른 기록·미추적 진단 폴더가 있다. 이를 되돌리거나 이번 작업의 코드 변경으로 간주하지 않았다.

## Sources

| 입력 | 이번에 읽은 내용과 용도 |
|---|---|
| memory/org.md · team.md · project.md · phases/inception.md | 다섯 승인 관례, R-M1~11·R-F1~15, 2026-10-19 학습 대체 규칙, 한국어 및 문서 취급 규칙 |
| inception/reverse-engineering/developer-scan.md | 현재 교체점·패키지 검증·회귀 위험·조사 한계. 코드 전체 재스캔 생략 |
| codekb/mujungi/code-structure.md | 초기·후속 지시·상태 제어·검수·시험 연결점. 역사적 이름·줄 수를 새 사실로 인증하지 않음 |
| codekb/mujungi/technology-stack.md | Java 21·Gradle·JUnit·기존 공급자 경계. 버전 전체 재검증 없음 |
| codekb/mujungi/dependencies.md | 계약 모듈·앱 경계, GPT-Live와 JEV 독립성, 제공 패키지의 누락 원본 |
| codekb/mujungi/code-quality-assessment.md | 정책 문자열·wire·연습·출력 보류 회귀 및 대역 검증 한계 |
| codekb/mujungi/architecture.md | 초기/후속 프롬프트와 서버 출력 검수의 분리 |
| codekb/mujungi/business-overview.md | 사용자 교체 의도와 다음 요구사항에서 결정할 범위 |
| git status · log -8 · rev-parse HEAD main | 현 브랜치·main 동일성·merge commit 관례 |
| .github/workflows/ci.yml | 경로 필터·필수 집계·프런트 기능 범위·main 전용 이미지 및 skeleton 순서 |
| .github/workflows/deploy.yml | ECR/ECS demo 경로·건너뜀 조건·이전 SHA 롤백·HTTP 확인의 한계 |
| backend/build.gradle.kts · realtime-activity/build.gradle.kts · frontend/vitest.config.ts · package.json | 실호출 작업 격리·프런트 80%·범위 파일 실패 조건 |

## 유지할 기존 선택

- 짧은 main 기반 작업 브랜치·merge commit PR·한국어 커밋·기존 ci-gate 통과 관례.
- 새 스켈레톤 없이 기존 끝-끝 회귀 경로 사용. 변경 층의 검사를 먼저 하고 Build and Test에서 전체 verify.sh.
- Methodology test-after: 층 하나 구현 → 해당 층 시험 작성/실행 → 다음 층. 줄 80%, 기존 단언·대조·ArchUnit 보호, 가짜 공급자.
- 기능 이름 시험 태그·폴더·커버리지 파일, 기능 Screen 이름, 주석은 무엇·왜 중심이라는 최신 학습을 유지.
- 저장소 전체 재서식 없이 바꾼 파일만 정리. 수동 mujoong-manual 보호. 이번 배포 실행 없음.

## 실제 차이와 제안

1. 기존 배포 사실 GHCR/SSM/호스트 Compose는 ECR/ECS demo/Terraform 관리 태스크로 바뀌었다. 동작 파일 근거로 사실 설명을 갱신한다. 원격 환경을 검증한 것은 아니다.
2. ci-gate는 이미지·skeleton을 집계하지 않는다. 이미지는 관문 뒤 main 전용, skeleton은 main 전용 임시 비차단이다. 현황은 기록하되 이번 작업의 검증을 약화하거나 관문을 변경하지 않는다.
3. 오래된 화면/시험 이름은 team.md의 2026-10-19 학습으로 이미 대체되었다. 새 결정 질문이 아니다.
4. 정적 파일만으로 원격 브랜치 보호·리뷰 수·demo 환경 제한·저장소 변수·실제 ECS/ECR 정책을 인증할 수 없다. 기존 미확인은 이번 교체의 범위를 넓혀 재질문하지 않고 남긴다.
5. org 기본 squash와 team의 기존 merge 선택, 조직 staging/production 기본값과 팀 demo 선택은 기존 문서에도 명시된 차이다. 새 선택으로 만들거나 자동으로 뒤집지 않는다.

## 인터뷰 결정과 통합

- 질문: 기존 다섯 관례 유지와 현재 CI·배포 설명 갱신을 한 번에 확인했다.
- 사용자 응답: `기존 방식 유지 + 설명 갱신 (Recommended)`.
- 근거: practices-discovery-questions.md의 Q1 답변과 orchestrator의 answer 기록. 별도 지원 검토는 없다.
- 기존 기술 선택은 재질문하지 않았다. team-practices.md의 과거 GHCR/SSM/호스트 Compose·이미지 포함 관문·옛 시험 이름 설명은 현재 ECR/ECS·현재 집계·학습 대체 규칙으로 통합했다.
- discovered-rules.md는 기존 승인 제약만 승계했다. R-F10의 GHCR 문구 등 기존 강제 규칙 자체는 이 인터뷰로 바꾸지 않았다.
- 네 산출물은 관례 확정 게이트에 제출할 준비가 됐다. 이 문서가 게이트 승인이나 배포 승인을 뜻하지 않는다.

## 다음 단계로 넘길 제품 항목

- ZIP은 사용자 제공 정책·계약 데이터다. 실행 가능한 설정·새 에이전트 명령·팀 관례가 아니다.
- 프롬프트 교체 범위, 후속 지시 충돌, 서버 안내 전달·참여, 종료/타이머, 자유대화와 연습 분리, JEV/기억/결과 갭은 Requirements Analysis에서 확정한다.
- 누락 참조 원본과 라이선스 선언 미확인은 승인된 reverse-engineering 기록의 열린 항목으로 넘긴다. 이를 이번 관례 질문으로 반복하지 않는다.
- 기존 OutputGate/GuardrailCoordinator의 검수 경계는 교체 범위를 정하기 전까지 완화하지 않는다.

