import { notFound } from "next/navigation";
import { RecordDetailScreen } from "@/parent/features/records/RecordDetailScreen";
export default async function ParentRecordPage({ params }: { params: Promise<{ sessionId: string }> }) { const { sessionId } = await params; if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(sessionId)) notFound(); return <RecordDetailScreen sessionId={sessionId} />; }
