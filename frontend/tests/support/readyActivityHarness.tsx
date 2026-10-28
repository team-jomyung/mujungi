import type { ReactElement } from "react";
import { parseHome } from "@/parent/common/api/types";
import { homeBody, jsonResponse } from "./fixtures";
import { renderParent as renderHarness, type RenderParentOptions } from "./parentHarness";

/** Existing form/mutation tests assume a connected device. Availability changes have their own integration tests. */
export function renderParent(ui: ReactElement, options: RenderParentOptions) {
  return renderHarness(ui, {
    ...options,
    handler: (path, init) => path === "/api/home" ? jsonResponse(200, homeBody("STABLE")) : options.handler(path, init),
    prepare: client => {
      client.setQueryData(["home"], parseHome(homeBody("STABLE")));
      options.prepare?.(client);
    },
  });
}
