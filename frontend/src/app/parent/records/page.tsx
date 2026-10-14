import { RESULT_TEMP_COPY as C } from "@/shared/copy/result";
import { Suspense } from "react";
import { Records26 } from "@/parent/screens/Records26";
export default function ParentRecordsPage() { return <Suspense fallback={<p>{C.readingRecords}</p>}><Records26 /></Suspense>; }
