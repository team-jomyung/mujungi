#!/usr/bin/env bash
# Claude Code 클라우드 세션(Linux 샌드박스)에서 AI-DLC 명령을 설치하고 이어서 돌릴 준비를 한다.
# .claude/ 하네스와 aidlc/ 기록은 이미 커밋돼 있다. 커밋되지 않는 것은 사용자 커서 파일 둘뿐이라 여기서 복원한다.
# 공식 설치 스크립트는 root 설치를 거부한다(컨테이너는 root). 그래서 root일 때는 설치 스크립트가 하는 일을
# 같은 순서로 직접 한다: 릴리스 파일 내려받기 → 체크섬 확인 → 바이너리의 install-apply 실행. 엔진 자체는 root를 막지 않는다.
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"
cd "$REPO"
BASE="https://github.com/awslabs/aidlc-workflows/releases/latest/download"

install_direct() {
  local arch target tmp ver bin a expected actual
  case "$(uname -m)" in x86_64|amd64) arch=x64 ;; arm64|aarch64) arch=arm64 ;; *) return 1 ;; esac
  target="linux-$arch"
  if { command -v ldd >/dev/null 2>&1 && ldd --version 2>&1 | grep -qi musl; } || ls /lib/ld-musl-*.so.1 >/dev/null 2>&1; then
    target="$target-musl"
  fi
  tmp="$(mktemp -d)"
  for a in version.json checksums.txt aidlc-release.intoto.jsonl; do
    curl -fsSL "$BASE/$a" -o "$tmp/$a" || return 1
  done
  ver="$(sed -n 's/.*"version":[[:space:]]*"\([0-9][0-9A-Za-z.-]*\)".*/\1/p' "$tmp/version.json" | head -n1)"
  [ -n "$ver" ] || return 1
  bin="aidlc-$target"
  for a in "$bin" "aidlc-runtime-$ver.tar.gz"; do
    curl -fsSL "$BASE/$a" -o "$tmp/$a" || return 1
    expected="$(sed -n "s/^\([a-f0-9]\{64\}\)  $a\$/\1/p" "$tmp/checksums.txt")"
    actual="$(sha256sum "$tmp/$a" | awk '{print $1}')"
    if [ -z "$expected" ] || [ "$actual" != "$expected" ]; then echo "체크섬 불일치: $a"; return 1; fi
  done
  chmod 755 "$tmp/$bin"
  mkdir -p /usr/local/bin
  AIDLC_BIN_DIR=/usr/local/bin "$tmp/$bin" system lifecycle install-apply --from "$tmp" --version "$ver" --quiet || return 1
  rm -rf "$tmp"
  echo "릴리스 바이너리 $ver 를 /usr/local/bin/aidlc 로 설치"
}

install_via_user() {
  command -v useradd >/dev/null 2>&1 || return 1
  id aidlc >/dev/null 2>&1 || useradd -m -s /bin/bash aidlc || return 1
  su - aidlc -c "curl -fsSL $BASE/install.sh | sh" || return 1
  ln -sf /home/aidlc/.local/bin/aidlc /usr/local/bin/aidlc
  echo "비root 사용자(aidlc)로 설치하고 /usr/local/bin/aidlc 에 링크"
}

install_bun_wrapper() {
  if ! command -v bun >/dev/null 2>&1; then
    curl -fsSL https://bun.sh/install | bash >/dev/null 2>&1 || return 1
    export PATH="$HOME/.bun/bin:$PATH"
  fi
  printf '#!/bin/sh\nexec "%s" "%s/.claude/tools/aidlc.ts" "$@"\n' "$(command -v bun)" "$REPO" > /usr/local/bin/aidlc
  chmod 755 /usr/local/bin/aidlc
  echo "bun 으로 레포 안의 엔진 소스를 실행하는 /usr/local/bin/aidlc 래퍼 생성"
}

echo "[1/4] AI-DLC 설치"
if ! command -v aidlc >/dev/null 2>&1; then
  if [ "$(id -u)" -eq 0 ]; then
    install_direct || install_via_user || install_bun_wrapper || { echo "설치 실패: 세 가지 길이 모두 막혔습니다."; exit 1; }
  else
    curl -fsSL "$BASE/install.sh" | sh
  fi
fi
hash -r 2>/dev/null || true
for d in /usr/local/bin "$HOME/.local/bin" "$HOME/.aidlc/bin" "$HOME/.local/share/aidlc/bin"; do
  [ -x "$d/aidlc" ] && export PATH="$d:$PATH"
done
command -v aidlc >/dev/null || { echo "aidlc 명령을 못 찾았습니다."; exit 1; }
aidlc --version
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
