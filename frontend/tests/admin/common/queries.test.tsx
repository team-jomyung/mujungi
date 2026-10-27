import type { PropsWithChildren } from "react";
import { QueryClientProvider, useIsFetching } from "@tanstack/react-query";
import { act, fireEvent, render, renderHook, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminSession, useAdminSession } from "@/admin/common/AdminSession";
import { AdminQueryScope, createAdminQueryClient, useAdminQuery } from "@/admin/common/queries";
import { defaultTiming } from "@/shared/timing/timing";
import { TimingProvider } from "@/shared/timing/TimingProvider";

afterEach(() => vi.unstubAllGlobals());

describe("관리자 조회 격리", () => {
  it("자동 재시도·포커스 및 재연결 재조회 없이 오프라인 요청도 실행한다", () => {
    expect(createAdminQueryClient().getDefaultOptions().queries).toMatchObject({
      retry: false,
      refetchOnWindowFocus: false,
      refetchOnReconnect: false,
      networkMode: "always",
    });
  });

  it("첫 조회 실패 후 재시도하는 동안 로딩을 보여 주고 성공하면 해제한다", async () => {
    const failure = new Error("첫 조회 실패");
    const retry = deferred<string>();
    const load = vi.fn<() => Promise<string>>()
      .mockRejectedValueOnce(failure)
      .mockImplementationOnce(() => retry.promise);
    const { result } = renderQuery(load);
    await waitFor(() => expect(result.current.error).toBe(failure));
    expect(result.current.loading).toBe(false);

    act(() => result.current.reload());
    await waitFor(() => expect(result.current.loading).toBe(true));
    expect(result.current.data).toBeNull();
    await act(async () => {
      retry.resolve("복구된 결과");
      await retry.promise;
    });
    await waitFor(() => expect(result.current.data).toBe("복구된 결과"));
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(load).toHaveBeenCalledTimes(2);
  });

  it("성공 캐시가 남은 조회 오류도 재시도하는 동안 로딩을 표시한다", async () => {
    const failure = new Error("재조회 실패");
    const retry = deferred<string>();
    const load = vi.fn<() => Promise<string>>()
      .mockResolvedValueOnce("기존 결과")
      .mockRejectedValueOnce(failure)
      .mockImplementationOnce(() => retry.promise);
    const { result } = renderQuery(load);
    await waitFor(() => expect(result.current.data).toBe("기존 결과"));

    act(() => result.current.reload());
    await waitFor(() => expect(result.current.error).toBe(failure));
    expect(result.current.data).toBeNull();
    expect(result.current.loading).toBe(false);
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.loading).toBe(true));
    expect(result.current.data).toBeNull();
    await act(async () => {
      retry.resolve("새 결과");
      await retry.promise;
    });
    await waitFor(() => expect(result.current.data).toBe("새 결과"));
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(load).toHaveBeenCalledTimes(3);
  });

  it("정상적인 재조회 중에는 기존 결과를 로딩 화면으로 바꾸지 않는다", async () => {
    const refresh = deferred<string>();
    const load = vi.fn<() => Promise<string>>()
      .mockResolvedValueOnce("기존 결과")
      .mockImplementationOnce(() => refresh.promise);
    const { result } = renderQuery(load);
    await waitFor(() => expect(result.current.data).toBe("기존 결과"));

    act(() => result.current.reload());
    await waitFor(() => expect(result.current.fetching).toBe(1));
    expect(load).toHaveBeenCalledTimes(2);
    expect(result.current.data).toBe("기존 결과");
    expect(result.current.loading).toBe(false);
    await act(async () => {
      refresh.resolve("새 결과");
      await refresh.promise;
    });
    await waitFor(() => expect(result.current.data).toBe("새 결과"));
    expect(result.current.loading).toBe(false);
  });

  it("미리보기 상태별 조회 키를 분리해 이전 상태의 캐시를 보여주지 않는다", async () => {
    const client = createAdminQueryClient();
    function Probe({ value }: { value: string }) {
      const query = useAdminQuery(["sample", { from: "2026-10-20" }, 0], async () => value);
      return <p>{query.data ?? "불러오는 중"}</p>;
    }
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <AdminQueryScope.Provider value={["admin", "preview", "normal", "2026-10-26"]}><Probe value="기존 결과" /></AdminQueryScope.Provider>
      </QueryClientProvider>,
    );
    await screen.findByText("기존 결과");
    rerender(
      <QueryClientProvider client={client}>
        <AdminQueryScope.Provider value={["admin", "preview", "empty", "2026-10-26"]}><Probe value="빈 상태 결과" /></AdminQueryScope.Provider>
      </QueryClientProvider>,
    );
    expect(screen.queryByText("기존 결과")).not.toBeInTheDocument();
    await screen.findByText("빈 상태 결과");
  });

  it.each([401, 403])("상세에서 %s를 받으면 전체 내용을 숨기고 요청과 캐시를 정리한다", async (status) => {
    const client = createAdminQueryClient();
    const fetchImpl = vi.fn(async (url: string) => url.endsWith("/me")
      ? Response.json({ displayName: "검증 관리자", role: "ADMIN" })
      : Response.json({ code: "UNKNOWN_ADMIN_CODE", traceId: "검증 추적 ID" }, { status }));
    vi.stubGlobal("fetch", fetchImpl);
    function Probe() {
      const session = useAdminSession();
      if (!session.source) return <p>{session.error ? "접근 차단" : "불러오는 중"}</p>;
      return <button onClick={() => {
        void session.source!.costDetail("sample").catch(() => undefined);
      }}>비공개 조회 영역</button>;
    }
    render(
      <TimingProvider timing={defaultTiming()}>
        <QueryClientProvider client={client}>
          <AdminQueryScope.Provider value={["admin", "api", "normal"]}>
            <AdminSession mode="api" previewState="normal" today="2026-10-26"><Probe /></AdminSession>
          </AdminQueryScope.Provider>
        </QueryClientProvider>
      </TimingProvider>,
    );
    await screen.findByRole("button", { name: "비공개 조회 영역" });
    client.setQueryData(["admin", "private"], "기존 비공개 내용");
    let pendingSignal: AbortSignal | undefined;
    const pending = client.fetchQuery({
      queryKey: ["admin", "pending"],
      queryFn: ({ signal }) => {
        pendingSignal = signal;
        return new Promise<string>(() => undefined);
      },
    }).catch(() => undefined);
    await waitFor(() => expect(pendingSignal).toBeDefined());
    fireEvent.click(screen.getByRole("button", { name: "비공개 조회 영역" }));
    await screen.findByText("접근 차단");
    expect(screen.queryByRole("button", { name: "비공개 조회 영역" })).not.toBeInTheDocument();
    await waitFor(() => expect(client.getQueryData(["admin", "private"])).toBeUndefined());
    expect(pendingSignal?.aborted).toBe(true);
    expect(client.getQueryCache().getAll().every((query) => query.state.data === undefined)).toBe(true);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    await act(async () => { await pending; });
  });
});

function renderQuery(load: () => Promise<string>) {
  const client = createAdminQueryClient();
  function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return renderHook(() => {
    const query = useAdminQuery(["retry"], load);
    const fetching = useIsFetching();
    return { ...query, fetching };
  }, { wrapper: Wrapper });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => { resolve = complete; });
  return { promise, resolve };
}
