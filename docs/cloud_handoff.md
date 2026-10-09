# 클라우드 세션 인수인계 (2026-10-09)

## 목표
AI-DLC 워크플로를 현재 단계(2.6 Domain Design, Running)부터 **3.4 Infrastructure Design 승인까지** 진행한다.
3.5 Code Generation은 시작하지 않는다(사람 확인 필요). 끝나면 보고를 `docs/cloud_run_report.md`에 쓰고 커밋·푸시한다.

## 0. 준비
1. `bash scripts/cloud-setup.sh` (aidlc 설치 + 커서 복원 + status)
2. `aidlc engine status`에서 `Current Stage: Domain Design (2.6)`, `Status: Running`을 확인한다.
3. `/aidlc` 스킬(.claude/skills/aidlc/SKILL.md)을 호출해 저장된 상태에서 이어간다. 훅이 등록돼 있지 않으면 스킬이 지시하는 엔진 명령(`aidlc engine …`, `aidlc-orchestrate …`)을 직접 실행한다.

## 1. 역할
너는 팀장(준하)의 대리다. 게이트마다 산출물을 화면 설계 v1.5j(`aidlc/spaces/default/knowledge/documents/10_…`)와 대조하고, 어긋나는 곳이 있으면 "Request Changes"로 한 번만 고친 뒤 "Approve" 한다.
사람에게 묻고 멈추지 않는다. 질문 파일(Edit File 방식)이 생기면 아래 규칙과 문서로 `[Answer]:`를 직접 채우고 진행한다.
스스로 판단한 것은 전부 산출물의 가정 표에 **"준하 제안(가정)"**으로 번호를 붙여 남긴다. 뒤에 팀이 뒤집을 수 있어야 한다.

## 2. 중재 규칙 (우선순위 순)
1. 화면 설계 v1.5j > requirements.md·stories.md·mockups.md(이미 승인된 산출물) > 요구사항 v1.1(20_) > ERD v0.1(40_)·ADR_2S(50_) > WBS(60_). 다르면 앞쪽이 맞다(80_점검표 2절).
2. 계정 = 아이 = 기기 1:1(D-24). 기기 등록은 8자리 코드(05·SET-01). 기기 상태 5단계와 시간값은 requirements NFR6 그대로(참여 대기 20+20초, 기기 응답 없음 20초, 공백 4분/6분, 끊김 40초, 마무리 확인 30초).
3. 결과·목표 소유는 **Core로 통일**(준하 제안): core_goal_recommendation·core_practice_goal_selection·core_scenario·core_activity_context·core_support_item·결과 문서·리포트·사후 텍스트 AI 호출은 Core(ERD v0.1, ADR-002 Backend 표). Realtime은 진행 중 세션·기기 WSS·GPT-Live 중계(B안)·제브 판정 프로토콜·세션–목표 스냅숏(realtime_session_goal)만 갖고, 세션이 끝나면 Outbox→Inbox로 결과를 Core에 넘긴다. 목표 정의(behavior) 카탈로그는 Core 참조 테이블에 version 열, Realtime은 세션 시작 때 key+version으로 스냅숏. ResultJob은 Core 하나(REQUESTED→RUNNING→DONE/FAILED, 서버 재시작 시 FAILED). 17 표시는 세션 상태 × 결과 작업 상태 표 그대로.
4. 연습은 2안 + 제브 판정(D-05): 상황 생성(재생성 1회) → 첫 반응(Cue 금지) → 도움 동의 → Cue(GPT-Live 실시간, 5초 예산, 정답 직접 제시 금지) → 도움 후 반응 1회 → 새 상황 → 판정. 연습 목표는 여러 개(12 머리 n/N). 12 말풍선은 승인된 AI 말만.
5. 리포트는 AI 없이 원문 PDF, "이 문서는 진단 결과가 아닙니다" 문장 포함. 생활 팁 없음. 판정값은 보호자 화면에 보이지 않는다.
6. 웹 푸시 3종은 Should. 계정 삭제는 접수형. 생성형 AI 고지는 02-1 약관 본문.
7. 배포는 EC2 1대 · Docker Compose · Nginx · HTTPS(ADR-006). Next.js 한 앱 두 접점(/parent·/device), Spring 둘(Core·Realtime), 저장소 구성은 ADR_2S 그대로. 보호자 화면 기준은 Android Chrome, 아이 기기는 데스크톱 Chrome.
8. 깊이 Standard, 테스트 전략 Standard, 리뷰·요약 설정은 기록된 대로 유지. 모델은 세션 기본값을 바꾸지 않는다.
9. 한국어로 쓴다(코드 식별자·고정 토큰 제외). Mermaid는 텍스트 대체를 함께 둔다.

## 3. 하지 말 것
- 3.5 Code Generation 시작 금지. 3.4 Infrastructure Design 승인에서 멈춘다.
- `aidlc/spaces/default/knowledge/documents/` 원본 수정 금지. `docs/aidlc_drafts/`는 참고만.
- 계정·비밀번호·API 키 같은 비밀값을 어떤 파일에도 쓰지 않는다.
- 사람에게 질문하고 멈추지 않는다(판단 → 가정 기록 → 진행).

## 4. 기록
- 세션에 허용된 브랜치가 따로 있으면(예: `claude/…`) 그 브랜치에 커밋·푸시하고 main으로 가는 draft PR을 하나 열어 둔다. 게이트를 하나 넘길 때마다 `git add aidlc docs && git commit -m "docs: <단계> 승인" && git push`. 허용 브랜치 제한이 없으면 main에 바로 푸시한다.
- 3.4 승인 뒤 `docs/cloud_run_report.md`에 적는다: 단계별 산출물 경로, 준하 제안(가정) 전체 목록, 열린 질문, 3.5 전에 사람이 정할 것, 사용량(`aidlc engine runtime summary --json`). 커밋·푸시로 끝낸다.

## 5. 설치가 막힐 때
- 클라우드 컨테이너는 root로 돌아가고 공식 설치 스크립트는 root 설치를 거부한다. `scripts/cloud-setup.sh`가 root일 때는 다른 길(릴리스 바이너리 직접 배치 → 비root 사용자로 설치 → bun 으로 엔진 소스 실행)을 차례로 시도한다. 스크립트를 다시 돌리면 된다. 설치 스크립트 본문을 고치지 않는다.
