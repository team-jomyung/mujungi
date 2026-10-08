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
