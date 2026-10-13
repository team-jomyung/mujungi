#!/usr/bin/env bash
# 무중 소스 지문 (U1 3.5 Step 43 · J134 (가) · 사람 결정 10 · SEC-09)
#
# 이미지 · 미리 만든 산출물이 "지금 저장소 내용"으로 만들어졌는지 가리는 지문을 한 줄로 낸다.
#   - 출력: sha256:<64자 소문자 16진수> (표준 출력 한 줄)
#   - 범위: 실행 단위 디렉터리 backend · frontend · infra · tools · contracts · scripts 의
#           추적 파일 + 무시되지 않은 미추적 파일(git ls-files --cached --others --exclude-standard).
#           aidlc/ · .claude/ · docs/ · README 같은 기록 · 문서 경로는 넣지 않는다. 엔진 체크포인트가 감사 기록을
#           쓰거나 문서만 커밋해도 산출물이 같으면 지문이 바뀌지 않게 하려는 것이다(J134 보완, code-summary J 후보).
#   - 내용: 파일마다 "<실행 비트> <git blob 해시> <경로>" 줄을 경로 순으로 이어 sha256을 낸다. 지워진 추적 파일은
#           "D - <경로>", 심볼릭 링크는 "L <링크 대상의 sha256> <경로>"로 넣는다. 커밋 id(HEAD)는 넣지 않으므로
#           내용이 같으면 커밋이 달라도 같은 지문이다. 커밋 id는 이미지 라벨 org.opencontainers.image.revision에 따로 둔다.
#   - 비밀: 무시되는 파일(.env* · build/ · 인증서)은 범위에 들어오지 않고, 출력은 해시뿐이다.
#
# 쓰는 곳: infra/ci/build-images.sh(이미지 라벨 kr.mujoong.source-fingerprint), scripts/verify.sh --engine(지문 대조, Step 49).
# 종료 코드: 0 성공, 2 전제 누락(git 작업 트리 아님), 1 그 밖의 오류(줄바꿈이 든 경로 등).
#
# 쓰는 법: bash infra/ci/source-fingerprint.sh [--manifest]
#   --manifest  지문 대신 파일별 줄 목록을 낸다(어느 파일이 지문을 바꿨는지 찾을 때, 값 없이 해시만 나온다)

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT
readonly SCOPE=(backend frontend infra tools contracts scripts)

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

mode=fingerprint
while [ "$#" -gt 0 ]; do
  case "$1" in
    --manifest) mode=manifest ;;
    -h | --help) usage; exit 0 ;;
    *) echo "source-fingerprint: 모르는 인자: $1" >&2; exit 1 ;;
  esac
  shift
done

if ! git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "source-fingerprint: $ROOT 는 git 작업 트리가 아니다" >&2
  exit 2
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cd "$ROOT"
# 경로 목록(NUL 구분 → 정렬 · 중복 제거). 줄바꿈이 든 경로는 blob 해시 일괄 계산에 쓸 수 없으므로 거부한다.
git ls-files -z --cached --others --exclude-standard -- "${SCOPE[@]}" | LC_ALL=C sort -zu >"$tmp/paths.z"
if [ "$(tr -cd '\n' <"$tmp/paths.z" | wc -c)" -ne 0 ]; then
  echo "source-fingerprint: 줄바꿈이 든 경로가 있어 지문을 만들 수 없다" >&2
  exit 1
fi

: >"$tmp/regular"
: >"$tmp/lines"
while IFS= read -r -d '' path; do
  if [ -L "$path" ]; then
    target_hash="$(readlink -- "$path" | sha256sum | cut -d' ' -f1)"
    printf 'L %s %s\n' "$target_hash" "$path" >>"$tmp/lines"
  elif [ -f "$path" ]; then
    printf '%s\n' "$path" >>"$tmp/regular"
  else
    printf 'D - %s\n' "$path" >>"$tmp/lines"
  fi
done <"$tmp/paths.z"

if [ -s "$tmp/regular" ]; then
  # blob 해시를 한 번에 계산한다(경로 순서와 출력 순서가 같다)
  git hash-object --stdin-paths <"$tmp/regular" >"$tmp/hashes"
  while IFS= read -r path && IFS= read -r hash <&3; do
    if [ -x "$path" ]; then bit=x; else bit=-; fi
    printf '%s %s %s\n' "$bit" "$hash" "$path"
  done <"$tmp/regular" 3<"$tmp/hashes" >>"$tmp/lines"
fi

LC_ALL=C sort -k3 "$tmp/lines" >"$tmp/manifest"

if [ "$mode" = manifest ]; then
  cat "$tmp/manifest"
else
  printf 'sha256:%s\n' "$(sha256sum <"$tmp/manifest" | cut -d' ' -f1)"
fi
