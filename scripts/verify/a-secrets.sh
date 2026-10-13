#!/usr/bin/env bash
# 무중 비밀 스캔 — 검증 명령 A4 · 사후 P3 (U1 3.5 Step 49 · J144 · SEC-10 · NFR9.10 · SD-12 · CD-1)
#
# 쓰는 법: bash scripts/verify/a-secrets.sh [--root <git 작업 트리>] [--values <값 목록 파일>]
#   (기본) ① 비밀 키 형식 스캔: 대상은 `git ls-files -co --exclude-standard`(추적 + 무시되지 않은 미추적 파일).
#          gitleaks가 있으면 저장소 이력도 gitleaks로 본다(.gitleaks.toml이 있으면 그 설정). gitleaks가 있든 없든
#          scripts/verify/secret-patterns.txt의 키 형식으로 작업 트리 파일을 grep한다(패턴 파일 자신은 뺀다).
#          시험의 가짜 값임을 밝힌 줄은 gitleaks와 같은 줄 끝 표지 `gitleaks:allow`로 뺀다(③ 값 대조에는 예외가 없다).
#        ② 무시 규칙: .env · .env.local · build/verify/env/x.env · build/verify/certs/x.pem · infra/x.key가 git에서 무시되는가
#   --values <파일>  ③ 값 대조: 목록의 값(한 줄에 하나, 실행 중 생성 · 관찰한 비밀)이 대상 파일에 0건인가.
#                    목록 파일은 0600이어야 하고 빈 줄은 무시한다(grep -F에서 빈 줄은 모든 줄과 맞는다).
#   --exclude-values <파일>  ③에서 뺄 값(줄 전체 일치). 가짜 공급자의 합성 대본 문장(tools/fake-provider/scenarios.mjs의
#                    TURN_SCRIPTS)처럼 저장소에 원래 있는 시험 문장만 넣는다. 로그 위생(P2)에서는 빼지 않는다.
#   --root <디렉터리> 다른 작업 트리를 스캔한다(infra/ci/test-gate-negatives.sh의 음성 대조).
# 출력: 걸린 파일 경로와 건수만. 걸린 문자열 · 값은 출력하지 않는다.
# 종료 코드: 0 깨끗함, 1 걸림, 2 전제 누락(git 작업 트리 · 목록 파일), 64 인자 오류

set -Eeuo pipefail
# shellcheck source=scripts/verify/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

root="$VERIFY_ROOT"
values=""
exclude_values=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --root) root="${2:-}"; shift ;;
    --values) values="${2:-}"; shift ;;
    --exclude-values) exclude_values="${2:-}"; shift ;;
    -h | --help) sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "a-secrets: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done

readonly PATTERN_FILE="$VERIFY_ROOT/scripts/verify/secret-patterns.txt"
if [ -n "$values" ]; then
  case "$values" in /*) ;; *) values="$PWD/$values" ;; esac
fi
if [ -n "$exclude_values" ]; then
  case "$exclude_values" in /*) ;; *) exclude_values="$PWD/$exclude_values" ;; esac
fi
root="$(cd "$root" 2>/dev/null && pwd)" || die_prereq "--root 디렉터리가 없다"
cd "$root"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die_prereq "$root는 git 작업 트리가 아니다"
[ -r "$PATTERN_FILE" ] || die_prereq "패턴 파일이 없다: scripts/verify/secret-patterns.txt"

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# 대상 파일 목록(NUL 구분). 패턴 파일 자신과 존재하지 않는(지운) 파일 · 링크는 뺀다
pattern_rel=""
case "$PATTERN_FILE" in "$root"/*) pattern_rel="${PATTERN_FILE#"$root"/}" ;; esac
git ls-files -z -co --exclude-standard | while IFS= read -r -d '' f; do
  [ "$f" = "$pattern_rel" ] && continue
  if [ ! -f "$f" ] || [ -L "$f" ]; then continue; fi
  printf '%s\0' "$f"
done >"$work/files"
file_count="$(tr -cd '\0' <"$work/files" | wc -c)"

# grep_files <grep 옵션...> — 대상 파일에서 걸린 파일 이름만. 바이너리는 건너뛴다(-I)
grep_files() {
  xargs -0 -r grep -I -l "$@" -- <"$work/files" || true
}

# grep_pattern_files <패턴 파일> — 키 형식이 걸린 파일 이름만. gitleaks와 같은 줄 단위 예외 표지 `gitleaks:allow`가 있는
# 줄(시험의 가짜 값임을 밝힌 줄)은 세지 않는다. 걸린 줄 내용은 파이프 안에서만 쓰고 출력하지 않는다
grep_pattern_files() {
  { xargs -0 -r grep -I -H -Z -E -f "$1" -- <"$work/files" || true; } >"$work/matches"
  { grep -a -v -F 'gitleaks:allow' "$work/matches" || true; } | sed 's/\x0.*//' | LC_ALL=C sort -u
  rm -f "$work/matches"
}

