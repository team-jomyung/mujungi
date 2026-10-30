import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PracticeStartCoordinator, PracticeStartStore, ownedSelection, type StartResult } from "@/parent/features/practice/practiceStart";
import { PracticeStartAction } from "@/parent/features/practice/PracticeStartAction";

afterEach(cleanup);
// FT-1027-a: 한 번에 목표 1개(이전 3개).
it("한 번에 1개는 허용하고 2개는 전송하지 않는다", async () => {
  const port=vi.fn().mockResolvedValue({kind:"accepted",sessionId:"session"});
  const ids=["a","b"];
  const coordinator=new PracticeStartCoordinator(port,()=>"key");
  expect(await coordinator.submit(ids,ids)).toEqual({kind:"rejected",code:"VALIDATION_FAILED"});
  expect(port).not.toHaveBeenCalled();
  expect(await coordinator.submit(ids.slice(0,1),ids)).toEqual({kind:"accepted",sessionId:"session"});
  expect(port.mock.calls[0][0].goalSelectionIds).toEqual(["a"]);
});
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((r) => { resolve = r; }); return { resolve, promise }; }

it("소유 목록과 교집합만 선택하고 빈 선택은 전송하지 않는다", async () => {
  const port = vi.fn(); const coordinator = new PracticeStartCoordinator(port, () => "key");
  expect(ownedSelection(["b", "a", "a", "foreign"], ["a", "b"])).toEqual(["a", "b"]);
  await expect(coordinator.submit([], ["a"])).rejects.toThrow("선택 목표"); expect(port).not.toHaveBeenCalled();
});
it("동시 제출은 같은 키의 한 요청을 공유한다", async () => {
  const pending = deferred<StartResult>(); const port = vi.fn(() => pending.promise); const key = vi.fn(() => "key");
  const coordinator = new PracticeStartCoordinator(port, key);
  const first = coordinator.submit(["a"], ["a", "b"]); const second = coordinator.submit(["b"], ["a", "b"]);
  expect(second).toBe(first); await Promise.resolve(); expect(port).toHaveBeenCalledTimes(1);
  pending.resolve({ kind: "accepted", sessionId: "session" }); await first; await coordinator.submit(["a"], ["a"]);
  expect(port).toHaveBeenCalledTimes(1); expect(key).toHaveBeenCalledTimes(1);
});
it("전송 결과 미확인 뒤에도 키와 원래 선택을 유지한다", async () => {
  const port = vi.fn().mockRejectedValueOnce(new TypeError()).mockResolvedValue({ kind: "accepted", sessionId: "session" });
  const coordinator = new PracticeStartCoordinator(port, () => "key");
  expect(await coordinator.submit(["a"], ["a", "b"])).toEqual({ kind: "unknown" });
  await coordinator.submit(["b"], ["a", "b"]);
  expect(port.mock.calls[0][0]).toEqual(port.mock.calls[1][0]); expect(port.mock.calls[1][0].goalSelectionIds).toEqual(["a"]);
});
it("확정 거절 뒤 명시 재시도는 선택이 같아도 새 키다(U5b-I5)", async () => {
  let sequence = 0; const port = vi.fn().mockResolvedValue({ kind: "rejected", code: "SCENARIO_GEN_FAILED" });
  const coordinator = new PracticeStartCoordinator(port, () => `key-${++sequence}`);
  await coordinator.submit(["a"], ["a", "b"]); await coordinator.submit(["a"], ["a", "b"]); await coordinator.submit(["b"], ["a", "b"]);
  expect(port.mock.calls.map((call) => call[0].idempotencyKey)).toEqual(["key-1", "key-2", "key-3"]);
});
it("선택 0개 단추는 비활성이고 선택만으로 호출하지 않는다", () => {
  const port = vi.fn(); render(<PracticeStartAction selected={[]} owned={["a"]} port={port} newKey={() => "key"} onAccepted={vi.fn()} />);
  expect(screen.getByRole("button")).toBeDisabled(); expect(port).not.toHaveBeenCalled();
});
it("키보드 제출 중 비활성 상태를 알리고 수락 뒤 한 번 이동한다", async () => {
  const pending = deferred<StartResult>(); const accepted = vi.fn();
  render(<PracticeStartAction selected={["a"]} owned={["a"]} port={() => pending.promise} newKey={() => "key"} onAccepted={accepted} />);
  const user = userEvent.setup(); await user.tab(); await user.keyboard("{Enter}");
  expect(screen.getByRole("button")).toBeDisabled(); expect(screen.getByRole("status")).toHaveTextContent("요청하고");
  await act(async () => { pending.resolve({ kind: "accepted", sessionId: "session" }); });
  expect(accepted).toHaveBeenCalledExactlyOnceWith("session");
});
it("상황 준비 실패를 표시하고 선택 수를 유지한다", async () => {
  render(<PracticeStartAction selected={["a"]} owned={["a", "b"]} port={async () => ({ kind: "rejected", code: "SCENARIO_GEN_FAILED" })}
    newKey={() => "key"} onAccepted={vi.fn()} />);
  fireEvent.click(screen.getByRole("button")); expect(await screen.findByText(/연습 상황을 만들지 못했어요/)).toBeVisible();
  expect(screen.getByRole("button")).toHaveTextContent("연습 시작"); expect(screen.getByRole("button")).toBeEnabled();
});
it("통신 오류를 시작 실패나 성공으로 표시하지 않는다", async () => {
  const accepted = vi.fn(); render(<PracticeStartAction selected={["a"]} owned={["a"]} port={async () => { throw new TypeError(); }}
    newKey={() => "key"} onAccepted={accepted} />);
  fireEvent.click(screen.getByRole("button")); expect(await screen.findByText(/시작 결과를 확인하지 못했어요/)).toBeVisible();
  expect(accepted).not.toHaveBeenCalled();
});
// FT-1030-a: 상황 준비가 1분까지 걸려, 기다리는 동안 단추에 회전 표시를 두고 끝나면 거둔다.
it("상황 준비를 기다리는 동안만 단추에 로딩 표시를 둔다", async () => {
  const pending = deferred<StartResult>();
  render(<PracticeStartAction selected={["a"]} owned={["a"]} port={() => pending.promise} newKey={() => "key"} onAccepted={vi.fn()} />);
  expect(screen.queryByTestId("practice-start-spinner")).toBeNull();
  fireEvent.click(screen.getByRole("button"));
  expect(await screen.findByTestId("practice-start-spinner")).toBeInTheDocument();
  expect(screen.getByRole("button")).toHaveAttribute("aria-busy", "true");
  await act(async () => { pending.resolve({ kind: "rejected", code: "SCENARIO_GEN_FAILED" }); });
  expect(screen.queryByTestId("practice-start-spinner")).toBeNull();
  expect(screen.getByRole("button")).toHaveAttribute("aria-busy", "false");
});

