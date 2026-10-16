# 보안 시험 방법 — U8 · U9 범위

| 확인 | 대상 요구 | 명령 · 근거 |
|---|---|---|
| 비밀 형식 스캔(작업 트리) | U8 NFR3.5, U9 NFR3.6 | `bash scripts/verify.sh` A4 · P3 |
| 이력 비밀 스캔 | 같음 | CI `secrets` 작업(gitleaks, 다이제스트 고정). 로컬은 같은 이미지로 `586238b..HEAD` 검사 |
| 의존성 취약점(bootJar) | U9 NFR5.4 | CI와 같은 trivy 이미지 · `rootfs --scanners vuln --severity HIGH,CRITICAL` |
| 로그 위생(전사 · 본문 · 키 미기록) | U8 NFR3.6, U9 NFR3.5 | `LogCapture` 단위 시험(`@Tag("u8")` · `@Tag("u9")`), `verify.sh` P2 |
| 보내는 입력 정리 · 금지 키 · 이름 치환 | U9 NFR3.1 · NFR3.2 | `LunaInputSanitizer` 시험, 대역이 받은 요청 본문 검사 |
| 나가는 길 제한(리다이렉트 · 프록시 · 호스트 · 크기) | U9 NFR3.4 · NFR3.9 · NFR3.10 | `LunaHosts` · `LunaTransport` 시험 |
| 패키지 경계(가드레일 · 로깅 · 상대 앱 · Jackson) | U9 NFR3.8 | `LunaArchTest`(두 앱) |
| 교차 스키마 쓰기 차단 | 기존(R-F5) | `verify.sh` P1 |
| shellcheck(인프라 스크립트) | 인프라 관례 | `infra/bin/infra-validate`(shellcheck 필요) |

DAST · 침투 시험은 이번 범위에 없다(새 공개 경로 없음).
