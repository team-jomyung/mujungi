import { isValidElement, type ReactElement } from "react";
import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET as googleStart, dynamic as googleStartDynamic } from "@/app/parent/login/google/route";
import ParentLoginCallbackPage from "@/app/parent/login/callback/page";
import ParentLoginPage from "@/app/parent/login/page";
import ParentConsentPage from "@/app/parent/consent/page";
import ParentChildPage from "@/app/parent/child/page";
import ParentStopExpressionsPage from "@/app/parent/child/stop-expressions/page";
import { authorizeUrl, newState, readGoogleConfig, singleParam, stateMatches } from "@/parent/oauth";
import { ChildScreen } from "@/parent/features/child/ChildScreen";
import { ConsentScreen } from "@/parent/features/consent/ConsentScreen";
import { LoginScreen } from "@/parent/features/login/LoginScreen";
import { LoginCallbackScreen } from "@/parent/features/login/LoginCallbackScreen";
import { StopExpressionsScreen } from "@/parent/features/child/StopExpressionsScreen";

// U2 라우트 · 구글 OAuth 시작 · 콜백 state 시험 (U2 · J21 · U2-J1 · U2-J4)
// 라우트 처리기 · 서버 컴포넌트를 직접 부른다. redirect는 next/navigation이 던지는 신호(digest)로 확인하고,
// 요청 범위 밖에서 쓸 수 없는 connection() · cookies()만 대역으로 바꾼다.

const connectionMock = vi.hoisted(() => vi.fn(async () => undefined));
const cookieJar = vi.hoisted(() => ({ value: undefined as string | undefined }));

vi.mock("next/server", async (importOriginal) => ({
  ...(await importOriginal<typeof import("next/server")>()),
  connection: connectionMock,
}));

vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (name === "MUJOONG_OAUTH_STATE" && cookieJar.value !== undefined ? { name, value: cookieJar.value } : undefined),
  }),
}));

const CLIENT_ID = "u2-test-client.apps.invalid";
const REDIRECT_URI = "https://mujoong.invalid/parent/login/callback";

function elementOf(value: unknown): ReactElement<Record<string, unknown>> {
  if (!isValidElement<Record<string, unknown>>(value)) {
    throw new Error("React 요소가 아니다");
  }
  return value;
}

async function digestOf(run: () => Promise<unknown>): Promise<string | null> {
  try {
    await run();
  } catch (error) {
    return typeof error === "object" && error !== null ? String((error as { digest?: unknown }).digest) : null;
  }
  return null;
}

