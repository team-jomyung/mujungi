import { notFound } from "next/navigation";
import { Live12 } from "@/parent/screens/Live12";

// /parent/sessions/[sessionId] — 12 진행 + P1 끝내기(U1 3.5 Step 40 · BR6.9 · BR6.15).
// sessionId는 UUID 형식일 때만 받는다(그 밖은 404, 서버로 보내지 않는다).
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

interface LivePageProps {
  readonly params: Promise<{ sessionId: string }>;
}

export default async function ParentLivePage({ params }: LivePageProps) {
  const { sessionId } = await params;
  if (!UUID_PATTERN.test(sessionId)) {
    notFound();
  }
  return <Live12 sessionId={sessionId} />;
}
