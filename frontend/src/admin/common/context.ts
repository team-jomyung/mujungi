"use client";

import { createContext, useContext } from "react";
import type { AdminDataSource, AdminFilter } from "../data/types";

export const AdminContext = createContext<{ source: AdminDataSource; filter: AdminFilter } | null>(null);

export function useAdminContext() {
  const context = useContext(AdminContext);
  if (!context) throw new Error("관리자 조회 문맥이 없습니다.");
  return context;
}
