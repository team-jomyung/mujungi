# Business Overview

> 기준: 커밋 570a625, 전체 재스캔(261018-structure-refactor). 근거는 개발자 핸드오프(`inception/reverse-engineering/developer-scan.md`)와 `docs/refactor-structure-proposal.md`다. 기준 문서는 화면 설계 v1.5j이며 문서끼리 다르면 화면 설계가 맞다.

## 목적

무중(mujoong)은 6~12세 아이의 화용(대화 상황에서 말을 쓰는) 능력을 돕는 AI 음성 대화 서비스다. 아이는 기기(`/device` 웹)로 AI와 음성 대화를 하고, 보호자는 `/parent` 웹으로 아이 등록 · 동의 · 활동 시작 · 라이브 확인 · 결과 · 리포트를 본다.

## 주요 기능

| 영역 | 내용 | 주 담당 |
|---|---|---|
| 계정 · 아이 · 동의 · 기기 | 보호자 로그인, 아이 등록, 동의, 기기 연결 | core-api |
| 활동 시작 · 라이브 | 자유 대화와 연습 흐름 시작, 진행 중 조회, 종료 | core-api가 요청, realtime-activity가 실행 |
| 실시간 세션 | 음성 입출력, 출력 게이트, 가드레일, 판정 턴, 연습 흐름 | realtime-activity |
| 결과 · 리포트 · 알림 | 활동 결과 정리, 리포트, 푸시 알림 | core-api |
| 지식 · luna | 아이별 지식 그래프, 상황 생성 | core-api (luna는 양쪽 앱에 각자) |

기능별 코드 위치는 `component-inventory.md`, 패키지 구성은 `code-structure.md`에 있다.

## 이번 의도의 맥락

이번 의도(`261018-structure-refactor`, scope `refactor`)는 동작 변경 없이 구조 · 이름을 정리한다. 핵심 문제는 다음 둘이다(수치와 근거는 `code-structure.md` · `code-quality-assessment.md`).

- Realtime 실행기 `SessionExecutor.java`(4,361줄)에 책임이 몰려 있다.
- 프런트 이름이 화면 설계 번호(`Live12`, `Report30`)와 유닛 번호(`u5bCopy.ts`)에 묶여 현재 기능을 바로 알기 어렵다.

## 불변 조건(업무 관점)

- 시험 · CI · 검증은 가짜 공급자만 쓴다(R-F1). 실공급자 성공과 대역 성공을 구분해 보고한다(R-M11).
- 오류 코드 · 설정 키 · 시간 값 계약(`contracts/`)은 이름과 무관하게 유지한다(R-M5, R-M6, R-M10).
