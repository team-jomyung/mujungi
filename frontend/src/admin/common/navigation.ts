import { ADMIN_COPY as C } from "./copy";

export type AdminSection = keyof typeof C.nav;
export type PreviewState = "normal" | "empty" | "unauthenticated" | "forbidden" | "network" | "loading";

export const ADMIN_NAV: { section: AdminSection; href: string }[] = [
  { section: "overview", href: "/admin" },
  { section: "activity", href: "/admin/activities" },
  { section: "quality", href: "/admin/quality" },
  { section: "cost", href: "/admin/costs" },
];
