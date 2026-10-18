import { notFound } from "next/navigation";
import { connection } from "next/server";
import { DevLoginScreen } from "@/parent/features/login/DevLoginScreen";

// /parent/dev-login — 시험 로그인 화면(U1 3.5 Step 40 · J119 · BR8.4 · SD-14).
// 기동 환경 변수 MUJOONG_DEVLOGIN_ENABLED가 정확히 "true"일 때만 보이고 그 밖에는 없는 화면(404)이다.
// 서버도 dev 프로필 + enabled가 아니면 POST /api/dev/login을 404로 막는다(두 겹).
const DEV_LOGIN_ENV = "MUJOONG_DEVLOGIN_ENABLED";

export default async function ParentDevLoginPage() {
  await connection();
  if (process.env[DEV_LOGIN_ENV] !== "true") {
    notFound();
  }
  return <DevLoginScreen />;
}
