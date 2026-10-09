# mujoong-aidlc

무중(6~12세 아이 화용 능력 지원 AI 음성 대화 서비스)을 AI-DLC(awslabs/aidlc-workflows 2.0)로 돌려 보는 저장소. 기준 문서는 화면 설계 v1.5j(2026-10-08).

- `docs/project-description.md` : `/aidlc` 시작용 설명 한 장
- `docs/aidlc_drafts/` : AI-DLC 산출물 이름에 맞춘 초안(Edit File 모드에서 붙여넣기용)
- `aidlc/spaces/default/knowledge/documents/` : 참고 문서 8묶음(화면 설계 · 개발 명세 · 요구사항 v1.1 · 기능명세 · ADR_2S · ERD v0.1 · WBS v0.5 · 점검표). 읽는 순서는 그 안의 00_읽는순서.md

## 시작
```
aidlc doctor
```
Claude Code를 이 폴더에서 열고:
```
/aidlc knowledge onboard aidlc/spaces/default/knowledge/documents
/aidlc Read ./docs/project-description.md and build what it describes
```
스코프는 `classic`, 모드는 `Edit File`. 문서끼리 다르면 화면 설계가 맞다(개발전환_점검표 2절).

## 클라우드 세션(claude.ai/code)에서 이어가기
클라우드 세션은 플랜 한도보다 클라우드 세션 크레딧을 먼저 쓴다. 작업 기록은 전부 git에 있으므로 어느 기계에서든 이어진다.
1. claude.ai/code 에서 이 GitHub 레포(baikjunha/mujoong-aidlc)로 세션을 연다.
2. 세션 안에서 `bash scripts/cloud-setup.sh` 를 실행한다(AI-DLC 설치 → `aidlc config --harness claude` → `aidlc doctor`).
3. Claude Code를 재시작하고 `/aidlc` 를 입력하면 저장된 단계에서 이어진다. 게이트마다 "Approve" 또는 고칠 내용을 적는다.
4. 끝나면 `git add aidlc/ && git commit && git push` 로 기록을 올린다. 로컬에서 이어 받을 때는 먼저 `git pull`.
한 시점에 한 곳(로컬 또는 클라우드)에서만 돌려야 기록이 갈라지지 않는다.

## Windows + Git Bash 주의
Claude Code 훅은 bash로 실행되는데 설치된 명령은 `aidlc.cmd`라 bash가 못 찾는다(`aidlc: command not found`로 훅이 전부 실패하고 `/aidlc`가 제대로 돌지 않는다). `%LOCALAPPDATA%\aidlc\bin\` 안에 확장자 없는 `aidlc` 셸 스크립트를 하나 두면 된다.

```sh
#!/bin/sh
exec powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "C:\Users\<사용자>\AppData\Local\aidlc\aidlc-shim.ps1" "$@"
```

`cmd.exe //c ...` 로 감싸는 방법은 Claude Code 훅 환경(MSYS_NO_PATHCONV=1)에서 `//c`가 변환되지 않아 cmd 배너만 찍고 끝나므로 쓰지 않는다. 래퍼를 둔 뒤 Claude Code를 완전히 다시 시작한다(`/clear`로는 안 된다). Git Bash에서 `claude -p "/aidlc ..."`처럼 슬래시 명령을 넘길 때는 `MSYS_NO_PATHCONV=1`을 붙여야 `/aidlc`가 경로로 바뀌지 않는다.