if [ -n "$values" ]; then
  # ③ 값 대조
  [ -f "$values" ] || die_prereq "값 목록 파일이 없다: $values"
  mode_bits="$(stat -c '%a' "$values")"
  [ "$mode_bits" = 600 ] || die_assert P3.mode "값 목록 파일 권한이 600이 아니다($mode_bits)"
  grep -v -x '' "$values" >"$work/values.all" || true
  excluded=0
  if [ -n "$exclude_values" ]; then
    [ -f "$exclude_values" ] || die_prereq "뺄 값 목록 파일이 없다: $exclude_values"
    grep -v -x '' "$exclude_values" >"$work/exclude" || true
    { grep -v -x -F -f "$work/exclude" "$work/values.all" || true; } >"$work/values"
    excluded=$(($(wc -l <"$work/values.all") - $(wc -l <"$work/values")))
  else
    cp "$work/values.all" "$work/values"
  fi
  value_count="$(wc -l <"$work/values")"
  if [ "$value_count" -eq 0 ]; then
    die_assert P3.values "값 목록이 비었다(빈 목록으로 통과하지 않는다)"
  fi
  grep_files -F -f "$work/values" >"$work/hits"
  hits="$(wc -l <"$work/hits")"
  if [ "$hits" -ne 0 ]; then
    fail P3.values "비밀 값이 든 파일 ${hits}개: $(head -n 10 "$work/hits" | paste -sd' ' -)"
  else
    pass P3.values "값 ${value_count}개 · 파일 ${file_count}개 대조, 걸림 0(합성 대본 문장 ${excluded}개 제외)"
  fi
  finish_asserts
  exit 0
fi

# ① 키 형식 — 주석 · 빈 줄을 뺀 패턴
grep -v -E '^\s*(#|$)' "$PATTERN_FILE" >"$work/patterns"
grep_pattern_files "$work/patterns" >"$work/hits"
hits="$(wc -l <"$work/hits")"
if [ "$hits" -ne 0 ]; then
  fail A4.patterns "키 형식 문자열이 든 파일 ${hits}개: $(head -n 10 "$work/hits" | paste -sd' ' -)"
else
  pass A4.patterns "키 형식 $(wc -l <"$work/patterns")종 · 파일 ${file_count}개, 걸림 0"
fi

if have gitleaks; then
  gl_args=(detect --source "$root" --redact --no-banner --exit-code 1 --report-path "$work/gitleaks.json")
  [ -f "$root/.gitleaks.toml" ] && gl_args+=(--config "$root/.gitleaks.toml")
  if gitleaks "${gl_args[@]}" >"$work/gitleaks.log" 2>&1; then
    pass A4.gitleaks "gitleaks 이력 스캔 걸림 0"
  else
    fail A4.gitleaks "gitleaks가 비밀을 찾았거나 실패했다($(jq -r 'length' "$work/gitleaks.json" 2>/dev/null || echo '?')건, 값은 출력하지 않음)"
  fi
else
  note A4.gitleaks "gitleaks가 없어 이력 스캔은 CI secrets 작업 몫이다(작업 트리는 키 형식 grep으로 봤다)"
fi

# ② 무시 규칙
not_ignored=()
for p in .env .env.local build/verify/env/x.env build/verify/certs/x.pem infra/x.key frontend/.env.local; do
  git check-ignore -q --no-index -- "$p" || not_ignored+=("$p")
done
if [ "${#not_ignored[@]}" -gt 0 ]; then
  fail A4.ignore "무시되지 않는 비밀 경로: ${not_ignored[*]}"
else
  pass A4.ignore ".env* · build/verify/ · *.pem · *.key 무시 규칙"
fi

finish_asserts
