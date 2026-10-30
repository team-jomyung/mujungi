import { useSyncExternalStore } from "react";

// 아이 기기 배경(public/backgrounds/*.svg, 자체 제작 그림: 초원 · 아이 방 · 달에서 본 지구). 페이지를 불러올 때마다 하나를 고르고, 그 페이지 안에서는 바꾸지 않는다.
// 서버 그림에는 배경을 넣지 않고(무작위 값이 서버 · 브라우저에서 달라 화면이 어긋나지 않게) 브라우저에서 고른 뒤 그린다.

export const DEVICE_SCENES = ["meadow", "room", "moon"] as const;
export type DeviceScene = (typeof DEVICE_SCENES)[number];

/** random()은 0 이상 1 미만. 시험에서 값을 넣어 고르는 쪽을 정한다. */
export function pickScene(random: () => number = Math.random): DeviceScene {
  const index = Math.min(DEVICE_SCENES.length - 1, Math.max(0, Math.floor(random() * DEVICE_SCENES.length)));
  return DEVICE_SCENES[index];
}

let chosen: DeviceScene | null = null;
const subscribe = () => () => undefined;
const snapshot = (): DeviceScene => (chosen ??= pickScene());

/** 이번 페이지의 배경. 서버 그림 · 첫 수화 때는 null이고, 브라우저에서는 페이지를 새로 불러올 때마다 새로 고른 값이다. */
export function useDeviceScene(): DeviceScene | null {
  return useSyncExternalStore(subscribe, snapshot, () => null);
}
