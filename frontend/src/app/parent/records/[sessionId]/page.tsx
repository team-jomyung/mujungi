import { notFound } from "next/navigation";
import { RecordDetail27 } from "@/parent/screens/RecordDetail27";
export default async function ParentRecordPage({ params }: { params: Promise<{ sessionId: string }> }) { const { sessionId } = await params; if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(sessionId)) notFound(); return <RecordDetail27 sessionId={sessionId} />; }
