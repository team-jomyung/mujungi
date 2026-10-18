# Requirements Analysis 질문 — 구조 · 이름 리팩토링

> 기준 문서: 제안서 `docs/refactor-structure-proposal.md`, 코드 스캔 `aidlc/spaces/default/codekb/mujungi/`(원격 `main` 570a625 기준), 이 기록의 처음 요청(동작 · 계약 · 설정 키는 바꾸지 않는다).
> 이 기록은 새 기능을 만들지 않고 이미 있는 코드의 구조와 이름만 바꾼다. 제안서가 이미 정한 것(단계 순서, 가드레일, 이동과 내용 변경 분리)은 아래 가정 묶음으로 확인받고, 사람이 정해야 하는 갈림길만 개별 질문으로 묻는다.
> 제안서 6절의 결정 사항 4개가 Q1 ~ Q4이고, Q5는 코드 스캔에서 새로 나온 갈림길이다.

## Q1. 코드 주석에 쓰는 결정 ID 규칙을 바꿀까요?

지금 팀 규칙(`team.md` 코드 스타일)은 "코드 주석은 결정 ID(J · BR · U*-J · 편차)를 인용한다"입니다. 그 결과 `SessionExecutor` 한 파일에만 ID가 약 315개, 프런트에는 약 547개가 있어 지금 기능을 읽기 어렵습니다. 이 질문의 답에 따라 3단계 이후 주석 정리를 할 수 있는지가 정해집니다.

A. 규칙을 바꿉니다: 주석은 "지금 이 코드가 무엇을 왜 하는가"만 쓰고 ID는 필요할 때 한 줄 문서 링크로 줄입니다. 이번 작업에서 **건드린 파일부터** 정리하고, `team.md`의 코드 스타일 절은 학습 절차(§13)로 고칩니다.
B. 규칙은 그대로 두고 이름 · 구조만 바꿉니다. 주석은 옮기기만 하고 내용을 줄이지 않습니다.
C. 규칙은 이번 범위에서 바꾸지 않고, 주석 정리는 후속 작업 기록으로 미룹니다(이번 기록에서는 단계 6을 뺍니다).
X. Other (please specify)

[Answer]: A

## Q2. `SessionExecutor`(4,361줄)를 이번에 어디까지 나눌까요?

제안서 3-1은 구역 12개를 역할별 협력 객체로 나눕니다. 가장 큰 구역은 "연습 확장점"(약 1,450줄)이고 가장 위험합니다. 이 실행기는 한 세션을 한 스레드로 처리하는 구조라 나눈 객체도 모두 그 스레드에서만 불려야 하며, 작업 중인 다른 유닛과 같은 파일에서 부딪힐 수 있습니다.

A. 작은 구역부터 순서대로 나눕니다: 끝내기 → 시계 → 공백 시계 → 참여 확인 · 판정 흡수까지 이번에 하고, 연습 확장점(약 1,450줄)은 앞 결과를 평가한 뒤 이 기록의 마지막 단계에서 결정합니다.
B. 연습 확장점까지 이번에 모두 나눕니다(PR을 구역별로 쪼갭니다).
C. 가장 작은 구역 하나(끝내기)만 시범으로 나누고, 효과를 본 뒤 후속 기록에서 계속합니다.
X. Other (please specify)

[Answer]: A

## Q3. 이름이 비슷한 `practice`(옛 연습)와 `practiceflow`(새 연습)를 어떻게 구분할까요?

realtime 패키지에 `practice/`(29파일, U5b 레거시)와 `practiceflow/`(21파일, U12 새 흐름)가 따로 있고, `SessionExecutor`는 대본의 `scriptVersion` 유무로 둘 중 하나를 고릅니다. 이름만 보면 어느 쪽이 지금 쓰는 것인지 알 수 없습니다. 패키지 이름이 바뀌면 `PracticeFlowArchTest` · U5b 범위 파일 · 계약 문서의 경로도 같은 PR에서 고쳐야 합니다.

A. 레거시만 `practicelegacy`로 바꾸고 `practiceflow`는 그대로 둡니다(바뀌는 쪽이 하나라 혼동이 적습니다).
B. 두 패키지를 한 `practice` 아래 하위 패키지(`practice.legacy` · `practice.flow`)로 묶습니다.
C. 패키지 이름은 바꾸지 않고, 각 패키지에 한 줄 설명(`package-info`)과 코드 스캔 문서로만 구분합니다.
X. Other (please specify)

[Answer]: A

## Q4. 유닛 번호가 들어간 이름(테스트 폴더 · 커버리지 범위 파일 · CI 작업)을 어디까지 바꿀까요?

