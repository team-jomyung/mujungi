import { vi } from "vitest";

// next/navigation 대역 (U1 3.5 Step 41 · 42 · unit-test-instructions '라우팅은 next/navigation 대역')
// 시험 파일은 vi.mock("next/navigation", async () => (await import("../support/nextNavigation")).nextNavigationMock)로
// 이 모듈을 쓰고, 같은 router 객체로 이동을 단언한다.

export const router = {
  push: vi.fn<(href: string) => void>(),
  replace: vi.fn<(href: string) => void>(),
  back: vi.fn<() => void>(),
  forward: vi.fn<() => void>(),
  refresh: vi.fn<() => void>(),
  prefetch: vi.fn<(href: string) => void>(),
};

export function resetRouter(): void {
  for (const fn of Object.values(router)) {
    fn.mockReset();
  }
}

export const nextNavigationMock = {
  useRouter: () => router,
};
