# U9 Luna 호출부 — 보안 · 개인정보 · 품질 요구

> U9는 `library` 단위라 성능 · 확장성 · 신뢰성 · 관측 요구를 따로 문서로 두지 않고, 이 문서에 NFR1 ~ NFR7의 U9 몫을 함께 적는다. 근거: `nfr-requirements-questions.md`(가정 1 ~ 8 수락), `../functional-design/rules.md`(BR1 ~ BR9), `../../../inception/requirements-analysis/requirements.md`(NFR1 ~ NFR7).

## 1. 위협과 경계 (STRIDE 요약)

신뢰 경계는 **앱(core · realtime) ↔ 외부 공급자(OpenAI Responses API)** 하나다. 호출부는 이 경계를 넘는 유일한 길이다.

| 위협 | 대상 | 막는 것 |
|---|---|---|
| S 위장 | 공급자로 위장한 주소 | 프로필별 허용 호스트 · https만(BR1.4). 주소 바꿔치기는 기동 거부. 리다이렉트를 따르지 않고 프록시를 쓰지 않아, 실행 중에도 허용 호스트 밖으로 나가지 않는다(NFR3.9) |
| T 변조 | 공급자 응답 | 원본 계약 스키마로 검증(BR4.2), 모델 값 확인(BR4.3), 바깥 게이트웨이 검사 유지(BR7.2) |
| R 부인 | 무엇을 보냈는지 | 시도마다 `LUNA_CALL`에 보낸 필드 이름 · 모델 · 판 · 사유(BR6.1) |
| I 노출 | 아동 데이터 · 키 · 대화 | 금지 키 제거 · '아이' 치환 · 연락처 가림(BR2), `store=false`(BR1.6), 본문 로그 금지(BR6.2), 키는 env만(BR1.5) |
| D 거부 | 느린 · 막힌 · 거대한 응답 | 시한 = min(마감, 기존 키)(BR5.1), 재시도 상한 3회 · 예산 안(BR3), 401 · 403 재시도 없음(BR3.5), 응답 본문 크기 상한(NFR3.10), 재시도 대기는 마감 안에서만(NFR1.3) |
| E 권한 상승 | 입력으로 지시 바꾸기 | 지시(`instructions`)와 데이터(`input`) 자리 분리(BR2.5) |

**코드가 막지 않는 위험**

1. R-F15(약관 · 보호자 동의 범위 확인 전 실제 아동 데이터 전송 금지)는 Q2-B대로 코드 스위치가 없다. 키를 운영 env에 넣는 순간 실제 아동 대화가 공급자로 나갈 수 있다. 방어선은 RUNBOOK "키를 넣기 전 확인" 절차 하나이고, 그 확인은 기록으로 남긴다(NFR3.7).
2. 이름 치환은 호출자가 준 별칭 표에 있는 이름만 바꾼다. 아이가 말한 친구 · 가족 이름처럼 표 밖의 이름은 전사에 섞인 채 공급자로 나갈 수 있다. 별칭 표를 채우는 책임은 전사를 보내는 호출 단위(U10 · U11 · U14)에 있고, 그 단위의 설계가 '표 밖 이름' 처리를 정한다(NFR3.2).

## 2. 요구

