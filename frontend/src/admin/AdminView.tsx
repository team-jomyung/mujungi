"use client";
import { useAdminContext } from "./common/context";
import type { AdminSection } from "./common/navigation";
import { ActivityScreen } from "./features/activity/ActivityScreen";
import { CostScreen } from "./features/cost/CostScreen";
import { OverviewScreen } from "./features/overview/OverviewScreen";
import { QualityScreen } from "./features/quality/QualityScreen";

export function AdminView({ section }: { section: AdminSection }) {
  const props = useAdminContext();
  const Screen = { overview: OverviewScreen, activity: ActivityScreen, quality: QualityScreen, cost: CostScreen }[section];
  return <Screen key={JSON.stringify(props.filter)} {...props} />;
}
