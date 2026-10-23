# 변경 전 기준선

## 실행과 결과

- 브랜치: feat/service-guardrail-replacement, HEAD: 2e3d7b0.
- Java: OpenJDK 21.0.12.1. 기존 Docker 실행 환경 확인, 사용자 스택 변경 없음.
- 실행 전 backend/contracts/frontend git diff 없음. 계획 단계 완료 표시는 실제 이력과 대조했다.
- backend 작업 디렉터리에서 `./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.voice.ProviderRequestFactoryWireTest -Pmujoong.test.db=docker`: PASS, 종료0, 37초.
- 저장소 루트에서 `(cd backend && ./gradlew --no-daemon clean check -Pmujoong.test.db=docker) && (cd frontend && npm run lint && npx vitest run tests --coverage && npm run build)`: PASS, 종료0.
- backend 전체 clean check: BUILD SUCCESSFUL, 8분35초. contract-model/core-api/realtime-activity 모듈 줄80% 검사 통과.
- frontend: eslint PASS, 75개 파일/998개 시험 PASS, 줄98.54%(3455/3506), Next production build PASS.

## 근거와 제한

Gradle 최초 sandbox 실행은 읽기 전용 사용자 캐시 잠금 때문에 실행 전 실패했다. 동일 명령의 승인된 확장 실행으로 실제 시험을 수행했다. 기본 test 그래프의 유료 공급자 격리 검사는 통과했다. Docker 선택의 LOCAL 대안은 기존 조건에 따라 SKIPPED이며 Docker 실행 성공을 전체 결과로 확인했다.

기존 경고는 unchecked/deprecated API 및 JVM CDS 경고다. 하한/단언/기존 시험을 변경하지 않았다. 이 결과는 변경 전 기준선이며 신규 가드레일 구현의 합격 근거가 아니다. 실제 공급자/기기/아동 시험은 실행하지 않았다.
