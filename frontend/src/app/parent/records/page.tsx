import { RESULT_TEMP_COPY as C } from "@/shared/copy/result";
import { Suspense } from "react";
import { RecordsScreen } from "@/parent/features/records/RecordsScreen";
export default function ParentRecordsPage() { return <Suspense fallback={<p>{C.readingRecords}</p>}><RecordsScreen /></Suspense>; }