지금 프런트 테스트는 `tests/u1` ~ `u14`, 커버리지 범위 파일은 `backend/coverage-scope/u5b.txt` 등, CI에는 `u5b-scope` 작업이 있습니다. 소스 파일 이름과 달리 이쪽은 CI 설정과 브랜치 보호 규칙이 이름으로 부르므로, 바꾸면 같은 PR에서 CI를 같이 고쳐야 하고 필수 검사 이름이 바뀌면 보호 규칙이 막을 수 있습니다(필수 검사가 `ci-gate` 하나뿐인지는 아직 확인하지 못했습니다).

A. 소스 파일 이름의 유닛 번호(`u5bCopy.ts` 등)와 프런트 `tests/` 폴더 구조만 바꾸고, 커버리지 범위 파일 · CI 작업 이름은 유지합니다.
B. 범위 파일 · CI 작업 이름까지 모두 바꿉니다. 먼저 브랜치 보호의 필수 검사를 확인하고 같은 PR에서 CI를 고칩니다.
C. 유닛 번호가 들어간 이름은 전부 유지합니다(소스 파일 이름만 정리).
X. Other (please specify)

[Answer]: B

## Q5. 프런트는 어느 쪽까지 구조 · 이름을 바꿀까요?

화면 번호가 붙은 파일 17개는 모두 보호자 쪽(`parent/screens`)에 있습니다. 기기 쪽(`device`)에는 `T19View` · `D0View` 둘 정도만 번호 이름이고, 기기 코드는 오디오 · 웹소켓처럼 이미 책임별로 나뉘어 있습니다. 보호자 화면은 요청 · 오류 · 화면 이동이 한 파일에 섞인 곳이 있습니다(`Live12`, `Home04` 등).

A. 보호자(`parent`)는 이름 · 폴더 · 화면 흐름 훅 분리까지 하고, 기기(`device`)는 번호 이름 두 개만 바꿉니다.
B. 보호자와 기기 모두 같은 기능 폴더 구조로 맞춥니다(기기도 `device/features/…`로 이동).
C. 보호자 화면만 합니다. 기기는 이번 범위에서 뺍니다.
X. Other (please specify)

[Answer]: B

## Assumption Confirmation

아래는 제안서나 코드 스캔 사실과 바로 맞는다고 보고 정한 가정입니다. 한 묶음으로 확인해 주세요.

1. **동작 불변**: 오류 코드 · 설정 키 · 시간 값 · API · 이벤트 계약 · DB 스키마는 바꾸지 않습니다. 결과를 바꾸는 수정이 필요해 보이면 이 기록에서 하지 않고 따로 올립니다.
2. **이동과 내용 변경 분리**: 한 PR은 목적 하나만 가집니다. 파일 이동 · 이름 변경(`git mv`)과 내용 변경을 섞지 않습니다.
3. **진행 순서**: 제안서 4절의 0 → 6단계 순서(안전망 → 프런트 이름 → 프런트 흐름 훅 → 실행기 분리 → 설정 · 패키지 이름 → 시험 폴더 · CI → 주석)를 따릅니다. Q1 ~ Q5의 답에 따라 해당하지 않는 단계는 뺍니다.
4. **안전망 먼저**: 실행기를 나누기 전에 현재 동작을 고정하는 특성화 시험을 보강합니다. 코드 스캔에 구역 간 처리 순서를 고정하는 시험이 따로 있는지 확인하지 못했다고 적혀 있어, 이 단계에서 먼저 확인합니다.
5. **실행기 구조**: 한 세션을 한 스레드로 처리하는 모델을 그대로 둡니다. 나눈 객체는 실행기 스레드에서만 호출되고 공유 상태는 `SessionContext` 하나가 소유합니다. 새 스레드를 만들지 않습니다.
6. **배정되지 않은 구역**: `SessionExecutor`의 머리 619줄(필드 · 생성자)과 끝의 "전이 보조" 약 282줄은 제안서에 담당이 없습니다. 기능 설계 단계에서 `SessionContext` 또는 남는 실행기로 배정합니다.
7. **이름에 묶인 참조는 같은 PR에서**: 클래스나 파일을 옮기면 `SingleInstanceMarkerTest`의 `REALTIME_SHARE` 표(SI-2, SI-9)가 가리키는 `SessionExecutor.class`, `TimeLiteralScanTest` 허용 목록, `coverage-scope` 파일, `contracts/` · `docs/` 경로, ESLint · ArchUnit 규칙을 같은 PR에서 고칩니다.
8. **화면 번호 대응표**: 화면 설계 v1.5j의 번호와 파일의 대응은 `docs/screen-map.md` 표 하나로 옮기고, 파일 이름에는 번호를 쓰지 않습니다.
9. **프런트 구조**: 기능 폴더(`parent/features/<기능>/`) 안에 화면 · 흐름 훅(`useXxxScreen`) · 요청(`api.ts`) · 문구(`copy.ts`) · 시험을 둡니다. 공통 클라이언트 · CSRF는 `shared/api`, 둘 이상 쓰는 문구만 `shared/copy`입니다. 오류 문구는 기존 `codeToCopy` 한 길을 유지합니다.
10. **core-api**: 패키지 구조는 바꾸지 않습니다. 큰 파일(`StartActivityService` · `DeviceJdbcRepository`)만 같은 기준으로 다시 확인하고, 필요할 때만 후속으로 올립니다. 설정 레코드(`CoreProperties` · `RealtimeProperties`) 분리는 설정 키 이름을 바꾸지 않는 조건으로 4단계에 포함합니다.
11. **가드레일**: 커버리지 하한 · 기존 단언 · 계약 대조 시험 · ArchUnit 시험은 낮추거나 끄지 않습니다. `/parent` ↔ `/device` ↔ `shared` import 경계와 `core-api` ↔ `realtime-activity` 분리는 유지합니다(R-F2 · R-F4 · R-F5).
12. **시험**: 기존 시험이 무변경으로 통과하는 것이 합격 기준입니다. 새 시험은 특성화 시험과 이름 변경으로 깨지는 대조 시험의 갱신에 한합니다(`refactor` 범위는 새 시험 하한이 없고 기존 시험을 초록으로 유지합니다). 전체 `bash scripts/verify.sh`는 Build and Test에서 한 번 돌립니다.
13. **브랜치와 병합**: 작업 브랜치는 `claude/refactor-structure`(원격 `main` 570a625 위)이고 `main`에는 직접 올리지 않습니다. PR은 merge commit으로 합치고 `ci-gate` 통과가 필수입니다. 롤백용 `snapshot/main-pre-refactor-20261018`을 원격에 만들어 두었습니다. `aidlc/` 기록은 함께 커밋합니다.
14. **규칙 문서 변경 방식**: `team.md` 코드 스타일 절(Q1) 등 규칙 문서는 직접 고치지 않고 학습 절차(§13)로만 고칩니다. 배포 단계는 이 기록에서 다루지 않습니다.

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions

