import { expect, it } from "vitest";
import { DEVICE_SCENES, pickScene } from "@/device/features/scene/deviceScene";

// 아이 기기 배경은 새로고침마다 초원 · 아이 방 · 달에서 본 지구 중 하나를 고른다.

it("무작위 값을 셋으로 나눠 초원 · 아이 방 · 달을 고른다", () => {
  expect(DEVICE_SCENES).toEqual(["meadow", "room", "moon"]);
  expect(pickScene(() => 0)).toBe("meadow");
  expect(pickScene(() => 0.33)).toBe("meadow");
  expect(pickScene(() => 0.34)).toBe("room");
  expect(pickScene(() => 0.66)).toBe("room");
  expect(pickScene(() => 0.67)).toBe("moon");
  expect(pickScene(() => 0.999)).toBe("moon");
});

it("범위를 벗어난 값이 와도 목록 안의 배경을 고른다", () => {
  expect(pickScene(() => 1)).toBe("moon");
  expect(pickScene(() => -0.2)).toBe("meadow");
});
