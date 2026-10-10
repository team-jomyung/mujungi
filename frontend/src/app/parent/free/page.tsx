import { Free08 } from "@/parent/screens/Free08";

// /parent/free — 08 자유대화 준비(U1 3.5 Step 40 · BR6.1 · BR6.4). ?failed=1이면 12에서 FAILED로 돌아온 것이다(사유 카드, J120).
interface FreePageProps {
  readonly searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function ParentFreePage({ searchParams }: FreePageProps) {
  const params = await searchParams;
  return <Free08 returnedFailed={params.failed === "1"} />;
}
