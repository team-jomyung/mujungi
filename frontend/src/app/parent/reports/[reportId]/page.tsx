import { notFound } from "next/navigation";
import { ReportScreen } from "@/parent/features/reports/ReportScreen";
export default async function ParentReportPage({ params }: { params: Promise<{ reportId: string }> }) { const { reportId } = await params; if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(reportId)) notFound(); return <ReportScreen reportId={reportId} />; }
