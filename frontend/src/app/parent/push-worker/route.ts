import { PUSH_WORKER_SOURCE } from "@/parent/push/pushWorker";

// 보호자 전용 서비스 워커. Next standalone에 포함하며 별도 public 복사나 PWA 플러그인은 없다.
export const dynamic = "force-static";

export function GET(): Response {
  return new Response(PUSH_WORKER_SOURCE, {
    headers: {
      "Content-Type": "text/javascript; charset=utf-8",
      "Cache-Control": "no-cache",
      "X-Content-Type-Options": "nosniff",
    },
  });
}
