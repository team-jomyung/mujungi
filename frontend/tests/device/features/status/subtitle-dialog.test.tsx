import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SubtitleDialog } from "@/device/features/status/SubtitleDialog";
import { PROPOSED_COPY } from "@/shared/copy/ko";

// 기기 게임식 대화창(docs/ui/device-subtitle.md): 말하는 중 = 자막을 한 글자씩, 듣는 중 = 아이 차례, 생각 중 = 점, 그 밖 = 내림.

describe("SubtitleDialog", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("말하는 중에는 자막을 한 글자씩 써서 다 쓰면 끝 표시를 한다", () => {
    render(<SubtitleDialog display="speaking" subtitle="안녕 하늘" />);
    const dialog = screen.getByTestId("t19-dialog");
    expect(dialog.dataset.kind).toBe("say");
    expect(dialog.textContent).toContain(PROPOSED_COPY.device.dialogSpeaker);
    expect(dialog.querySelector(".mj-dialog__text")?.textContent).toBe("");
    act(() => {
      vi.advanceTimersByTime(45 * 2);
    });
    expect(dialog.querySelector(".mj-dialog__text")?.textContent).toBe("안녕");
    act(() => {
      vi.advanceTimersByTime(45 * 10);
    });
    expect(dialog.querySelector(".mj-dialog__text")?.textContent).toBe("안녕 하늘");
    expect(dialog.dataset.done).toBe("true");
  });

  it("화면 읽기용 글은 처음부터 다 쓴 문장 하나다", () => {
    render(<SubtitleDialog display="speaking" subtitle="같이 놀자" />);
    expect(screen.getByText("같이 놀자", { selector: ".mj-dialog__sr" })).toBeTruthy();
  });

  it("자막이 없으면 말하는 중에도 창을 내린다", () => {
    render(<SubtitleDialog display="speaking" subtitle={null} />);
    expect(screen.getByTestId("t19-dialog").dataset.kind).toBe("none");
  });

  it("듣는 중은 아이 차례 창, 생각 중은 생각 창, 대기는 내린 창이다", () => {
    const { rerender } = render(<SubtitleDialog display="listening" subtitle={null} />);
    expect(screen.getByTestId("t19-dialog").dataset.kind).toBe("turn");
    expect(screen.getByTestId("t19-dialog").textContent).toContain(PROPOSED_COPY.device.dialogTurnBody);
    rerender(<SubtitleDialog display="thinking" subtitle={null} />);
    expect(screen.getByTestId("t19-dialog").dataset.kind).toBe("think");
    rerender(<SubtitleDialog display="standby" subtitle={null} />);
    expect(screen.getByTestId("t19-dialog").dataset.kind).toBe("none");
  });

  it("자막이 남아 있으면 묶음 사이에 생각 중 · 듣는 중으로 잠깐 바뀌어도 말 창을 바꾸지 않는다(깜빡임 방지)", () => {
    const { rerender } = render(<SubtitleDialog display="speaking" subtitle="같이 놀자" />);
    rerender(<SubtitleDialog display="thinking" subtitle="같이 놀자" />);
    expect(screen.getByTestId("t19-dialog").dataset.kind).toBe("say");
    rerender(<SubtitleDialog display="reconnecting" subtitle="같이 놀자" />);
    expect(screen.getByTestId("t19-dialog").dataset.kind).toBe("none");
  });

  it("앞 글에 이어 붙은 글은 처음부터가 아니라 이어서 쓴다", () => {
    const { rerender } = render(<SubtitleDialog display="speaking" subtitle="안녕" />);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    rerender(<SubtitleDialog display="speaking" subtitle="안녕 하늘" />);
    expect(screen.getByTestId("t19-dialog").querySelector(".mj-dialog__text")?.textContent).toBe("안녕");
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByTestId("t19-dialog").querySelector(".mj-dialog__text")?.textContent).toBe("안녕 하늘");
  });

  it("새 자막이 오면 처음부터 다시 쓴다", () => {
    const { rerender } = render(<SubtitleDialog display="speaking" subtitle="첫 문장" />);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    rerender(<SubtitleDialog display="speaking" subtitle="둘째" />);
    expect(screen.getByTestId("t19-dialog").querySelector(".mj-dialog__text")?.textContent).toBe("");
  });
});
