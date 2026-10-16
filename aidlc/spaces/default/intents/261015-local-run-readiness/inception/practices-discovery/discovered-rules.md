# 발견한 규칙 (초안 — 사람 확인 전)

> 아래는 저장소 설정 · CI 관문 · 문서가 이미 **강제하거나 명시한** 제약에서 뽑은 후보다. 사람이 확인한 것만 남기고,
> 아니라고 한 것은 지운다. 근거 파일은 `evidence.md` 「discovered-rules 근거」에 있다.
> 이미 `project.md` `## Corrections`에 있는 규칙(커밋 전 mode change 확인 등)은 다시 적지 않았다.

## Mandated

- ALWAYS 모든 PR은 필수 검사 `ci-gate`가 통과한 뒤에만 `main`에 합친다. (초안)
- ALWAYS Flyway 마이그레이션은 버전 순서대로 `main`에 들어가게 병합 순서를 정하고, 이미 `main`에 있는 마이그레이션 파일 내용은 바꾸지 않는다. (초안)
- ALWAYS 기존 표에 제약을 더하는 마이그레이션 문장은 이전 앱이 깨지지 않는 근거를 `infra/migration-approvals.txt`에 적고 리뷰를 받는다. (초안)
- ALWAYS GitHub Actions 액션은 40자 커밋 SHA로, 도구 컨테이너 이미지는 다이제스트로 고정한다. (초안)
- ALWAYS 시험용 비밀은 실행할 때 만들고 끝나면 지우며, 저장소에는 비밀 값도 비밀 변수 값도 남기지 않는다. (초안)

## Forbidden

- NEVER 시험 · CI · 검증 명령에서 실제 유료 AI 공급자를 부르지 않는다. 가짜 공급자를 쓴다. (초안)
- NEVER 시험을 통과시키려고 커버리지 하한(모듈 · 유닛 범위 80%)이나 기존 시험 단언을 낮추거나 끄지 않는다. (초안)
- NEVER 검증 스택(dev 프로필 · 시험 로그인 · 가짜 공급자, `compose.ci.yml`)을 demo 호스트에서 돌리지 않는다. (초안)
- NEVER `/parent`와 `/device` 영역이 서로를 import하지 않게 하고, 공용 계층은 둘 다 import하지 않는다. (초안)
