# Neo4j 기본 활성화와 실행·검증 방법

## 무엇이 달라졌나

전체 서비스를 실행할 때 지식 그래프를 기본으로 사용한다. `core-api/application.yml`의 `mujoong.knowledge.neo4j.enabled`는 **true**다. Neo4j는 필요한 순간마다 켜지는 DB가 아니라, 실행 중인 상태에서 U15·U14·U10이 필요할 때 사용하는 DB다.

- U15: 아동별 기본 지식, 생성 지식, 관계·상황 조회와 삭제.
- U14: 결과에서 만든 목표 후보에 지식 참조를 연결하고 선택 버전을 보존.
- U10: 연결된 지식과 변형 계획으로 기본·변형 상황 생성·검토.

활성화 상태에서 인증값이 없거나 DB가 연결되지 않으면 Core의 기동을 거절한다. 인증된 트랜잭션으로 제약을 준비한 뒤에만 실제 그래프 어댑터를 사용한다. **그래프 없이 조용히 정상 서비스로 기동하던 경로를 제거했다.**

기동 이후의 일시적인 그래프 장애에는 기존 시간 제한과 기본 경로를 유지한다. 따라서 기동 성공만으로 모든 요청이 그래프를 사용했다고 설명해서는 안 된다. U10의 `graphContextUsed`와 해당 시험·기록을 함께 확인한다.

## 전체 실행과 자동 시험

| 실행 방법 | 그래프 |
|---|---|
| 기본 앱 설정·IDE 실행 | 기본 true. 주소·인증을 연결해야 기동 |
| `infra/compose.demo.yml` | Neo4j 기본 포함, Core가 HTTP 준비 상태를 기다림 |
| `infra/compose.dev.yml` | PostgreSQL과 Neo4j 기본 포함. 앱은 호스트에서 실행 |
| `scripts/live/up.sh` | 실제 AI용 로컬 전체 스택. CI 덮어쓰기 위에서 그래프를 다시 true로 켬 |
| `infra/compose.ci.yml` | Core false 명시. Neo4j는 `knowledge` 프로필로만 선택 |
| Java `test` 프로필 | false 명시. 그래프와 무관한 시험의 외부 DB 의존 제거 |
| `GraphWorkflowIT`, `Neo4jIT` | 별도 실제 Neo4j와 PostgreSQL을 사용하는 통합 시험 |

개발 환경을 전부 끄는 것이 아니다. 기능을 직접 실행할 때는 켜고, 그래프가 필요 없는 자동 시험에서만 끈다.

## 팀원이 main을 받아 로컬 실행하기

Docker·JDK 21·Node 22를 준비한다. Linux/WSL 기준이다.

### 실제 AI까지 포함한 전체 스택

```bash
bash scripts/live/keys.sh
bash scripts/live/up.sh --local
```

첫 명령은 OpenAI·Jev 키를 저장소 밖에서 준비한다. 두 번째 명령은 앱을 빌드하고 DB 변경·참조 자료·Neo4j까지 기동한다. 보호자 주소는 기본 `https://localhost:9443/parent/dev-login`이다. 실제 공급자 호출은 사용량에 따라 비용이 발생한다. 실제 모델 품질과 실제 기기 음성 입출력은 별도 확인한다. 자세한 사항은 `docs/live-run.md`를 따른다.

Neo4j 인증은 이 전체 실행 스크립트가 사용하는 `gen-env.sh`에서 무작위로 만든다. `build/live/env/core-api.env`와 `neo4j.env`에 같은 인증을 넣는다. `build/`는 Git에서 제외된다. 기존 파일 재사용 시 DB 비밀번호를 바꾸지 않고 그래프 인증만 안전하게 보충한다. 양쪽에 서로 다른 인증값이 있으면 덮어쓰지 않고 중단한다.

### IDE·호스트에서 앱을 실행하는 경우

```bash
bash infra/ci/gen-env.sh --out build/dev/env --origin http://localhost:3000 --keep-existing
bash infra/bin/render-pgconf --out build/dev/postgres/postgresql.conf
docker compose --env-file build/dev/env/compose.env -f infra/compose.dev.yml up -d --wait
```

Core 실행 환경에는 다음을 전달한다.

- `NEO4J_AUTH`: 생성된 `build/dev/env/core-api.env`의 값. 채팅·Git·로그에 복사하지 않는다.
- `MUJOONG_KNOWLEDGE_NEO4J_ENDPOINT=http://127.0.0.1:7474`.
- 기존 PostgreSQL·내부 토큰·프로필 등 앱 설정은 README의 호스트 실행 안내를 따른다.

Neo4j 사용자 이름/비밀번호를 코드에 넣지 않는다. IDE 환경 설정이나 로컬 env 로더에서 주입한다. env 파일을 작성한 것만으로 이미 실행 중인 앱에 적용되지는 않으므로 앱을 다시 시작한다.

`--keep-existing`는 기존 PostgreSQL 데이터에 맞는 비밀번호를 보존하기 위한 옵션이다. 인증값을 바꿔 파일에 적는 것만으로 이미 초기화된 Neo4j의 비밀번호가 자동 변경되지는 않는다. 운영 인증 변경은 DB의 사용자 비밀번호와 Core의 주입 값을 함께 관리한다.

## 시연·AWS 배포

Compose 호스트의 Parameter Store에는 **NEO4J_AUTH가 필수**다. `render-env`는 누락 시 앱을 바꾸기 전에 실패하고 값을 출력하지 않는다. Core와 Neo4j에 같은 인증 파일 값을 전달한다. `deploy-apply`는 기존 `/var/lib/mujoong/neo4j/{data,logs}` 경로를 유지하고 Neo4j를 먼저 기동·확인한 뒤 Core를 교체한다.

