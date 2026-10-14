import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { PracticeStartCoordinator, ownedSelection, type StartResult } from "@/parent/practice/practiceStart";
import { PracticeStartAction } from "@/parent/practice/PracticeStartAction";

afterEach(cleanup);
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((r) => { resolve = r; }); return { resolve, promise }; }

it("소유 목록과 교집합만 선택하고 빈 선택은 전송하지 않는다", async () => {
  const port = vi.fn(); const coordinator = new PracticeStartCoordinator(port, () => "key");
  expect(ownedSelection(["b", "a", "a", "foreign"], ["a", "b"])).toEqual(["a", "b"]);
  await expect(coordinator.submit([], ["a"])).rejects.toThrow("선택 목표"); expect(port).not.toHaveBeenCalled();
});
it("동시 제출은 같은 키의 한 요청을 공유한다", async () => {
  const pending = deferred<StartResult>(); const port = vi.fn(() => pending.promise); const key = vi.fn(() => "key");
  const coordinator = new PracticeStartCoordinator(port, key);
  const first = coordinator.submit(["b", "a"], ["a", "b"]); const second = coordinator.submit(["a"], ["a", "b"]);
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
  render(<PracticeStartAction selected={["a", "b"]} owned={["a", "b"]} port={async () => ({ kind: "rejected", code: "SCENARIO_GEN_FAILED" })}
    newKey={() => "key"} onAccepted={vi.fn()} />);
  fireEvent.click(screen.getByRole("button")); expect(await screen.findByText(/연습 상황을 만들지 못했어요/)).toBeVisible();
  expect(screen.getByRole("button")).toHaveTextContent("2개"); expect(screen.getByRole("button")).toBeEnabled();
});
it("통신 오류를 시작 실패나 성공으로 표시하지 않는다", async () => {
  const accepted = vi.fn(); render(<PracticeStartAction selected={["a"]} owned={["a"]} port={async () => { throw new TypeError(); }}
    newKey={() => "key"} onAccepted={accepted} />);
  fireEvent.click(screen.getByRole("button")); expect(await screen.findByText(/시작 결과를 확인하지 못했어요/)).toBeVisible();
  expect(accepted).not.toHaveBeenCalled();
});
