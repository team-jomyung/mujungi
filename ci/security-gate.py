#!/usr/bin/env python3
"""무중 공급망 관문 판정 (U1 3.5 Step 50 · cicd 2.2.2 · 2.13 · NFR9.13 · SD-16).

CI의 backend-deps · frontend-deps · images 작업이 부른다. 표준 라이브러리만 쓴다(러너 · 로컬 python3 -I).

  exceptions  <예외 파일>                예외 목록 형식 검사. 만료된 항목 · 사유나 만료일이 없는 항목이 있으면 실패(종료 코드 1)
  trivyignore <예외 파일>                형식 검사 뒤 만료되지 않은 id를 한 줄씩 출력(trivy --ignorefile 용). 만료 항목이 있으면 실패
  npm-audit   <예외 파일> <audit.json>...  `npm audit --json` 결과에서 high · critical 권고(GHSA) 중 예외에 없는 것이 있으면 실패

예외 파일 형식은 ci/security-exceptions.yml 머리 주석이다(`- id:` 다음 줄들의 `reason:` · `expires: YYYY-MM-DD`).
출력에는 패키지 이름 · 권고 id · 등급만 쓴다. 종료 코드: 0 통과, 1 위반, 2 입력 오류.
"""

import datetime
import json
import re
import sys

SEVERE = {"high", "critical"}


def load_exceptions(path):
    items = []
    current = None
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            match = re.match(r"^\s*-\s*id:\s*(\S+)\s*$", line)
            if match:
                current = {"id": match.group(1).strip("'\"")}
                items.append(current)
                continue
            match = re.match(r"^\s+(package|reason|expires|status):\s*(.+?)\s*$", line)
            if match and current is not None:
                current[match.group(1)] = match.group(2).strip("'\"")
                continue
            if re.match(r"^exceptions:\s*(\[\])?\s*$", line):
                continue
            raise ValueError("알 수 없는 줄: " + line.strip()[:80])
    return items


def check_exceptions(path):
    """(유효 id 집합, 문제 목록)."""
    today = datetime.date.today()
    problems = []
    active = set()
    for item in load_exceptions(path):
        ident = item["id"]
        if not re.match(r"^(GHSA(-[23456789cfghjmpqrvwx]{4}){3}|CVE-\d{4}-\d{4,})$", ident):
            problems.append(ident + ": id 형식(GHSA-xxxx-xxxx-xxxx 또는 CVE-YYYY-N)")
            continue
        if not item.get("reason"):
            problems.append(ident + ": reason 없음")
        expires = item.get("expires", "")
        try:
            expiry = datetime.date.fromisoformat(expires)
        except ValueError:
            problems.append(ident + ": expires가 YYYY-MM-DD가 아님")
            continue
        if expiry < today:
            problems.append(ident + ": 만료됨(" + expires + ")")
            continue
        active.add(ident)
    return active, problems


def advisories(audit):
    """npm audit JSON(v2) → {(id, 패키지, 등급)}. via의 객체만 권고다(문자열은 다른 패키지를 거친 경로)."""
    found = set()
    for name, vuln in (audit.get("vulnerabilities") or {}).items():
        for via in vuln.get("via") or []:
            if not isinstance(via, dict):
                continue
            severity = str(via.get("severity", "")).lower()
            url = str(via.get("url", ""))
            ident = url.rstrip("/").rsplit("/", 1)[-1] if url else str(via.get("source", ""))
            found.add((ident, via.get("name") or name, severity))
    return found


def main(argv):
    if len(argv) < 3 or argv[1] not in ("exceptions", "trivyignore", "npm-audit"):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    command, exceptions_path = argv[1], argv[2]
    try:
        active, problems = check_exceptions(exceptions_path)
    except (OSError, ValueError) as error:
        print("security-gate: 예외 파일을 읽지 못했다: " + str(error), file=sys.stderr)
        return 2
    for problem in problems:
        print("security-gate: 예외 목록 위반: " + problem, file=sys.stderr)
    if problems:
        return 1
    if command == "exceptions":
        print("security-gate: 예외 " + str(len(active)) + "건 유효(" + ", ".join(sorted(active)) + ")")
        return 0
    if command == "trivyignore":
        for ident in sorted(active):
            print(ident)
        return 0
    if len(argv) < 4:
        print("security-gate: npm-audit <예외 파일> <audit.json>...", file=sys.stderr)
        return 2
    violations = []
    allowed = []
    for path in argv[3:]:
        try:
            with open(path, encoding="utf-8") as handle:
                audit = json.load(handle)
        except (OSError, ValueError) as error:
            print("security-gate: npm audit 결과를 읽지 못했다(" + path + "): " + str(error), file=sys.stderr)
            return 2
        if "error" in audit and "vulnerabilities" not in audit:
            print("security-gate: npm audit 실행 오류(" + path + ")", file=sys.stderr)
            return 2
        for ident, package, severity in sorted(advisories(audit)):
            if severity not in SEVERE:
                continue
            (allowed if ident in active else violations).append(path + " " + package + " " + ident + " " + severity)
    for line in allowed:
        print("security-gate: 예외로 허용: " + line)
    for line in violations:
        print("security-gate: 위반: " + line)
    print("security-gate: high 이상 " + str(len(violations) + len(allowed)) + "건, 위반 " + str(len(violations)) + "건")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