| ID | 요구 | 확인 방법 |
|---|---|---|
| NFR1.1 | 시도 시한은 호출자 마감까지 남은 시간과 목적 시한 키(core `text-ai.request-timeout` · `result.request-timeout`, realtime `timing.judgment.live-budget`) 중 짧은 쪽을 넘지 않는다. 새 시간 키는 없다 | 루프백 대역 지연 대본 단위 시험, `TimeLiteralScanTest` |
| NFR1.3 | 일시 실패(429 · 5xx · 연결 오류 · 빈 응답) 뒤 재시도 대기(기존 `text-ai.retry.backoff`, 429의 `Retry-After`가 더 길면 그 값)가 남은 마감보다 길거나 같으면 재시도하지 않고 바로 실패를 돌려준다 | 루프백 대역 429(`Retry-After` 큼) · 500 대본 단위 시험 |
| NFR1.2 | 시도마다 지연을 `LUNA_CALL`에 남겨, 목적별 P50 · P95 · P99를 모을 수 있게 한다. 실측 대조는 쓰는 단위 · Build and Test 몫 | `LogCapture` 단위 시험(지연 속성 존재) |
| NFR2.1 | 미완료 · 빈 응답 · 거절 · 스키마 위반 · 모델 없음 · 시한 초과 · 공급자 오류는 모두 실패로 돌려주고, 빈 값 · 부분 응답을 성공으로 돌려주지 않는다 | 응답 분류 단위 시험(사유별) |
| NFR2.2 | 어댑터가 꺼지면 기존 실패 기본 빈이 쓰여 지금 동작과 같다 | 설정 단위 시험(키 없음 · 공백) |
| NFR3.1 | 보내기 전 금지 키(아동 · 보호자 · 세션 · 기기 식별자, 키, 음성, 이메일, 동의 상태, core `PRIVATE_KEYS`)를 모든 깊이에서 지우고, 남으면 부르지 않는다 | 입력 정리 단위 시험, 대역이 받은 본문 검사 |
| NFR3.2 | 호출자가 준 별칭 표의 아이 표시 이름은 '아이'로, 연락처 모양 값은 '연락처'로 바꿔 보낸다. 표 밖 이름은 잔여 위험으로 남고, 표를 채우는 책임은 호출 단위에 있다(1절) | 입력 정리 단위 시험 |
| NFR3.3 | 공급자 요청은 언제나 `store=false`이고 `temperature`를 보내지 않는다 | 요청 조립 단위 시험 |
| NFR3.4 | 주소는 demo · prod · `lunaLiveTest`에서 https + `api.openai.com`만, dev에서 그 규칙 또는 루프백, test에서 루프백만 받는다. 어기면 기동 거부 | 설정 단위 시험, 허용 호스트 상수 = `ai-io.md` 값 대조 시험(앱마다) |
| NFR3.5 | 요청 · 응답 본문, 전사, 이름, 키는 로그 · 예외 메시지 · `toString`에 나오지 않는다. `LUNA_CALL` 속성은 목적 · 모델 · 판 · 보낸 필드 이름 · 사유 · 지연 · 시도 번호뿐이다 | `LogCapture.anyLineContains`로 대역 본문 · 별칭 원래 이름 · 가짜 키 문자열 0건, 속성 목록 단언 |
| NFR3.6 | 키는 env `OPENAI_API_KEY`에서만 읽고 코드 · 설정 파일 · 시험 · 문서 · 로그에 값을 두지 않는다. 시험은 가짜 키 문자열만 쓴다 | 비밀 스캔(`a-checks` 키 형식 · gitleaks CI), 코드 검토 |
| NFR3.7 | R-F15 확인 절차(공급자 약관 · 개인정보 처리 조건 · 보호자 동의 범위)를 RUNBOOK의 `OPENAI_API_KEY` 입력 단계 바로 앞에 체크 항목으로 둔다. 확인할 때마다 확인자 · 날짜 · 확인한 약관 판(또는 문서 날짜) · 환경(demo 등)을 RUNBOOK의 확인 기록 표에 한 줄 남기고, 기록 없이는 키를 넣지 않는다. 키 값 · 계정 정보는 적지 않는다(R-F3). `contracts/ai-io.md` 10.2의 "'확인됨' 상태를 코드에서 판정하는 방법은 U9가 정한다(BR5.5)" 문장은 U9 Code Generation에서 "U9는 코드로 판정하지 않고 RUNBOOK 확인 기록으로 지킨다(편차 U9-b)"로 바꾸고(R-M10), U8의 R-F15 문장 대조 시험(`U8ContractSchemasTest`)도 새 문장에 맞춘다 | 문서 대조 시험(`ai-io.md` 새 문장 존재), RUNBOOK 검토 |
| NFR3.9 | 공급자 HTTP 클라이언트는 리다이렉트를 따르지 않고(3xx는 `REQUEST_REJECTED` 실패) 프록시를 쓰지 않는다. 요청을 보내기 직전 대상 주소가 허용 규칙(NFR3.4)에 맞는지 다시 확인한다 | 루프백 대역 302 대본 단위 시험(다른 호스트로 요청 0건), 클라이언트 구성 단위 시험 |
| NFR3.10 | 응답 본문은 정해진 상한(설정 `mujoong.luna.max-response-bytes`, 기본 1MB — 기존 `ResultTextGateway`의 출력 상한과 같은 크기)까지만 읽고, 넘으면 읽기를 멈추고 `LENGTH_EXCEEDED` 실패로 돌려준다. 시간 값이 아니다 | 루프백 대역 큰 응답 대본 단위 시험 |
| NFR3.8 | Luna 호출부 패키지는 가드레일(제브) · slf4j · 상대 앱 패키지에 의존하지 않는다 | ArchUnit(`core/arch` · `realtime/arch`) |
| NFR4.1 | `LUNA_CALL`의 사유 · 모델 · 판 · 지연을 U11 판정 보류율 · 지연 계측의 재료로 제공한다(계측 자체는 U11) | `LogCapture` 단위 시험 |
| NFR5.1 | 기본 `test` · `check` · CI · `verify.sh`는 루프백 대역만 쓴다. `luna-live` 고정 제외는 공통 `tasks.withType<Test>()` 블록이 아니라 기본 `test` 작업에만 걸고, `lunaLiveTest`는 그 태그만 포함하며 `check`에 물리지 않는다. 고정 제외 값은 `-PexcludeTags`와 함께 시험 작업 입력(캐시 키)에 들어가게 한다(편차 U9-c) | 빌드 구성 시험(`check` 작업 그래프에 `lunaLiveTest` 없음, 기본 `test`의 제외 목록에 `luna-live` 있음, `lunaLiveTest`의 포함 목록 = `luna-live`) |
| NFR5.2 | `lunaLiveTest`는 키가 없으면 `SKIPPED_NO_KEY`로 끝나 통과로 보고되지 않고, 합성 자료만 쓰며, 결과를 '실제 API · 합성 자료' 증거로 따로 보고한다(R-M11) | 키 없는 실행 결과 확인, 보고서 형식 검토 |
| NFR5.3 | test-after · Standard, `@Tag("u9")`, `backend/coverage-scope/u9.txt` 줄 80% | JaCoCo 범위 측정 |
| NFR5.4 | `json-schema-validator`를 실행 범위로 옮긴 커밋 바로 뒤에 의존성 취약점 스캔과 이미지 점검(`infra/ci/image-checks.sh` · CI 의존성 스캔)을 돌려 새 전이 의존성의 결과를 확인한다. 예외가 필요하면 `ci/security-exceptions.yml` 승인 절차를 따른다(R-M7) | Code Generation 단계의 스캔 결과 기록 |
| NFR6.1 | `TextAiProvider` · `ResultTextProvider` 인터페이스와 바깥 게이트웨이 · `OutputChecker` 경로를 바꾸지 않는다. 기존 시험이 그대로 통과한다 | 기존 시험 기준선 대비 회귀 0 |

