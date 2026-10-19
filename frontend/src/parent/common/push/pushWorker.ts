import { COPY, PUSH_COPY } from "@/shared/copy/ko";

// U7 · C6 · US1.7: Next 정적 JS 라우트가 이 코드를 그대로 낸다(AudioWorklet과 같은 방식).
// 문구는 ko.ts 한 곳에서 온다. 별도 전역 VM 시험이 실제 JS의 모든 사건 경로를 실행한다.
// tag는 세션+유형: payload에 사건 키를 더하지 않고 같은 유형의 최신 표시로 합친다(보강).
export const PUSH_WORKER_SOURCE = '"use strict";\nconst copy = ' + JSON.stringify({ brand: COPY.brand, ...PUSH_COPY }) + ";\n" + `
function readPayload(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  if (!["VOICE_PROBLEM", "DEVICE_STATUS_UNKNOWN", "ENDED_BY_CONNECTION", "ACTIVITY_COMPLETED"].includes(value.type)) return null;
  if (!["FREE_TALK", "PRACTICE"].includes(value.activityType)) return null;
  if (typeof value.sessionId !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.sessionId)) return null;
  if (typeof value.sentAt !== "string" || !Number.isFinite(Date.parse(value.sentAt))) return null;
  return { type: value.type, activityType: value.activityType, sessionId: value.sessionId, sentAt: value.sentAt };
}
self.addEventListener("push", function(event) {
  let payload;
  try { payload = readPayload(event.data && event.data.json()); } catch (_) { return; }
  if (!payload) return;
  const completed = payload.type === "ACTIVITY_COMPLETED";
  const title = completed ? copy.completedTitle[payload.activityType] : copy.brand;
  const body = completed ? copy.completedBody : copy.problem[payload.type];
  event.waitUntil(self.registration.showNotification(title, {
    body: body, data: payload, tag: "mujoong:" + payload.sessionId + ":" + payload.type
  }));
});
self.addEventListener("notificationclick", function(event) {
  event.notification.close();
  const payload = readPayload(event.notification.data);
  if (!payload) return;
  const target = new URL("/parent?notificationSessionId=" + encodeURIComponent(payload.sessionId), self.location.origin).href;
  event.waitUntil((async function() {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const client of windows) {
      let url;
      try { url = new URL(client.url); } catch (_) { continue; }
      if (url.origin !== self.location.origin || !(url.pathname === "/parent" || url.pathname.startsWith("/parent/"))) continue;
      try {
        const navigated = await client.navigate(target);
        if (navigated) {
          await navigated.focus();
          return;
        }
      } catch (_) { /* 닫힌 창이면 다른 보호자 창 또는 새 창을 쓴다. */ }
    }
    await self.clients.openWindow(target);
  })());
});
`;
