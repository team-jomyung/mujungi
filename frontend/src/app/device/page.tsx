import type { Metadata } from "next";
import { DeviceApp } from "@/device/DeviceApp";
import "@/device/device.css";

// /device 아이 기기 웹 (U1 3.5 Step 38 · BR9.1 · AC6.2.9). ?debug=1이면 상태 로그를 보인다.
// 비밀 · 토큰은 HttpOnly 쿠키로만 오가고 이 페이지는 아무것도 받지 않는다(서버 컴포넌트는 debug 질의만 읽는다).

export const metadata: Metadata = {
  title: "무중",
};

interface DevicePageProps {
  readonly searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function DevicePage({ searchParams }: DevicePageProps) {
  const params = await searchParams;
  const debug = params.debug === "1";
  return <DeviceApp debug={debug} />;
}
