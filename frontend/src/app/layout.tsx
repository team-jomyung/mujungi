import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "@/shared/styles/tokens.css";

// 루트 레이아웃 (U1 3.5 Step 4): 한 앱에 /parent · /device를 둔다. 문서 언어는 한국어.
export const metadata: Metadata = {
  title: "무중",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
