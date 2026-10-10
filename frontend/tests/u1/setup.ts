// U1 프런트 시험 공통 준비 (U1 3.5 Step 6): jest-dom 단언을 Vitest expect에 더하고, 시험마다 렌더 결과를 지운다.
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => {
  cleanup();
});
