# U9 Luna 호출부 — 기술 선택

> 근거: `nfr-requirements-questions.md`(가정 8), `../functional-design/functional-design-questions.md`(Q1-A · Q3-A · Q4-A), CodeKB `aidlc/spaces/default/codekb/mujungi/technology-stack.md`, `backend/gradle/libs.versions.toml`.

| 영역 | 선택 | 까닭 | 버린 안 |
|---|---|---|---|
| 공급자 호출 | JDK `java.net.http.HttpClient`로 Responses API(`POST /v1/responses`) 직접 호출. 클라이언트는 `Redirect.NEVER` · 프록시 없음으로 만들고, 응답 본문은 상한까지만 읽는다(NFR3.9 · NFR3.10) | 저장소의 바깥 호출이 모두 JDK `HttpClient`다(`ProviderClient` · `GoogleTokenClient` · `InternalHttpClient`). 새 의존성 · 취약점 점검 대상이 없다(Q1-A) | `openai-java` SDK — Jackson 2 · OkHttp · Kotlin 런타임을 들여와 J139와 부딪히고 이미지가 커진다(편차 U9-a) |
| JSON 읽기 · 쓰기 | `kr.mujoong.contract.json.JsonCodec`(Jackson 3) | J139: Jackson 직접 사용은 `JsonCodec` 한 곳. 모르는 필드 무시 · 모르는 enum은 `UNRECOGNIZED` | 호출부에서 `tools.jackson` 직접 사용 |
| 응답 스키마 검증 | `com.networknt:json-schema-validator` 3.0.6(JSON Schema 2020-12)을 두 앱에서 `implementation`으로 옮김. 문자열 입력 방식으로 불러 Jackson 타입을 호출부에 들이지 않는다 | 이미 목록에 있고 시험에서 쓰는 판이다. Jackson 3.1로 빌드돼 Boot BOM과 맞는다. 계약 스키마 정본 한 곳으로 요청 · 검증(Q3-A) | 손으로 쓴 검사(스키마와 따로 놀음), 검증 생략(FR1.2 위반) |
| 스키마 파일 배포 | 빌드 때 `contracts/schemas/ai-*.schema.json`을 두 앱 실행 자원으로 복사. 요청용 사본(허용 키워드만)은 기동 때 메모리에서 만든다(BR4.5) | 정본 파일 하나, 앱 사이 코드 공유 없음(R-F5) | contract-model에 스키마 · 호출부를 두기(R-F5 · 학습 교정 위반) |
| 감사 기록 | 앱 `EventLogger` → `EventLog`(`LUNA_CALL`, `Component.LUNA_CLIENT`) | 팀 규칙: 운영 코드는 slf4j 직접 사용 금지, 사건 코드 · 속성 목록이 고정된 구조 로그 | slf4j 자유 문자열 로그 |
| 시각 · 시간 값 | `MujoongClock` 주입, 시간 값은 기존 설정 레코드(`TextAiProperties` · `RealtimeProperties.Timing`) | R-F6 · R-F7 | `Instant.now()` · 숫자 시간 값 |
| 시험 대역 | `test-support`에 JDK `HttpServer` 루프백 Responses 대역(정상 · 스키마 위반 · 빈 응답 · 미완료 · 거절 · 429 · 500 · 401 · 지연 · 비지원 키워드 거절 모드) | 기존 `ContractStubServer`와 같은 방식, 새 의존성 없음(R-F1) | WireMock 등 새 시험 의존성 |
| 실호출 시험 | Gradle `Test` 작업 `lunaLiveTest`(태그 `luna-live`), 공통 빌드 규칙이 기본 `test`에서만 태그를 고정 제외 | 사람이 키를 넣으면 바로 돌릴 수 있게(Q4 추가 요구), 자동 검증 경로에서 빠지게(편차 U9-c) | 기본 `test`에 포함 · `-PexcludeTags` 우회(R-F2 위반) |

## 버전 · 호환

- Java 21 · Spring Boot 4.1 · Gradle 8.14(기존). 새 버전 핀은 없다. `libs.versions.toml`은 고치지 않는다.
- `json-schema-validator`를 운영 이미지에 넣으므로 주간 의존성 취약점 스캔 · 이미지 점검 대상이 된다. 예외가 필요하면 `ci/security-exceptions.yml`의 승인 절차를 따른다(R-M7).
- `luna-live` 고정 제외는 `mujoong.java-conventions`의 공통 `tasks.withType<Test>()` 블록이 아니라 기본 `test` 작업에만 걸어야 `lunaLiveTest`의 포함 필터와 부딪히지 않는다(FD 재검토 메모).

## Sources

- `nfr-requirements-questions.md`, `security-requirements.md`(같은 폴더)
- `../functional-design/functional-spec.md` 1 · 7 · 9절, `rules.md` BR4.5 · BR9.3
- CodeKB `technology-stack.md`, `backend/gradle/libs.versions.toml`(11 ~ 12 · 71행), `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`

## Assumptions & Open Questions

- `json-schema-validator` 3.0.6의 문자열 입력 검증 API 이름은 Code Generation에서 라이브러리 판에 맞춰 확인한다. 문자열 입력이 없으면 검증기 호출을 `JsonCodec` 옆 한 클래스로 가두고 ArchUnit으로 묶는다.
