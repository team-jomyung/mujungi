import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { CodeInput, emptyCodeCells, isCodeComplete, normalizeCodeText } from "@/parent/components/CodeInput";

// 05a 코드 입력 8칸 시험 (U1 3.5 Step 42 · BR2.8 · BR1.1 · 편차 (d) · AC2.2.1 · interaction-spec 3.7 · 접근성 O2 · U3)
// 자동 대문자, 넣으면 다음 칸, 빈 칸에서 지우면 앞 칸, 8자 붙여넣기, 8칸 미만이면 「등록」 꺼짐, 칸마다 'n번째 글자'.

function Harness({ initial = emptyCodeCells(), invalid = false }: { initial?: string[]; invalid?: boolean }) {
  const [cells, setCells] = useState<string[]>(initial);
  return (
    <div>
      <p id="guide">아이 기기 화면의 8자리 코드</p>
      <CodeInput cells={cells} onChange={setCells} invalid={invalid} describedById="guide" />
      <button type="button" data-testid="submit" disabled={!isCodeComplete(cells)}>
        등록
      </button>
    </div>
  );
}

const cell = (index: number) => screen.getByTestId(`code-cell-${index}`);
const values = () => Array.from({ length: 8 }, (_, index) => (cell(index) as HTMLInputElement).value).join("");

describe("normalizeCodeText · isCodeComplete", () => {
  it("공백 · 하이픈 · 가운뎃점을 지우고 대문자 영숫자만 남긴다(BR1.1)", () => {
    expect(normalizeCodeText("7f3k-92qd")).toBe("7F3K92QD");
    expect(normalizeCodeText(" 7F3K · 92QD ")).toBe("7F3K92QD");
    expect(normalizeCodeText("7F3K–92QD")).toBe("7F3K92QD");
    expect(normalizeCodeText("a!b@c#")).toBe("ABC");
  });

  it("발급 알파벳 밖 글자(0 · 1 · I · L · O)도 그대로 받는다(서버가 404, 편차 (d))", () => {
    expect(normalizeCodeText("o0i1l")).toBe("O0I1L");
  });

  it("8칸이 모두 한 글자일 때만 완성이다", () => {
    expect(isCodeComplete(emptyCodeCells())).toBe(false);
    expect(isCodeComplete(["7", "F", "3", "K", "9", "2", "Q", ""])).toBe(false);
    expect(isCodeComplete("7F3K92QD".split(""))).toBe(true);
    expect(isCodeComplete("7F3K92Q".split(""))).toBe(false);
  });
});

describe("CodeInput", () => {
  it("입력하면 대문자로 바뀌고 다음 칸으로 가며 8칸이 차야 「등록」이 켜진다(AC2.2.1)", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(cell(0));
    await user.keyboard("7f3k92q");
    expect(values()).toBe("7F3K92Q");
    expect(cell(7)).toHaveFocus();
    expect(screen.getByTestId("submit")).toBeDisabled();
    await user.keyboard("d");
    expect(values()).toBe("7F3K92QD");
    expect(screen.getByTestId("submit")).toBeEnabled();
  });

  it("빈 칸에서 지우면 앞 칸을 지우고 그리로 가며, 글자가 있는 칸은 그 칸만 지운다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(cell(0));
    await user.keyboard("ab");
    expect(cell(2)).toHaveFocus();
    await user.keyboard("{Backspace}");
    expect(values()).toBe("A");
    expect(cell(1)).toHaveFocus();
    await user.click(cell(0));
    await user.keyboard("{Backspace}");
    expect(values()).toBe("");
    expect(cell(0)).toHaveFocus();
    await user.keyboard("{Backspace}");
    expect(cell(0)).toHaveFocus();
  });

  it("8자 붙여넣기는 어느 칸에서든 처음부터 채우고 구분 기호를 지운다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(cell(3));
    await user.paste("7f3k · 92qd");
    expect(values()).toBe("7F3K92QD");
    expect(cell(7)).toHaveFocus();
  });

  it("짧게 붙여넣으면 그 칸부터 채우고, 기호뿐이면 아무것도 바꾸지 않는다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(cell(2));
    await user.paste("ab-c");
    expect(values()).toBe("ABC");
    expect((cell(2) as HTMLInputElement).value).toBe("A");
    expect(cell(5)).toHaveFocus();
    await user.paste("--");
    expect(values()).toBe("ABC");
  });

  it("글자가 있는 칸에 이어 치면 새 글자만 남기고, 화살표로 칸을 옮긴다", async () => {
    const user = userEvent.setup();
    render(<Harness initial={["A", "", "", "", "", "", "", ""]} />);
    fireEvent.change(cell(0), { target: { value: "AB" } });
    expect((cell(0) as HTMLInputElement).value).toBe("B");
    expect(cell(1)).toHaveFocus();
    await user.keyboard("{ArrowLeft}");
    expect(cell(0)).toHaveFocus();
    await user.keyboard("{ArrowRight}{ArrowRight}");
    expect(cell(2)).toHaveFocus();
    fireEvent.change(cell(2), { target: { value: "!" } });
    expect((cell(2) as HTMLInputElement).value).toBe("");
  });

  it("칸마다 'n번째 글자' 이름이 있고 묶음은 안내 문장과 이어지며 오류면 aria-invalid다(접근성 O2)", () => {
    render(<Harness invalid />);
    expect(screen.getByLabelText("1번째 글자")).toBe(cell(0));
    expect(screen.getByLabelText("8번째 글자")).toBe(cell(7));
    const group = screen.getByRole("group", { name: "등록 코드 8자리" });
    expect(group).toHaveAttribute("aria-describedby", "guide");
    expect(cell(4)).toHaveAttribute("aria-invalid", "true");
    expect(cell(4)).toHaveAttribute("autocapitalize", "characters");
  });

  it("끝 칸에서 넘치게 친 글자는 버린다", () => {
    render(<Harness initial={["7", "F", "3", "K", "9", "2", "Q", ""]} />);
    fireEvent.change(cell(7), { target: { value: "DXY" } });
    expect(values()).toBe("7F3K92QD");
  });
});
