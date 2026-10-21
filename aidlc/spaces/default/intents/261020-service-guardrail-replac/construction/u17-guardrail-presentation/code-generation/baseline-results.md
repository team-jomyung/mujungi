# U17 프런트 기준선

최초 기준선은 아래에 보존한다. Loop-back 1은 기존 구현의 수정이며998개 초기 기준선을 새 실행처럼 덮어쓰지 않는다.

## Loop-back 1 수정 전 결함 기준선

- 승인된 재시도에서 기존 구현/31파일 manifest·16운영파일 범위·초기 검증 이력을 대조했다. 승인 계획의 빈 체크 표시만으로 구현을 재생성하지 않았다.
- `npx vitest run tests/parent/features/done/guardrail-review-regression.test.tsx` (frontend): 종료1, 1파일/2시험 모두 FAIL. 부모가 쓴 기존 단언을 그대로 실행해 F-R01(online 뒤 캐시 인사 재표시)·F-R02(GET403 뒤 이전 인용 잔존)를 재현했다.
- 앞선 Build and Test의 동일2FAIL과 일치한다. R-03의 기존 추적 invalid_targets46도 이전 검토 기록으로 확인했다.
- 이번 수정 뒤 실행과 지문은 verification-results.md의 Loop-back 1 절에 별도로 기록한다. 브라우저/Next 서버·backend·전체 verify.sh는 이번 재시도에서 실행하지 않는다.

## 최초 구현 전 기준선(보존)

- 브랜치 `feat/service-guardrail-replacement`, HEAD `2e3d7b0e0796844166fb54b21220512ff20ce9e1`. 앱 편집 전 실행. 기존 변경 보존.
- `npm run lint` (frontend): 종료0.
- `npx vitest run tests --coverage`: 종료0, 75파일/998시험 PASS, skipped0. LINE3455/3506=98.54%, statements3973/4087=97.21%.
- `npm run build`: 종료0, TypeScript/production build 성공.
- 실행기: package.json 고정 Vitest4.1.11/jsdom/v8, 기존 vitest.config.ts LINE80 유지.
- 첫 한정 명령 `npx vitest run tests/parent/common/api/api-types.test.ts tests/device/audio/player.test.ts`: 종료0, 2파일/33시험 PASS.
- 합성 API/오디오 대역. 실제 공급자·실기기·배포·manual 스택 사용0. 전체 verify.sh는 후속 Build and Test 소유.

## Loop-back 2 — 기존 결함 재현 이력

2026-10-21 15:59 KST, frontend에서 `npx vitest run tests/parent/common/guardrail-live-updates.test.tsx`를 실행했다. 종료1·11개 중8PASS/3FAIL. identity/sequence/leave 경계 뒤 새 ACTIVE 성공을 적용하고 이전403을 반환하면 현재 캐시가 undefined가 됐다. 기존3단언은 보존했고 수정 후 결과는 verification-results.md 마지막 Loop-back 2 절을 따른다. 원래14단계의 전체 기준선을 재실행한 기록이 아니다.
