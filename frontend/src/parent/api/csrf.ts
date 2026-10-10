import type { CsrfToken } from "./types";

// CSRF 토큰 보관 (U1 3.5 Step 39 · SD-6 · BR8.8 · 편차 (u) · security-design 2.5)
// 토큰은 GET /api/session/csrf로 받아 JS 메모리에만 둔다(localStorage · sessionStorage · 쿠키에 두지 않는다).
// 앱 시작 · 로그인 직후에 받고, 변경 요청 전에 없으면 그 앞에서 한 번 받는다. 403 CSRF_TOKEN_INVALID면 apiClient가
// refresh()로 한 번만 다시 받는다. 토큰 값은 로그에 쓰지 않는다.

export interface CsrfStore {
  /** 있으면 그대로, 없으면 받아 온다(동시에 부르면 한 번만 요청한다). */
  ensure(): Promise<CsrfToken>;
  /** 버리고 새로 받는다. */
  refresh(): Promise<CsrfToken>;
  /** 버린다(로그인 · 로그아웃으로 세션 ID가 바뀔 때). */
  clear(): void;
  /** 지금 가진 토큰(없으면 null). */
  peek(): CsrfToken | null;
}

export function createCsrfStore(fetchToken: () => Promise<CsrfToken>): CsrfStore {
  let token: CsrfToken | null = null;
  let inFlight: Promise<CsrfToken> | null = null;
  let generation = 0;

  const load = (): Promise<CsrfToken> => {
    if (inFlight !== null) {
      return inFlight;
    }
    const loadGeneration = generation;
    const request: Promise<CsrfToken> = fetchToken()
      .then((received) => {
        // 받는 사이에 clear()가 불렸으면 옛 세션의 토큰이라 보관하지 않는다.
        if (loadGeneration === generation) {
          token = received;
        }
        return received;
      })
      .finally(() => {
        if (inFlight === request) {
          inFlight = null;
        }
      });
    inFlight = request;
    return request;
  };

  return {
    ensure() {
      return token !== null ? Promise.resolve(token) : load();
    },
    refresh() {
      token = null;
      return load();
    },
    clear() {
      token = null;
      inFlight = null;
      generation += 1;
    },
    peek() {
      return token;
    },
  };
}
