import { describe, expect, it } from "vitest";
import { liveTalkingLine } from "@/shared/copy/ko";
import { attachParticle, finalConsonantIndex, hasFinalConsonant } from "@/shared/copy/particle";

// 받침 조사 시험 (U1 3.5 Step 41 · 문구표 '{아이}가 / 민준이' 꼴 · C.S-12.12.2)

describe("particle — 받침 판정", () => {
  it("마지막 한글 음절의 종성 순번을 돌려준다", () => {
    expect(finalConsonantIndex("민준")).toBe(4); // ㄴ
    expect(finalConsonantIndex("서아")).toBe(0);
    expect(finalConsonantIndex("하늘 ")).toBe(8); // 끝 공백은 무시, ㄹ
  });

  it("한글 음절이 아니거나 빈 이름이면 null이다", () => {
    expect(finalConsonantIndex("")).toBeNull();
    expect(finalConsonantIndex("   ")).toBeNull();
    expect(finalConsonantIndex("Mia")).toBeNull();
    expect(finalConsonantIndex("민준3")).toBeNull();
    expect(finalConsonantIndex("ㄱ")).toBeNull();
    expect(hasFinalConsonant("Mia")).toBeNull();
  });

  it("받침 유무를 참 · 거짓으로 알려 준다", () => {
    expect(hasFinalConsonant("민준")).toBe(true);
    expect(hasFinalConsonant("서아")).toBe(false);
  });
});

describe("particle — 조사 붙이기", () => {
  it.each([
    ["민준", "이/가", "민준이"],
    ["서아", "이/가", "서아가"],
    ["하늘", "은/는", "하늘은"],
    ["서아", "은/는", "서아는"],
    ["민준", "을/를", "민준을"],
    ["서아", "을/를", "서아를"],
    ["민준", "과/와", "민준과"],
    ["서아", "과/와", "서아와"],
    ["민준", "으로/로", "민준으로"],
    ["서아", "으로/로", "서아로"],
    ["하늘", "으로/로", "하늘로"],
  ] as const)("%s + %s → %s", (word, pair, expected) => {
    expect(attachParticle(word, pair)).toBe(expected);
  });

  it("한글이 아니면 두 꼴을 함께 쓴다", () => {
    expect(attachParticle("Mia", "이/가")).toBe("Mia이(가)");
    expect(attachParticle("", "은/는")).toBe("은(는)");
  });

  it("12 진행 문구가 받침에 맞는 조사를 쓴다", () => {
    expect(liveTalkingLine("민준")).toBe("민준이 이야기하고 있어요");
    expect(liveTalkingLine("하늘")).toBe("하늘이 이야기하고 있어요");
    expect(liveTalkingLine("서아")).toBe("서아가 이야기하고 있어요");
  });
});
