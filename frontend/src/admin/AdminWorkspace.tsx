"use client";

import { useState, type ReactNode } from "react";
import type { AdminMode } from "./config";
import { AdminProviders } from "./common/AdminProviders";
import { AdminSession } from "./common/AdminSession";
import { AdminShell } from "./common/AdminShell";
import type { PreviewState } from "./common/navigation";

export function AdminWorkspace({ mode, today, children }: { mode: AdminMode; today: string; children: ReactNode }) {
  const [previewState, setPreviewState] = useState<PreviewState>("normal");
  const scope = ["admin", mode, previewState, today];
  return (
    <AdminProviders key={JSON.stringify(scope)} scope={scope}>
      <AdminSession mode={mode} previewState={previewState} today={today}>
        <AdminShell mode={mode} today={today} previewState={previewState} onPreview={setPreviewState}>
          {children}
        </AdminShell>
      </AdminSession>
    </AdminProviders>
  );
}
