import { notFound } from "next/navigation";
import { Done17 } from "@/parent/screens/Done17";

// /parent/sessions/[sessionId]/done — 17 활동 끝(U1 3.5 Step 40 · BR6.10 · BR6.15 · J120).
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

interface DonePageProps {
  readonly params: Promise<{ sessionId: string }>;
}

export default async function ParentDonePage({ params }: DonePageProps) {
  const { sessionId } = await params;
  if (!UUID_PATTERN.test(sessionId)) {
    notFound();
  }
  return <Done17 sessionId={sessionId} />;
}
