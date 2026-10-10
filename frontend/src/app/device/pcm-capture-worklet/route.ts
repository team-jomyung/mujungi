import { PCM_CAPTURE_WORKLET_SOURCE } from "@/device/audio/pcm-capture.worklet";

// /device/pcm-capture-worklet — 마이크 캡처 AudioWorklet 처리기 코드 (U1 3.5 Step 38 · J121 · J138)
// 같은 origin 정적 JavaScript라 CSP script-src 'self'에 들고, 비밀 · 개인정보가 없다. 빌드 때 한 번 만든다.
export const dynamic = "force-static";

export function GET(): Response {
  return new Response(PCM_CAPTURE_WORKLET_SOURCE, {
    headers: {
      "Content-Type": "text/javascript; charset=utf-8",
      "Cache-Control": "no-cache",
      "X-Content-Type-Options": "nosniff",
    },
  });
}
