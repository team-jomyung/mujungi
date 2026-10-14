import { RESULT_TEMP_COPY as C } from "@/shared/copy/result";
import { Suspense } from "react";
import { ReportRange28 } from "@/parent/screens/ReportRange28";
export default function ParentReportRangePage() { return <Suspense fallback={<p>{C.readingActivities}</p>}><ReportRange28 /></Suspense>; }