## Consolidated Summary Confirmation

- Q1 주석 규칙: 규칙을 바꾼다. 주석은 "무엇을 왜"만 쓰고 결정 ID는 한 줄 문서 링크로 줄인다. 이번에 건드린 파일부터 정리하고 `team.md` 코드 스타일 절은 학습 절차(§13)로 고친다.
- Q2 실행기 분리 범위: 작은 구역부터 나눈다(끝내기 → 시계 → 공백 시계 → 참여 확인 · 판정 흡수). 연습 확장점(약 1,450줄)은 앞 결과를 평가한 뒤 마지막 단계에서 결정한다.
- Q3 `practice` 이름: 레거시 `practice/`만 `practicelegacy`로 바꾸고 `practiceflow`는 그대로 둔다. ArchUnit · 범위 파일 · 계약 경로는 같은 PR에서 고친다.
- Q4 유닛 번호 이름: 전부 바꾼다(프런트 `tests/u*` 폴더, 커버리지 범위 파일, CI의 `u5b-scope` 같은 작업 이름까지). 같은 PR에서 CI 설정을 함께 고친다. 확인한 사실: 원격 `main`에는 지금 브랜치 보호(필수 검사)가 없다(`team.md`의 "ci-gate 필수"와 다름, 규칙 `123`은 꺼져 있음). 그래도 보호 규칙이 나중에 생길 때를 위해 바뀐 작업 이름을 문서에 남긴다.
- Q5 프런트 범위: 보호자와 기기 모두 같은 기능 폴더 구조로 맞춘다(`device/features/…` 포함).
- 가정 14개 수용: 동작 · 계약 · 설정 키 · DB 불변, 이동과 내용 변경 PR 분리, 0 → 6단계 순서(해당 없는 단계는 뺌), 실행기 분리 전 특성화 시험, 단일 스레드 모델 유지 + `SessionContext`, 배정 안 된 구역(머리 619줄 · 전이 보조 282줄)은 설계에서 배정, 이름에 묶인 참조(`REALTIME_SHARE` 표 등)는 같은 PR에서 고침, `docs/screen-map.md` 신설, 프런트 기능 폴더(화면 · 흐름 훅 · api · copy · 시험), core-api 구조 유지 · 설정 레코드 분리는 4단계, 가드레일 유지, 기존 시험 무변경 통과가 합격 기준, 브랜치 `claude/refactor-structure` · merge commit · 롤백 snapshot, 규칙 문서는 학습 절차로만 개정.

Does this all look correct before I generate the requirements artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
