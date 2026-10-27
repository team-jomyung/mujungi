import type { ReactElement } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { createAdminQueryClient } from "@/admin/common/queries";
import { fireEvent, render as renderView, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ActivityScreen } from "@/admin/features/activity/ActivityScreen";
import { createMockAdminSource } from "@/admin/data/mock";
import type { AdminFilter } from "@/admin/data/types";

const filter: AdminFilter = { from: "2026-10-13", to: "2026-10-26", type: "ALL" };

describe("관리자 활동 분석", () => {
  it("상세를 한 번 열면 원문과 장면 결과를 바로 보여 주며 비용 편집기는 없다", async () => {
    const source = createMockAdminSource("2026-10-26");
    const rows = await source.activities({ ...filter, type: "PRACTICE" }, 0);
    const activity = rows.items.find(item => item.status !== "FAILED_TO_START")!;
    const detail = await source.activity(activity.id);
    render(<ActivityScreen source={source} filter={{ ...filter, type: "PRACTICE" }} />);
    const detailButtonName = `${activity.childName} · ${activity.id} 상세 보기`;
    const childNames = await screen.findAllByText(activity.childName);
    const activityRow = childNames
      .map(element => element.closest("tr"))
      .find(row => row !== null && within(row).queryByRole("button", { name: detailButtonName }));
    expect(activityRow).toBeDefined();
    fireEvent.click(within(activityRow!).getByRole("button", { name: detailButtonName }));
    expect(await screen.findByText(detail.transcript[0].text)).toBeVisible();
    const detailRow = screen.getByText(detail.transcript[0].text).closest("tr")!;
    expect(detailRow.previousElementSibling).toBe(activityRow);
    expect(detailRow.previousElementSibling).toHaveTextContent(activity.childName);
    expect(detailRow.querySelector("td")).toHaveAttribute("colspan", "8");
    expect(screen.getByRole("heading", { name: "장면별 결과" })).toBeVisible();
    expect(screen.getAllByText("판정 이유").length).toBeGreaterThan(0);
    expect(screen.queryByText("대화내용 보기")).not.toBeInTheDocument();
    expect(screen.queryByText(/가정 입력 토큰/)).not.toBeInTheDocument();
    expect(screen.queryByRole("spinbutton")).not.toBeInTheDocument();
  });

  it("보호자·기기의 아이 선택을 활동 필터에 전달한다", async () => {
    const source = createMockAdminSource("2026-10-26");
    const activitySpy = vi.spyOn(source, "activities");
    const guardian = (await source.guardians(filter, 0)).items[0];
    const device = guardian.devices[0];
    const child = device.children[0];
    render(<ActivityScreen source={source} filter={filter} />);
    fireEvent.click(screen.getByRole("button", { name: "보호자 · 기기" }));
    const row = (await screen.findByText(guardian.name)).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: "기기 · 아이 보기" }));
    fireEvent.click(screen.getAllByRole("button", { name: "활동 보기↗" })[0]);
    await waitFor(() => expect(activitySpy).toHaveBeenLastCalledWith(
      expect.objectContaining({ childId: child.id, guardianId: guardian.id }),
      0,
      expect.any(AbortSignal),
    ));
    expect(screen.getByRole("button", { name: "아이 선택 해제" })).toBeVisible();
  });

  it("종료 상태를 바꾸면 페이지를 첫 페이지로 돌리고 새 조건을 조회한다", async () => {
    const source = createMockAdminSource("2026-10-26");
    const activitySpy = vi.spyOn(source, "activities");
    render(<ActivityScreen source={source} filter={filter} />);
    fireEvent.click(await screen.findByRole("button", { name: "다음" }));
    await waitFor(() => expect(activitySpy).toHaveBeenLastCalledWith(
      expect.anything(),
      1,
      expect.any(AbortSignal),
    ));
    fireEvent.change(screen.getByLabelText("종료 상태"), { target: { value: "INTERRUPTED" } });
    await waitFor(() => expect(activitySpy).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: "INTERRUPTED" }),
      0,
      expect.any(AbortSignal),
    ));
    expect(await screen.findByRole("button", { name: "이전" })).toBeDisabled();
  });
});

function render(element: ReactElement) {
  return renderView(
    <QueryClientProvider client={createAdminQueryClient()}>
      {element}
    </QueryClientProvider>,
  );
}
