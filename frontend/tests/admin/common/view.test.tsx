import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { createAdminQueryClient, useAdminQuery } from "@/admin/common/queries";
import { describe, expect, it, vi } from "vitest";
import { DateFilter } from "@/admin/common/DateFilter";
import { AdminToolbar } from "@/admin/common/AdminToolbar";
import { StatusBadge } from "@/admin/common/Badge";
import { SceneResults } from "@/admin/common/SceneResults";
import { ResourceState } from "@/admin/common/ResourceState";
import { AdminDataError } from "@/admin/data/errors";
import { createMockAdminSource } from "@/admin/data/mock";
import { CostScreen } from "@/admin/features/cost/CostScreen";
import { OverviewScreen } from "@/admin/features/overview/OverviewScreen";
import { ADMIN_COPY as C } from "@/admin/common/copy";

const filter = { from: "2026-10-20", to: "2026-10-26", type: "ALL" as const };
describe("관리자 화면 공통 동작", () => {
  it("직접 선택 기간을 검증하고 날짜 입력으로 적용한다", () => {
    const change = vi.fn();
    render(<DateFilter filter={filter} today="2026-10-26" onChange={change} />);
    fireEvent.click(screen.getByRole("button", { name: /조회 기간/ }));
    fireEvent.change(screen.getByLabelText(C.from), { target: { value: "2026-10-25" } });
    fireEvent.change(screen.getByLabelText(C.to), { target: { value: "2026-10-24" } });
    fireEvent.click(screen.getByRole("button", { name: C.apply }));
    expect(change).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(C.dateError);
    fireEvent.change(screen.getByLabelText(C.to), { target: { value: "2026-10-27" } });
    fireEvent.click(screen.getByRole("button", { name: C.apply }));
    expect(change).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(C.dateError);
    fireEvent.change(screen.getByLabelText(C.to), { target: { value: "2026-10-26" } });
    fireEvent.click(screen.getByRole("button", { name: C.apply }));
    expect(change).toHaveBeenCalledWith({ ...filter, from: "2026-10-25" });
  });
  it("검색어는 계약의 최대 50자로 제한하고 검색 시 공백을 정리한다", () => {
    const change = vi.fn();
    render(<AdminToolbar filter={filter} effectiveFilter={filter} quality={false} onChange={change} />);
    const input = screen.getByRole("searchbox", { name: C.search });
    expect(input).toHaveAttribute("maxlength", "50");
    fireEvent.change(input, { target: { value: "  아이 01  " } });
    fireEvent.click(screen.getByRole("button", { name: C.searchAction }));
    expect(change).toHaveBeenLastCalledWith({ ...filter, query: "아이 01" });
  });
  it("완료 상태라도 종료 확인 여부를 구분하고 중단 원인을 연결로 한정하지 않는다", () => {
    const { rerender } = render(<StatusBadge status="COMPLETED" endCategory="ENDED_UNCONFIRMED" />);
    expect(screen.getByText("종료 확인 안 됨")).toBeVisible();
    expect(screen.queryByText("정상 종료")).not.toBeInTheDocument();
    rerender(<StatusBadge status="INTERRUPTED" endCategory="INTERRUPTED" />);
    expect(screen.getByText("진행 중 중단")).toBeVisible();
    expect(screen.queryByText("연결 중단")).not.toBeInTheDocument();
    rerender(<StatusBadge status="FAILED_TO_START" endCategory="DECLINED" />);
    expect(screen.getByText("아이가 참여하지 않음")).toBeVisible();
  });
  it("장면의 제안 전 종료와 확인 불가를 구분하고 저장되지 않은 횟수는 미확인이다", async () => {
    const source = createMockAdminSource("2026-10-26");
    const quality = await source.quality({ ...filter, type: "PRACTICE" });
    const scene = quality.records.flatMap(record => record.scenes)[0];
    expect(scene).toBeDefined();
    render(<SceneResults scenes={[
      { ...scene, id: "not-offered", title: "제안 전에 끝난 장면", helpOffer: "NOT_OFFERED", helpResponse: null, retryCount: null, reExplanationCount: null },
      { ...scene, id: "unknown", title: "제안 상태를 모르는 장면", helpOffer: "UNKNOWN", helpResponse: null },
    ]} />);
    const notOffered = screen.getByRole("heading", { name: "제안 전에 끝난 장면" }).closest("article")!;
    const unknown = screen.getByRole("heading", { name: "제안 상태를 모르는 장면" }).closest("article")!;
    expect(within(notOffered).getAllByText(C.notOffered)).toHaveLength(2);
    expect(within(unknown).getAllByText(C.responseUnknown)).toHaveLength(2);
    expect(within(notOffered).getAllByText(C.unknown)).toHaveLength(2);
    expect(screen.queryByText("미확인회")).not.toBeInTheDocument();
  });
  it("로그인 필요와 권한 없음을 구분하고 로그인 반복 이동을 막는다", () => {
    const { rerender } = render(<ResourceState loading={false} error={new AdminDataError("unauthenticated")} />);
    expect(screen.getByRole("link", { name: C.login })).toHaveAttribute("href", "/parent/login");
    rerender(<ResourceState loading={false} error={new AdminDataError("forbidden")} />);
    expect(screen.queryByRole("link", { name: C.login })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: C.forbidden })).toBeInTheDocument();
  });
  it("자유대화와 연습대화 평균을 구분한다", async () => {
    render(<QueryClientProvider client={createAdminQueryClient()}><OverviewScreen source={createMockAdminSource("2026-10-26")} filter={filter} /></QueryClientProvider>);
    expect(await screen.findByText(C.averageFree)).toBeInTheDocument();
    expect(screen.getByText(C.averagePractice)).toBeInTheDocument();
  });
  it("비용 상세에서 캐시 사용량을 보여주고 수동 토큰 입력은 없다", async () => {
    render(<QueryClientProvider client={createAdminQueryClient()}><CostScreen source={createMockAdminSource("2026-10-26")} filter={filter} /></QueryClientProvider>);
    const detailButtons = await screen.findAllByRole("button", { name: /상세 보기/ });
    fireEvent.click(detailButtons[0]);
    const detail = await screen.findByRole("region", { name: C.costDetail });
    expect(await within(detail).findAllByText(C.cacheWrite)).not.toHaveLength(0);
    expect(within(detail).queryByRole("spinbutton")).not.toBeInTheDocument();
  });
  it("조회 조건이 바뀌면 이전 값을 숨기고 늦은 응답을 무시한다", async () => {
    let finishOld!: (value: string) => void;
    const old = new Promise<string>((resolve) => { finishOld = resolve; });
    function Harness({ id }: { id: string }) {
      const query = useAdminQuery(["test", id], () => id === "old" ? old : Promise.resolve("new-result"));
      return <p>{query.loading ? "loading" : query.data}</p>;
    }
    const client = createAdminQueryClient();
    const { rerender } = render(<QueryClientProvider client={client}><Harness id="old" /></QueryClientProvider>);
    await waitFor(() => expect(client.getQueryCache().getAll()).toHaveLength(1));
    rerender(<QueryClientProvider client={client}><Harness id="new" /></QueryClientProvider>);
    await screen.findByText("new-result");
    finishOld("old-result");
    await waitFor(() => expect(screen.queryByText("old-result")).not.toBeInTheDocument());
  });
});
