# U12 새 연습 흐름 — 빌드 · 시험 결과

> 실행: `sg docker -c 'bash scripts/verify.sh --mode compose'` · 2026-10-18 · 개정 `7c4651fbe158` · 지문 `sha256:3f89f423…1c0f0442` · `RESULT PASS rc=0 total=395s`. 원본 보고서는 `build/verify/report.txt`(저장소 밖 무시 폴더)다.
> **증거 구분(R-M11):** 아래 통과는 모두 *테스트 대역(가짜 공급자 · 가짜 제브 · 가짜 판정)* 과 *합성 대화* 의 결과다. 실제 API · 실제 제브 · 실제 기기에서 성공한 것은 없다.

## 빌드와 시험 결과

| 단계 | 결과 | 내용 |
|---|---|---|
| A1 backend | PASS 264s | `gradle clean check verifyBootJarHasNoTestClasses`. 시험 3,937건 · 실패 0 · 건너뜀 3, bootJar 두 개 · 시험 클래스 0, 모듈 전체 JaCoCo 80% 줄(check) 통과 |
| A2 frontend | PASS 35s | eslint · vitest(80%) · next build · standalone. 줄 커버리지 98.55% |
| A3 tools | PASS 10s | tools vitest 통과 |
| A4 secrets | PASS 0s | 키 형식 9종 · 파일 3,315개 걸림 0, 무시 규칙 통과 |
| B 기동 | PASS 62s | 이미지 5종 · 병합 설정 · migrate · 시드 두 번(멱등) · 프로필 가드 · 기동 · health 200 · `/device` 200 · WS 101 → 4401 · 금지 경로 403 등 |
| C 한 턴 | PASS 20s | 기기 시뮬레이터 C1 ~ C12(자유대화 한 턴, 게이트 차단, 끝내기, ENDED) |
| P1 격리 | PASS 2s | 교차 스키마 쓰기 · `CREATE TABLE` 6건 모두 SQLSTATE 42501 |
| P2 위생 | PASS 0s | 필수 로그 줄 존재, 비밀 값 27개 × 7개 파일 일치 0 |
| P3 값 | PASS 0s | 값 23개 · 파일 3,315개 걸림 0 |
| T 작업 트리 | PASS 0s | 시작 · 끝 작업 트리가 같음 |

- **건너뜀 3건:** 모두 이번 작업 이전부터 있던 실호출 시험이 키 없이 건너뛰는 것이다(`CoreLunaLiveTest` · `JevLiveTest` · `LunaReachTest` 계열의 `assumeTrue`). 이번 변경(`git diff 17ba094..HEAD`)은 `@Disabled` · 건너뛰기를 더하지 않았다.
- **이 유닛 범위:** 코드 생성 단계에서 `u12` 범위 줄 96.2%(1,222/1,270줄), 검토자가 같은 수치를 재측정했다. 이번 `verify.sh`는 유닛 범위 측정을 따로 돌리지 않는다(CI가 막는 범위는 U5b · U7뿐이다).
- 모듈별 시험 수는 `verify.sh`가 합산한 3,937건만 보고서에 있다. 코드 생성 보고의 1,862(realtime) · 1,722(core-api)와 합산 방식이 다를 수 있어 서로 맞춰 쓰지 않는다.

## 실패와 루프백

없음. 빌드 · 시험 명령 실패 0, `Not Met` 0, 이 단계 안 수정 시도 0, 루프백 0건이다.

<!-- 루프백이 없어 `## Loop-Back Log` 항목은 만들지 않는다. -->

## 확인하지 못한 것

- gitleaks 이력 스캔(이 PC에 gitleaks 없음 → PR의 CI `secrets` 작업).
- 연습 흐름이 기기 시뮬레이터 C 단계에서 도는지: 시뮬레이터 대본에 연습 시작이 없어 돌지 않는다. 연습 흐름 끝-끝은 `PracticeFlowIT`(대역)만 증거다.
- 실제 제브 · 실제 공급자 · 실제 기기, 한국어 되묻기 표현 품질, 역할 전환 이해, 변형 동의 분류 품질(`[실호출-합성]` · `[실기기]` 확인 대상).
