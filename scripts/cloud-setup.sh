#!/usr/bin/env bash
# Claude Code 클라우드 세션(Linux 샌드박스)에서 AI-DLC 명령을 설치한다.
# 이 레포는 .claude/ 하네스 파일이 이미 커밋돼 있으므로 설치만 하면 /aidlc 가 저장된 단계에서 이어진다.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

echo "[1/3] AI-DLC 설치 (README의 macOS/Linux 명령 그대로)"
curl -fsSL https://github.com/awslabs/aidlc-workflows/releases/latest/download/install.sh | sh

hash -r 2>/dev/null || true
for d in "$HOME/.local/bin" "$HOME/.aidlc/bin" "$HOME/.local/share/aidlc/bin" "/usr/local/bin"; do
  [ -x "$d/aidlc" ] && export PATH="$d:$PATH"
done
command -v aidlc >/dev/null || { echo "aidlc 명령을 못 찾았습니다. 설치 출력의 PATH 안내대로 export 한 뒤 다시 실행하세요."; exit 1; }
aidlc --version

# Claude Code 훅은 로그인 셸이 아니어서 ~/.local/bin 이 PATH에 없을 수 있다. 가능하면 /usr/local/bin 에 링크를 둔다.
if [ ! -e /usr/local/bin/aidlc ] && [ -w /usr/local/bin ]; then
  ln -s "$(command -v aidlc)" /usr/local/bin/aidlc && echo "/usr/local/bin/aidlc 링크 생성"
fi

echo "[2/3] 하네스 확인"
if [ -f .claude/tools/data/stage-graph.json ]; then
  echo "레포에 .claude/ 하네스가 이미 있어 config 를 건너뜁니다."
else
  aidlc config --harness claude
fi

echo "[3/3] 점검"
aidlc doctor || true
aidlc engine status || true

cat <<'MSG'

끝. Claude Code를 완전히 재시작한 뒤 /aidlc 를 입력하면 위 status 의 단계부터 이어집니다.
게이트에서는 "Approve" 또는 고칠 내용을 적으면 됩니다. 문서끼리 다르면 화면 설계 v1.5j가 기준입니다.
MSG