Neo4j 설정 파일은 전용 볼륨에 두고 루트 파일시스템은 읽기 전용으로 유지한다. `/tmp`는 Browser 자원 압축 해제와 네이티브 라이브러리에 필요한 256MB·exec 설정, 실행 상태 폴더는 작은 전용 tmpfs를 쓴다. 일반 웹·DB 컨테이너의 쓰기 권한을 함께 풀지 않는다.

전체 Compose 상시 서비스는 8개이며 메모리 한도 합계는 약 **6.575GiB**다. 배포 전 검사의 호스트 메모리 70% 제한은 유지한다. 기존 8GB 호스트는 이 조건을 충족하지 못하므로 전체 시연 호스트는 **16GB급**을 사용한다. Terraform의 EC2 설정도 이에 맞춰 `t3.xlarge`로 변경했다. 이 변경 자체가 AWS에 배포되거나 과금 자원을 생성하는 것은 아니다.

ECS 설정에는 Neo4j 태스크·서비스, 내부 HTTP 주소와 동일 SSM 인증 주입을 추가했다. ECS의 비밀 경로는 기존 구조에 맞춰 `/mujoong/app/NEO4J_AUTH`이고, Compose 호스트의 `/mujoong/demo/NEO4J_AUTH`와 구분한다. 기존 ECS 호스트에서는 데이터·로그 폴더를 7474:7474 소유로 준비해야 한다. 새 EC2의 초기화 스크립트에는 해당 준비가 들어 있다. 관리 포트를 인터넷에 공개하지 않는다.

이번 변경은 설정·코드 반영이며 AWS 배포 실행을 포함하지 않는다. Terraform의 기존 이미지 태그와 실제 AI·Google 비밀 주입은 실제 배포 시 별도로 맞춰야 한다.

## 검증 방법과 증거 범위

```bash
cd backend
./gradlew --no-daemon :core-api:test \
  --tests kr.mujoong.core.practicegoal.GraphWorkflowIT \
  --tests kr.mujoong.core.knowledge.Neo4jIT -Pmujoong.test.db=docker
```

`GraphWorkflowIT`는 실제 두 DB를 사용해 다음을 한 흐름으로 확인한다.

1. 기본 지식 14개 초기화, 아동별 조회 격리.
2. 합성 Luna 응답으로 새 지식 1개 생성·Neo4j 투영.
3. U14 목표 후보 버전 2의 지식 참조, 담은 목표의 참조 보존.
4. 재처리 시 중복 생성 방지와 기존 결과 DONE 유지.
5. U10에서 실제 맥락·변형 계획을 조회하고 별도 검토를 통과한 2개 장면 반환.
6. `graphContextUsed=true`, 승인 결과 확인.
7. 기록별 생성 지식 삭제, 기본 지식 유지, 아동 삭제 뒤 재초기화 방지.

`Neo4jIT`는 실제 인증 기동·잘못된 인증 거절·트랜잭션·재연결·삭제 경계를 확인한다. `ConfigurationTest`는 인증 누락·연결 불가 시 기동 실패와 명시적 false 경로를 확인한다.

자동 시험의 모델 응답은 합성 대역이다. 실제 AI의 판단 품질·15초 안의 응답 성공률·실제 아동 효과를 증명하지 않는다. 원문과 비밀값을 보고서에 남기지 않는다.

## 2026-10-20 로컬 검증 기록

- 관련 Java 시험 316개: 실패 0·오류 0·skip 0. `knowledge.*`, `practicegoal.*`, `ScenarioGraph*`, `SpringScenarioIT`, 설정 계약·기동 시험 포함.
- `test-infra-validate.sh`: 46개 모두 기대대로 통과. 잘못된 네트워크·권한·비밀·마이그레이션 등의 거절 검사를 유지했다.
- `test-host-tools.sh`: 43개 통과. Neo4j 인증 누락 거절을 추가했다.
- `test-neo4j-runtime.sh`: 기본 포함·CI 제외·live 재활성화·개발 실행·비밀 분배·기존 env 업그레이드·불일치 거절 통과.
- 실제 Neo4j 이미지의 읽기 전용 루트 + 설정 볼륨 + 256MB exec 임시 공간으로 HTTP 기동 성공 확인. 시험용 컨테이너·볼륨은 정리했다.
- Terraform 1.13.5의 `init -backend=false`, `validate` 통과. AWS provider 버전 6.69.0은 유지하고 Linux 패키지의 검증 해시만 lock 파일에 추가했다. AWS 자원을 적용하지 않았다.

기본 자료를 건별 HTTP 요청으로 쓰던 부분은 한 번의 UNWIND 요청으로 묶었다. 새 DB의 초기화 비용을 줄이면서 기존 2초 예산과 데이터 의미를 유지한다. 위 연결 시험과 Neo4j 저장·삭제 시험으로 확인했다.

Windows 작업 복사본에서는 YAML에서 읽은 이미지 태그 뒤에 CR 문자가 붙어 실행 검사가 실패하는 문제도 확인했다. YAML·Docker 관련 파일·호스트 실행 진입점은 LF로 받도록 Git 속성을 보완했다. Linux CI가 Windows 체크아웃 문제를 대신 발견해 주는 것은 아니므로 실행 파일의 줄바꿈 규칙도 함께 유지한다.
