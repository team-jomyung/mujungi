import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { connection } from "next/server";
import { OAUTH_STATE_COOKIE, readGoogleConfig, singleParam, stateMatches } from "@/parent/oauth";
import { LoginCallbackScreen } from "@/parent/features/login/LoginCallbackScreen";

// /parent/login/callback — 구글이 돌려보낸 자리 (U2 · US1.1 · AC1.1.4 · U2-J4)
// 취소(error) · code 없음 · state가 쿠키와 다름 · 구글 설정 없음이면 01 실패 토스트로 돌려보내고, 맞으면 화면이 code를 Core에 교환한다.
interface CallbackPageProps {
  readonly searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function ParentLoginCallbackPage({ searchParams }: CallbackPageProps) {
  await connection();
  const params = await searchParams;
  const code = singleParam(params.code);
  const state = singleParam(params.state) ?? undefined;
  const expected = (await cookies()).get(OAUTH_STATE_COOKIE)?.value;
  const config = readGoogleConfig(process.env);
  if (params.error !== undefined || code === null || config === null || !stateMatches(state, expected)) {
    redirect("/parent/login?failed=1");
  }
  return <LoginCallbackScreen code={code} redirectUri={config.redirectUri} />;
}
