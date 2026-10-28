import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ADMIN_COPY as C } from "@/admin/common/copy";
import { createAdminQueryClient } from "@/admin/common/queries";
import { createMockAdminSource } from "@/admin/data/mock";
import type { AdminFilter } from "@/admin/data/types";
import { OverviewScreen } from "@/admin/features/overview/OverviewScreen";

const filter: AdminFilter = { from: "2026-10-13", to: "2026-10-26", type: "ALL" };

describe("관리자 대시보드", () => {
  it("자유·연습 평균을 구분하고 비용 카드를 새로 추가하지 않는다", async () => {
    render(
      <QueryClientProvider client={createAdminQueryClient()}>
        <OverviewScreen source={createMockAdminSource("2026-10-26")} filter={filter} />
      </QueryClientProvider>,
    );
    expect(await screen.findByText(C.averageFree)).toBeVisible();
    expect(screen.getByText(C.averagePractice)).toBeVisible();
    expect(screen.queryByText(C.costTotal)).not.toBeInTheDocument();
  });
});
