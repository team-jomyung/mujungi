import { describe, expect, it, vi } from "vitest";
import {
  CONSENT_TYPES,
  fetchChild,
  fetchMe,
  fetchPolicy,
  fetchStopExpressions,
  loginWithGoogle,
  logout,
  parseChild,
  parseMe,
  parsePolicy,
  parseStopExpressions,
  registerChild,
  requestAccountDeletion,
  saveConsents,
  saveStopExpressions,
  updateChild,
} from "@/parent/api/account";
import { createApiClient, type FetchLike } from "@/parent/api/apiClient";
import { ResponseShapeError } from "@/parent/api/types";
import { routeAfterLogin } from "@/parent/features/login/LoginCallbackScreen";
import { errorResponse, jsonResponse } from "../../u1/support/fixtures";
import { childBody, jsonBody, meBody, noContent, policyBody, stopBody } from "../support/accountFixtures";

// U2 보호자 계정 API 형식 · 요청 시험 (contracts/parent-api.yaml U2 경로 · 계약 10절 받는 쪽 규칙 · R3)

interface Recorded {
  readonly path: string;
  readonly init: RequestInit;
}

function client(handler: (path: string, init: RequestInit) => Response) {
  const calls: Recorded[] = [];
  const fetchImpl = vi.fn<FetchLike>(async (path, init) => {
    calls.push({ path, init });
    if (path === "/api/session/csrf") {
      return jsonResponse(200, { token: "t", headerName: "X-CSRF-TOKEN" });
    }
    return handler(path, init);
  });
  return { api: createApiClient({ timeoutMs: 1_000, fetchImpl }), calls };
}

function header(init: RequestInit, name: string): string | null {
  return new Headers(init.headers).get(name);
}

describe("읽기(계약 10절: 모르는 값은 UNKNOWN, 필수 누락은 형식 오류)", () => {
  it("Me를 읽고 모르는 동의 상태는 UNKNOWN이다", () => {
    expect(parseMe(meBody())).toEqual({ guardianId: expect.any(String), consentStatus: "GIVEN", childRegistered: true, deletionInProgress: false });
    expect(parseMe(meBody({ consentStatus: "LATER", deletionStatus: "IN_PROGRESS" }))).toMatchObject({
      consentStatus: "UNKNOWN",
      deletionInProgress: true,
    });
    expect(() => parseMe({ guardianId: "g", consentStatus: "GIVEN", deletionStatus: "NONE" })).toThrow(ResponseShapeError);
    expect(() => parseMe([])).toThrow(ResponseShapeError);
  });

  it("약관은 네 항목이 모두 있어야 하고 모르는 항목은 버린다", () => {
    const policy = parsePolicy(policyBody());
    expect(policy.items.map((item) => item.type)).toEqual([...CONSENT_TYPES]);
    const withUnknown = policyBody();
    (withUnknown.items as unknown[]).push({ type: "MARKETING", title: "x", sections: [] });
    expect(parsePolicy(withUnknown).items).toHaveLength(4);
    const missing = policyBody();
    (missing.items as unknown[]).pop();
    expect(() => parsePolicy(missing)).toThrow(ResponseShapeError);
    expect(() => parsePolicy({ policyVersion: "1.0", items: [{ type: "RAW_AUDIO", title: "t", sections: [{ heading: "" }] }] })).toThrow(
      ResponseShapeError,
    );
  });

  it("아이 · 종료 표현을 읽는다(순서는 sortOrder)", () => {
    expect(parseChild(childBody())).toEqual({ childId: expect.any(String), displayName: "하늘", age: 8, interests: ["공룡", "그림"] });
    expect(() => parseChild({ ...childBody(), age: "8" })).toThrow(ResponseShapeError);
    expect(parseChild({ ...childBody(), interests: ["a", 1] }).interests).toEqual(["a"]);
    const body = stopBody(["이제 그만", "끝낼래"]);
    (body.items as unknown[]).reverse();
    expect(parseStopExpressions(body).items.map((item) => item.text)).toEqual(["이제 그만", "끝낼래"]);
    expect(parseStopExpressions({ default: "무중아 그만할래" }).items).toEqual([]);
    expect(() => parseStopExpressions({ items: [] })).toThrow(ResponseShapeError);
  });
});

