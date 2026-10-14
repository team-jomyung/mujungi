import type { NextConfig } from "next";

// 무중 웹 Next.js 설정 (U1 3.5 Step 4 · tech-stack 2.2 · R4-16 · SD-11)
// - standalone 출력: 이미지가 .next/standalone/server.js만으로 돈다(HOSTNAME=0.0.0.0 · PORT=3000은 Compose가 준다).
// - X-Powered-By 헤더를 내지 않는다.
// - 시간 설정(mujoong.timing.*)은 빌드 때 굽지 않고 서버 컴포넌트가 기동 환경 변수에서 읽는다(NFR6.1 · LC-2).
// - 기기 서비스 워커 · PWA는 없다(NFR10.4). U7 보호자 알림 전용 SW는 Next 정적 JS 라우트다.
const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  reactStrictMode: true,
};

export default nextConfig;
