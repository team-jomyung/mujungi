import type { Metadata, Viewport } from "next";
import { connection } from "next/server";
import type { ReactNode } from "react";
import { TimingProvider } from "@/shared/timing/TimingProvider";
import { readTimingFromEnv } from "@/shared/timing/readTimingFromEnv";
import "@/shared/styles/tokens.css";
import "@/shared/toast/toast.css";

// 루트 레이아웃 (U1 3.5 Step 4 · Step 37): 한 앱에 /parent · /device를 둔다. 문서 언어는 한국어.
// 요청마다 렌더링한다(connection): ① 시간 설정을 기동 환경 변수에서 읽어 첫 응답에 싣고(빌드 때 굽지 않음, NFR6.1 · LC-2 · J94)
// ② proxy.ts가 만든 요청별 nonce를 Next.js가 스크립트에 붙이게 한다(SD-2 · J122).
export const metadata: Metadata = {
  title: "무중",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
};

export default async function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  await connection();
  const timing = readTimingFromEnv();
  return (
    <html lang="ko">
      <body>
        <TimingProvider timing={timing}>{children}</TimingProvider>
      </body>
    </html>
  );
}
