# U13 제브 게이트 — 보안 시험 지시서

> 이 단위는 아이 발화 후보 문장과 익명 최근 맥락 3턴을 외부 공급자(제브)로 보내는 경로를 만든다. 아래 점검은 모두 **가짜 제브 · 정적 검사**로 하며, 실제 제브 보안 특성은 확인하지 않았다.

## 1. 위협 → 통제 → 증거 (STRIDE 요약)

| STRIDE | 위협 | 통제 | 증거 (테스트 대역 · 정적 검사) |
|---|---|---|---|
| Information Disclosure | 후보 문장 · 맥락 · 키가 로그 · 기록 · 지표 · 오류 응답으로 샘다(R-F14) | `guardrail_event`에 원문 칸 없음, 이유 코드는 대문자 정규식으로 제한, 어댑터는 예외 원인 메시지를 버림, `toString`은 길이 · 모드만 | `GuardrailEventSchemaIT`(칸 목록), `JevGuardrailClientTest` 11번(키가 본문 · 결과 · 클라이언트 `toString` · 로그에 없음), `GuardrailCoordinatorTest`(기록에 원문 없음), 아키텍처 검토의 grep(로거 · `System.out` 없음) |
| Information Disclosure | 식별자가 외부로 나감 | 요청에 식별자 필드 없음, 이름 치환, 맥락 3턴 익명화, 지시문은 데이터 필드에만 | `GuardrailRequestFactoryTest` 8개, `JevGuardrailClientTest` 1번(본문 키 집합 정확히 일치) |
| Spoofing / SSRF | 요청이 허용 밖 호스트로 나가거나 리다이렉트를 따라감 | 허용 호스트 상수 한 곳 + 직전 재확인, 리다이렉트 · 프록시 없음, 3xx는 전송 오류 | `JevHostsTest` 6개, `JevGuardrailClientTest` 6번(리다이렉트 대상 호출 0회), 10번(루프백 · 잘못된 주소 호출 0회) |
| Tampering (프롬프트 주입) | 아이 발화가 제브 지시문을 바꿈 | 후보 문장은 데이터 필드에만 넣고 시스템 지시는 정책 리소스에서만 가져옴 | `GuardrailRequestFactoryTest`(지시문 격리) |
| Elevation / Fail-open | 제브 오류 · 시간 초과가 자동 통과가 됨 | 모든 실패는 `FAILED`(막힘), `ALLOW`인데 `reviewNeeded=true`인 모순 응답도 막힘, 잘못된 JSON 12종 막힘, 안전 타이머 | `JevGuardrailClientTest` 7번 · 8번, `GuardrailSafetyTimerTest`, `GuardrailExecutorTest`(포트 예외 fail-closed) |
| Denial of Service | 제브가 안 끝나 기기가 '생각 중'에 갇힘 | 800ms 상한 + 안전 타이머 + 대기열 1칸 + 재지시 1회 | `GuardrailCoordinatorTest`, `GuardrailExecutorTest` |
| 비밀 관리 | 키가 저장소 · 이미지 · 로그에 들어감(R-F3) | 키는 호출 때마다 환경에서 읽고 필드에 보관하지 않음, 저장소 Actions 비밀 0개(R-M8), 실호출 도구는 셸 env로만 | `JevLiveTaskIsolationTest`, `verifyJevLiveIsolation`, gitleaks(`ci-gate`), `infra/bin/log-hygiene-check` |
| 운영 기동 안전 | 제브가 꺼진 채 demo · prod가 뜸 | demo · prod는 REAL + 키 + 허용 호스트 + 고정 버전 모델이 아니면 기동 거부 | `GuardrailModeStartupChecksTest` 10개, `image-checks.sh` ④(키 없는 realtime이 기동 거부로 끝나는지) |

## 2. 실행 방법

```bash
sg docker -c 'cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker'
sg docker -c 'bash scripts/verify.sh --mode compose'     # 정적 · 보안 단계 포함
sg docker -c 'bash infra/ci/image-checks.sh'             # 이미지 ④ 기동 거부 확인
bash infra/bin/log-hygiene-check                         # 로그 위생(비밀 이름 목록 일관)
```

## 3. 확인하지 못한 것

- **실제 제브로 나가는 데이터의 처리 조건.** 공급자 약관 · 개인정보 처리 · 아동 데이터 전달 허용 범위(보호자 동의 포함)는 확인한 적이 없다. `infra/RUNBOOK.md` 단계 4의 제브 R-F15 확인 칸이 **비어 있다**. 코드는 이를 막지 못하므로(키를 넣으면 나간다) 사람의 R-F15 확인이 유일한 방어선이다(R-F15).
- 의존성 취약점 · 비밀 스캔은 CI(`ci-gate`)가 돌린다. 이 단위는 새 외부 의존성을 더하지 않았다(JDK `HttpClient` 사용).
