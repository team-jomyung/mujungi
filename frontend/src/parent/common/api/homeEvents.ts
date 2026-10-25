/** Same-origin guardian session cookie; no identity or credential is put in the URL. */
export function subscribeHomeEvents(refresh: () => void, connected: (value: boolean) => void): () => void {
  let source: EventSource | null = null;
  let disposed = false;
  const close = () => { source?.close(); source = null; connected(false); };
  const open = () => {
    if (disposed || document.visibilityState === "hidden" || source !== null || typeof EventSource === "undefined") return;
    source = new EventSource("/api/home/events");
    const current = source;
    source.addEventListener("ready", () => { if (!disposed && source === current) { connected(true); refresh(); } });
    source.addEventListener("home-changed", () => { if (!disposed && source === current) refresh(); });
    source.onerror = () => {
      if (!disposed && source === current) { connected(false); refresh(); }
      // Native EventSource reconnects. Until ready, the existing short polling fallback remains active.
    };
  };
  const visibility = () => { if (document.visibilityState === "hidden") close(); else open(); };
  document.addEventListener("visibilitychange", visibility);
  open();
  return () => { disposed = true; document.removeEventListener("visibilitychange", visibility); close(); };
}
