# U8 계약 · 버전 리소스 — 보안 · 개인정보 요구

> 근거: `../functional-design/functional-spec.md` · `../functional-design/rules.md`(BR1 ~ BR7), `../../../inception/requirements-analysis/requirements.md`(NFR2 · NFR3 · NFR6), `../../../inception/contract-design/contract-summary.md`(C-DS4 ~ C-DS9 · C-DS14), CodeKB `aidlc/spaces/default/codekb/mujungi/technology-stack.md`, 팀 규칙 R-F3 · R-F9 · R-F14 · R-F15 · R-M9, 질문 · 답 `nfr-requirements-questions.md`(가정 1 ~ 6 수락).
> 요구 ID는 요구사항 NFR 번호에 하위 번호를 붙인다(NFR2.x 안전 실패, NFR3.x 개인정보 · 비밀, NFR6.x 호환).

## 1. 위협과 경계 (STRIDE 요약)

U8이 만드는 것은 계약 파일, 설정 키, 시드 문구, realtime 메모리 안의 조회 컴포넌트(`LiveContentRegistry`)다. 외부와 직접 통신하지 않는다. 위협은 '이 계약을 따르는 다른 단위가 무엇을 새게 하거나 우회할 수 있는가'로 본다.

| 위협 | 대상 | 막는 요구 |
|---|---|---|
| 정보 노출 — 아동 식별자 · 원문이 외부 AI로 감 | AI 요청 스키마(C-DS4 · C-DS8) | NFR3.1 · NFR3.2 · NFR3.3 |
| 정보 노출 — 비밀 값이 저장소 · 로그에 남음 | 비밀 이름 목록 · 설정 | NFR3.4 · NFR3.5 |
| 정보 노출 — 출력 전사 · 대본이 로그 · DB에 남음 | `LiveContentRegistry` | NFR3.6 |
| 변조 — 출처 불명 문장이 '승인 원문'으로 통과해 제브를 피함 | 원문 일치 | NFR2.1 · NFR2.2 |
| 변조 — 판 리소스가 깨진 채 판정 · 검사가 돎 | 판 리소스 적재 | NFR2.3 |
| 부인 — 어떤 판으로 판정 · 검사했는지 알 수 없음 | 세션 고정 | NFR2.4 |
| 서비스 거부 — 새 속성 때문에 기존 수신 쪽이 세션을 거절 | 시작 패키지 | NFR6.1 |

## 2. 요구

