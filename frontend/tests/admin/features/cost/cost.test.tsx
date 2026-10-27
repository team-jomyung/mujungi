import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ADMIN_COPY as C } from "@/admin/common/copy";
import { createAdminQueryClient } from "@/admin/common/queries";
import { createMockAdminSource } from "@/admin/data/mock";
import { CostScreen } from "@/admin/features/cost/CostScreen";
import { AdminView } from "@/admin/AdminView";
import { AdminContext } from "@/admin/common/context";

const filter = { from: "2026-10-20", to: "2026-10-26", type: "ALL" as const };

describe("관리자 비용 행 상세", () => {
  it("조회 기간을 바꾸면 열린 상세와 페이지를 초기화한다", async () => {
    const base = createMockAdminSource("2026-10-26");
    const costs = vi.fn(base.costs);
    const source = { ...base, costs };
    const client = createAdminQueryClient();
    function View({ from }: { from: string }) {
      return (
        <QueryClientProvider client={client}>
          <AdminContext.Provider value={{ source, filter: { ...filter, from } }}><AdminView section="cost" /></AdminContext.Provider>
        </QueryClientProvider>
      );
    }
    const { rerender } = render(<View from={filter.from} />);
    await screen.findByRole("table", { name: C.costList });
    fireEvent.click(screen.getByRole("button", { name: C.next }));
    await waitFor(() => expect(costs).toHaveBeenLastCalledWith(filter, 1, expect.any(AbortSignal)));
    const table = await screen.findByRole("table", { name: C.costList });
    fireEvent.click(within(table).getAllByRole("button", { name: /상세 보기/ })[0]);
    await screen.findByRole("region", { name: C.costDetail });
    rerender(<View from="2026-10-22" />);
    expect(screen.queryByRole("region", { name: C.costDetail })).not.toBeInTheDocument();
    await waitFor(() => expect(costs).toHaveBeenLastCalledWith({ ...filter, from: "2026-10-22" }, 0, expect.any(AbortSignal)));
  });

  it("선택한 행 바로 다음 행에 상세를 펼치고 다른 행 선택·닫기를 처리한다", async () => {
    const source = createMockAdminSource("2026-10-26");
    render(<QueryClientProvider client={createAdminQueryClient()}><CostScreen source={source} filter={filter} /></QueryClientProvider>);
    const table = await screen.findByRole("table", { name: C.costList });
    const buttons = within(table).getAllByRole("button", { name: /상세 보기/ });
    fireEvent.click(buttons[0]);
    const detail = await screen.findByRole("region", { name: C.costDetail });
    const cell = detail.closest("td");
    expect(cell).toHaveAttribute("colspan", "8");
    expect(cell?.parentElement?.previousElementSibling).toBe(buttons[0].closest("tr"));
    expect(document.getElementById(buttons[0].getAttribute("aria-controls")!)).toContainElement(detail);
    fireEvent.click(buttons[1]);
    await waitFor(() => expect(buttons[0]).toHaveAttribute("aria-expanded", "false"));
    expect(screen.getAllByRole("region", { name: C.costDetail })).toHaveLength(1);
    fireEvent.click(buttons[1]);
    expect(screen.queryByRole("region", { name: C.costDetail })).not.toBeInTheDocument();
  });

  it("대화 외 목록도 자기 행 아래에 펼치고 목록 사이 상세 ID는 겹치지 않는다", async () => {
    const source = createMockAdminSource("2026-10-26");
    render(<QueryClientProvider client={createAdminQueryClient()}><CostScreen source={source} filter={filter} /></QueryClientProvider>);
    const activityTable = await screen.findByRole("table", { name: C.costList });
    const otherTable = screen.getByRole("table", { name: C.otherCostList });
    const first = within(activityTable).getAllByRole("button", { name: /상세 보기/ })[0];
    const other = within(otherTable).getAllByRole("button", { name: /상세 보기/ })[0];
    expect(first.getAttribute("aria-controls")).not.toBe(other.getAttribute("aria-controls"));
    fireEvent.click(first);
    fireEvent.click(other);
    const detail = await within(otherTable).findByRole("region", { name: C.costDetail });
    expect(detail.closest("tr")?.previousElementSibling).toBe(other.closest("tr"));
    expect(within(activityTable).queryByRole("region", { name: C.costDetail })).not.toBeInTheDocument();
  });

  it("페이지 변경은 상세를 닫고 새 페이지를 조회한다", async () => {
    const base = createMockAdminSource("2026-10-26");
    const costs = vi.fn(base.costs);
    render(<QueryClientProvider client={createAdminQueryClient()}><CostScreen source={{ ...base, costs }} filter={filter} /></QueryClientProvider>);
    const table = await screen.findByRole("table", { name: C.costList });
    fireEvent.click(within(table).getAllByRole("button", { name: /상세 보기/ })[0]);
    await screen.findByRole("region", { name: C.costDetail });
    fireEvent.click(screen.getByRole("button", { name: C.next }));
    await waitFor(() => expect(costs).toHaveBeenLastCalledWith(filter, 1, expect.any(AbortSignal)));
    expect(screen.queryByRole("region", { name: C.costDetail })).not.toBeInTheDocument();
  });
});
