# 제브 어댑터 선 형식 정정 — 보안 시험 지시서

> 이 정정은 아이 발화 후보 문장과 익명 최근 맥락 3턴이 외부 공급자(제브)로 나가는 선 형식을 바꾼다. 아래 점검은 모두 **가짜 제브 · 정적 검사**로 하며, 실제 제브의 보안 특성은 확인하지 않았다.

## 1. 위협 → 통제 → 증거 (STRIDE 요약, 변경분 중심)

| STRIDE | 위협 | 통제 | 증거 (테스트 대역 · 정적 검사) |
|---|---|---|---|
| Information Disclosure | 후보 문장 · 맥락 · 키 · 주소 · 오류 본문이 로그 · 기록 · 보고서 · 지표로 샌다(R-F14) | 어댑터는 아무것도 기록하지 않고 오류 본문을 읽지 않으며 예외 원인 메시지를 버린다. `guardrail_event`에 원문 칸 없음. `jevLiveTest` 보고서는 판정 · 확률 · 모델 · 지연만 | `JevGuardrailClientTest`(키 비노출 · 오류 본문 표식 비노출), `JevLiveTaskIsolationTest`(보고서에 원문 · 키 · 주소 없음), 아키텍처 검토의 grep |
| Information Disclosure | 새 요청 본문에 식별자가 실려 나간다 | `state`는 `candidate`와 `context[{speaker,text}]`뿐, 아이 이름 치환, 식별자 필드 없음 | `JevGuardrailClientTest`(본문 키 집합 · 이름 치환), `GuardrailRequestFactoryTest` |
| Tampering (프롬프트 주입) | 아이 발화가 검사 질문 · 선택지를 바꿈 | 후보 문장은 `state`의 데이터 필드에만 들어가고 `instructions` · `criteria`는 정책 리소스에서만 온다. 정책 문안에 지시 격리 문장 포함 | `JevGuardrailClientTest`(지시문 격리), `GuardrailPolicyV2Test` |
| Spoofing / SSRF | 요청이 허용 밖 호스트로 나가거나 리다이렉트를 따라감 | 허용 호스트 상수 + 직전 재확인, 리다이렉트 · 프록시 없음 | `JevHostsTest`, `JevGuardrailClientTest`(리다이렉트 대상 호출 0회 · 허용 밖 호스트 호출 0회) |
| Elevation / Fail-open | 형식 오류 · 오류 상태 · 모호한 응답이 자동 통과가 됨 | 자동 `ALLOW`는 `PASS` 1순위 + 문턱 이상 + 엄격 최대일 때만. 동률 · NaN · 범위 밖 값 · 여분 키 · `choice` 불일치 · 문턱 범위 밖은 모두 막힘 | `JevGuardrailClientTest` 판정 경계 · 비정상 응답 시험, `GuardrailModeStartupChecksTest`(문턱 범위) |
| Denial of Service | 제브 지연 · 429 · 529로 기기가 '생각 중'에 갇힘 | 800ms 상한 + 안전 타이머 + 재시도 없음(429 · 529도 `TRANSPORT_ERROR`) | `JevGuardrailClientTest` · `GuardrailSafetyTimerTest` |
| 비밀 관리 | 키가 저장소 · 이미지 · 로그에 들어감(R-F3) | 키는 호출 때마다 환경에서 읽음, `jevLiveTest`는 셸 env로만, 저장소 Actions 비밀 0개(R-M8) | `JevLiveTaskIsolationTest`, `verifyJevLiveIsolation`, `bash scripts/verify/a-secrets.sh`(문서 · 기록 포함) |
| 운영 기동 안전 | 문턱이 잘못된 채 demo · prod가 뜸 | REAL에서 문턱이 `0 < x <= 1`이 아니면 기동 거부, 어댑터도 범위 밖이면 보내지 않고 막음 | `GuardrailModeStartupChecksTest`, `image-checks.sh` ④ |

## 2. 실행 방법

```bash
sg docker -c 'cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker'
sg docker -c 'bash scripts/verify.sh --mode compose'        # 정적 · 보안 단계 포함
sg docker -c 'bash infra/ci/image-checks.sh'                # 이미지 ④ 기동 거부 확인
bash infra/bin/log-hygiene-check                            # 로그 위생
bash scripts/verify/a-secrets.sh                            # 비밀 스캔(aidlc/ 기록 문서 포함)
```

## 3. 확인하지 못한 것

- **실제 제브로 나가는 데이터의 처리 조건.** 공급자 약관 · 개인정보 처리 · 아동 데이터 전달 허용 범위(보호자 동의 포함)는 확인한 적이 없다. `infra/RUNBOOK.md` 단계 4의 제브 R-F15 확인 칸이 **비어 있다**. 코드는 이를 막지 못하므로(키를 넣으면 나간다) 사람의 R-F15 확인이 유일한 방어선이다.
- 실제 제브가 이 요청 형식에서 보이는 오류 · 응답 특성. 형식이 어긋나면 모든 검사가 막힘이 되어 아이가 대화를 못 듣는다(fail-closed, 안전하지만 가용성 문제).
- 의존성 취약점 · 이력 비밀 스캔(`trivy` · `gitleaks`)은 이 PC에 도구가 없어 CI가 돌린다. 이 정정은 새 외부 의존성을 더하지 않았다.