| ID | 요구 | 확인 방법 | 근거 |
|---|---|---|---|
| NFR2.1 | 원문 일치는 공백 제거 뒤 완전 일치만 인정한다. 그 밖의 정규화 · 유사도 판단은 하지 않는다. | `LiveContentRegistry` 단위 시험(한 글자 차이 → `NOT_MATCHED`) | BR1.4, FR6.6 |
| NFR2.2 | 세션 계획이 없거나 출처를 확인할 수 없으면 `UNKNOWN`을 돌려준다. `UNKNOWN` · `NOT_MATCHED`는 제브 생략 근거가 될 수 없다고 계약에 적는다. | 단위 시험, `ai-io.md` 문장 | BR1.4, AC2.2.3 |
| NFR2.3 | 판 리소스 파일 적재 · 검증에 실패하면 realtime 기동을 거부하고 사유 코드를 남긴다. | 단위 시험(형식 오류 자원 → 기동 실패) | BR2.1 |
| NFR2.4 | 세션 고정 판 번호는 세션 동안 바뀌지 않고, 조회로 꺼낼 수 있다(기록 행에 싣는 일은 U11 · U13). | 단위 시험 | BR2.2 |
| NFR3.1 | AI 요청 스키마는 `additionalProperties: false`로 닫고 금지 필드(아동 · 보호자 · 세션 식별자, 키, 원음, 보호자 입력 원문)를 속성으로 두지 않는다. | 스키마 거절 예시 시험 | BR5.4, FR1.4, FR6.7 |
| NFR3.2 | 요청 스키마의 아이 관련 문자열 필드 `description`에 "아이 이름은 '아이'로 치환된 값"을 명시한다. | U8 계약 대조 시험: 요청 스키마의 해당 필드 `description`에 치환 문구가 있는지 단언 | NFR3, FR1.4 |
| NFR3.3 | `ai-io.md`에 R-F15 가드를 적는다. 공급자 약관 · 보호자 동의 확인 전에는 실제 아동 데이터를 외부 모델로 보내지 않는다. 실호출은 합성 자료로만 한다. | U8 계약 대조 시험: `ai-io.md` 새 절에 R-F15 문장(고정 표식 `R-F15`)이 있는지 단언 | R-F15, BR5.5 |
| NFR3.4 | 새 비밀은 이름만 저장소에 두고 R-M9 목록 전부에 같은 커밋으로 넣는다. 둘 다 선택(OPTIONAL)이다. **앱별 매핑(최소 권한):** `OPENAI_API_KEY`는 core-api · realtime-activity(Luna를 두 앱이 각자 부름, ADR-004), `JEV_API_KEY`는 realtime-activity만. GPT-Live의 `PROVIDER_API_KEY`는 그대로 realtime만이고, core 예약 이름 `MUJOONG_TEXT_AI_API_KEY`는 쓰지 않는다(BR4.2). | CI 비밀 검사 · `infra-validate`(`SECRET_NAMES` + 서비스별 `SECRET_TABLE` 매핑) · `test-infra-validate.sh` · `render-env`의 `write_env` 앱별 분배 | BR4.1, BR4.2, R-M9 |
| NFR3.5 | 계약 · 설정 · 시드 · 시험 · 문서에 비밀 값 · 실제 도메인을 쓰지 않는다. `.gitleaks.toml` 허용 목록은 늘리지 않는다. | gitleaks CI | R-F3, R-F9 |
| NFR3.6 | `LiveContentRegistry`는 대본 문장 · 승인 발화를 세션 메모리에만 두고 정산 뒤 지운다. 원문 일치 검사는 입력 전사를 저장하지 않고 **로그를 남기지 않는다**. 판 번호 · 일치 결과는 조회 반환값으로만 내고, 기록이 필요하면 그것을 받는 단위(U11 · U13)가 자기 기록 행에 쓴다. 그래서 U8은 새 `EventCode` · `AttrKey`나 `log-hygiene-check` 허용 표를 고치지 않는다. | 단위 시험: 정산 뒤 조회 `UNKNOWN`. 로그 수집 시험: 시험용 로그 수집기를 붙이고 원문 일치 · 대본 등록을 돌린 뒤, 전사 · 대본 문장의 부분 문자열이 어떤 로그 줄에도 없다고 단언 | R-F14, NFR 검토 R-01 |
| NFR3.7 | 판 리소스 · 시드 문구에는 개인 정보가 없다. | 리뷰 | NFR3 |
| NFR6.1 | `approvedSpeech`는 선택 속성 더하기다. `approvedSpeech`가 없는 옛 패키지와 모르는 속성이 있는 패키지를 realtime이 거절하지 않는다. | realtime 수신 시험 | NFR6, BR1.2 |
| NFR6.2 | `record.practice`에 `flowVersion`이 없으면 U5b 형식으로 읽는다. `snapshotVersion`의 뜻은 바꾸지 않는다. | 스키마 · 문서 확인(읽는 시험은 U14) | BR6.1, FR7.5 |

## 3. 다루지 않는 것

- 인증 · 권한 · 전송 암호화: U8이 새 경로나 외부 연결을 만들지 않는다. 내부 API 토큰 · TLS는 기존 그대로다.
- 성능 · 확장성 · 신뢰성 · 관측: `spec` 단위라 문서를 두지 않는다. 시간 키 실측과 지표는 그 키를 쓰는 단위와 Build and Test가 맡는다(가정 1).

## Sources

- `nfr-requirements-questions.md`(같은 폴더), `../functional-design/functional-spec.md`, `../functional-design/rules.md`
- `../../../inception/requirements-analysis/requirements.md`, `../../../inception/contract-design/contract-summary.md`
- `aidlc/spaces/default/codekb/mujungi/technology-stack.md`
- `aidlc/spaces/default/memory/project.md`(R-F3 · R-F9 · R-F14 · R-F15 · R-M9)

## Assumptions & Open Questions

- R-F15 '확인됨' 상태를 코드에서 어떻게 판정할지(설정 플래그 · 동의 스냅숏)는 U9 몫이다. U8은 계약 문장만 둔다.
