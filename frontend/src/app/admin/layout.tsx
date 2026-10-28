import type { Metadata } from "next";
import type { ReactNode } from "react";
import { AdminWorkspace } from "@/admin/AdminWorkspace";
import { readAdminMode } from "@/admin/config";
import { koreaDate } from "@/admin/data/dates";
import "@/admin/admin.css";

export const metadata: Metadata = { title: "무중 · 관리자", robots: { index: false, follow: false } };

export default function AdminLayout({ children }: { children: ReactNode }) {
  return <AdminWorkspace mode={readAdminMode(process.env)} today={koreaDate()}>{children}</AdminWorkspace>;
}
