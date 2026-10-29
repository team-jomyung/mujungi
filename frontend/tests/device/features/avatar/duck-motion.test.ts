import { expect, it } from "vitest";
import { motionFor } from "@/device/features/avatar/duckClips";

// 무중이 발화 중(묶음 사이 틈 포함)에는 말하기 동작을 이어 가고, 발화가 끝나면 상태 동작으로 돌아간다.

it("발화 중이면 묶음 사이 틈에 상태가 듣는 중 · 생각 중으로 바뀌어도 말하기 동작을 유지한다", () => {
  expect(motionFor("speaking", true)).toBe("talk");
  expect(motionFor("listening", true)).toBe("talk");
  expect(motionFor("thinking", true)).toBe("talk");
  expect(motionFor("standby", true)).toBe("talk");
  expect(motionFor("closing", true)).toBe("talk");
});

it("발화가 끝나면 상태에 맞는 동작으로 돌아간다", () => {
  expect(motionFor("listening", false)).toBe("look");
  expect(motionFor("standby", false)).toBe("wave");
  expect(motionFor("closing", false)).toBe("laugh");
  expect(motionFor("speaking")).toBe("talk");
});

it("꺼짐 · 마이크 · 연결 화면은 발화 유지와 관계없이 상태 동작이다", () => {
  expect(motionFor("off", true)).toBe("idle");
  expect(motionFor("mic-denied", true)).toBe("sad");
  expect(motionFor("reconnecting", true)).toBe("steps");
  expect(motionFor("connecting", true)).toBe("steps");
});
