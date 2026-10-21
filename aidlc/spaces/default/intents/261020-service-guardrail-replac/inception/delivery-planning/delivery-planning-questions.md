# Delivery Planning Questions

## Sources

- [desc] Initial description: 현재의 gpt-live 가드레일에서 무중_서비스용_가드레일로 교체하고 싶어
- [scope] Workflow-selected scope: classic / Standard, 이번 배포 제외.
- [U1] ../units-generation/{unit-of-work,unit-of-work-dependency,unit-of-work-story-map}.md: U16 service(L), U17 ui(M), U17은 U16에 의존한다.
- [C1] ../contract-design/contract-summary.md: 기존 API/WS 유지, 서버·화면 함께 갱신, 실제 안내 증거·사실 수명·fallback·미확인 계약.
- [R1] ../requirements-analysis/requirements.md 및 ../user-stories/stories.md: 승인 범위·8개 스토리·40개 AC, 실제 local/J6 요청 검증 인계.
- [UX1] ../refined-mockups/mockups.md: 기존 화면 배치와 RP-1–RP-4.
- [D1] ../domain-design/components.md: 기존 단일 actor 상태 권위.
- [memory:M1] ../practices-discovery/team-practices.md 및 space memory/team.md: main 기준/대상·merge commit, test-after/80%, 새 스켈레톤 의식 없음, 기존 verify.sh·수동 스택 보호·배포 제외.
- [STATE1] 현재 기록: Construction Checkpoints enabled, Iteration unit-major, Execution serial. 명시적 변경 요청 전 유지한다.

## Q1 — 작업 묶음과 우선순위

Bolt는 일부 작업을 설계·구현해 실행 가능한 결과까지 만드는 한 차례의 작업 묶음이다. 새 배포나 시장 출시를 뜻하지 않는다.

- A. 위험 확인 우선, 단위별 2개 Bolt (추천): B1=U16에서 실제 안내/참여·종료·늦은 출력/판정과 자원 문제를 먼저 확인하고, B2=U17에서 그 사실을 화면·기기에 반영한다. 순차 진행하며 정량 순위 점수는 만들지 않는다. 두 단위와 최종 통합이 모두 통과해야 전체 교체 완료다.
- B. 하나의 통합 Bolt: U16+U17을 하나의 최종 완료 묶음으로 관리한다. 실제 작업은 같은 의존 순서로 진행하고, 중간 단위 확인과 코드 계획 승인은 유지한다. 최종 사용자 흐름의 단일 완료 지점에 중점을 둔다.
- X. Other (please specify)

[Answer]: B. 하나의 통합 Bolt

## 고정된 실행 조건과 외부 의존

- U17이 U16에 의존하므로 독립 Unit 병렬 작업은 계획하지 않는다. 기존 단위별 순차 실행을 유지하며 단계/깊이 변경을 이 단계에서 결정하지 않는다.
- 실제 안내 음성이 제공되지 않았고 기존 재생 확인 상한은20초다. 승인 텍스트/필수 사실·음성 버전/길이 일치를 U16 Functional Design에서 먼저 해결한다. 외부 제작/제공 담당과 소요 시간은 미확인이고 임의로 확정하지 않는다. 자원 부재는 보류이며 시험 톤/무음으로 완료하지 않는다.
- JDK21/Docker 등 로컬 검증 준비는 실행 전에 확인한다. 유료 공급자 호출·클라우드 배포·수동 mujoong-manual 변경은 포함하지 않는다.
- 가장 큰 기술 위험은 실제 안내/현재 참여 증거와 늦은 출력 무효화이며 B1의 검증 가설로 제안한다. 별도의 사업 가치 점수·납기 숫자는 제공되지 않았다.

## 후속 확인

- 구현 담당 방식은 별도 선택으로 확인한다.
- 단위 체크포인트에서 사용할 실제 검증 명령은 명령 전문을 제시하고 별도로 확인한다. 기존 전체 회귀 기준은 bash scripts/verify.sh이며 현재 단계 계획 선택만으로 명령 실행/체크포인트 선택을 승인한 것으로 처리하지 않는다.

## Q2 — 구현 담당 방식

기존 단위별 순차 진행을 유지한다. 통합 Bolt 선택은 여러 팀이나 동시 실행의 선택을 뜻하지 않는다.

- A. 이 세션에서 두 단위를 이어서 진행 (추천): AI 개발 담당이 U16, U17을 순차 구현하고 현재 세션에서 확인한다.
- B. 팀별로 Unit을 나눠 담당: 각 팀이 자기 Unit의 작업과 승인을 독립적으로 맡는다. U17의 U16 의존은 유지한다. 이 선택 후 팀별 승인 빈도를 추가로 정한다.
- X. Other (please specify)

[Answer]: A. 이 세션에서 두 단위를 이어서 진행

## Q3 — 단위 완료 검증 명령

이 intent의 verification-command.txt에 기록한 다음 명령을 각 완료 Unit의 결과 검증에 사용한다. 최종 전체 verify.sh는 별도 Build and Test에서 수행한다.

`(cd backend && ./gradlew --no-daemon clean check -Pmujoong.test.db=docker) && (cd frontend && npm run lint && npx vitest run tests --coverage && npm run build)`

- Approve — 이 명령 사용
- Request Changes — 검증 명령 변경

[Answer]: Approve

명령 승인 답변과 전용 설정 반영이 완료되었다. 실제 명령 실행/통과는 아직 아니다. Construction 진입의 자동 진행 여부는 별도 선택을 따른다.