beforeEach(() => {
  cookieJar.value = undefined;
  connectionMock.mockClear();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("oauth 도우미", () => {
  it("환경 변수 둘이 다 있어야 설정이 있다(U2-J1)", () => {
    expect(readGoogleConfig({ GOOGLE_OAUTH_CLIENT_ID: CLIENT_ID, GOOGLE_OAUTH_REDIRECT_URI: REDIRECT_URI })).toEqual({
      clientId: CLIENT_ID,
      redirectUri: REDIRECT_URI,
    });
    expect(readGoogleConfig({ GOOGLE_OAUTH_CLIENT_ID: CLIENT_ID })).toBeNull();
    expect(readGoogleConfig({ GOOGLE_OAUTH_CLIENT_ID: " ", GOOGLE_OAUTH_REDIRECT_URI: REDIRECT_URI })).toBeNull();
    // 리뷰 3: web은 Core와 같은 이름만 읽는다(예전 web 전용 이름은 배선에 없다)
    expect(readGoogleConfig({ MUJOONG_GOOGLE_CLIENT_ID: CLIENT_ID, MUJOONG_GOOGLE_REDIRECT_URI: REDIRECT_URI })).toBeNull();
  });

  it("인가 주소는 code · openid · state를 싣고 state는 매번 새 값이다", () => {
    const url = new URL(authorizeUrl({ clientId: CLIENT_ID, redirectUri: REDIRECT_URI }, "s1"));
    expect(url.origin + url.pathname).toBe("https://accounts.google.com/o/oauth2/v2/auth");
    expect(Object.fromEntries(url.searchParams)).toEqual({
      response_type: "code",
      client_id: CLIENT_ID,
      redirect_uri: REDIRECT_URI,
      scope: "openid",
      state: "s1",
      prompt: "select_account",
    });
    expect(newState()).not.toBe(newState());
    expect(newState()).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it("state 비교는 같을 때만 참이다", () => {
    expect(stateMatches("abc", "abc")).toBe(true);
    expect(stateMatches("abc", "abd")).toBe(false);
    expect(stateMatches("abc", "abcd")).toBe(false);
    expect(stateMatches(undefined, "abc")).toBe(false);
    expect(stateMatches("", "")).toBe(false);
    expect(singleParam(["a", "b"])).toBeNull();
    expect(singleParam("a")).toBe("a");
  });
});

describe("GET /parent/login/google", () => {
  it("state를 HttpOnly 쿠키에 두고 구글로 보낸다", () => {
    vi.stubEnv("GOOGLE_OAUTH_CLIENT_ID", CLIENT_ID);
    vi.stubEnv("GOOGLE_OAUTH_REDIRECT_URI", REDIRECT_URI);
    const response = googleStart(new NextRequest("https://mujoong.invalid/parent/login/google"));

    expect(response.status).toBe(302);
    const location = new URL(response.headers.get("location") ?? "");
    expect(location.hostname).toBe("accounts.google.com");
    const cookie = response.headers.get("set-cookie") ?? "";
    expect(cookie).toContain(`MUJOONG_OAUTH_STATE=${location.searchParams.get("state")}`);
    expect(cookie).toMatch(/HttpOnly/i);
    expect(cookie).toMatch(/Secure/i);
    expect(cookie).toMatch(/SameSite=lax/i);
    expect(cookie).toContain("Path=/parent/login");
    expect(cookie).toContain("Max-Age=600");
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(googleStartDynamic).toBe("force-dynamic");
  });

  it("구글 설정이 없으면 01 실패로 돌려보낸다", () => {
    const response = googleStart(new NextRequest("https://mujoong.invalid/parent/login/google"));
    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe("https://mujoong.invalid/parent/login?failed=1");
    expect(response.headers.get("set-cookie")).toBeNull();
  });
});

describe("/parent/login/callback", () => {
  const call = (params: Record<string, string | string[] | undefined>) =>
    ParentLoginCallbackPage({ searchParams: Promise.resolve(params) });

  it("state가 쿠키와 같으면 교환 화면을 그린다", async () => {
    vi.stubEnv("GOOGLE_OAUTH_CLIENT_ID", CLIENT_ID);
    vi.stubEnv("GOOGLE_OAUTH_REDIRECT_URI", REDIRECT_URI);
    cookieJar.value = "state-1";
    const element = elementOf(await call({ code: "code-1", state: "state-1" }));
    expect(element.type).toBe(LoginCallbackScreen);
    expect(element.props).toEqual({ code: "code-1", redirectUri: REDIRECT_URI });
    expect(connectionMock).toHaveBeenCalledTimes(1);
  });

  it.each<[string, Record<string, string | string[] | undefined>, string | undefined]>([
    ["state 다름", { code: "c", state: "other" }, "state-1"],
    ["쿠키 없음", { code: "c", state: "state-1" }, undefined],
    ["code 없음", { state: "state-1" }, "state-1"],
    ["취소", { error: "access_denied", state: "state-1" }, "state-1"],
    ["code 여러 개", { code: ["a", "b"], state: "state-1" }, "state-1"],
  ])("%s이면 01 실패로 돌려보낸다", async (_, params, cookie) => {
    vi.stubEnv("GOOGLE_OAUTH_CLIENT_ID", CLIENT_ID);
    vi.stubEnv("GOOGLE_OAUTH_REDIRECT_URI", REDIRECT_URI);
    cookieJar.value = cookie;
    expect(await digestOf(() => call(params))).toBe("NEXT_REDIRECT;replace;/parent/login?failed=1;307;");
  });

  it("구글 설정이 없으면 01 실패다", async () => {
    cookieJar.value = "state-1";
    expect(await digestOf(() => call({ code: "c", state: "state-1" }))).toBe("NEXT_REDIRECT;replace;/parent/login?failed=1;307;");
  });
});

describe("U2 화면 라우트", () => {
  it("01은 ?failed=1 · ?deleting=1을 화면에 넘긴다", async () => {
    const plain = elementOf(await ParentLoginPage({ searchParams: Promise.resolve({}) }));
    expect(plain.type).toBe(LoginScreen);
    expect(plain.props).toMatchObject({ failed: false, deleting: false });
    const failed = elementOf(await ParentLoginPage({ searchParams: Promise.resolve({ failed: "1", deleting: "1" }) }));
    expect(failed.props).toMatchObject({ failed: true, deleting: true });
  });

  it("02 · 03 · SET-01을 그린다", () => {
    expect(elementOf(ParentConsentPage()).type).toBe(ConsentScreen);
    expect(elementOf(ParentChildPage()).type).toBe(ChildScreen);
    expect(elementOf(ParentStopExpressionsPage()).type).toBe(StopExpressionsScreen);
  });
});
