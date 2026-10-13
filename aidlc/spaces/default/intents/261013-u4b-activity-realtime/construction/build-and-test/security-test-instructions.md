# U4b 보안 시험 지시 — 활동 세션 Realtime

> Build and Test(3.6) · 시험 전략 Standard. U4b가 바꾼 경계에서 보안 · 개인정보 목표(NFR4 개인정보 비노출, NFR9 삭제 완결성)와 기존 보안 검사를 확인한다. 새 공개 엔드포인트 · 인증 방식은 없다.

## 1. 공격면과 위협(STRIDE 요약)

| 경계 | 바뀐 것 | 위협 | 대응 · 확인 |
|---|---|---|---|
| Core → Realtime 내부 API(끝내기 · 진행 조회) | CONSENT_WITHDRAWN 사유 · 응답표 | Tampering(알 수 없는 사유) · Repudiation | 사유는 열거 값만 받고 그 밖은 400(`EndRequestU4bTest`), 반복 요청은 멱등(NFR3). 내부 API 인증은 기존 그대로(U1) |
| 세션 로그 · 사건 · Outbox | 참여 · 확인 질문 · 공백 · 정산 · 삭제 경로 로그 | Information Disclosure(전사 원문 · 원음 경로 노출) | `PrivacyLogU4bTest`: 로그 · SessionEvent payload · Outbox(스냅숏 record 제외)에 원문 · 원음 경로 0건(NFR4 · BR12.1) |
| 동의 철회 | 철회 뒤 아이 전사 · 원음 저장 차단 | Information Disclosure | `EndRequestU4bTest` · `SessionStopU4bTest` 계열 |
| 계정 삭제 | Realtime 행 · 원음 파일 실제 삭제 | Information Disclosure(잔존 데이터) | `RealtimeDeletionHandlerIT`: 삭제 뒤 행 · 파일 0건, 재시도 멱등(NFR9) |
| 공급자 요청 | `session.response.create` · 연습 지시(`session.instructions.correct` 재사용) | Tampering(허용되지 않은 요청 형식) | `ProviderRequestWhitelistIT` 허용 목록 · `RealtimeArchTest` |
| 스키마 권한 | 새 표 `realtime_result_input_snapshot` | Elevation of Privilege | `RealtimeSchemaIT` 권한 · `SchemaPrivilegeIT`, 끝-끝 P1 스키마 격리 |

## 2. 실행 명령

```
cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u4b --tests 'kr.mujoong.realtime.*PrivacyLogU4bTest' --tests 'kr.mujoong.realtime.*RealtimeDeletionHandlerIT' --tests 'kr.mujoong.realtime.*EndRequestU4bTest'
cd backend && ./gradlew --no-daemon check
bash scripts/verify.sh --mode host
```

- `check`는 ArchUnit · 설정 키 대조 · 허용 목록 시험을 포함한다.
- `verify.sh`의 A4(비밀 스캔) · P1(스키마 격리) · P2(로그 위생) · P3(비밀 값 0건)이 보안 검사다.
- Docker가 없으면 Gradle 명령에 `-Pmujoong.test.db=local`을 붙인다.

## 3. 기대값

- NFR4: 원문 · 원음 경로 0건
- NFR9: 삭제 뒤 그 보호자의 Realtime 행 · 원음 파일 0건
- verify.sh A4 · P1 · P2 · P3 PASS
- SAST · DAST · 의존성 취약점 검사는 이 저장소의 CI 구성 범위이며 U4b가 바꾸지 않았다.

## 4. 시험 데이터

- 로그 검사는 시험마다 고유한 전사 문자열을 만들고, `LogCapture`가 모은 줄에 그 문자열이 없는지 본다.
- 삭제 시험은 시험마다 새 보호자 · 세션 · 원음 파일을 만든다.
