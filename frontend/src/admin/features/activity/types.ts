import type { AdminDataSource, AdminFilter } from "../../data/types";

export interface ActivityScreenProps {
  source: AdminDataSource;
  filter: AdminFilter;
}

export interface SelectedChild {
  id: string;
  name: string;
  guardianId: string;
  deviceId: string;
}

export type ActivityTab = "all" | "guardians";
