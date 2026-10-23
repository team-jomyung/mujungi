# 합성 음성 전체 경로 시험 — 실행 환경 사전 확인

확인 시각: 2026-10-20 05:14 UTC. 이 문서는 환경 확인이며 실제 음성 입력·AI 호출·부모 화면·재생 시험 결과가 아니다.

| 항목 | 확인 결과 | 의미 |
|---|---|---|
| Node | `/usr/bin/node` 존재 | 기존 Node 도구 실행 기반이 있음 |
| 브라우저 | 캐시의 Chromium headless shell 실행 결과 `Google Chrome for Testing 153.0.8010.12` | 브라우저 프로세스를 실행할 수 있음. 가상 마이크·오디오 재생 동작은 아직 미확인 |
| Node 브라우저 자동화 라이브러리 | 기본 모듈 탐색에서 playwright·playwright-core·@playwright/test·puppeteer를 찾지 못함 | 자동화 라이브러리 설치 또는 기존 WebSocket 기반 CDP 연결 검토 필요 |
| 한국어 말소리 생성 도구 | PATH의 espeak·espeak-ng·piper·edge-tts 없음. Python의 edge_tts·pyttsx3 모듈 없음 | 합성 말소리 입력 자료를 준비할 별도 방법 필요 |
| 음성 변환 도구 | PATH의 ffmpeg 없음 | 생성 WAV 형식을 맞추는 변환 방법도 준비해야 함 |
| 기존 합성 입력 자료 | build 및 realtime build의 한정된 gpt-live·synthetic·speech·tts·input PCM 경로에서 자료를 찾지 못함 | 이전 합성 자료를 재사용할 수 있다고 가정하지 않음. 저장소의 안내 톤과 말소리는 구분해야 함 |
| 작업 기준 | `ops/synthetic-voice-e2e`, 생성 당시 main과 같은 커밋 | 기존 작업의 변경을 보존하고 별도 브랜치에서 조사 |
| 로컬 화면 응답 | 기존 로컬 HTTPS의 `/device`, `/parent/dev-login` 모두 HTTP 200, HTML 제목 `무중` | 화면 문서는 제공됨. 로그인·마이크·WebSocket·DOM 갱신·재생 성공을 뜻하지 않음 |

아직 도구 설치·실제 공급자 요청·실행 중 스택 재기동·설정 변경을 수행하지 않았다. 브라우저 자동 재생 성공과 물리 스피커의 청취 성공은 별도 결과로 기록한다.
