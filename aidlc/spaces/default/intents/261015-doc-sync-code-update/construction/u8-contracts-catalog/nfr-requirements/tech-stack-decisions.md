# U8 계약 · 버전 리소스 — 기술 선택

> 근거: CodeKB `aidlc/spaces/default/codekb/mujungi/technology-stack.md`, `backend/gradle/libs.versions.toml`(json-schema-validator 3.0.6), `../functional-design/functional-spec.md`, `../../../inception/requirements-analysis/requirements.md`(NFR5), `../../../inception/contract-design/contract-summary.md`, 질문 · 답 `nfr-requirements-questions.md`(가정 5 · 6).
> **새 의존성은 없다.** 모두 저장소에 이미 있는 것을 쓴다.

| 용도 | 선택 | 까닭 | 다른 안 |
|---|---|---|---|
| AI 입출력 스키마 형식 | JSON Schema 2020-12, `contracts/schemas/*.schema.json`, `$id` `https://mujoong.invalid/contracts/schemas/<파일>` | 기존 계약 규약과 같고 `ContractFilesTest`가 메타 검증을 한다(Contract Design Q2-A) | OpenAPI 안에 직접 적기 — 스펙 YAML이 AI 입출력을 다루지 않아 맞지 않음 |
| 스키마 정상 · 거절 예시 시험 | `com.networknt:json-schema-validator` 3.0.6(이미 contract-model 시험 의존, Jackson 3.1 빌드) | 같은 검증기로 계약 시험과 U8 시험을 맞춘다 | 다른 검증기 — 의존성만 늘어남 |
| 판 리소스 파일 형식 · 읽기 | JSON 파일을 `kr.mujoong.contract.json.JsonCodec`으로 읽고, 기동 때 한 번 검증한다. Jackson을 직접 import하지 않는다(J139: Jackson 직접 사용은 `kr.mujoong.contract.json` 한 곳). 필요하면 `JsonCodec`에 읽기 메서드를 더한다 | `gate/seed-dictionary.json`(`SeedDictionary`가 `JsonCodec` 사용)과 같은 방식이다 | YAML — 기존 연습 설정(u5b-runtime.yml)과 혼동 가능. `tools.jackson` 직접 사용 — J139 위반 |
| 승인 문구 시드 | SQL 시드 `infra/seed/004-u8-reference.sql` + 기존 `DevSeedRunner`(dev 프로필) | 기존 승인 문구 정본 경로 그대로(Q1-A) | Flyway 마이그레이션 — 참조 데이터는 시드로 넣는 팀 방식과 다름 |
| 시간 키 | `application.yml` + 설정 레코드(Spring `@ConfigurationProperties` 레코드, 기존 `RealtimeProperties` · `CoreProperties` 구조) | `ConfigKeyContractTest`가 대조하는 기존 경로 | 별도 yml + Binder(u5b 방식) — 대조 시험을 우회해 R-M5와 어긋남 |
| 시험 | JUnit 5 · AssertJ(백엔드), `@Tag("u8")`, 범위 파일 `backend/coverage-scope/u8.txt` 줄 80% | team.md Testing Posture | — |

## 버전 · 호환

- Java 21 · Spring Boot 4.1 · Gradle 8.14 그대로다.
- json-schema-validator 3.0.6은 Boot BOM의 Jackson 3.1과 맞는다(libs.versions.toml 주석).
- 프런트 변경은 없다. 화면 키를 더하지 않으므로 `timing-defaults.json` · `timing.test.ts`는 바뀌지 않는다.

## Sources

- `nfr-requirements-questions.md`, `security-requirements.md`(같은 폴더)
- `aidlc/spaces/default/codekb/mujungi/technology-stack.md`, `backend/gradle/libs.versions.toml`
- `../functional-design/functional-spec.md`, `../../../inception/requirements-analysis/requirements.md`, `../../../inception/contract-design/contract-summary.md`

## Assumptions & Open Questions

- 없음.
