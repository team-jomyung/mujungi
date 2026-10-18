import type { QueryClient } from "@tanstack/react-query";
import { render, type RenderResult } from "@testing-library/react";
import type { ReactElement } from "react";
import { vi, type Mock } from "vitest";
import { ParentProviders } from "@/parent/common/ParentProviders";
import { createApiClient, type ApiClient, type FetchLike } from "@/parent/common/api/apiClient";
import { createParentQueryClient } from "@/parent/common/api/queries";
import { TimingProvider } from "@/shared/timing/TimingProvider";
import type { Timing } from "@/shared/timing/timing";
import { jsonResponse, makeCsrfToken, testTiming } from "./fixtures";

// 보호자 화면 시험 틀 (U1 3.5 Step 42): TimingProvider(줄인 시간) + ParentProviders(QueryClient · API 클라이언트 · 토스트)를
// 실제 그대로 쓰고, 망만 fetch 대역으로 바꾼다. CSRF 토큰은 실행마다 새로 만든다.

export type RequestHandler = (path: string, init: RequestInit) => Response | Promise<Response>;

export interface RecordedRequest {
  readonly path: string;
  readonly method: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly body: unknown;
}

export interface ParentHarness {
  readonly fetchImpl: Mock<FetchLike>;
  readonly api: ApiClient;
  readonly queryClient: QueryClient;
  readonly csrfToken: string;
  readonly view: RenderResult;
  /** 경로가 맞는 요청 기록(path가 없으면 전부). */
  requests(path?: string | RegExp): RecordedRequest[];
}

export interface RenderParentOptions {
  readonly handler: RequestHandler;
  readonly timing?: Partial<Timing>;
  readonly timeoutMs?: number;
  /** GET /api/session/csrf 응답을 바꿀 때. 없으면 200 {token, headerName: X-CSRF-TOKEN}. */
  readonly csrfHandler?: RequestHandler;
  /** 렌더 전에 QueryClient를 채울 때(예: 04가 받아 둔 홈 응답). */
  readonly prepare?: (queryClient: QueryClient) => void;
}

function headersOf(init: RequestInit): Record<string, string> {
  const result: Record<string, string> = {};
  new Headers(init.headers).forEach((value, key) => {
    result[key] = value;
  });
  return result;
}

function bodyOf(init: RequestInit): unknown {
  return typeof init.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
}

function matches(path: string, filter: string | RegExp | undefined): boolean {
  if (filter === undefined) {
    return true;
  }
  return typeof filter === "string" ? path === filter : filter.test(path);
}

export function renderParent(ui: ReactElement, options: RenderParentOptions): ParentHarness {
  const csrfToken = makeCsrfToken();
  const recorded: RecordedRequest[] = [];
  const fetchImpl = vi.fn<FetchLike>(async (path, init) => {
    recorded.push({ path, method: init.method ?? "GET", headers: headersOf(init), body: bodyOf(init) });
    if (path === "/api/session/csrf") {
      return options.csrfHandler !== undefined
        ? options.csrfHandler(path, init)
        : jsonResponse(200, { token: csrfToken, headerName: "X-CSRF-TOKEN" });
    }
    return options.handler(path, init);
  });
  const timing = testTiming(options.timing);
  const api = createApiClient({ timeoutMs: options.timeoutMs ?? timing.parentRequestTimeoutMs, fetchImpl });
  const queryClient = createParentQueryClient();
  options.prepare?.(queryClient);
  const view = render(
    <TimingProvider timing={timing}>
      <ParentProviders api={api} queryClient={queryClient}>
        {ui}
      </ParentProviders>
    </TimingProvider>,
  );
  return {
    fetchImpl,
    api,
    queryClient,
    csrfToken,
    view,
    requests: (path) => recorded.filter((request) => matches(request.path, path)),
  };
}
