"""로컬 live 스택(dev 프로필)에서 연습 시작을 직접 시험한다. 개발 로그인(/api/dev/login)으로 시드 보호자 세션을 열고 실제 API만 부른다.

사용(저장소 루트에서, live 스택이 https://localhost:9443 에 떠 있어야 한다):
  python3 -I tools/diagnostics/practice-start-trial.py goals                      # 저장된 연습 목표 목록
  python3 -I tools/diagnostics/practice-start-trial.py start "<목표 이름>" 5       # 연습 시작 5번(성공하면 곧 세션 종료)
  python3 -I tools/diagnostics/practice-start-trial.py end                        # 남아 있는 진행 중 세션 종료
  python3 -I tools/diagnostics/practice-start-trial.py retry-result <sessionId>   # 실패(FAILED) 상태인 결과 작업 재시도

주의
- 연습 시작은 실제 Luna를 부른다(실호출). 결과 보고 때 테스트 대역과 나눠 적는다(R-M11).
- 기기 화면이 열려 있으면 시작된 세션이 잠깐 기기에 나타났다 끝난다.
- localhost 자체 서명 인증서는 이 PC 시계 차이로 만료 판정이 날 수 있어 localhost에 한해 인증서 검증을 끈다. 다른 주소에는 쓰지 않는다.
- 응답 상태 · 오류 코드 · 걸린 시간만 출력한다. 대본 · 전사 원문은 출력하지 않는다(R-F14).
"""
import http.cookiejar
import json
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "https://localhost:9443"
_ctx = ssl._create_unverified_context()  # localhost 자체 서명 전용
_op = urllib.request.build_opener(urllib.request.HTTPSHandler(context=_ctx),
                                  urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
_csrf = {}


def call(method, path, body=None, timeout=75):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Origin", BASE)
    if _csrf and method != "GET":
        req.add_header(_csrf["h"], _csrf["t"])
    started = time.time()
    try:
        with _op.open(req, timeout=timeout) as res:
            return res.status, json.loads(res.read() or b"null"), time.time() - started
    except urllib.error.HTTPError as err:
        raw = err.read()
        try:
            return err.code, json.loads(raw), time.time() - started
        except ValueError:
            return err.code, None, time.time() - started


def login():
    status, body, _ = call("POST", "/api/dev/login")
    if status != 200:
        sys.exit(f"개발 로그인 실패: {status}")
    _, token, _ = call("GET", "/api/session/csrf")
    _csrf["h"], _csrf["t"] = token["headerName"], token["token"]


def code(body):
    return body.get("code") if isinstance(body, dict) else None


def goals():
    _, body, _ = call("GET", "/api/goal-selections")
    items = body.get("items", body) if isinstance(body, dict) else body
    return [(g.get("goalSelectionId") or g.get("id"), g.get("displayName")) for g in items]


def end(session_id):
    # 세션이 아직 REQUESTED면 종료가 거절되므로 몇 번 다시 시도한다
    for _ in range(10):
        time.sleep(3)
        status, body, _ = call("POST", f"/api/sessions/{session_id}/end", {"requestId": str(uuid.uuid4())})
        if status == 202:
            break
    print(f"   end status={status} code={code(body)}")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    login()
    mode = sys.argv[1]
    if mode == "goals":
        for gid, name in goals():
            print(gid, name)
    elif mode == "start":
        name, tries = sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 1
        gid = next((g for g, n in goals() if n == name), None)
        if gid is None:
            sys.exit(f"목표를 찾지 못했다: {name}")
        for i in range(tries):
            status, body, took = call("POST", "/api/activities/practice",
                                      {"idempotencyKey": str(uuid.uuid4()), "goalSelectionIds": [gid]})
            print(f"try{i + 1} status={status} code={code(body)} t={took:.1f}s")
            if status == 202 and isinstance(body, dict) and body.get("sessionId"):
                end(body["sessionId"])
                time.sleep(10)
    elif mode == "end":
        _, body, _ = call("GET", "/api/sessions/current")
        session = (body or {}).get("session") if isinstance(body, dict) else None
        if session and session.get("sessionId"):
            end(session["sessionId"])
        else:
            print("진행 중 세션 없음")
    elif mode == "retry-result":
        status, body, _ = call("POST", f"/api/sessions/{sys.argv[2]}/result/retry")
        print("retry", status, body)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
