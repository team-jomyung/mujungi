import type { Metadata } from "next";
import type { ReactNode } from "react";
import { ParentProviders } from "@/parent/ParentProviders";
import "@/parent/parent.css";

// /parent 보호자 웹 레이아웃 (U1 3.5 Step 40 · PD-12 · interaction-spec 6절 · 가정 C4)
// 폰 세로 한 열(최대 480px)이고 큰 화면은 가운데 정렬한다. TanStack Query · API 클라이언트 · 토스트는 여기 한 번 둔다.
export const metadata: Metadata = {
  title: "무중",
};

export default function ParentLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <div className="mj-parent">
      <ParentProviders>{children}</ParentProviders>
    </div>
  );
}
