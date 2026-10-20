import { RESULT_TEMP_COPY as C } from "@/shared/copy/result";
import { Suspense } from "react";
import { ReportRangeScreen } from "@/parent/features/reports/ReportRangeScreen";
export default function ParentReportRangePage() { return <Suspense fallback={<p>{C.readingActivities}</p>}><ReportRangeScreen /></Suspense>; }