// FT-1030-c: 준비 중에 다른 탭으로 갔다 돌아와도 같은 요청의 준비 중 상태를 이어 보이고, 새 목표 선택 · 시작을 막는다.
describe("다른 화면에 갔다 돌아왔을 때", () => {
  function action(store: PracticeStartStore, port: (request: unknown) => Promise<StartResult>, props: { onAccepted?: () => void; onLockedChange?: (locked: boolean) => void } = {}) {
    return render(<PracticeStartAction selected={["a"]} owned={["a"]} port={port} newKey={() => crypto.randomUUID()} store={store}
      onAccepted={props.onAccepted ?? vi.fn()} onLockedChange={props.onLockedChange} />);
  }

  it("준비 중이던 요청을 이어 기다리고 그 결과로 이동한다", async () => {
    const store = new PracticeStartStore(); const pending = deferred<StartResult>(); const port = vi.fn(() => pending.promise);
    const first = action(store, port);
    fireEvent.click(screen.getByRole("button"));
    await act(async () => { await Promise.resolve(); });
    first.unmount();
    expect(store.preparing).toBe(true);
    const accepted = vi.fn(); const locked = vi.fn();
    action(store, port, { onAccepted: accepted, onLockedChange: locked });
    expect(screen.getByRole("button")).toBeDisabled();
    expect(screen.getByTestId("practice-start-spinner")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("요청하고");
    expect(locked).toHaveBeenCalledWith(true);
    fireEvent.click(screen.getByRole("button"));
    await act(async () => { pending.resolve({ kind: "accepted", sessionId: "session" }); });
    expect(port).toHaveBeenCalledTimes(1);
    expect(accepted).toHaveBeenCalledExactlyOnceWith("session");
    expect(store.preparing).toBe(false);
  });

  it("떠나 있는 동안 거절됐으면 돌아와 거절을 보이고 다시 시작할 수 있다", async () => {
    const store = new PracticeStartStore(); const pending = deferred<StartResult>(); const port = vi.fn(() => pending.promise);
    const first = action(store, port);
    fireEvent.click(screen.getByRole("button"));
    await act(async () => { await Promise.resolve(); });
    first.unmount();
    await act(async () => { pending.resolve({ kind: "rejected", code: "SCENARIO_GEN_FAILED" }); });
    action(store, port);
    expect(await screen.findByText(/연습 상황을 만들지 못했어요/)).toBeVisible();
    expect(screen.getByRole("button")).toBeEnabled();
  });

  it("떠나 있는 동안 시작됐으면 지난 세션으로 옮기지 않고 다음 시작은 새 요청으로 한다", async () => {
    const store = new PracticeStartStore(); const pending = deferred<StartResult>();
    const port = vi.fn().mockImplementationOnce(() => pending.promise).mockResolvedValue({ kind: "accepted", sessionId: "next" });
    const first = action(store, port);
    fireEvent.click(screen.getByRole("button"));
    await act(async () => { await Promise.resolve(); });
    first.unmount();
    await act(async () => { pending.resolve({ kind: "accepted", sessionId: "old" }); });
    const accepted = vi.fn();
    action(store, port, { onAccepted: accepted });
    await act(async () => { await Promise.resolve(); });
    expect(accepted).not.toHaveBeenCalled();
    expect(screen.getByRole("button")).toBeEnabled();
    fireEvent.click(screen.getByRole("button"));
    await act(async () => { await Promise.resolve(); });
    expect(accepted).toHaveBeenCalledExactlyOnceWith("next");
    expect(port.mock.calls[1][0].idempotencyKey).not.toBe(port.mock.calls[0][0].idempotencyKey);
  });
});
