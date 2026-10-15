# U8 계약 · 버전 리소스 — NFR Requirements 질문지

> 근거: `../functional-design/functional-spec.md` · `rules.md`(U8 설계), `../../../inception/requirements-analysis/requirements.md`(NFR1 ~ NFR7), `../../../inception/contract-design/contract-summary.md`, CodeKB `technology-stack.md`(networknt json-schema-validator 3.0.6 · Jackson 3), 팀 규칙 R-F1 · R-F3 · R-F14 · R-F15 · R-M9.
> U8은 `spec` 단위라 성능 · 확장성 · 신뢰성 · 관측 문서를 따로 만들지 않고 보안 요구와 기술 선택만 쓴다. 정할 수치가 새로 없고 모두 요구사항 · 팀 규칙에서 정해지므로, 아래 가정 묶음 하나로 확인받는다.

## Assumption Confirmation

1. **성능 · 시간:** U8에는 자체 응답 시간 목표가 없다. U8이 정하는 시간 키 7개(판정 2초 · 응답 준비 10초 · 제브 800ms · 무응답 10초 · 집중 90초 · 목표 10분 · 그래프 2초)는 설정값일 뿐이고, 실측(NFR1)은 그 키를 읽는 단위 · Build and Test가 한다. `LiveContentRegistry` 조회는 세션 메모리 안의 조회라 따로 목표를 두지 않는다.
2. **보안 · 개인정보**
   - AI 요청 스키마는 금지 필드(아동 · 보호자 · 세션 식별자, 키, 원음, 보호자 입력 원문)를 둘 수 없게 `additionalProperties: false`로 닫는다(NFR3).
   - `ai-io.md`에 R-F15 가드(공급자 약관 · 보호자 동의 확인 전 실제 아동 데이터 전달 금지, 실호출은 합성 자료만)를 적는다.
   - 저장소에는 비밀 이름만 두고 값은 두지 않는다(R-F3). 두 새 비밀은 선택(OPTIONAL)이다.
   - `LiveContentRegistry`는 대본 문장과 승인 발화를 세션 메모리에만 두고 정산 뒤 지운다. 원문 일치 검사는 출력 전사를 저장하거나 로그에 남기지 않는다(R-F14).
   - 판 리소스 파일에는 개인 정보가 없다.
3. **안전 실패:** 판 리소스 적재 실패면 기동을 거부한다. 세션 계획이 없으면 원문 일치는 `UNKNOWN`이라 제브 생략이 허용되지 않는다(NFR2).
4. **호환:** `approvedSpeech`는 선택 속성 더하기이고, `flowVersion`이 없으면 U5b 형식으로 읽는다. 옛 수신 쪽이 깨지지 않는다(NFR6).
5. **기술 선택:** 새 의존성은 없다. 스키마 시험은 이미 쓰는 `com.networknt:json-schema-validator` 3.0.6(2020-12, Jackson 3 빌드)을 쓰고, 자원 파일 읽기는 Jackson 3(`tools.jackson`), 시드는 SQL 파일 + 기존 `DevSeedRunner`를 쓴다.
6. **시험:** test-after, Standard 전략이다. `@Tag("u8")`, 범위 파일 `coverage-scope/u8.txt` 줄 80%, 가짜 공급자만 쓴다(R-F1 · NFR5). 운영 격리(NFR7)는 U8과 관계없다.

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions

## Q1. (검토 뒤) NFR 검토의 Major 2건을 어떻게 처리할까

R-01: NFR3.6(전사를 로그에 남기지 않음)의 확인 방법으로 적은 `log-hygiene-check`는 운영 컨테이너 로그 점검이라 이 요구를 검증하지 못한다. R-02: 판 리소스를 `tools.jackson`으로 직접 읽는다고 적었으나, 저장소 규약(J139)은 Jackson 직접 사용을 `JsonCodec` 한 곳으로 묶는다. Minor 2건(R-03 앱별 비밀 매핑, R-04 R-F15 문장 대조 시험)도 같은 묶음이다.

A. 문서 고친 뒤 진행 — 확인 방법을 로그 수집 단위 시험으로 바꾸고(판 번호는 로그에 남기지 않고 조회로만), 읽기는 `JsonCodec`으로 고치고, 앱별 비밀 매핑과 대조 시험을 적은 뒤 다음 단계로 간다 (추천)
B. 코드 생성 계획에서 정하기
X. Other (please specify)

[Answer]: A
