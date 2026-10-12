import { Login01 } from "@/parent/screens/Login01";

// /parent/login — 01 보호자 로그인(U2 · US1.1). ?failed=1이면 실패 토스트, ?deleting=1이면 '삭제 처리 중' 안내(AC1.6.4).
interface LoginPageProps {
  readonly searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function ParentLoginPage({ searchParams }: LoginPageProps) {
  const params = await searchParams;
  return <Login01 failed={params.failed === "1"} deleting={params.deleting === "1"} />;
}