describe("요청(경로 · 메서드 · CSRF · 본문)", () => {
  it("구글 로그인은 CSRF 없이 code · redirectUri를 보내고 새 세션의 CSRF를 다시 받는다", async () => {
    const { api, calls } = client(() => jsonResponse(200, meBody()));
    const me = await loginWithGoogle(api, "code-1", "https://mujoong.invalid/parent/login/callback");
    expect(me.consentStatus).toBe("GIVEN");
    const login = calls.find((call) => call.path === "/api/auth/google");
    expect(login?.init.method).toBe("POST");
    expect(header(login!.init, "X-CSRF-TOKEN")).toBeNull();
    expect(jsonBody(login!.init)).toEqual({ code: "code-1", redirectUri: "https://mujoong.invalid/parent/login/callback" });
    expect(calls.at(-1)?.path).toBe("/api/session/csrf");
    expect(api.csrf.peek()).not.toBeNull();
  });

  it("삭제 처리 중 로그인은 CSRF를 받지 않는다(세션이 없다)", async () => {
    const { api, calls } = client(() => jsonResponse(200, meBody({ deletionStatus: "IN_PROGRESS" })));
    await loginWithGoogle(api, "c", "r");
    expect(calls.map((call) => call.path)).toEqual(["/api/auth/google"]);
    expect(api.csrf.peek()).toBeNull();
  });

  it("변경 요청은 CSRF를 싣고 맞는 메서드 · 본문을 쓴다", async () => {
    const { api, calls } = client((path, init) => {
      if (init.method === "PUT" || (init.method === "POST" && path === "/api/auth/logout")) {
        return noContent();
      }
      if (init.method === "DELETE") {
        return jsonResponse(202, { deletionRequestId: "00000000-0000-4000-8000-000000000050" });
      }
      return jsonResponse(path === "/api/child" && init.method === "POST" ? 201 : 200, childBody());
    });

    await saveConsents(api, "1.0");
    await registerChild(api, { displayName: "하늘", age: 8, interests: ["공룡"] });
    await updateChild(api, { displayName: "하늘", age: 9, interests: [] });
    await saveStopExpressions(api, ["이제 그만"]);
    expect(await requestAccountDeletion(api)).toBe("00000000-0000-4000-8000-000000000050");
    await api.csrf.ensure();
    await logout(api);

    const mutating = calls.filter((call) => call.path !== "/api/session/csrf");
    expect(mutating.map((call) => `${call.init.method} ${call.path}`)).toEqual([
      "PUT /api/consents",
      "POST /api/child",
      "PATCH /api/child",
      "PUT /api/child/stop-expressions",
      "DELETE /api/me",
      "POST /api/auth/logout",
    ]);
    expect(mutating.every((call) => header(call.init, "X-CSRF-TOKEN") === "t")).toBe(true);
    expect(jsonBody(mutating[0].init)).toEqual({
      policyVersion: "1.0",
      items: CONSENT_TYPES.map((type) => ({ type, agreed: true })),
    });
    expect(jsonBody(mutating[3].init)).toEqual({ items: [{ text: "이제 그만" }] });
    expect(api.csrf.peek()).toBeNull();
  });

  it("조회는 CSRF 없이 GET이고 404 CHILD_NOT_REGISTERED는 오류 code로 온다", async () => {
    const { api, calls } = client((path) => {
      switch (path) {
        case "/api/me":
          return jsonResponse(200, meBody());
        case "/api/policies/current":
          return jsonResponse(200, policyBody());
        case "/api/child/stop-expressions":
          return jsonResponse(200, stopBody(["끝낼래"]));
        default:
          return errorResponse(404, "CHILD_NOT_REGISTERED");
      }
    });
    await fetchMe(api);
    await fetchPolicy(api);
    await fetchStopExpressions(api);
    await expect(fetchChild(api)).rejects.toMatchObject({ status: 404, code: "CHILD_NOT_REGISTERED" });
    expect(calls.every((call) => call.init.method === "GET" && header(call.init, "X-CSRF-TOKEN") === null)).toBe(true);
  });
});

describe("로그인 뒤 갈 곳(R3 · AC1.1.1 ~ AC1.1.3 · AC1.6.4)", () => {
  it.each([
    [{ consentStatus: "NOT_GIVEN", childRegistered: false }, "/parent/consent"],
    [{ consentStatus: "OUTDATED", childRegistered: true }, "/parent/consent"],
    [{ consentStatus: "GIVEN", childRegistered: false }, "/parent/child"],
    [{ consentStatus: "GIVEN", childRegistered: true }, "/parent"],
    [{ consentStatus: "GIVEN", childRegistered: true, deletionStatus: "IN_PROGRESS" }, "/parent/login?deleting=1"],
  ])("%j → %s", (options, route) => {
    expect(routeAfterLogin(parseMe(meBody(options)))).toBe(route);
  });
});
