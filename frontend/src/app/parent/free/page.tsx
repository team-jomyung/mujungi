import { FreeTalkScreen } from "@/parent/features/free/FreeTalkScreen";

// /parent/free — 08 자유대화 준비(U1 3.5 Step 40). ?failed=1이면 12에서 FAILED로 돌아온 것이다(사유 카드).
// U4a: &reason=<실패 코드>가 있으면 그 사유 문구를 보인다.
interface FreePageProps {
  readonly searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function ParentFreePage({ searchParams }: FreePageProps) {
  const params = await searchParams;
  const reason = typeof params.reason === "string" ? params.reason : null;
  return <FreeTalkScreen returnedFailed={params.failed === "1"} failureCode={reason} />;
}
