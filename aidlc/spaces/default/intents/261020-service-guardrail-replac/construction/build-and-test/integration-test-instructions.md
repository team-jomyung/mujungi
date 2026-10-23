# 경계 통합 검증

## Sources

U16·U17의 NFR3.2/NFR3.3 및 현재 unit-test-instructions.md. 번호가 같은 상세 NFR은 유닛으로 구분한다.

## 실행

기존 Spring/Testcontainers·Vitest/jsdom 대역을 사용한다. build-instructions.md의 서로 다른 단위 명령을 모두 수집해 한 번씩 실행하고, 최종 전체 경로는 저장소 루트에서 다음 명령을 실행한다.

```sh
bash scripts/verify.sh
```

GNU date를 PATH 앞에 둔다. A 전체 검사→B 별도 스택/시드/호출→C 기기 시뮬레이터/가짜 공급자 한 턴→P1/P2/P3/T를 모두 확인한다. --engine으로 대체하지 않는다. build/verify/report.txt와 단계 로그를 확인하고 전제 실패/실패/미실행을 구분한다. 기존 톤을 실제 승인 말소리로 등록해 통과시키지 않는다.

## 핵심 인수 경계

검토 지적을 실제 화면에서 재현하는 추가 회귀 명령(작업 디렉터리 frontend):

```sh
npx vitest run tests/parent/features/done/guardrail-review-regression.test.tsx
```

현재 두 시험은 실패하며 상위 구현 수정 뒤 같은 단언으로 통과해야 한다. 브라우저를 띄우지 않고 실제 ParentProviders/QueryClient/ApiClient와 DOM을 사용하며 fetch만 대역이다.

안내 전 시작된 전사 항목은 안내 실제 완료와 quiet 만료를 지나 늦게 와도 참여를 새로 수락하지 않아야 한다. 연결 복구의 캐시 PLAYING은 현재 응답 전까지 표시하지 않아야 한다. 세션 GET403은 해당 세션의 인용/items/facts와 늦은 응답의 복구를 차단해야 하며 계정 전체 강제 로그아웃이나 CSRF403 재시도 변경을 요구하지 않는다. 기존 음성/전사·시간·권한·연습 계약을 보존한다.

## 자료·범위·증거

합성 자료/가짜 공급자만 사용한다. DB·기기 대역/컴포넌트·실브라우저·실기기를 분리한다. 모듈/기능 LINE80%와 기존 단언을 유지한다. 현재 리뷰의 미해결 지적은 기존 녹색 시험만으로 닫지 않는다. 실행하지 않은 경계는 Unverified다.