## 3. 다루지 않는 것

- **확장성 · 가용성 목표:** 호출부는 앱 안 라이브러리이고 상태를 갖지 않는다. 동시 호출 수 · 공급자 속도 제한 대응은 쓰는 단위(U10 · U11 · U14)가 목적별로 정한다. 429는 일시 실패로 분류해 돌려준다.
- **NFR7 운영 격리:** U9는 Compose 프로젝트 · 볼륨을 건드리지 않는다. `mujoong-manual`과 관계없다.
- **판정 확률 값 품질 · 모델 품질:** 판정 · 생성 품질은 각 단위의 `[실호출-합성]` 확인표 몫이다.

## Sources

- `nfr-requirements-questions.md`(같은 폴더)
- `../functional-design/functional-spec.md` · `rules.md` · `entities.md`
- `../../../inception/requirements-analysis/requirements.md`(NFR1 ~ NFR7), `../../../inception/contract-design/contract-summary.md`(C-DS4 ~ C-DS7)
- 팀 규칙 `aidlc/spaces/default/memory/project.md`(R-F1 · R-F3 · R-F13 · R-F14 · R-F15 · R-M7 · R-M11)
- 계약 · 코드: `contracts/ai-io.md` 10절, `backend/gradle/libs.versions.toml`

## Assumptions & Open Questions

- R-F15를 코드가 막지 않는 것은 사람이 Q2-B로 고른 결과다. 확인 기록은 RUNBOOK의 확인 기록 표(확인자 · 날짜 · 약관 판 · 환경)로 남긴다(NFR3.7, NFR 검토 Q1-A).
- 응답 크기 상한 기본값 1MB는 기존 `ResultTextGateway`의 출력 상한(`output.length() > 1_000_000`)에 맞춘 값이다. `max_output_tokens` 4000에서 정상 응답이 이 값에 닿지 않는다.
- 공급자 속도 제한(분당 요청 · 토큰)의 실제 값은 계정 등급에 달려 있어 `lunaLiveTest`와 운영 관측으로 확인한다.
