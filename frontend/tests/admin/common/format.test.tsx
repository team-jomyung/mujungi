import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { duration, money, times } from "@/admin/common/format";
import { createAdminQueryClient } from "@/admin/common/queries";
import { createMockAdminSource } from "@/admin/data/mock";
import type { AdminDataSource } from "@/admin/data/types";
import { ActivityScreen } from "@/admin/features/activity/ActivityScreen";
import { CostScreen } from "@/admin/features/cost/CostScreen";
import { OverviewScreen } from "@/admin/features/overview/OverviewScreen";

describe("관리자 표시 형식", () => {
  it.each([
    ["0", "$0.00"],
    ["0.00000001", "< $0.000001"],
    ["0.0000004", "< $0.000001"],
    ["0.0000005", "$0.000001"],
    ["0.000123", "$0.000123"],
    ["0.5", "$0.50"],
    ["0.99999999", "$1.00"],
    ["1.005", "$1.01"],
    ["12.345678", "$12.35"],
    ["1234.5", "$1,234.50"],
    ["9007199254740993.125", "$9,007,199,254,740,993.13"],
    [null, "미확인"],
  ])("USD %s는 %s로 표시한다", (value, expected) => {
    expect(money(value)).toBe(expected);
  });

  it.each([
    [45, "45초"],
    [59.6, "1분 0초"],
    [60, "1분 0초"],
    [89.6, "1분 30초"],
    [null, "미확인"],
  ])("시간 %s는 반올림한 뒤 %s로 나눈다", (value, expected) => {
    expect(duration(value)).toBe(expected);
  });

  it("횟수의 미확인은 0회나 미확인회로 표시하지 않는다", () => {
    expect(times(null)).toBe("미확인");
    expect(times(0)).toBe("0회");
    expect(times(3)).toBe("3회");
  });

  it("같은 경과 시간을 대시보드·활동·비용에서 동일하게 표시한다", async () => {
    const base = createMockAdminSource("2026-10-26");
    const source: AdminDataSource = {
      ...base,
      overview: async (...args) => {
        const result = await base.overview(...args);
        return { ...result, averageSeconds: { all: 59.6, freeTalk: 59.6, practice: 59.6 } };
      },
      activities: async (...args) => {
        const result = await base.activities(...args);
        return { ...result, items: result.items.map((item) => ({ ...item, durationSeconds: 59.6 })) };
      },
      costs: async (...args) => {
        const result = await base.costs(...args);
        return { ...result, activities: { ...result.activities, items: result.activities.items.map((item) => ({ ...item, voiceSeconds: 59.6 })) } };
      },
    };
    const filter = { from: "2026-10-20", to: "2026-10-26", type: "ALL" as const };
    render(
      <QueryClientProvider client={createAdminQueryClient()}>
        <section aria-label="대시보드"><OverviewScreen source={source} filter={filter} /></section>
        <section aria-label="활동"><ActivityScreen source={source} filter={filter} /></section>
        <section aria-label="비용"><CostScreen source={source} filter={filter} /></section>
      </QueryClientProvider>,
    );
    for (const name of ["대시보드", "활동", "비용"]) {
      const region = screen.getByRole("region", { name });
      expect((await within(region).findAllByText("1분 0초")).length).toBeGreaterThan(0);
      expect(within(region).queryByText("0분 60초")).not.toBeInTheDocument();
      expect(within(region).queryByText("60초")).not.toBeInTheDocument();
    }
  });
});
