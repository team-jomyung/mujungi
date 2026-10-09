#!/usr/bin/env bash
# Claude Code 클라우드 세션(Linux 샌드박스)에서 AI-DLC 명령을 설치하고 이어서 돌릴 준비를 한다.
# .claude/ 하네스와 aidlc/ 기록은 이미 커밋돼 있다. 커밋되지 않는 것은 사용자 커서 파일 둘뿐이라 여기서 복원한다.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

echo "[1/4] AI-DLC 설치 (README의 macOS/Linux 명령 그대로)"
if ! command -v aidlc >/dev/null 2>&1; then
  curl -fsSL https://github.com/awslabs/aidlc-workflows/releases/latest/download/install.sh | sh
fi
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

echo "[2/4] 사용자 커서 복원 (gitignore 대상)"
[ -f aidlc/active-space ] || printf 'default\n' > aidlc/active-space
[ -f aidlc/spaces/default/intents/active-intent ] || printf '261008-mujoong-service\n' > aidlc/spaces/default/intents/active-intent

echo "[3/4] 하네스 확인"
if [ -f .claude/tools/data/stage-graph.json ]; then
  echo "레포에 .claude/ 하네스가 이미 있어 config 를 건너뜁니다."
else
  aidlc config --harness claude
fi

echo "[4/4] 점검"
aidlc doctor || true
aidlc engine status || true

cat <<'MSG'

끝. 위 status 의 단계부터 이어집니다. 절차와 중재 규칙은 docs/cloud_handoff.md 에 있습니다.
MSG
