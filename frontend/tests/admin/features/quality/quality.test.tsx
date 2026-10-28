import type { ReactElement } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { createAdminQueryClient } from "@/admin/common/queries";
import { fireEvent, render as renderView, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ADMIN_COPY as C, consentLabel } from "@/admin/common/copy";
import { QualityScreen } from "@/admin/features/quality/QualityScreen";
import { QualityCharts } from "@/admin/features/quality/QualityCharts";
import { SceneResults } from "@/admin/common/SceneResults";
import { createMockAdminSource } from "@/admin/data/mock";
import type { AdminFilter } from "@/admin/data/types";

const filter: AdminFilter = { from: "2026-10-13", to: "2026-10-26", type: "ALL" };

describe("관리자 연습 · AI 품질", () => {
  it("계산할 수 없는 수락률을 0%로 표시하지 않는다", async () => {
    render(<QualityScreen source={createMockAdminSource("2026-10-26", { empty: true })} filter={filter} />);
    expect(await screen.findByText("미확인")).toBeVisible();
    expect(screen.queryByText("0%")).not.toBeInTheDocument();
    expect(screen.getByText("조회할 기록이 없습니다")).toBeVisible();
  });

  it("불명확·제안 전 종료·확인 불가를 구분하고 표현 예시의 두 종류를 설명한다", async () => {
    const source = createMockAdminSource("2026-10-26");
    const quality = await source.quality(filter);
    const withHelp = quality.records.find(record => record.scenes.some(scene => scene.helpRecords.length > 0))!;
    render(<QualityScreen source={{ ...source, quality: async () => ({ ...quality, records: [withHelp] }) }} filter={filter} />);
    expect(await screen.findByText("불명확")).toBeVisible();
    expect(screen.getByText(C.notOffered)).toBeVisible();
    expect(screen.getByText(C.responseUnknown)).toBeVisible();
    expect(screen.getByText("표현 예시")).toBeVisible();
    expect(screen.getByText("초점 도움에 포함된 표현 예시")).toBeVisible();
    expect(screen.getByText(/ACCEPT ÷ \(ACCEPT \+ DECLINE\)/)).toBeVisible();
    fireEvent.click(screen.getAllByRole("button", { name: "상세 보기" })[0]);
    expect(screen.getAllByText("판정 이유").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/^제안에 대한 반응: (수락|직접 요청|기록 없음)$/).length).toBeGreaterThan(0);
  });

  it("미응답 null을 횟수로 더하지 않고 서버의 여섯 반응 집계를 표시한다", async () => {
    const quality = await createMockAdminSource("2026-10-26").quality(filter);
    const acceptance = {
      accepted: 1,
      declined: 2,
      requested: 3,
      unclear: 4,
      unanswered: null,
      notOffered: 5,
      unknown: 6,
      rate: 1 / 3,
    };
    render(<QualityCharts quality={{ ...quality, acceptance }} />);
    const reactions = [
      [consentLabel.ACCEPT, 1],
      [consentLabel.DECLINE, 2],
      [consentLabel.REQUESTED, 3],
      [consentLabel.UNCLEAR, 4],
      [C.notOffered, 5],
      [C.responseUnknown, 6],
    ] as const;
    for (const [label, count] of reactions) {
      const row = screen.getByText(label).closest("div")!;
      expect(within(row).getByText(`${count}회`)).toBeVisible();
    }
    expect(screen.queryByText("미확인회")).not.toBeInTheDocument();
  });

  it("최근 기록 범위와 전체 건수를 구분하고 받은 기록 안에서만 페이지를 나눈다", async () => {
    const source = createMockAdminSource("2026-10-26");
    const quality = await source.quality(filter);
    const records = quality.records.slice(0, 9);
    expect(records).toHaveLength(9);
    render(
      <QualityScreen
        source={{ ...source, quality: async () => ({ ...quality, records, recordsTotal: 137, recordsLimit: 100 }) }}
        filter={filter}
      />,
    );
    expect(await screen.findByText("최근 9건 / 전체 137건 · 최대 100건")).toBeVisible();
    const table = screen.getByRole("table", { name: C.practiceCaption });
    expect(within(table).getAllByRole("row")).toHaveLength(9);
    fireEvent.click(screen.getByRole("button", { name: C.next }));
    expect(within(table).getAllByRole("row")).toHaveLength(2);
    expect(screen.getByRole("button", { name: C.next })).toBeDisabled();
    expect(screen.getByText("최근 9건 / 전체 137건 · 최대 100건")).toBeVisible();
  });

  it("실행된 도움이 없어도 장면의 거절 응답을 상세에 보여 준다", async () => {
    const quality = await createMockAdminSource("2026-10-26").quality(filter);
    const scene = quality.records.flatMap(record => record.scenes).find(item => item.helpResponse === "DECLINE")!;
    expect(scene.helpRecords).toHaveLength(0);
    render(<SceneResults scenes={[scene]} showHelp />);
    expect(screen.getByText("도움 제안에 대한 반응")).toBeVisible();
    expect(screen.getByText("거절")).toBeVisible();
    expect(screen.getByText("저장된 도움 실행 기록이 없습니다.")).toBeVisible();
  });

  it("행 바로 아래에서 상세를 펼치고 페이지를 바꾸면 닫는다", async () => {
    render(<QualityScreen source={createMockAdminSource("2026-10-26")} filter={filter} />);
    const button = (await screen.findAllByRole("button", { name: "상세 보기" }))[0];
    const row = button.closest("tr")!;
    fireEvent.click(button);
    const detailId = button.getAttribute("aria-controls")!;
    const detail = document.getElementById(detailId)!;
    expect(detail.closest("tr")?.previousElementSibling).toBe(row);
    expect(detail.closest("td")).toHaveAttribute("colspan", "7");
    fireEvent.click(screen.getByRole("button", { name: "다음" }));
    expect(document.getElementById(detailId)).toBeNull();
  });
});

function render(element: ReactElement) {
  return renderView(
    <QueryClientProvider client={createAdminQueryClient()}>
      {element}
    </QueryClientProvider>,
  );
}
