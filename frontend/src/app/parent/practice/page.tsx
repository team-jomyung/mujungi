import { PracticeScreen } from "@/parent/features/practice/PracticeScreen";

// U5b: 시작 실패 뒤에도 18에서 목표를 고르고 다시 시작한다.
export default async function ParentPracticePage({ searchParams = Promise.resolve({}) }: {
  searchParams?: Promise<Record<string, string | string[] | undefined>>;
} = {}) {
  const params = await searchParams;
  return <PracticeScreen returnedFailed={params.failed === "1"} failureCode={typeof params.reason === "string" ? params.reason : null} />;
}
